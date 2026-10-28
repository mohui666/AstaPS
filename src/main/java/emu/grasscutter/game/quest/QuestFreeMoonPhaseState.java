package emu.grasscutter.game.quest;

import dev.morphia.annotations.Entity;
import lombok.Getter;

/** An actual quest activation, never inferred from old completed quests at login. */
@Getter
@Entity(useDiscriminator = false)
public final class QuestFreeMoonPhaseState {
    private long activatedAt;
    private int questId;

    void activate(long now, int sourceQuest) {
        if (now <= 0 || sourceQuest <= 0) throw new IllegalArgumentException("Invalid free moon activation");
        activatedAt = now;
        questId = sourceQuest;
    }

    void clear() {
        activatedAt = 0;
        questId = 0;
    }
}
