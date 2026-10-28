package emu.grasscutter.game.quest.persistent;

import dev.morphia.annotations.Entity;
import emu.grasscutter.game.dungeons.PersistentDungeonProgress;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Activated by a native quest exec, with one retained instance per authorized scene. */
@Entity(useDiscriminator = false)
public final class QuestPersistentDungeonState {
    @Entity(useDiscriminator = false)
    public static final class Run {
        private PersistentDungeonProgress progress;
        private Map<Integer, Set<Integer>> activeSuites = new ConcurrentHashMap<>();
        private transient boolean suitesDirty;

        public PersistentDungeonProgress getProgress() { return progress; }
        public boolean hasGroup(int groupId) { return activeSuites.containsKey(groupId); }
        public Set<Integer> getSuites(int groupId) {
            var suites = activeSuites.get(groupId);
            return suites == null ? Set.of() : Set.copyOf(suites);
        }

        private synchronized boolean update(PersistentDungeonProgress progress) {
            if (progress.sameProgress(this.progress) && !suitesDirty) return false;
            this.progress = progress;
            suitesDirty = false;
            return true;
        }

        private synchronized void suiteAdded(int groupId, int suiteId) {
            var suites = activeSuites.get(groupId);
            if (suites == null) {
                suites = ConcurrentHashMap.newKeySet();
                activeSuites.put(groupId, suites);
                suitesDirty = true;
            }
            suitesDirty |= suites.add(suiteId);
        }

        private synchronized void suiteRemoved(int groupId, int suiteId) {
            var suites = activeSuites.get(groupId);
            if (suites == null) {
                suites = ConcurrentHashMap.newKeySet();
                activeSuites.put(groupId, suites);
                suitesDirty = true;
            }
            suitesDirty |= suites.remove(suiteId);
        }
    }

    private Map<Integer, Run> scenes = new ConcurrentHashMap<>();

    public synchronized boolean activate(int sceneId) {
        if (sceneId <= 0) throw new IllegalArgumentException("Invalid persistent dungeon scene");
        return scenes.putIfAbsent(sceneId, new Run()) == null;
    }

    public synchronized boolean clear(int sceneId) { return scenes.remove(sceneId) != null; }
    public Run getRun(int sceneId) { return scenes.get(sceneId); }
    public boolean isActive(int sceneId) { return scenes.containsKey(sceneId); }

    public boolean update(PersistentDungeonProgress progress) {
        var run = scenes.get(progress.getSceneId());
        return run != null && run.update(progress);
    }

    public void suiteAdded(int sceneId, int groupId, int suiteId) {
        var run = scenes.get(sceneId);
        if (run != null && groupId > 0 && suiteId > 0) run.suiteAdded(groupId, suiteId);
    }

    public void suiteRemoved(int sceneId, int groupId, int suiteId) {
        var run = scenes.get(sceneId);
        if (run != null && groupId > 0 && suiteId > 0) run.suiteRemoved(groupId, suiteId);
    }
}
