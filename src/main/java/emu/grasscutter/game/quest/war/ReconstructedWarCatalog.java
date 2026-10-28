package emu.grasscutter.game.quest.war;

import emu.grasscutter.game.world.Position;
import emu.grasscutter.utils.FileUtils;
import emu.grasscutter.utils.JsonUtils;
import java.io.IOException;
import java.util.*;

/** Explicitly custom encounter definitions. The file records every original 5011–5013 node. */
public final class ReconstructedWarCatalog {
    public static final int SCENE = 20238, DUNGEON = 1222;
    public static final class Enemy { public int configId, monsterId, level; public Position position; }
    public static final class Npc { public int configId, id; public Position position; }
    public static final class Stage {
        public int questId, mainId, order, groupId, suite, regionId, regionConfig, operationConfig, portalConfig;
        public String kind, title, luaKey;
        public Position position, destination, landing;
        public boolean gates;
        public Map<Integer, Integer> selectionGadgets = Map.of();
        public List<List<Enemy>> waves = List.of();
        public List<Npc> npcs = List.of();
        public List<Enemy> enemies() { return waves.stream().flatMap(Collection::stream).toList(); }
    }
    private static final class File { int schema, sceneId, dungeonId; boolean custom; List<Stage> stages; Stage exit; }
    public record EnemyBinding(Stage stage, Enemy enemy) {}
    private final List<Stage> stages;
    private final Map<Integer, Stage> byQuest = new HashMap<>();
    private final Map<Long, EnemyBinding> enemies = new HashMap<>();
    private final Set<Integer> groups = new HashSet<>();
    private final Set<String> luaKeys = new HashSet<>();
    private final Stage exit;
    private final Map<Long, Stage> gadgets = new HashMap<>();
    private static volatile ReconstructedWarCatalog installed;

    private ReconstructedWarCatalog(File file) {
        if (file.schema != 1 || !file.custom || file.sceneId != SCENE || file.dungeonId != DUNGEON)
            throw new IllegalStateException("Not an AstaPS custom war reconstruction");
        stages = List.copyOf(file.stages);
        exit = file.exit;
        for (var stage : stages) {
            if (byQuest.put(stage.questId, stage) != null) throw new IllegalStateException("Duplicate war quest");
            if (stage.groupId != 0) groups.add(stage.groupId);
            if (stage.luaKey != null && !stage.luaKey.isEmpty()) luaKeys.add(stage.luaKey);
            for (int config : stage.selectionGadgets.keySet()) gadgets.put(key(stage.groupId,config),stage);
            if (stage.kind.equals("TRAVEL") || stage.gates) gadgets.put(key(stage.groupId,stage.operationConfig),stage);
            if (stage.gates) gadgets.put(key(stage.groupId,stage.portalConfig),stage);
            for (var enemy : stage.enemies())
                if (enemies.put(key(stage.groupId, enemy.configId), new EnemyBinding(stage, enemy)) != null)
                    throw new IllegalStateException("Duplicate war monster config");
        }
        if (stages.size() != 112 || groups.size() != 9) throw new IllegalStateException("Incomplete custom war");
        if (exit == null || exit.groupId != 220238046 || !"EXIT".equals(exit.kind))
            throw new IllegalStateException("Missing custom war exit");
        gadgets.put(key(exit.groupId,exit.operationConfig),exit);
    }

    public static ReconstructedWarCatalog installed() {
        if (installed == null) synchronized (ReconstructedWarCatalog.class) {
            if (installed == null) try {
                installed = new ReconstructedWarCatalog(JsonUtils.loadToClass(FileUtils.getScriptPath(
                        "Scene/20238/ReconstructedWar.json"), File.class));
            } catch (IOException error) { throw new IllegalStateException("Cannot read custom Natlan war", error); }
        }
        return installed;
    }

    private static long key(int group, int config) { return (long) group << 32 | Integer.toUnsignedLong(config); }
    public List<Stage> stages() { return stages; }
    public Stage stage(int quest) { return byQuest.get(quest); }
    public Stage exit() { return exit; }
    public Stage gadget(int group, int config) { return gadgets.get(key(group,config)); }
    public Set<Integer> groups() { return Set.copyOf(groups); }
    public EnemyBinding enemy(int group, int config) { return enemies.get(key(group, config)); }
    public boolean serverOwnedLua(String key) { return luaKeys.contains(key); }
}
