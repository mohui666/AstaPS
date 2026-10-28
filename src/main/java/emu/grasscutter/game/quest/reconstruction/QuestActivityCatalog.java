package emu.grasscutter.game.quest.reconstruction;

import com.google.gson.*;
import emu.grasscutter.game.world.Position;
import emu.grasscutter.game.quest.enums.QuestContent;
import emu.grasscutter.utils.FileUtils;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;

/** A fixed allow-list: installing a new quest never automatically invents a replacement for it. */
public final class QuestActivityCatalog {
    public record Goal(QuestContent type, int activity, int trigger, int count) {
        public String key() { return type.getValue() + ":" + activity + ":" + trigger; }
    }
    public record Trial(int quest, int parent, int scene, Position position, boolean anchorAtPlayer,
            String title, String kind, boolean observer, List<Goal> goals) {}
    private final Map<Integer, Trial> trials;
    private static final class Installed { static final QuestActivityCatalog VALUE = load(); }
    private QuestActivityCatalog(Map<Integer, Trial> trials) { this.trials = Map.copyOf(trials); }
    public static QuestActivityCatalog installed() { return Installed.VALUE; }
    public Trial trial(int quest) { return trials.get(quest); }
    public Collection<Trial> trials() { return trials.values(); }
    private static QuestActivityCatalog load() {
        try {
            var json = JsonParser.parseString(Files.readString(FileUtils.getDataPath("QuestActivityTrials.json"))).getAsJsonObject();
            if (!json.get("reconstructed").getAsBoolean()) throw new IllegalStateException("Activity trial rules must identify reconstruction");
            var trials = new LinkedHashMap<Integer, Trial>();
            for (var value : json.getAsJsonArray("trials")) {
                var row = value.getAsJsonObject(); var goals = new ArrayList<Goal>();
                for (var entry : row.getAsJsonArray("goals")) {
                    var g = entry.getAsJsonObject(); var goal = new Goal(QuestContent.valueOf(g.get("type").getAsString()),g.get("activity").getAsInt(), g.get("trigger").getAsInt(), g.get("count").getAsInt());
                    if (!Set.of(QuestContent.QUEST_CONTENT_ACTIVITY_TRIGGER_UPDATE,QuestContent.QUEST_CONTENT_IRODORI_FINISH_FLOWER_COMBINATION,
                            QuestContent.QUEST_CONTENT_IRODORI_POETRY_REACH_MIN_PROGRESS,QuestContent.QUEST_CONTENT_IRODORI_POETRY_FINISH_FILL_POETRY).contains(goal.type())
                            || goal.activity() <= 0 || goal.trigger() < 0 || goal.count() <= 0 || goals.contains(goal)) throw new IllegalStateException("Invalid activity trial goal");
                    goals.add(goal);
                }
                Position point = null;
                if (!row.get("position").isJsonNull()) {
                    var p = row.getAsJsonArray("position");
                    point = new Position(p.get(0).getAsFloat(),p.get(1).getAsFloat(),p.get(2).getAsFloat());
                    if (!Float.isFinite(point.getX()) || !Float.isFinite(point.getY()) || !Float.isFinite(point.getZ())) throw new IllegalStateException("Invalid activity trial point");
                }
                var trial = new Trial(row.get("quest").getAsInt(),row.get("parent").getAsInt(),row.get("scene").getAsInt(),point,
                        row.get("anchorAtPlayer").getAsBoolean(),row.get("title").getAsString(),row.get("kind").getAsString(),row.get("observer").getAsBoolean(),List.copyOf(goals));
                if (trial.quest() <= 0 || trial.parent() <= 0 || trial.scene() <= 0 || goals.isEmpty()
                        || !Set.of("sequence","alchemy","choice").contains(trial.kind()) || trial.anchorAtPlayer() != (point == null)
                        || trials.put(trial.quest(),trial) != null) throw new IllegalStateException("Invalid activity trial " + trial.quest());
            }
            return new QuestActivityCatalog(trials);
        } catch (IOException error) { throw new IllegalStateException("Cannot load activity trial reconstruction",error); }
    }
}
