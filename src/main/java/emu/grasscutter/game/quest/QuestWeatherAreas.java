package emu.grasscutter.game.quest;

import com.google.gson.annotations.SerializedName;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.world.WeatherData;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.ClimateType;
import emu.grasscutter.game.world.Position;
import emu.grasscutter.game.world.Scene;
import emu.grasscutter.utils.*;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Native weather areas are spatial enables; enabling one must not change the whole world's sky. */
public final class QuestWeatherAreas {
    private static final Map<Integer, List<Area>> areas = new ConcurrentHashMap<>();
    private QuestWeatherAreas() {}

    static final class Point { float x; float y; }
    static final class Area {
        @SerializedName("area_id") int id;
        int sceneId;
        boolean reconstructed;
        boolean mode1;
        String replacementFamily = "";
        List<Point> points = List.of();
        boolean isUseHeightBorder;
        float bottom;
        float top;

        boolean contains(Position position) {
            if (points.size() < 3 || (isUseHeightBorder
                    && (position.getY() < bottom || position.getY() > top))) return false;
            double x = position.getX(), z = position.getZ();
            boolean inside = false;
            for (int i = 0, j = points.size() - 1; i < points.size(); j = i++) {
                var a = points.get(i);
                var b = points.get(j);
                double cross = (x - a.x) * (b.y - a.y) - (z - a.y) * (b.x - a.x);
                if (Math.abs(cross) < 0.0001 && x >= Math.min(a.x,b.x) && x <= Math.max(a.x,b.x)
                        && z >= Math.min(a.y,b.y) && z <= Math.max(a.y,b.y)) return true;
                if ((a.y > z) != (b.y > z)
                        && x < (b.x - a.x) * (z - a.y) / (b.y - a.y) + a.x) inside = !inside;
            }
            return inside;
        }
    }

    static List<Area> areas(int sceneId) {
        return areas.computeIfAbsent(sceneId, id -> {
            var path = FileUtils.getScriptPath("Scene/" + id + "/scene" + id + "_weather_areas.json");
            try {
                var result = Files.exists(path) ? new ArrayList<>(JsonUtils.loadToList(path, Area.class))
                        : new ArrayList<Area>();
                var replacements = FileUtils.getScriptPath("Common/QuestReconstruction/weather_areas.json");
                if (Files.exists(replacements)) {
                    for (var replacement : JsonUtils.loadToList(replacements, Area.class)) {
                        if (replacement.sceneId == id && replacement.reconstructed
                                && result.stream().noneMatch(area -> area.id == replacement.id)) result.add(replacement);
                    }
                }
                return List.copyOf(result);
            }
            catch (IOException error) { throw new IllegalStateException("Cannot read native weather areas " + path, error); }
        });
    }

    /** User-authorized replacement: mode 1 immediately switches the authored weather family. */
    public static boolean changeReconstructedMode1(Player owner, int[] ids, int[] states) {
        if (ids.length == 0 || ids.length != states.length) return false;
        var changes = new LinkedHashMap<Integer,Integer>();
        for (int i = 0; i < ids.length; i++) {
            var config = GameData.getWeatherDataMap().get(ids[i]);
            if (config == null || states[i] < 0 || states[i] > 1) return false;
            var area = areas(config.getSceneID()).stream()
                    .filter(value -> value.id == config.getWeatherAreaId() && value.reconstructed && value.mode1)
                    .findFirst().orElse(null);
            if (area == null) return false;
            if (states[i] == 1 && !area.replacementFamily.isEmpty()) {
                for (var sibling : areas(config.getSceneID())) {
                    if (sibling.reconstructed && sibling.id != area.id
                            && area.replacementFamily.equals(sibling.replacementFamily)
                            && changes.getOrDefault(sibling.id,
                                    owner.getQuestWeatherAreas().getOrDefault(sibling.id,0)) == 1)
                        changes.put(sibling.id,0);
                }
            }
            changes.put(ids[i],states[i]);
        }
        return change(owner,changes.keySet().stream().mapToInt(Integer::intValue).toArray(),
                changes.values().stream().mapToInt(Integer::intValue).toArray());
    }

    public static boolean change(Player owner, int[] ids, int[] states) {
        if (owner.getWorld() == null || owner.getWorld().getHost() != owner
                || ids.length == 0 || ids.length != states.length) return false;
        for (int i = 0; i < ids.length; i++) {
            if (!GameData.getWeatherDataMap().containsKey(ids[i]) || (states[i] != 0 && states[i] != 1)) return false;
        }
        boolean changed = false;
        for (int i = 0; i < ids.length; i++) {
            Integer previous = owner.getQuestWeatherAreas().put(ids[i], states[i]);
            changed |= previous == null || previous != states[i];
        }
        if (changed) owner.save();
        owner.getWorld().getPlayers().forEach(player -> update(player, true));
        return true;
    }

    /** Lua changes the area's existence in this scene; a redundant operation returns -1. */
    public static int setState(Scene scene, int areaId, int state) {
        var config = GameData.getWeatherDataMap().get(areaId);
        if (scene == null || config == null || config.getSceneID() != scene.getId()
                || state < 0 || state > 1) return -1;
        var owner = scene.getWorld().getHost();
        if (owner == null || enabled(owner, config) == (state == 1)) return -1;
        return change(owner, new int[] {areaId}, new int[] {state}) ? 0 : -1;
    }

    /**
     * ScriptLib::EnterWeatherArea selects an existing area for context.uid, without a position
     * test. Dreamland calls it directly after enabling its challenge area.
     */
    public static int enter(Scene scene, int uid, int areaId) {
        var config = GameData.getWeatherDataMap().get(areaId);
        if (scene == null || uid <= 0 || config == null || config.getSceneID() != scene.getId()) return -1;
        var owner = scene.getWorld().getHost();
        if (owner == null || !enabled(owner, config)) return -1;
        var player = scene.getPlayers().stream().filter(value -> value.getUid() == uid
                && value.getScene() == scene).findFirst().orElse(null);
        if (player == null) return -1;
        if (areas(scene.getId()).stream().anyMatch(area -> area.id == config.getWeatherAreaId()
                        && area.reconstructed)
                && !QuestSceneVarReconstruction.mayUseArea(owner, scene.getId(),
                        config.getWeatherAreaId(), player.getPosition())) return -1;
        apply(player, areaId);
        return 0;
    }

    private static boolean enabled(Player owner, WeatherData config) {
        return owner.getQuestWeatherAreas().getOrDefault(config.getId(), config.isDefaultValid() ? 1 : 0) != 0;
    }

    static int select(Player owner, int sceneId, Position position) {
        int selected = 0, priority = Integer.MIN_VALUE;
        // Geometry is keyed by weatherAreaId. areaID identifies one of its height layers.
        var heightLayers = new HashMap<Integer, WeatherData>();
        for (var config : GameData.getWeatherDataMap().values()) {
            if (config.getSceneID() != sceneId) continue;
            var previous = heightLayers.get(config.getWeatherAreaId());
            float ceiling = ceiling(config);
            if (previous == null || (ceiling >= position.getY()
                    ? ceiling(previous) < position.getY() || ceiling < ceiling(previous)
                    : ceiling(previous) < position.getY() && ceiling > ceiling(previous))) {
                heightLayers.put(config.getWeatherAreaId(), config);
            }
        }
        for (var area : areas(sceneId)) {
            var config = heightLayers.get(area.id);
            if (config == null
                    || area.reconstructed && !owner.getQuestWeatherAreas().containsKey(config.getId())
                    || area.reconstructed && !QuestSceneVarReconstruction.mayUseArea(owner, sceneId, area.id, position)
                    || !enabled(owner, config)
                    || !area.contains(position)) continue;
            if (config.getPriority() > priority) {
                selected = config.getId();
                priority = config.getPriority();
            }
        }
        return selected;
    }

    private static float ceiling(WeatherData config) {
        String height = config.getMaxHeightStr();
        return height == null || height.isEmpty() ? Float.MAX_VALUE : Float.parseFloat(height);
    }

    public static void update(Player player, boolean explicitChange) {
        var scene = player.getScene();
        if (scene == null || player.getSceneLoadState() != Player.SceneLoadState.LOADED) return;
        var owner = scene.getWorld().getHost();
        if (owner == null || owner.getQuestWeatherAreas().keySet().stream().noneMatch(id -> {
            var config = GameData.getWeatherDataMap().get(id);
            return config != null && config.getSceneID() == scene.getId();
        })) return;
        var current = GameData.getWeatherDataMap().get(player.getWeatherId());
        // A scripted weather gadget has its own start/end actions; do not override it on movement.
        if (!explicitChange && current != null && current.getGadgetID() > 0
                && player.getManagedQuestWeatherArea() != player.getWeatherId()) return;
        int next = select(owner, scene.getId(), player.getPosition());
        apply(player, next);
    }

    private static void apply(Player player, int next) {
        int sceneId = player.getScene().getId();
        var owner = player.getScene().getWorld().getHost();
        if (owner == player) owner.getQuestSceneOverrides().updatePermanentWeather(sceneId, next);
        next = emu.grasscutter.game.quest.world.QuestTemporaryResourceRuntime.effectiveWeatherGadget(owner, sceneId, next);
        player.setManagedQuestWeatherArea(next);
        if (next != player.getWeatherId()) player.setWeather(next,
                next == 0 ? ClimateType.CLIMATE_SUNNY : ClimateType.CLIMATE_NONE);
    }
}
