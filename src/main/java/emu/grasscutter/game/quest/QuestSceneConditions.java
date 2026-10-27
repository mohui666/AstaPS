package emu.grasscutter.game.quest;

import emu.grasscutter.data.GameData;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.game.world.Position;
import emu.grasscutter.scripts.ScriptLoader;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.script.ScriptException;
import org.luaj.vm2.LuaTable;

/** Spatial quest predicates use the actual loaded scene and native dummy points. */
public final class QuestSceneConditions {
    private static final Map<Integer, LuaTable> nativeDummyPoints = new ConcurrentHashMap<>();
    private QuestSceneConditions() {}

    public static Position dummyPoint(int sceneId, String name) {
        if (name == null || name.isBlank()) return null;
        // New dumps embed coordinates; older quests name a scene dummy point.
        if (name.startsWith("[")) return literalPoint(sceneId, name);
        var globals = GameData.getScriptSceneDataMap().get("flat.luas.scenes.full_globals.lua.json");
        var scene = globals == null || globals.getScriptObjectList() == null ? null
                : globals.getScriptObjectList().get(sceneId + "/scene" + sceneId + "_dummy_points.lua");
        List<Float> xyz = scene == null || scene.getDummyPoints() == null ? null
                : scene.getDummyPoints().get(name + ".pos");
        if (xyz != null && xyz.size() >= 3) return new Position(xyz.get(0), xyz.get(1), xyz.get(2));
        return nativeDummyComponent(sceneId, name, "pos");
    }

    public static Position dummyRotation(int sceneId, String name) {
        if (name == null || name.isBlank()) return null;
        if (name.startsWith("[")) {
            if (literalPoint(sceneId, name) == null) return null;
            String[] values = name.substring(name.indexOf(':') + 1, name.length() - 1).split(",");
            if (values.length == 3) return new Position();
            if (values.length < 6) return null;
            try {
                float x = Float.parseFloat(values[3].trim());
                float y = Float.parseFloat(values[4].trim());
                float z = Float.parseFloat(values[5].trim());
                return Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z)
                        ? new Position(x, y, z) : null;
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        var globals = GameData.getScriptSceneDataMap().get("flat.luas.scenes.full_globals.lua.json");
        var scene = globals == null || globals.getScriptObjectList() == null ? null
                : globals.getScriptObjectList().get(sceneId + "/scene" + sceneId + "_dummy_points.lua");
        var xyz = scene == null || scene.getDummyPoints() == null ? null
                : scene.getDummyPoints().get(name + ".rot");
        if (xyz != null && xyz.size() >= 3) return new Position(xyz.get(0), xyz.get(1), xyz.get(2));
        return nativeDummyComponent(sceneId, name, "rot");
    }

    private static Position nativeDummyComponent(int sceneId, String name, String component) {
        // The converted full_globals dump omits Q3007CityCenterPos and Q7302521_guide,
        // even though both exist in this resource pack's current scene Lua.
        if (ScriptLoader.getEngine() == null) return null;
        var points = nativeDummyPoints.computeIfAbsent(sceneId, QuestSceneConditions::loadNativeDummyPoints);
        var point = points.get(name);
        if (point.isnil()) return null;
        var value = point.get(component);
        return value.isnil() ? null : ScriptLoader.getSerializer().toObject(Position.class, value);
    }

    private static LuaTable loadNativeDummyPoints(int sceneId) {
        var script = ScriptLoader.getScript("Scene/" + sceneId + "/scene" + sceneId + "_dummy_points.lua");
        if (script == null) return new LuaTable();
        var bindings = ScriptLoader.getEngine().createBindings();
        try {
            ScriptLoader.eval(script, bindings);
            if (bindings.get("dummy_points") instanceof LuaTable points) return points;
        } catch (ScriptException exception) {
            Grasscutter.getLogger().error("Could not load scene {} quest dummy points", sceneId, exception);
        }
        return new LuaTable();
    }

    static Position literalPoint(int sceneId, String point) {
        int colon = point.indexOf(':');
        int separator = point.indexOf('_');
        if (!point.endsWith("]") || colon <= 1) return null;
        int sceneEnd = separator > 0 && separator < colon ? separator : colon;
        try {
            if (Integer.parseInt(point.substring(1, sceneEnd)) != sceneId) return null;
            String[] xyz = point.substring(colon + 1, point.length() - 1).split(",");
            if (xyz.length < 3) return null;
            float x = Float.parseFloat(xyz[0].trim());
            float y = Float.parseFloat(xyz[1].trim());
            float z = Float.parseFloat(xyz[2].trim());
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) return null;
            return new Position(x, y, z);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    public static boolean outsideRange(int currentScene, Position position,
            int[] target, String pointAndRooms, boolean allowRooms) {
        if (target == null || target.length < 2 || target[0] <= 0 || target[1] <= 0
                || currentScene <= 0 || position == null || pointAndRooms == null) return false;
        String[] parts = pointAndRooms.split(";", -1);
        if (allowRooms && parts.length > 1) {
            try {
                if (Arrays.stream(parts[1].split(","))
                        .map(String::trim).mapToInt(Integer::parseInt).anyMatch(id -> id == currentScene))
                    return false;
            } catch (NumberFormatException exception) {
                return false;
            }
        }
        Position center = dummyPoint(target[0], parts[0]);
        if (center == null) return false;
        return currentScene != target[0] || position.computeDistance(center) > target[1];
    }
}
