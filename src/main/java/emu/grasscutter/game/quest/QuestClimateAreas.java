package emu.grasscutter.game.quest;

import com.google.gson.annotations.SerializedName;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.PlayerProperty;
import emu.grasscutter.game.world.Position;
import emu.grasscutter.server.packet.send.PacketPlayerPropNotify;
import emu.grasscutter.utils.FileUtils;
import emu.grasscutter.utils.JsonUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Quest action 60 changes a JSON climate type, independently of sky weather action 68. */
public final class QuestClimateAreas {
    private static final Map<Integer, List<Area>> catalogs = new ConcurrentHashMap<>();
    // ConfigClimate.cpp::enumStrToVal(JsonClimateType), native 3.4. Count (14) is a sentinel.
    private static final Map<String, Integer> types = Map.ofEntries(
            Map.entry("Normal", 0), Map.entry("Cold", 1), Map.entry("Hot", 2),
            Map.entry("ColdMonster", 3), Map.entry("HotMonster", 4), Map.entry("SeaMist", 5),
            Map.entry("HeroCourse", 6), Map.entry("SeaMistSurrounding", 7),
            Map.entry("TatariRegion", 8), Map.entry("TransClimate", 9),
            Map.entry("SeiraiStorm", 10), Map.entry("TsurumiMist", 11),
            Map.entry("TsurumiThunder", 12), Map.entry("TsurumiRegionalMist", 13));
    private static final List<PlayerProperty> properties = List.of(
            PlayerProperty.PROP_CUR_CLIMATE_AREA_ID,
            PlayerProperty.PROP_CUR_CLIMATE_AREA_CLIMATE_TYPE,
            PlayerProperty.PROP_CUR_CLIMATE_TYPE,
            PlayerProperty.PROP_CUR_CLIMATE_METER);

    private QuestClimateAreas() {}

    static final class Point { float x; float y; }

    static final class Area {
        @SerializedName("area_id") int id;
        String climateType = "Normal";
        boolean isForceClearDifferentClimateMeter;
        List<Integer> transPoints = List.of();
        float bottom;
        float top;
        List<Point> points = List.of();

        int defaultType() {
            var value = types.get(climateType);
            if (value == null) throw new IllegalStateException("Unknown native JSON climate type " + climateType);
            return value;
        }

        boolean contains(Position position) {
            if (points.size() < 3 || position.getY() < bottom || position.getY() > top) return false;
            double x = position.getX(), z = position.getZ();
            boolean inside = false;
            for (int i = 0, j = points.size() - 1; i < points.size(); j = i++) {
                var a = points.get(i);
                var b = points.get(j);
                double cross = (x - a.x) * (b.y - a.y) - (z - a.y) * (b.x - a.x);
                if (Math.abs(cross) < 0.0001 && x >= Math.min(a.x, b.x) && x <= Math.max(a.x, b.x)
                        && z >= Math.min(a.y, b.y) && z <= Math.max(a.y, b.y)) return true;
                if ((a.y > z) != (b.y > z)
                        && x < (b.x - a.x) * (z - a.y) / (b.y - a.y) + a.x) inside = !inside;
            }
            return inside;
        }
    }

    static List<Area> areas(int sceneId) {
        return catalogs.computeIfAbsent(sceneId, id -> {
            var path = FileUtils.getScriptPath("Scene/" + id + "/scene" + id + "_climate_areas.json");
            if (!Files.isRegularFile(path)) return List.of();
            try {
                var result = JsonUtils.loadToList(path, Area.class);
                for (var area : result) area.defaultType();
                return List.copyOf(result);
            } catch (IOException error) {
                throw new IllegalStateException("Cannot read native climate areas " + path, error);
            }
        });
    }

    /** The quest belongs to the personal world's host; a visitor cannot change the host's areas. */
    public static boolean change(Player owner, int sceneId, int areaId, int type) {
        if (owner.getWorld() == null || owner.getWorld().getHost() != owner
                || sceneId <= 0 || areaId <= 0 || type < 0 || type >= 14
                || areas(sceneId).stream().noneMatch(area -> area.id == areaId)) return false;
        if (owner.getQuestSceneOverrides().setClimateAreaType(sceneId, areaId, type)) owner.save();
        owner.getWorld().getPlayers().stream().filter(player -> player.getScene() != null
                && player.getScene().getWorld() == owner.getWorld() && player.getSceneId() == sceneId)
                .forEach(player -> update(player, false));
        return true;
    }

    /** Scene::getClimateAreaIdByPos first retains the previous type when polygons overlap. */
    static Area select(List<Area> candidates, Map<Integer, Integer> overrides,
            Position position, int previousType) {
        for (int pass = 0; pass < 2; pass++) {
            for (var area : candidates) {
                int type = overrides.getOrDefault(area.id, area.defaultType());
                if (type == 0 || (type == previousType) != (pass == 0) || !area.contains(position)) continue;
                return area;
            }
        }
        return null;
    }

    /** Recompute derived player properties on movement and after a scene's enter handshake. */
    public static void update(Player player, boolean onSceneEnter) {
        update(player, onSceneEnter, System.currentTimeMillis());
    }

    static void update(Player player, boolean onSceneEnter, long nowMs) {
        var scene = player.getScene();
        if (scene == null || player.getWorld() != scene.getWorld() || player.getSceneId() != scene.getId()
                || !onSceneEnter && player.getSceneLoadState() != Player.SceneLoadState.LOADED) return;
        var owner = scene.getWorld().getHost();
        if (owner == null) return;
        var overrides = owner.getQuestSceneOverrides().climateAreaTypes(scene.getId());
        var area = select(areas(scene.getId()), overrides, player.getPosition(),
                player.getProperty(PlayerProperty.PROP_CUR_CLIMATE_AREA_CLIMATE_TYPE));
        int id = area == null ? 0 : area.id;
        int type = area == null ? 0 : overrides.getOrDefault(id, area.defaultType());
        var changed = new LinkedHashSet<PlayerProperty>();
        int previousArea = player.getProperty(PlayerProperty.PROP_CUR_CLIMATE_AREA_ID);
        int previousType = player.getProperty(PlayerProperty.PROP_CUR_CLIMATE_AREA_CLIMATE_TYPE);
        set(player, PlayerProperty.PROP_CUR_CLIMATE_AREA_ID, id, changed);
        set(player, PlayerProperty.PROP_CUR_CLIMATE_AREA_CLIMATE_TYPE, type, changed);
        QuestClimateRuntime.update(player, area, type, onSceneEnter, nowMs, changed);
        if (onSceneEnter) player.sendPacket(new PacketPlayerPropNotify(player, properties));
        else if (!changed.isEmpty()) player.sendPacket(new PacketPlayerPropNotify(player, changed));
        if ((previousArea != id || previousType != type) && player.getSession() != null)
            player.getQuestManager().queueEvent(
                    emu.grasscutter.game.quest.enums.QuestCond.QUEST_COND_CUR_CLIMATE, type);
    }

    static int typeId(String name) { return types.getOrDefault(name, -1); }

    static void set(Player player, PlayerProperty property, int value, Set<PlayerProperty> changed) {
        if (player.getProperty(property) != value && player.setProperty(property, value, false)) changed.add(property);
    }
}
