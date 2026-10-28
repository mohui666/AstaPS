package emu.grasscutter.game.quest.reconstruction;

import dev.morphia.annotations.Entity;
import emu.grasscutter.game.world.Position;
import java.util.*;
import lombok.Getter;
import lombok.Setter;

/** Stable quest/config slots survive relog; scene entity IDs and global Lua templates do not. */
@Entity(useDiscriminator = false)
public final class StoryReconstructionState {
    private Map<Integer, StageProgress> stages = new HashMap<>();

    @Getter @Setter @Entity(useDiscriminator = false)
    public static final class StageProgress {
        private Position anchor;
        private boolean started;
        private boolean complete;
        private boolean actionsComplete;
        private int wave;
        private Map<Integer, Float> monsterHp = new HashMap<>();
        private Set<Integer> switches = new HashSet<>();
        private Map<String, Integer> emitted = new HashMap<>();
        private int boatNode;
        private boolean riding;
        private int arrivalActions;
        private boolean departedAnchor;
    }

    public synchronized StageProgress get(int questId) { return stages.get(questId); }
    public synchronized StageProgress ensure(int questId) {
        return stages.computeIfAbsent(questId, ignored -> new StageProgress());
    }
    public synchronized StageProgress reset(int questId) {
        var next = new StageProgress();
        stages.put(questId, next);
        return next;
    }
}
