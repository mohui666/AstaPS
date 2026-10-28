package emu.grasscutter.game.quest.field;

import dev.morphia.annotations.Entity;
import emu.grasscutter.game.world.Position;
import java.util.*;
import lombok.Getter;
import lombok.Setter;

/** Real per-acceptance catches and surveys; an unfinished cast or scan never becomes a saved success. */
@Entity
@Getter
public final class QuestFieldState {
    private Map<Integer,Progress> rounds=new HashMap<>();
    @Setter private long lastCompassUse;
    @Setter private boolean diveExecObserved;
    public Progress round(int quest){return rounds.computeIfAbsent(quest,ignored->new Progress());}
    public void reset(int quest){rounds.put(quest,new Progress());}
    @Entity @Getter @Setter
    public static final class Progress {
        private Position anchor;
        private int count;
        private int sourceId;
        private int poolId;
        private int avatarId;
        private int materialId;
    }
}
