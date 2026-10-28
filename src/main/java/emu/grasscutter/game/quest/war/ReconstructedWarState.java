package emu.grasscutter.game.quest.war;

import dev.morphia.annotations.Entity;
import java.util.*;

/** Actual progress in AstaPS's custom 20238 encounters, separate from original war counters. */
@Entity(useDiscriminator = false)
public final class ReconstructedWarState {
    private Map<Integer, Encounter> encounters = new HashMap<>();
    private Map<Integer, Set<Integer>> selections = new HashMap<>();
    private Set<Integer> arrivals = new HashSet<>();
    private Set<Integer> journeys = new HashSet<>();
    private Set<Integer> rescuedNpcs = new HashSet<>();
    private Set<Integer> observedTalks = new HashSet<>();

    @Entity(useDiscriminator = false)
    public static final class Encounter {
        private int wave;
        private Set<Integer> deaths = new HashSet<>();
        private Map<Integer, Float> healthRatios = new HashMap<>();
        private boolean sealed;
        public synchronized int wave() { return wave; }
        public synchronized Set<Integer> deaths() { return Set.copyOf(deaths); }
        public synchronized Float healthRatio(int config) { return healthRatios.get(config); }
        public synchronized boolean sealed() { return sealed; }
    }

    public synchronized Encounter encounter(int quest) {
        return encounters.computeIfAbsent(quest, ignored -> new Encounter());
    }
    public synchronized Encounter findEncounter(int quest) { return encounters.get(quest); }

    public synchronized boolean recordDeath(int quest, int config) {
        var value = encounter(quest);
        synchronized (value) {
            value.healthRatios.remove(config);
            return value.deaths.add(config);
        }
    }

    public synchronized boolean recordHealth(int quest, int config, float ratio) {
        if (!Float.isFinite(ratio) || ratio <= 0 || ratio > 1) return false;
        var value = encounter(quest);
        synchronized (value) {
            if (value.deaths.contains(config)) return false;
            Float previous = value.healthRatios.put(config, ratio);
            return previous == null || Float.compare(previous, ratio) != 0;
        }
    }

    public synchronized boolean advanceWave(int quest, int wave) {
        var value = encounter(quest);
        synchronized (value) {
            if (wave != value.wave + 1) return false;
            value.wave = wave;
            return true;
        }
    }

    public synchronized boolean seal(int quest) {
        var value = encounter(quest);
        synchronized (value) {
            if (value.sealed) return false;
            value.sealed = true;
            return true;
        }
    }

    public synchronized boolean selected(int quest, int level) {
        return selections.getOrDefault(quest, Set.of()).contains(level);
    }
    public synchronized boolean select(int quest, int level) {
        return selections.computeIfAbsent(quest, ignored -> new HashSet<>()).add(level);
    }
    public synchronized boolean arrive(int quest) { return arrivals.add(quest); }
    public synchronized boolean arrived(int quest) { return arrivals.contains(quest); }
    public synchronized boolean journey(int quest) { return journeys.add(quest); }
    public synchronized boolean journeyed(int quest) { return journeys.contains(quest); }
    public synchronized boolean rescue(int npc) { return rescuedNpcs.add(npc); }
    public synchronized boolean observeTalk(int quest) { return observedTalks.add(quest); }
    public synchronized boolean observedTalk(int quest) { return observedTalks.contains(quest); }
    public synchronized int rescueCount() { return rescuedNpcs.size(); }
    public synchronized int killCount() { return encounters.values().stream().mapToInt(value -> value.deaths.size()).sum(); }
    public synchronized void reset() {
        encounters.clear(); selections.clear(); arrivals.clear(); journeys.clear(); rescuedNpcs.clear(); observedTalks.clear();
    }
}
