package emu.grasscutter.game.quest.war;

import dev.morphia.annotations.Entity;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Player-owned progress for Natlan's one-time main-story war. */
@Entity(useDiscriminator = false)
public final class QuestWarState {
    private Map<Integer, Integer> levelStates = new ConcurrentHashMap<>();
    private Map<Integer, Integer> regionStates = new ConcurrentHashMap<>();
    private Map<Integer, Integer> accountValues = new ConcurrentHashMap<>();
    private Set<String> appliedAccountActions = ConcurrentHashMap.newKeySet();
    private Map<Integer, Integer> settledAccountValues = new ConcurrentHashMap<>();
    private boolean performanceFinished;
    private ReconstructedWarState reconstruction = new ReconstructedWarState();

    public synchronized ReconstructedWarState getReconstruction() {
        if (reconstruction == null) reconstruction = new ReconstructedWarState();
        return reconstruction;
    }

    /** The authorized custom reconstruction reports observed events, not the unavailable original formula. */
    synchronized boolean setReconstructedAccount(int id, int value) {
        if (id <= 0 || value < 0) throw new IllegalArgumentException("Invalid custom war account");
        Integer previous = accountValues.put(id, value);
        return previous == null || previous != value;
    }

    public int getLevelState(int id) { return levelStates.getOrDefault(id, 0); }
    public int getRegionState(int id) { return regionStates.getOrDefault(id, 0); }
    public int getAccountValue(int id) { return accountValues.getOrDefault(id, 0); }
    public Map<Integer, Integer> getLevelStates() { return Map.copyOf(levelStates); }
    public Map<Integer, Integer> getRegionStates() { return Map.copyOf(regionStates); }
    public Map<Integer, Integer> getAccountValues() { return Map.copyOf(accountValues); }
    public Map<Integer, Integer> getSettledAccountValues() { return Map.copyOf(settledAccountValues); }
    public synchronized boolean isPerformanceFinished() { return performanceFinished; }

    public synchronized boolean setLevels(int[] ids, int state) {
        validateBatch(ids, state);
        boolean changed = false;
        for (int id : ids) {
            Integer previous = levelStates.put(id, state);
            changed |= previous == null || previous != state;
        }
        return changed;
    }

    public synchronized boolean setRegions(int[] ids, int state) {
        validateBatch(ids, state);
        boolean changed = false;
        for (int id : ids) {
            Integer previous = regionStates.put(id, state);
            changed |= previous == null || previous != state;
        }
        return changed;
    }

    private static void validateBatch(int[] ids, int state) {
        if (ids == null || ids.length == 0 || state < 0 || state > 4)
            throw new IllegalArgumentException("Invalid native war state batch");
        for (int id : ids) if (id <= 0) throw new IllegalArgumentException("Invalid native war ID");
    }

    /** Replayed one-time quest actions cannot count the same rescued people a second time. */
    public synchronized boolean addAccountOnce(int subQuestId, int accountId, int delta) {
        if (subQuestId <= 0 || accountId <= 0) throw new IllegalArgumentException("Invalid war account action");
        String key = subQuestId + ":" + accountId;
        if (appliedAccountActions.contains(key)) return false;
        int next = Math.addExact(getAccountValue(accountId), delta);
        accountValues.put(accountId, next);
        appliedAccountActions.add(key);
        return true;
    }

    /** Snapshot existing server totals only when the native main quest issues its finish action. */
    public synchronized boolean finishPerformance() {
        if (performanceFinished) return false;
        settledAccountValues = new ConcurrentHashMap<>(accountValues);
        performanceFinished = true;
        return true;
    }

    public synchronized void reset() {
        levelStates.clear();
        regionStates.clear();
        accountValues.clear();
        appliedAccountActions.clear();
        settledAccountValues.clear();
        performanceFinished = false;
        getReconstruction().reset();
    }
}
