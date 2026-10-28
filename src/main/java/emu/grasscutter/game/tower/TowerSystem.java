package emu.grasscutter.game.tower;

import static emu.grasscutter.config.Configuration.GAME_OPTIONS;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.*;
import emu.grasscutter.data.binout.MonsterPreviewData;
import emu.grasscutter.data.excels.tower.TowerScheduleData;
import emu.grasscutter.server.game.*;
import emu.grasscutter.utils.FileUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Serves one Spiral Abyss rotation at a time.
 *
 * <p>The default schedule follows the official openTime/closeTime in the loaded client resources.
 * Operator-selected schedules and daily historical rotation remain explicit options. Missing
 * battle scripts never change the advertised season to an older one.
 */
public class TowerSystem extends BaseGameSystem {
    /** Schedule ids already reported as missing, so each is named once rather than per lookup. */
    private static final java.util.Set<Integer> MISSING_SCHEDULES_REPORTED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();


    /** Daily rotation rolls over at this hour (server local time). */
    private static final int ROTATION_HOUR = 4;

    private TowerScheduleConfig towerScheduleConfig;

    /**
     * Rotations whose every chamber resolves to a scripted scene, ascending. Resolved on first use,
     * never in the constructor: the game server is built before {@code ResourceLoader.loadAll()}
     * runs, so the excel maps are still empty at that point.
     */
    private List<Integer> playableSchedules;

    /** Answers per scene, since one rotation asks about the same scenes repeatedly. */
    private final Map<Integer, Boolean> scriptedScenes = new HashMap<>();

    public TowerSystem(GameServer server) {
        super(server);
        this.load();
    }

    public synchronized void load() {
        playableSchedules = null;
        scriptedScenes.clear();
        try {
            towerScheduleConfig = DataLoader.loadClass("TowerSchedule.json", TowerScheduleConfig.class);
        } catch (Exception e) {
            Grasscutter.getLogger().error("Unable to load tower schedule config.", e);
        }
    }

    public TowerScheduleConfig getTowerScheduleConfig() {
        return towerScheduleConfig;
    }

    // region Rotation

    /** Whether the scene behind a chamber holds anything to fight. */
    private boolean isScripted(int sceneId) {
        return scriptedScenes.computeIfAbsent(
                sceneId,
                id -> hasBattleScripts(FileUtils.getScriptPath("Scene/" + id), id));
    }

    static boolean hasBattleScripts(Path dir, int sceneId) {
        if (!Files.isRegularFile(dir.resolve("scene" + sceneId + ".lua"))) return false;
        try (var blocks = Files.newDirectoryStream(dir, "scene" + sceneId + "_block*.lua");
                var groups = Files.newDirectoryStream(dir, "scene" + sceneId + "_group*.lua")) {
            return blocks.iterator().hasNext() && groups.iterator().hasNext();
        } catch (IOException e) {
            return false;
        }
    }

    /** A scene stub alone has no start mechanism, waves or challenge settlement. */
    public boolean isDungeonPlayable(int dungeonId) {
        var dungeon = GameData.getDungeonDataMap().get(dungeonId);
        return dungeon != null && isScripted(dungeon.getSceneId());
    }

    public List<Integer> getMissingBattleScenes(TowerScheduleData schedule) {
        if (schedule == null) return List.of();
        return floorsOf(schedule).stream()
                .map(GameData.getTowerFloorDataMap()::get)
                .filter(Objects::nonNull)
                .flatMap(floor -> GameData.getTowerLevelDataMap().values().stream()
                        .filter(level -> level.getLevelGroupId() == floor.getLevelGroupId()))
                .map(level -> GameData.getDungeonDataMap().get(level.getDungeonId()))
                .filter(Objects::nonNull)
                .map(dungeon -> dungeon.getSceneId())
                .filter(sceneId -> !isScripted(sceneId))
                .distinct()
                .sorted()
                .toList();
    }

    /** Every floor a rotation can put a player on, corridor and schedule floors alike. */
    private static List<Integer> floorsOf(TowerScheduleData schedule) {
        var floors = new ArrayList<Integer>();
        if (schedule.getEntranceFloorId() != null) floors.addAll(schedule.getEntranceFloorId());
        if (schedule.getSchedules() != null) {
            schedule.getSchedules().stream()
                    .filter(Objects::nonNull)
                    .map(TowerScheduleData.ScheduleDetail::getFloorList)
                    .filter(Objects::nonNull)
                    .forEach(floors::addAll);
        }
        return floors;
    }

    /** True when every chamber of every floor leads somewhere with monsters in it. */
    private boolean isPlayable(TowerScheduleData schedule) {
        if (schedule.getSchedules() == null || schedule.getSchedules().isEmpty()) return false;
        var floors = floorsOf(schedule);
        if (floors.isEmpty()) return false;
        return floors.stream().allMatch(this::isFloorPlayable);
    }

    public boolean isFloorPlayable(int floorId) {
        var floor = GameData.getTowerFloorDataMap().get(floorId);
        if (floor == null) return false;
        var levels = GameData.getTowerLevelDataMap().values().stream()
                .filter(level -> level.getLevelGroupId() == floor.getLevelGroupId())
                .toList();
        if (!levels.stream().map(level -> level.getLevelIndex()).sorted().toList()
                .equals(List.of(1, 2, 3))) return false;
        return levels.stream().allMatch(level -> isDungeonPlayable(level.getDungeonId()));
    }

    /** Playable rotations, ascending. Worked out once, after the resources are in. */
    public synchronized List<Integer> getPlayableSchedules() {
        if (playableSchedules != null) return playableSchedules;

        var all = GameData.getTowerScheduleDataMap();
        if (all.isEmpty()) return List.of(); // Resources are not in yet; do not cache that.

        var playable =
                all.values().stream()
                        .filter(this::isPlayable)
                        .map(TowerScheduleData::getScheduleId)
                        .sorted()
                        .toList();

        if (playable.isEmpty()) {
            Grasscutter.getLogger()
                    .error(
                            "No Spiral Abyss rotation is playable: every one names dungeon scenes this server has no group scripts for.");
        } else {
            Grasscutter.getLogger()
                    .info(
                            "Spiral Abyss: {} of {} rotations are playable, newest is {}.",
                            playable.size(),
                            all.size(),
                            playable.get(playable.size() - 1));
        }

        playableSchedules = playable;
        return playableSchedules;
    }

    /** Every schedule id in the excel, ascending - the full daily cycle. */
    private List<Integer> getAllScheduleIds() {
        var all = GameData.getTowerScheduleDataMap();
        if (all.isEmpty()) return List.of();
        return all.values().stream().map(TowerScheduleData::getScheduleId).sorted().toList();
    }

    /**
     * The rotations actually cycled through. Default ({@code rotationPool <= 0}) is every schedule
     * in the excel; a positive pool keeps only the newest N of those.
     */
    private List<Integer> getRotationPool() {
        var all = getAllScheduleIds();
        int pool = GAME_OPTIONS.tower.rotationPool;
        if (pool <= 0 || pool >= all.size()) return all;
        return all.subList(all.size() - pool, all.size());
    }

    /** The newest rotation this server can actually build, or 0 if none can be. */
    private int getNewestPlayable() {
        var playable = getPlayableSchedules();
        return playable.isEmpty() ? 0 : playable.get(playable.size() - 1);
    }

    /** Start of the daily rotation period holding {@code now}, at {@link #ROTATION_HOUR}. */
    private static Calendar periodStart(Date now) {
        var calendar = Calendar.getInstance();
        calendar.setTime(now);

        // Hours before the rollover still belong to yesterday's rotation.
        if (calendar.get(Calendar.HOUR_OF_DAY) < ROTATION_HOUR) {
            calendar.add(Calendar.DAY_OF_MONTH, -1);
        }

        calendar.set(Calendar.HOUR_OF_DAY, ROTATION_HOUR);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar;
    }

    private static Calendar periodEnd(Calendar start) {
        var calendar = (Calendar) start.clone();
        calendar.add(Calendar.DAY_OF_MONTH, 1);
        return calendar;
    }

    /** Days since a fixed epoch, so consecutive days pick consecutive rotations. */
    private static long periodOrdinal(Calendar start) {
        return start.toInstant().atZone(start.getTimeZone().toZoneId()).toLocalDate().toEpochDay();
    }

    /** Whether the operator enabled daily historical rotation instead of official season dates. */
    private static boolean isRotating() {
        return GAME_OPTIONS.tower.scheduleId <= 0 && GAME_OPTIONS.tower.rotate;
    }

    public record ScheduleWindow(Date startTime, Date endTime) {}

    public ScheduleWindow getScheduleWindow() {
        return getScheduleWindow(new Date());
    }

    ScheduleWindow getScheduleWindow(Date now) {
        if (!isRotating() && GAME_OPTIONS.tower.scheduleId <= 0) {
            var schedule = getCurrentOfficialSchedule(now);
            if (schedule != null) {
                return new ScheduleWindow(schedule.getScheduleStartTime(), schedule.getScheduleEndTime());
            }
        }
        if (!isRotating() && towerScheduleConfig != null) {
            var start = towerScheduleConfig.getScheduleStartTime();
            var end = towerScheduleConfig.getNextScheduleChangeTime();
            if (start != null && end != null && start.before(end) && end.after(now)) {
                return new ScheduleWindow(start, end);
            }
        }

        // Older deployments may still hold an expired TowerSchedule.json after a jar update.
        var start = periodStart(now);
        return new ScheduleWindow(start.getTime(), periodEnd(start).getTime());
    }

    /** When the rotation on offer began. */
    public Date getScheduleStartTime() {
        return getScheduleWindow().startTime();
    }

    /** When it gives way to the next one. The client counts down to this. */
    public Date getNextScheduleChangeTime() {
        return getScheduleWindow().endTime();
    }

    // endregion

    public TowerScheduleData getCurrentTowerScheduleData() {
        return getCurrentTowerScheduleData(new Date());
    }

    TowerScheduleData getCurrentTowerScheduleData(Date now) {
        // A pinned id is served as asked, playable or not - that is what pinning is for.
        int pinned = GAME_OPTIONS.tower.scheduleId;
        if (pinned > 0) {
            var data = GameData.getTowerScheduleDataMap().get(pinned);
            if (data != null) return data;
            Grasscutter.getLogger()
                    .error("Spiral Abyss rotation {} does not exist.", pinned);
            return null;
        }

        if (isRotating()) {
            var pool = getRotationPool();
            if (!pool.isEmpty()) {
                int index = (int) Math.floorMod(periodOrdinal(periodStart(now)), pool.size());
                var data = GameData.getTowerScheduleDataMap().get(pool.get(index).intValue());
                if (data != null) return data;
            }
        } else {
            var official = getCurrentOfficialSchedule(now);
            if (official != null) return official;
            // A dated resource pack must not silently serve a stale season when its current data
            // is missing. Undated custom resource packs retain their existing selection behavior.
            if (GameData.getTowerScheduleDataMap().values().stream()
                    .anyMatch(schedule -> schedule.getScheduleStartTime() != null
                            && schedule.getScheduleEndTime() != null)) return null;
            // Retain legacy selection only for custom resource packs without schedule timestamps.
            var newest = getNewestPlayable();
            if (newest > 0) {
                var data = GameData.getTowerScheduleDataMap().get(newest);
                if (data != null) return data;
            }
        }

        // Nothing validated - fall back to whatever the config file names, so a resource set this
        // check does not understand still opens the abyss rather than closing it entirely.
        if (towerScheduleConfig == null) return null;
        var fallback = GameData.getTowerScheduleDataMap().get(towerScheduleConfig.getScheduleId());
        if (fallback == null) {
            // The resource pack does not change while the server runs, so this is the same
            // complaint every time anyone opens the abyss - twelve lines in a ten-minute log.
            var logger = Grasscutter.getLogger();
            var firstTime = MISSING_SCHEDULES_REPORTED.add(towerScheduleConfig.getScheduleId());
            (firstTime ? logger.atError() : logger.atDebug())
                    .log(
                            "Could not get current tower schedule data by schedule id {}, please check your resource files",
                            towerScheduleConfig.getScheduleId());
        }
        return fallback;
    }

    private TowerScheduleData getCurrentOfficialSchedule(Date now) {
        return GameData.getTowerScheduleDataMap().values().stream()
                .filter(schedule -> {
                    var start = schedule.getScheduleStartTime();
                    var end = schedule.getScheduleEndTime();
                    return start != null && end != null && !now.before(start) && now.before(end);
                })
                .max(Comparator.comparing(TowerScheduleData::getScheduleStartTime))
                .orElse(null);
    }

    public List<Integer> getAllFloors() {
        var schedule = this.getCurrentTowerScheduleData();
        if (schedule == null) return List.of();

        List<Integer> floors = new ArrayList<>(schedule.getEntranceFloorId());
        floors.addAll(this.getScheduleFloors());
        return floors;
    }

    /** Missing previews are explicit; enemy kinds are never substituted for battle wave data. */
    public Optional<MonsterPreviewData> getMonsterPreview(int floorId, int chamber, boolean upperHalf) {
        if (chamber < 1 || chamber > 3 || !getAllFloors().contains(floorId)) return Optional.empty();
        var floor = GameData.getTowerFloorDataMap().get(floorId);
        if (floor == null) return Optional.empty();
        return GameData.getTowerLevelDataMap().values().stream()
                .filter(level -> level.getLevelGroupId() == floor.getLevelGroupId()
                        && level.getLevelIndex() == chamber)
                .findFirst()
                .flatMap(level -> {
                    var rows = GameData.getMonsterPreviewDataMap().get(level.getTowerMonsterPreview());
                    if (rows == null) return Optional.empty();
                    int id = upperHalf ? level.getTowerMonsterPreviewId1() : level.getTowerMonsterPreviewId2();
                    return Optional.ofNullable(rows.get(id));
                })
                .filter(preview -> preview.getMonsterPreviewInfo() != null
                        && !preview.getMonsterPreviewInfo().isEmpty());
    }

    public List<Integer> getScheduleFloors() {
        var schedule = this.getCurrentTowerScheduleData();
        // TowerScheduleData.onLoad drops the empty slots, so the first one left is the live half.
        if (schedule == null || schedule.getSchedules().isEmpty()) return List.of();
        return schedule.getSchedules().get(0).getFloorList();
    }

    public int getNextFloorId(int floorId) {
        var schedule = this.getCurrentTowerScheduleData();
        if (schedule == null) return 0;

        var entranceFloors = schedule.getEntranceFloorId();
        var scheduleFloors = getScheduleFloors();
        var nextId = 0;

        // find in entrance floors first
        for (int i = 0; i < entranceFloors.size() - 1; i++) {
            if (floorId == entranceFloors.get(i)) {
                nextId = entranceFloors.get(i + 1);
            }
        }

        if (!entranceFloors.isEmpty()
                && floorId == entranceFloors.get(entranceFloors.size() - 1)
                && !scheduleFloors.isEmpty()) {
            nextId = scheduleFloors.get(0);
        }

        if (nextId != 0) {
            return nextId;
        }

        // find in schedule floors
        for (int i = 0; i < scheduleFloors.size() - 1; i++) {
            if (floorId == scheduleFloors.get(i)) {
                nextId = scheduleFloors.get(i + 1);
            }
        }
        return nextId;
    }

    public Integer getLastEntranceFloor() {
        var schedule = this.getCurrentTowerScheduleData();
        if (schedule == null || schedule.getEntranceFloorId().isEmpty()) return 0;
        return schedule.getEntranceFloorId().get(schedule.getEntranceFloorId().size() - 1);
    }
}
