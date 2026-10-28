package emu.grasscutter.game.quest.coop;

import com.google.gson.*;
import emu.grasscutter.data.GameData;
import emu.grasscutter.utils.FileUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

/** Original interaction/save-point data; admission and rewards retain the installed Excel tables. */
public final class CoopCatalog {
    public record Rule(String type, int[] args) {}
    public record Reward(int id, int chapterId, int rewardId, List<Rule> conditions) {}
    public record Cg(int id, int chapterId, int pointId, List<Rule> conditions) {}
    public record Chapter(int id, int initialConfidence, int maxConfidence,
            Map<Integer, Boolean> savePoints, Map<Integer, Integer> normalVars,
            Map<Integer, Integer> tempVars, Set<Integer> retainedTempVars,
            Map<Integer, Set<Integer>> interactionSaves) {
        public boolean isEnd(int savePoint) { return Boolean.TRUE.equals(savePoints.get(savePoint)); }
    }

    private static final class Installed {
        static final Map<Integer, Chapter> CHAPTERS = loadChapters();
        static final List<Reward> REWARDS = loadRewards();
        static final List<Cg> CGS = loadCgs();
    }

    private CoopCatalog() {}

    public static Chapter chapter(int id) { return Installed.CHAPTERS.get(id); }
    public static List<Reward> rewards() { return Installed.REWARDS; }
    public static List<Cg> cgs() { return Installed.CGS; }

    public static int chapterForQuest(int mainQuestId) {
        var data = GameData.getMainQuestDataMap().get(mainQuestId);
        return data != null && GameData.getCoopChapterDataMap().containsKey(data.getSeries())
                ? data.getSeries() : 0;
    }

    private static JsonArray array(String path) {
        return read(path).getAsJsonArray();
    }

    private static JsonElement read(String path) {
        try { return JsonParser.parseString(Files.readString(FileUtils.getResourcePath(path))); }
        catch (IOException error) { throw new IllegalStateException("Cannot load hangout resource " + path, error); }
    }

    private static Map<Integer, Chapter> loadChapters() {
        var chapters = new TreeMap<Integer, Chapter>();
        for (var element : array("ExcelBinOutput/MainCoopExcelConfigData.json")) {
            var row = element.getAsJsonObject();
            int id = row.get("id").getAsInt();
            var config = read("BinOutput/Coop/" + row.get("mainCoopCfg").getAsString() + ".json")
                    .getAsJsonObject();
            var points = new HashMap<Integer, Boolean>();
            config.getAsJsonObject("savePointMap").entrySet().forEach(entry -> {
                var point = entry.getValue().getAsJsonObject();
                int key = Integer.parseInt(entry.getKey());
                if (point.get("id").getAsInt() != key || key <= 0)
                    throw new IllegalStateException("Invalid hangout save point " + id + "/" + key);
                points.put(key, point.has("isEnd") && point.get("isEnd").getAsBoolean());
            });
            var normal = new HashMap<Integer, Integer>();
            var temporary = new HashMap<Integer, Integer>();
            var retained = new HashSet<Integer>();
            if (config.has("coopTempValueMap")) config.getAsJsonObject("coopTempValueMap").entrySet()
                    .forEach(entry -> {
                        var variable = entry.getValue().getAsJsonObject();
                        int value = variable.has("param3") ? variable.get("param3").getAsInt() : 0;
                        int key = Integer.parseInt(entry.getKey());
                        temporary.put(key, value);
                        if (variable.has("isCoopVar") && variable.get("isCoopVar").getAsBoolean()) retained.add(key);
                    });
            var saves = new HashMap<Integer, Set<Integer>>();
            config.getAsJsonObject("coopInteractionMap").entrySet().forEach(entry -> {
                var keys = new HashSet<Integer>();
                var interaction = entry.getValue().getAsJsonObject();
                var nodes = interaction.has("coopMap") ? interaction.getAsJsonObject("coopMap") : new JsonObject();
                nodes.entrySet().forEach(node -> {
                    var value = node.getValue().getAsJsonObject();
                    if (value.has("savePointId") && value.get("savePointId").getAsInt() > 0)
                        keys.add(value.get("savePointId").getAsInt());
                });
                saves.put(Integer.parseInt(entry.getKey()), Set.copyOf(keys));
            });
            int confidence = config.get("confidenceValue").getAsInt();
            int max = config.get("maxConfidenceValue").getAsInt();
            // Two 7.1 Lynette wait points are absent from the recovered 5.0 interaction file.
            // Their IDs are taken from the current quest objectives, not guessed for other chapters.
            if (id == 108301) {
                points.put(191830101, false);
                points.put(191860101, false);
            }
            if (confidence < 0 || confidence > max) throw new IllegalStateException("Invalid hangout confidence " + id);
            chapters.put(id, new Chapter(id, confidence, max, Map.copyOf(points),
                    Map.copyOf(normal), Map.copyOf(temporary), Set.copyOf(retained), Map.copyOf(saves)));
        }
        return Map.copyOf(chapters);
    }

    public static List<Rule> rules(JsonObject row, String name) {
        var rules = new ArrayList<Rule>();
        if (row.has(name)) for (var entry : row.getAsJsonArray(name)) {
            var condition = entry.getAsJsonObject();
            String type = condition.has("condType") ? condition.get("condType").getAsString() : "COOP_COND_NONE";
            var values = condition.has("args") ? condition.getAsJsonArray("args") : new JsonArray();
            int[] args = new int[values.size()];
            for (int i = 0; i < args.length; i++) args[i] = values.get(i).getAsInt();
            rules.add(new Rule(type, args));
        }
        return List.copyOf(rules);
    }

    private static List<Reward> loadRewards() {
        var result = new ArrayList<Reward>();
        for (var value : array("ExcelBinOutput/CoopRewardExcelConfigData.json")) {
            var row = value.getAsJsonObject();
            result.add(new Reward(row.get("id").getAsInt(), row.get("chapterId").getAsInt(),
                    row.get("rewardId").getAsInt(), rules(row, "rewardCond")));
        }
        return List.copyOf(result);
    }

    private static List<Cg> loadCgs() {
        var result = new ArrayList<Cg>();
        for (var value : array("ExcelBinOutput/CoopCGExcelConfigData.json")) {
            var row = value.getAsJsonObject();
            result.add(new Cg(row.get("id").getAsInt(), row.get("chapterId").getAsInt(),
                    row.get("unlockPointId").getAsInt(), rules(row, "unlockCond")));
        }
        return List.copyOf(result);
    }
}
