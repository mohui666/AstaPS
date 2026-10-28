package emu.grasscutter.game.quest.delivery;

import dev.morphia.annotations.*;
import emu.grasscutter.game.entity.EntityGadget;
import emu.grasscutter.game.world.Position;
import java.util.*;
import lombok.*;

@Getter @Entity(useDiscriminator=false)
public final class QuestDeliveryState {
    private Map<Integer, Round> rounds=new HashMap<>();
    @Getter @Setter @Entity(useDiscriminator=false)
    public static final class Round {
        private int questStart;
        private int startSequence;
        private long deadline;
        private Position anchor;
        private boolean failed;
        private String failure;
        @Transient private transient EntityGadget abandon;
    }
}
