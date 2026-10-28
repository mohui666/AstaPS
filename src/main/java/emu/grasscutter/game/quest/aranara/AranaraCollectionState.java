package emu.grasscutter.game.quest.aranara;

import dev.morphia.annotations.Entity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Player-owned clipboard pages: 0 locked, 1 collectable, 2 collected, 3 finished. */
@Entity(useDiscriminator = false)
public final class AranaraCollectionState {
    public record Change(int pageId, int fromState, int targetState) {}
    private Map<Integer, Integer> pages = new ConcurrentHashMap<>();

    public int getState(int pageId) { return pages.getOrDefault(pageId, 0); }
    public synchronized Map<Integer, Integer> getPages() { return Map.copyOf(pages); }

    /** A native quest can explicitly change either direction; unchanged values produce no notify. */
    public synchronized Change modify(int pageId, int targetState) {
        if (pageId <= 0 || !AranaraCollectionCatalog.validState(targetState))
            throw new IllegalArgumentException("Invalid Aranara page state");
        int previous = getState(pageId);
        if (previous == targetState) return null;
        if (targetState == 0) pages.remove(pageId); else pages.put(pageId, targetState);
        return new Change(pageId, previous, targetState);
    }

    public synchronized List<Change> receiveAll(Set<Integer> pageIds) {
        var changes = new ArrayList<Change>();
        for (int pageId : pageIds.stream().sorted().toList()) {
            if (getState(pageId) != 1) continue;
            changes.add(modify(pageId, 2));
        }
        return List.copyOf(changes);
    }
}
