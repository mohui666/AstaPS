package emu.grasscutter.game.quest.world;

import dev.morphia.annotations.Entity;
import java.util.*;

/** Creator-owned scene values, region overrides and explicitly unlocked multiplayer scenes. */
@Entity(useDiscriminator = false)
public final class QuestSceneMechanicsState {
    private Map<Integer, Map<Integer, Float>> sceneVariables = new HashMap<>();
    private Set<Integer> unlockedSceneMps = new HashSet<>();
    private Map<Integer, Boolean> limitRegionStates = new HashMap<>();

    public Map<Integer, Float> variables(int sceneId) {
        return Map.copyOf(sceneVariables.getOrDefault(sceneId, Map.of()));
    }

    public void setVariable(int sceneId, int hash, float value) {
        sceneVariables.computeIfAbsent(sceneId, ignored -> new HashMap<>()).put(hash, value);
    }

    public Set<Integer> unlockedSceneMps() { return Set.copyOf(unlockedSceneMps); }
    public boolean isMpUnlocked(int sceneId) { return unlockedSceneMps.contains(sceneId); }
    public boolean unlockMp(int sceneId) { return unlockedSceneMps.add(sceneId); }
    public boolean regionEnabled(int id, boolean initial) { return limitRegionStates.getOrDefault(id, initial); }
    public void setRegionEnabled(int id, boolean enabled) { limitRegionStates.put(id, enabled); }
}
