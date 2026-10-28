package emu.grasscutter.game.gcg.story;

import dev.morphia.annotations.Entity;
import emu.grasscutter.game.world.Position;
import java.util.*;
import lombok.Getter;
import lombok.Setter;

/** Persistent, task-only reconstructed card games. No native GCG ranking/deck rewards are granted. */
@Getter
@Entity(useDiscriminator = false)
public final class StoryCardState {
    @Getter @Setter @Entity(useDiscriminator = false)
    public static final class Duel {
        private int questId;
        private int questStart;
        private int levelId;
        private int sceneId;
        private Position anchor;
        private boolean tutorial;
        private int round;
        private int actionPoints;
        private int playerHp;
        private int playerMaxHp;
        private int opponentHp;
        private int opponentMaxHp;
        private int shield;
        private int drawIndex;
        private int outcome;
        private boolean emitted;
        private int page;
        private List<Integer> deck = new ArrayList<>();
        private List<Integer> hand = new ArrayList<>();
        private List<Integer> discard = new ArrayList<>();
    }

    @Setter private Duel duel;
    private int tutorialProgress;
    private Set<String> outcomes = new HashSet<>();

    static String key(int quest, int start, int level, int result) {
        return quest + ":" + start + ":" + level + ":" + result;
    }
    public boolean hasOutcome(int quest, int start, int level, int result) {
        return outcomes != null && outcomes.contains(key(quest, start, level, result));
    }
    boolean record(int quest, int start, int level, int result) {
        if (outcomes == null) outcomes = new HashSet<>();
        return outcomes.add(key(quest, start, level, result));
    }
    void advanceTutorial() { tutorialProgress++; }
    boolean resetQuest(int quest) {
        boolean changed = outcomes != null && outcomes.removeIf(value -> value.startsWith(quest + ":"));
        if (duel != null && duel.questId == quest) { duel = null; changed = true; }
        return changed;
    }
}
