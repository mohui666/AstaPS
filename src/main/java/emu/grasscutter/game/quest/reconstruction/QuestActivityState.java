package emu.grasscutter.game.quest.reconstruction;

import dev.morphia.annotations.*;
import emu.grasscutter.game.entity.EntityGadget;
import emu.grasscutter.game.world.*;
import java.util.*;
import lombok.*;

/** Only the explicit replacement trials own these counters; ordinary activity data stays separate. */
@Getter @Entity(useDiscriminator = false)
public final class QuestActivityState {
    private Map<Integer, Trial> trials = new HashMap<>();
    private Map<Integer, Map<Integer, Boolean>> conditions = new HashMap<>();
    private Map<Integer, Integer> crops = new HashMap<>();
    private Set<Integer> cropGrants = new HashSet<>();
    @Getter @Setter @Entity(useDiscriminator = false)
    public static final class Trial {
        private int questStart;
        private Position anchor;
        private int goal;
        private int choice = -1;
        private boolean settled;
        private int pendingDungeon;
        private int step;
        private List<Integer> sequence = new ArrayList<>();
        private Map<String, Integer> completed = new HashMap<>();
        @Transient private transient Map<Integer, EntityGadget> actors = new HashMap<>();
        @Transient private transient Scene actorScene;
    }
}
