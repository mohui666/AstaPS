package emu.grasscutter.game.quest.reconstruction;

import emu.grasscutter.game.world.Position;
import emu.grasscutter.utils.FileUtils;
import emu.grasscutter.utils.JsonUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import lombok.Getter;

/** Only the explicitly reviewed reconstruction list; unknown quests never get a fallback stage. */
public final class StoryReconstructionCatalog {
    private boolean reconstructed;
    private int version;
    @Getter private List<Group> groups = List.of();
    @Getter private List<Stage> stages = List.of();
    private transient Map<Integer, Stage> byQuest;
    private transient Set<String> luaKeys;

    @Getter public static final class Group {
        private int sceneId;
        private int groupId;
        private Position position;
        private int suiteCount;
        private int controllerConfigId = 900001;
        private int controllerGadgetId = 70350081;
        private boolean sceneryVariant;
    }

    @Getter public static final class Notification {
        private String key;
        private int count;
    }

    @Getter public static final class GadgetEffect {
        private int groupId;
        private int configId;
        private int state;
    }

    @Getter public static final class Stage {
        private int questId;
        private int sceneId;
        private int groupId;
        private String kind;
        private String title;
        private Position position;
        private boolean anchorAtPlayer;
        private float radius = 3;
        private List<Position> points = List.of();
        private List<List<Integer>> waves = List.of();
        private List<Notification> notifications = List.of();
        private List<GadgetEffect> gadgetEffects = List.of();
        private int targetGadgetId = 70350081;
        private int carrierGadgetId = 70330500;
        private float speed = 6;
        private float holdSeconds;
        private int monsterLevel = 35;
        private int dungeonId;
        private int pointId;
        private boolean reconstructed;

        public int configId(int slot) { return Math.toIntExact((long) questId * 100 + slot); }
        public int monsterCount() { return waves.stream().mapToInt(List::size).sum(); }
    }

    private static final class Installed {
        static final StoryReconstructionCatalog VALUE = read();
    }

    public static StoryReconstructionCatalog installed() { return Installed.VALUE; }
    public Stage stage(int questId) { return byQuest.get(questId); }
    public boolean ownsKey(String key) { return luaKeys.contains(key); }
    public Group group(int scene, int group) {
        return groups.stream().filter(g -> g.sceneId == scene && g.groupId == group).findFirst().orElse(null);
    }

    private static StoryReconstructionCatalog read() {
        try {
            return parse(Files.readString(FileUtils.getDataPath("QuestStoryReconstruction.json")));
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read explicit story reconstruction list", error);
        }
    }

    public static StoryReconstructionCatalog parse(String json) {
        var result = JsonUtils.decode(json, StoryReconstructionCatalog.class);
        if (!result.reconstructed || result.version != 1)
            throw new IllegalArgumentException("Story reconstruction must be explicitly identified as version 1");
        result.byQuest = new LinkedHashMap<>();
        result.luaKeys = new HashSet<>();
        var groupKeys = new HashSet<String>();
        for (var group : result.groups) {
            if (group.sceneId <= 0 || group.groupId <= 0 || !finite(group.position)
                    || group.suiteCount <= 0 || group.controllerConfigId <= 0 || group.controllerGadgetId <= 0
                    || !groupKeys.add(group.sceneId + ":" + group.groupId))
                throw new IllegalArgumentException("Invalid explicit reconstruction group");
        }
        var kinds = Set.of("arrival", "battle", "sequence", "collect", "travel", "board", "enterDungeon");
        for (var stage : result.stages) {
            if (stage.questId <= 0 || stage.sceneId <= 0 || !kinds.contains(stage.kind)
                    || !stage.anchorAtPlayer && !finite(stage.position)
                    || !Float.isFinite(stage.radius) || stage.radius <= 0
                    || result.byQuest.put(stage.questId, stage) != null)
                throw new IllegalArgumentException("Invalid explicit reconstruction stage");
            stage.configId(99); // Stable config slots must fit the native integer field.
            if (stage.points.stream().anyMatch(p -> !finite(p)))
                throw new IllegalArgumentException("Invalid reconstructed stage points");
            if (stage.kind.equals("battle") && (stage.monsterCount() <= 0 || stage.monsterCount() > 49
                    || stage.waves.stream().anyMatch(w -> w.isEmpty() || w.stream().anyMatch(id -> id <= 0))))
                throw new IllegalArgumentException("A reconstructed battle needs explicit real monster waves");
            if ((stage.kind.equals("sequence") || stage.kind.equals("collect"))
                    && (stage.points.isEmpty() || stage.points.size() > 29 || stage.targetGadgetId <= 0))
                throw new IllegalArgumentException("A reconstructed sequence needs explicit targets");
            if (stage.kind.equals("travel") && (stage.points.size() < 2 || stage.carrierGadgetId <= 0
                    || !Float.isFinite(stage.speed) || stage.speed <= 0))
                throw new IllegalArgumentException("A reconstructed journey needs an explicit moving route");
            if(stage.kind.equals("board")&&(stage.carrierGadgetId<=0||stage.holdSeconds<=0))
                throw new IllegalArgumentException("Boarding requires an actual platform and a standing interval");
            if (stage.kind.equals("enterDungeon") && (stage.dungeonId <= 0 || stage.pointId <= 0
                    || !stage.notifications.isEmpty()))
                throw new IllegalArgumentException("Dungeon entry must use its native point and admission");
            if (!stage.kind.equals("enterDungeon") && stage.notifications.isEmpty() && stage.gadgetEffects.isEmpty())
                throw new IllegalArgumentException("Missing original completion key or explicit gadget target");
            for (var notification : stage.notifications) {
                if (notification.key == null || notification.key.isBlank() || notification.count <= 0)
                    throw new IllegalArgumentException("Invalid original story notification");
                result.luaKeys.add(notification.key);
            }
            if (stage.gadgetEffects.stream().anyMatch(effect -> effect.groupId <= 0
                    || effect.configId <= 0 || effect.state < 0))
                throw new IllegalArgumentException("Invalid explicit reconstructed gadget effect");
            if ((stage.kind.equals("sequence") || stage.kind.equals("collect"))
                    && stage.points.size() < stage.notifications.size())
                throw new IllegalArgumentException("Separate notification keys need independent actions");
            if (!Float.isFinite(stage.holdSeconds) || stage.holdSeconds < 0 || stage.monsterLevel < 1)
                throw new IllegalArgumentException("Invalid reconstruction duration/monster level");
        }
        result.groups = List.copyOf(result.groups);
        result.stages = List.copyOf(result.stages);
        return result;
    }

    static boolean finite(Position position) {
        return position != null && Float.isFinite(position.getX())
                && Float.isFinite(position.getY()) && Float.isFinite(position.getZ());
    }
}
