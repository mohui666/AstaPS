package emu.grasscutter.game.quest.coop;

import com.google.gson.*;
import emu.grasscutter.utils.FileUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

/** Explicit, editable reconstruction of missing hangout admission; no runtime order guessing. */
public final class CoopProgressionCatalog {
    public record Stage(int quest, String label, String kind, List<Integer> nextStages,
            List<Integer> nextPoints, Set<Integer> reportSaves) {}
    public record Auxiliary(int quest, String label, String kind, boolean observer, Set<Integer> reportSaves) {}
    public record Point(int id, int chapter, int entryQuest, int forwardEntryQuest, String label,
            Map<Integer, Stage> stages, Map<Integer, Auxiliary> auxiliary) {}
    private final Map<Integer, Point> points;
    private final Map<String, Integer> endings;
    private final Set<Integer> chapters;
    private final Set<Integer> stageQuests;
    private static final class Installed { static final CoopProgressionCatalog VALUE = load(); }
    private CoopProgressionCatalog(Map<Integer, Point> points, Map<String, Integer> endings) {
        this.points = Map.copyOf(points);
        this.endings = Map.copyOf(endings);
        var chapters = new HashSet<Integer>();
        points.values().forEach(point -> chapters.add(point.chapter()));
        this.chapters = Set.copyOf(chapters);
        var stages = new HashSet<Integer>();
        points.values().forEach(point -> stages.addAll(point.stages().keySet()));
        this.stageQuests = Set.copyOf(stages);
    }
    public static CoopProgressionCatalog installed() { return Installed.VALUE; }
    public Point point(int id) { return points.get(id); }
    public Collection<Point> points() { return points.values(); }
    public boolean chapter(int id) { return chapters.contains(id); }
    public boolean stageQuest(int id) { return stageQuests.contains(id); }
    public int endingPoint(int chapter, int point, int save) {
        var definition = points.get(point);
        return definition == null || definition.chapter() != chapter ? 0
                : endings.getOrDefault(point + ":" + save, 0);
    }
    private static List<Integer> ints(JsonObject row, String key) {
        var result = new ArrayList<Integer>();
        for (var value : row.getAsJsonArray(key)) {
            int id = value.getAsInt();
            if (id <= 0 || result.contains(id)) throw new IllegalStateException("Invalid hangout route " + key);
            result.add(id);
        }
        return List.copyOf(result);
    }
    private static CoopProgressionCatalog load() {
        try {
            var json = JsonParser.parseString(Files.readString(FileUtils.getDataPath(
                    "QuestCoopRoutes.json"))).getAsJsonObject();
            if (!json.get("reconstructed").getAsBoolean())
                throw new IllegalStateException("Hangout routes must identify reconstructed admission");
            var points = new LinkedHashMap<Integer, Point>();
            for (var element : json.getAsJsonArray("points")) {
                var row = element.getAsJsonObject();
                int id = row.get("id").getAsInt(), chapter = row.get("chapter").getAsInt();
                var stages = new LinkedHashMap<Integer, Stage>();
                var auxiliary = new LinkedHashMap<Integer, Auxiliary>();
                for (var value : row.getAsJsonArray("stages")) {
                    var item = value.getAsJsonObject();
                    int quest = item.get("quest").getAsInt();
                    var stage = new Stage(quest, item.get("label").getAsString(), item.get("kind").getAsString(), ints(item,"nextStages"),
                            ints(item,"nextPoints"), Set.copyOf(ints(item,"reportSaves")));
                    if (quest <= 0 || !Set.of("original","checkpoint").contains(stage.kind()) || stages.put(quest, stage) != null
                            || !stage.nextStages().isEmpty() && !stage.nextPoints().isEmpty())
                        throw new IllegalStateException("Invalid hangout stage " + id + "/" + quest);
                }
                for (var value : row.getAsJsonArray("auxiliary")) {
                    var item = value.getAsJsonObject(); int quest = item.get("quest").getAsInt();
                    if (quest <= 0 || stages.containsKey(quest) || auxiliary.put(quest, new Auxiliary(quest,
                            item.get("label").getAsString(), item.get("kind").getAsString(), item.get("observer").getAsBoolean(),
                            Set.copyOf(ints(item,"reportSaves")))) != null)
                        throw new IllegalStateException("Invalid hangout auxiliary " + id + "/" + quest);
                }
                var point = new Point(id, chapter, row.get("entryQuest").getAsInt(), row.get("forwardEntryQuest").getAsInt(),
                        row.get("label").getAsString(), Collections.unmodifiableMap(stages),
                        Collections.unmodifiableMap(auxiliary));
                if (id <= 0 || chapter <= 0 || !stages.containsKey(point.entryQuest())
                        || !stages.containsKey(point.forwardEntryQuest())
                        || points.put(id,point) != null)
                    throw new IllegalStateException("Invalid hangout point " + id);
            }
            for (var point : points.values()) for (var stage : point.stages().values()) {
                for (int next : stage.nextStages()) if (!point.stages().containsKey(next))
                    throw new IllegalStateException("Unknown hangout next stage " + next);
                for (int next : stage.nextPoints()) if (!points.containsKey(next)
                        || points.get(next).chapter() != point.chapter())
                    throw new IllegalStateException("Unknown hangout next point " + next);
            }
            var endings = new HashMap<String, Integer>();
            for (var value : json.getAsJsonArray("endings")) {
                var row = value.getAsJsonObject(); int point = row.get("point").getAsInt();
                var owner = points.get(point);
                int save = row.get("savePoint").getAsInt(), end = row.get("endPoint").getAsInt();
                if (owner == null || owner.chapter() != row.get("chapter").getAsInt()
                        || save <= 0 || end <= 0 || endings.put(point + ":" + save,end) != null)
                    throw new IllegalStateException("Invalid hangout ending " + point + "/" + save);
            }
            return new CoopProgressionCatalog(points,endings);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot load reconstructed hangout admission",error);
        }
    }
}
