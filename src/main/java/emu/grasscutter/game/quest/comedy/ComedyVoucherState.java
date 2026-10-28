package emu.grasscutter.game.quest.comedy;

import dev.morphia.annotations.Entity;
import emu.grasscutter.game.world.Position;
import java.util.*;
import lombok.*;

@Entity(useDiscriminator = false)
public final class ComedyVoucherState {
    private Map<Integer, Progress> stages = new HashMap<>();
    @Getter @Setter @Entity(useDiscriminator = false)
    public static final class Progress {
        private int questStart;
        private Position anchor;
        private int earnedItems;
        private int workpiecePoints;
        private List<Integer> order = new ArrayList<>();
        private int nextAction;
        private boolean emitted;
        public long total() { return (long)earnedItems + workpiecePoints; }
    }
    public Progress get(int quest) { return stages.get(quest); }
    void put(int quest,Progress progress) { stages.put(quest,progress); }
    boolean reset(int quest) { return stages.remove(quest) != null; }
}
