package emu.grasscutter.game.quest.rename;

import dev.morphia.annotations.Entity;
import java.util.*;

/** The companion's actual player-owned name, independent of avatar rename IDs. */
@Entity(useDiscriminator = false)
public final class QuestMiscRenameState {
    private Map<Integer, String> names = new HashMap<>();
    public synchronized String getName(int id) { return names.get(id); }
    public synchronized boolean hasName(int id) { return names.containsKey(id); }
    public synchronized Map<Integer, String> getNames() { return Map.copyOf(names); }
    synchronized void setName(int id, String name) { names.put(id,name); }
}
