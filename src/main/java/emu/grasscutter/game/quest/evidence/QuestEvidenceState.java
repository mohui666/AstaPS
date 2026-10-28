package emu.grasscutter.game.quest.evidence;

import dev.morphia.annotations.Entity;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;

/** Quest event evidence is scoped to its event, not to the ordinary item inventory. */
@Entity(useDiscriminator = false)
public final class QuestEvidenceState {
    private Map<Integer, EvidenceEvent> events = new HashMap<>();
    private Map<Integer, Integer> submitStates = new HashMap<>();
    private Map<Integer, EvidenceGiving> givings = new HashMap<>();

    public synchronized int getStatus(int eventId, int itemId) {
        var event = events.get(eventId);
        return event == null ? 0 : event.itemStatuses.getOrDefault(itemId, 0);
    }

    public synchronized boolean unlock(int eventId, Iterable<Integer> itemIds) {
        if (eventId <= 0) throw new IllegalArgumentException("Invalid quest event");
        var ids = new java.util.ArrayList<Integer>();
        for (int itemId : itemIds) {
            if (itemId <= 0) throw new IllegalArgumentException("Invalid evidence item");
            ids.add(itemId);
        }
        if (ids.isEmpty()) throw new IllegalArgumentException("Empty evidence grant");
        var event = events.computeIfAbsent(eventId, ignored -> new EvidenceEvent());
        boolean changed = false;
        for (int itemId : ids) {
            changed |= event.itemStatuses.putIfAbsent(itemId, 1) == null;
        }
        return changed;
    }

    public synchronized int getSubmitState(int givingId) {
        return submitStates.getOrDefault(givingId, 0);
    }

    public synchronized void setSubmitState(int givingId, boolean success) {
        if (givingId <= 0) throw new IllegalArgumentException("Invalid evidence giving");
        // QUEST_COND_EVENT_ITEM_SUBMIT_STATE_EQUAL uses 1 for correct and 2 for incorrect.
        submitStates.put(givingId, success ? 1 : 2);
    }

    public synchronized Map<Integer, Integer> getItemStatuses(int eventId) {
        var event = events.get(eventId);
        return event == null ? Map.of() : Map.copyOf(event.itemStatuses);
    }

    public synchronized Set<Integer> getEventIds() { return Set.copyOf(events.keySet()); }

    public synchronized List<GivingProgress> getGivings() {
        return givings.entrySet().stream().map(entry -> new GivingProgress(entry.getKey(),
                entry.getValue().finished, List.copyOf(entry.getValue().itemIds))).toList();
    }

    public synchronized boolean hasGiven(int givingId, int itemId) {
        var giving = givings.get(givingId);
        return giving != null && giving.itemIds.contains(itemId);
    }

    public synchronized boolean isGivingFinished(int givingId) {
        var giving = givings.get(givingId);
        return giving != null && giving.finished;
    }

    public synchronized boolean recordCorrectItem(int givingId, int itemId, List<Integer> required) {
        var giving = givings.computeIfAbsent(givingId, ignored -> new EvidenceGiving());
        if (giving.itemIds.contains(itemId)) return false;
        giving.itemIds.add(itemId);
        giving.finished = giving.itemIds.containsAll(required);
        submitStates.put(givingId, giving.finished ? 1 : 2);
        return true;
    }

    public synchronized void recordIncorrectItem(int givingId) {
        givings.computeIfAbsent(givingId, ignored -> new EvidenceGiving());
        submitStates.put(givingId, 2);
    }

    public record GivingProgress(int givingId, boolean finished, List<Integer> itemIds) {}

    @Entity(useDiscriminator = false)
    public static final class EvidenceGiving {
        private List<Integer> itemIds = new ArrayList<>();
        private boolean finished;
    }

    @Entity(useDiscriminator = false)
    public static final class EvidenceEvent {
        private Map<Integer, Integer> itemStatuses = new HashMap<>();
    }
}
