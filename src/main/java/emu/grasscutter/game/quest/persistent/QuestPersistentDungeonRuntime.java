package emu.grasscutter.game.quest.persistent;

import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.dungeon.DungeonData;
import emu.grasscutter.game.dungeons.BasicDungeonSettleListener;
import emu.grasscutter.game.dungeons.DungeonManager;
import emu.grasscutter.game.dungeons.enums.DungeonInvolveType;
import emu.grasscutter.game.dungeons.enums.DungeonType;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.SceneType;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.world.Scene;
import emu.grasscutter.game.world.Position;
import java.util.Arrays;
import java.util.Set;

/** Keeps authorized story instances alive and rebuilds a real manager on reconnect. */
public final class QuestPersistentDungeonRuntime {
    private QuestPersistentDungeonRuntime() {}

    public static boolean activate(GameQuest quest, int sceneId) {
        var owner = quest.getOwner();
        var data = dungeonForScene(sceneId);
        if (!ownWorld(owner) || data == null) return false;
        if (isCalibertDungeon(data) && (owner.getScene() == null || owner.getSceneId() != sceneId
                || owner.getScene().getDungeonManager() == null
                || owner.getScene().getDungeonManager().getDungeonData().getId() != data.getId()
                || !owner.getScene().getDungeonManager().capturePersistentProgress().isStarted())) return false;
        if (owner.getScene() != null && owner.getSceneId() == sceneId
                && owner.getScene().getDungeonManager() != null
                && owner.getScene().getDungeonManager().getDungeonData().getId() != data.getId()) return false;
        boolean changed = owner.getPersistentDungeons().activate(sceneId);
        if (owner.getScene() != null && owner.getSceneId() == sceneId
                && owner.getScene().getDungeonManager() != null) {
            if (!onEntered(owner.getScene())) return false;
        }
        if (changed) owner.save();
        return true;
    }

    public static boolean clear(GameQuest quest, int sceneId) {
        var owner = quest.getOwner();
        if (!ownWorld(owner) || dungeonForScene(sceneId) == null) return false;
        if (!owner.getPersistentDungeons().clear(sceneId)) return true;
        var scene = owner.getWorld().getScenes().get(sceneId);
        if (scene != null) {
            scene.setDontDestroyWhenEmpty(false);
            if (scene.getPlayerCount() == 0) {
                scene.getScriptManager().onDestroy();
                owner.getWorld().deregisterScene(scene);
            }
        }
        owner.save();
        return true;
    }

    public static boolean shouldRestoreGroups(Scene scene) {
        var owner = owner(scene);
        if (owner == null || !owner.getPersistentDungeons().isActive(scene.getId())
                || scene.getSceneType() != SceneType.SCENE_DUNGEON || scene.getDungeonManager() == null) return false;
        var actual = scene.getDungeonManager().getDungeonData();
        var registered = GameData.getDungeonDataMap().get(actual.getId());
        return validStoryDungeon(actual) && actual.getSceneId() == scene.getId()
                && validStoryDungeon(registered) && registered.getSceneId() == scene.getId();
    }

    public static boolean shouldRestoreGroup(Scene scene, int groupId) {
        return shouldRestoreGroups(scene) && owner(scene).getPersistentDungeons()
                .getRun(scene.getId()).hasGroup(groupId);
    }

    public static Set<Integer> savedSuites(Scene scene, int groupId) {
        if (!shouldRestoreGroup(scene, groupId)) return Set.of();
        return owner(scene).getPersistentDungeons().getRun(scene.getId()).getSuites(groupId);
    }

    public static void suiteAdded(Scene scene, int groupId, int suiteId) {
        if (shouldRestoreGroups(scene)) owner(scene).getPersistentDungeons()
                .suiteAdded(scene.getId(), groupId, suiteId);
    }

    public static void suiteRemoved(Scene scene, int groupId, int suiteId) {
        if (shouldRestoreGroups(scene)) owner(scene).getPersistentDungeons()
                .suiteRemoved(scene.getId(), groupId, suiteId);
    }

    /** A native script deliberately respawned this configuration; it is alive in the retained run. */
    public static void freshEntityCreated(Scene scene, int groupId, int configId) {
        if (!shouldRestoreGroups(scene)) return;
        var instance = scene.getScriptManager().getGroupInstanceById(groupId);
        if (instance != null) instance.getDeadEntities().remove(configId);
    }

    /** Called after the existing owner-scoped SceneGroupInstance saves. */
    public static boolean capture(Scene scene) {
        if (!shouldRestoreGroups(scene)) return false;
        var owner = owner(scene);
        emu.grasscutter.game.quest.war.ReconstructedWarRuntime.capture(owner);
        if (owner.getPersistentDungeons().update(scene.getDungeonManager().capturePersistentProgress()))
            owner.save();
        return true;
    }

    /** Called after a successful real dungeon entry has installed its manager. */
    public static boolean onEntered(Scene scene) {
        if (!shouldRestoreGroups(scene)) return false;
        var saved = owner(scene).getPersistentDungeons().getRun(scene.getId()).getProgress();
        int originScene = scene.getPrevScene(), originPoint = scene.getPrevScenePoint();
        if (saved != null) {
            if (!scene.getDungeonManager().restorePersistentProgress(saved)) return false;
            // A successful new entry and its native quest exit override supply the current origin.
            scene.setPrevScene(originScene);
            scene.setPrevScenePoint(originPoint);
        }
        scene.setDontDestroyWhenEmpty(true);
        return capture(scene);
    }

    /** Login may keep the saved scene only when an owned, valid instance can actually be rebuilt. */
    public static boolean canRestoreLogin(Player player) {
        return canRestoreScene(player, player.getSceneId());
    }

    private static boolean canRestoreScene(Player player, int sceneId) {
        var run = player.getPersistentDungeons().getRun(sceneId);
        if (run == null || run.getProgress() == null) return false;
        var saved = run.getProgress();
        var data = GameData.getDungeonDataMap().get(saved.getDungeonId());
        if (!validStoryDungeon(data) || data.getSceneId() != sceneId
                || isCalibertDungeon(data) && !saved.isStarted()) return false;
        if (emu.grasscutter.game.quest.coop.CoopDungeonRuntime.isDungeon(data)
                && (!saved.isStarted() || !emu.grasscutter.game.quest.coop.CoopDungeonRuntime.ownsRun(player, data)))
            return false;
        var sceneData = GameData.getSceneDataMap().get(sceneId);
        if (sceneData == null || sceneData.getSceneType() != SceneType.SCENE_DUNGEON) return false;
        var pass = data.getPassCond() == 0 ? null : GameData.getDungeonPassConfigDataMap().get(data.getPassCond());
        if (data.getPassCond() != 0 && (pass == null || pass.getConds() == null)) return false;
        if (!saved.matches(player.getUid(), sceneId, data, pass == null ? 0 : pass.getConds().size()))
            return false;
        if (!validRestoreOrigin(data, saved.getOriginSceneId(), saved.getOriginPointId())) return false;
        return saved.getActiveWayPoints().stream()
                .allMatch(id -> GameData.getScenePointEntryById(sceneId, id) != null);
    }

    /** These exact private story levels retain only a manager that actually started. */
    public static boolean retainStartedCalibert(Scene scene) {
        var player = owner(scene);
        if (player == null || player.getScene() != scene || player.getSceneId() != scene.getId()
                || scene.getDungeonManager() == null || !isCalibertDungeon(scene.getDungeonManager().getDungeonData())
                || scene.getDungeonManager().getDungeonData().getSceneId() != scene.getId()
                || !scene.getDungeonManager().capturePersistentProgress().isStarted()) return false;
        player.getPersistentDungeons().activate(scene.getId());
        scene.setDontDestroyWhenEmpty(true);
        return capture(scene);
    }

    /** Re-entry of an already visited inner stage needs an actual owned retained run. */
    public static boolean canResumeCalibertDungeon(Player player, int dungeonId) {
        var data = GameData.getDungeonDataMap().get(dungeonId);
        return ownWorld(player) && (dungeonId == 1175 || dungeonId == 1176)
                && isCalibertDungeon(data) && canRestoreScene(player, data.getSceneId());
    }

    public static boolean isCalibertDungeon(DungeonData data) {
        if (data == null || data.getInvolveType() != DungeonInvolveType.INVOLVE_ONLY_SINGLE) return false;
        return data.getId() == 1175 && data.getSceneId() == 20178 && data.getType() == DungeonType.DUNGEON_PLOT
                || data.getId() == 1176 && data.getSceneId() == 20181 && data.getType() == DungeonType.DUNGEON_PLOT
                || data.getId() == 1177 && data.getSceneId() == 20180 && data.getType() == DungeonType.DUNGEON_BIGWORLD_MIRROR;
    }

    public static boolean isCalibertPrivateOrigin(DungeonData data, int originScene, int originPoint) {
        return isCalibertDungeon(data) && originScene == 20180
                && (data.getId() == 1175 && originPoint == 2 || data.getId() == 1176 && originPoint == 4)
                && realEntryPoint(originScene, originPoint, data.getId(), SceneType.SCENE_DUNGEON);
    }

    /** Nested origins are allowed only for the two explicitly authored Calibert points. */
    public static boolean validRestoreOrigin(DungeonData data, int originScene, int originPoint) {
        if (!validStoryDungeon(data)) return false;
        if (emu.grasscutter.game.quest.coop.CoopDungeonRuntime.isDungeon(data))
            return emu.grasscutter.game.quest.coop.CoopDungeonRuntime.validOrigin(data, originScene, originPoint);
        if (isCalibertDungeon(data)) {
            return isCalibertPrivateOrigin(data, originScene, originPoint)
                    || data.getId() == 1177 && originScene == 3 && originPoint == 776
                            && realEntryPoint(3, 776, 1177, SceneType.SCENE_WORLD);
        }
        var origin = GameData.getSceneDataMap().get(originScene);
        return origin != null && origin.getSceneType() == SceneType.SCENE_WORLD;
    }

    private static boolean realEntryPoint(int sceneId, int pointId, int dungeonId, SceneType sceneType) {
        var scene = GameData.getSceneDataMap().get(sceneId);
        var entry = GameData.getScenePointEntryById(sceneId, pointId);
        if (scene == null || scene.getSceneType() != sceneType || entry == null || entry.getSceneId() != sceneId)
            return false;
        var point = entry.getPointData();
        return point != null && point.getId() == pointId && "DungeonEntry".equals(point.getType())
                && point.getDungeonIds() != null && Arrays.stream(point.getDungeonIds()).anyMatch(id -> id == dungeonId)
                && finite(point.getPos()) && finite(point.getTranPos());
    }

    private static boolean finite(Position position) {
        return position != null && Float.isFinite(position.getX()) && Float.isFinite(position.getY())
                && Float.isFinite(position.getZ());
    }

    /** Run after World.addPlayer, before the quest manager or scene-entry packets are sent. */
    public static boolean restoreLogin(Player player) {
        if (!ownWorld(player) || !canRestoreLogin(player) || player.getScene() == null) return false;
        var scene = player.getScene();
        if (scene.getId() != player.getSceneId()) return false;
        var saved = player.getPersistentDungeons().getRun(scene.getId()).getProgress();
        var manager = new DungeonManager(scene, GameData.getDungeonDataMap().get(saved.getDungeonId()));
        if (!manager.restorePersistentProgress(saved)) return false;
        scene.setDungeonManager(manager);
        if (scene.getDungeonSettleListeners() == null || scene.getDungeonSettleListeners().stream()
                .noneMatch(listener -> listener instanceof BasicDungeonSettleListener))
            scene.addDungeonSettleObserver(new BasicDungeonSettleListener());
        scene.setDontDestroyWhenEmpty(true);
        return true;
    }

    private static DungeonData dungeonForScene(int sceneId) {
        var scene = GameData.getSceneDataMap().get(sceneId);
        if (scene == null || scene.getSceneType() != SceneType.SCENE_DUNGEON) return null;
        DungeonData result = null;
        for (var data : GameData.getDungeonDataMap().values()) {
            if (data.getSceneId() != sceneId || !validStoryDungeon(data)) continue;
            if (result != null) return null;
            result = data;
        }
        return result;
    }

    private static boolean validStoryDungeon(DungeonData data) {
        if (data == null || data.getInvolveType() != DungeonInvolveType.INVOLVE_ONLY_SINGLE) return false;
        if (data.getId() == 1111 || data.getId() == 1112 || data.getSceneId() == 20035 || data.getSceneId() == 20036)
            return emu.grasscutter.game.quest.coop.CoopDungeonRuntime.isDungeon(data);
        if (data.getId() == 1175 || data.getId() == 1176 || data.getId() == 1177
                || data.getSceneId() == 20178 || data.getSceneId() == 20181 || data.getSceneId() == 20180)
            return isCalibertDungeon(data);
        return data.getType() == DungeonType.DUNGEON_BIGWORLD_MIRROR;
    }

    private static boolean ownWorld(Player player) {
        return player.getWorld() != null && player.getWorld().getHost() == player;
    }

    private static Player owner(Scene scene) {
        if (scene == null || scene.getWorld() == null) return null;
        var owner = scene.getWorld().getHost();
        return owner != null && owner.getWorld() == scene.getWorld() ? owner : null;
    }
}
