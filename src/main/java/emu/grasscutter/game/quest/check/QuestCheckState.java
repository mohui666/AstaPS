package emu.grasscutter.game.quest.check;

import dev.morphia.annotations.Entity;
import java.util.*;
import lombok.Getter;

/** Saved server rolls. A missing entry is neither a successful roll nor a failed roll. */
@Entity(useDiscriminator = false)
public final class QuestCheckState {
    @Getter @Entity(useDiscriminator = false)
    public static final class Result {
        private int checkId;
        private int parentQuestId;
        private int dialogueQuestId;
        private int dialogueStartTime;
        private int dieRoll;
        private int totalRoll;
        private boolean success;
        private List<Integer> appliedBonusIds = new ArrayList<>();

        public Result() {}
        Result(QuestCheckCatalog.Check check, int startTime, int roll, int total, List<Integer> bonuses) {
            checkId = check.id(); parentQuestId = check.parent(); dialogueQuestId = check.dialogue();
            dialogueStartTime = startTime; dieRoll = roll; totalRoll = total;
            success = total >= check.difficulty();
            appliedBonusIds.addAll(bonuses);
        }
    }

    private Map<Integer, Result> results = new HashMap<>();

    public Result get(int id) { return results == null ? null : results.get(id); }
    public Collection<Result> values() { return results == null ? List.of() : List.copyOf(results.values()); }
    void put(Result result) {
        if (results == null) results = new HashMap<>();
        results.put(result.checkId, result);
    }
    boolean clearForDialogue(int id) {
        return results != null && results.values().removeIf(value -> value.dialogueQuestId == id);
    }
}
