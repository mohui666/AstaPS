package emu.grasscutter.game.train;

import dev.morphia.annotations.Entity;
import java.util.HashMap;
import java.util.Map;

/** Quest-earned decorations of the player's own train. */
@Entity(useDiscriminator = false)
public final class TrainFigureState {
    private Map<Integer, Integer> values = new HashMap<>();

    public synchronized int get(int id, int initialValue) {
        return values.getOrDefault(id, initialValue);
    }

    public synchronized boolean set(int id, int value) {
        if (id <= 0 || value < 0 || value > 1) throw new IllegalArgumentException("Invalid train figure");
        return !Integer.valueOf(value).equals(values.put(id, value));
    }

    public synchronized Map<Integer, Integer> snapshot() { return Map.copyOf(values); }
}
