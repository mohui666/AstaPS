package emu.grasscutter.game.quest.scenery;

import dev.morphia.annotations.Entity;
import java.util.*;
import lombok.Getter;
import lombok.Setter;

/** Account-owned scenery progress. Temporary scene actors are never persisted here. */
@Entity
@Getter
public final class QuestSceneryState {
    private Map<Integer, Entry> entries = new HashMap<>();
    public Entry entry(int primary) { return entries.computeIfAbsent(primary, ignored -> new Entry()); }

    @Entity
    @Getter
    @Setter
    public static final class Entry {
        private int state;
        private int subSceneryId;
        private Set<Integer> pausedQuests = new HashSet<>();
        private Map<Integer,Integer> resumeStarts = new HashMap<>();
    }
}
