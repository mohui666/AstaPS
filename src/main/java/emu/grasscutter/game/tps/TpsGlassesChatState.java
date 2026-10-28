package emu.grasscutter.game.tps;

import dev.morphia.annotations.Entity;
import java.util.*;

/** Per-player topic progress and selected native option IDs, persisted with the player. */
@Entity(useDiscriminator = false)
public final class TpsGlassesChatState {
    private Map<Integer, TopicState> topics = new HashMap<>();

    @Entity(useDiscriminator = false)
    public static final class TopicState {
        private int progress;
        private List<Integer> chosenOptions = new ArrayList<>();
        private int cpganappfdm;
        private int kgbbaaclklb;
    }

    public record Topic(int id, int progress, List<Integer> chosenOptions,
            int cpganappfdm, int kgbbaaclklb) {}

    public synchronized Topic topic(int id) {
        var value = topics.get(id);
        return value == null ? null : new Topic(id, value.progress,
                List.copyOf(value.chosenOptions), value.cpganappfdm, value.kgbbaaclklb);
    }

    public synchronized List<Topic> snapshot() {
        return topics.keySet().stream().sorted().map(this::topic).toList();
    }

    public synchronized void setProgress(int id, int progress, List<Integer> retainedOptions) {
        var value = topics.computeIfAbsent(id, ignored -> new TopicState());
        value.progress = progress;
        value.chosenOptions = new ArrayList<>(retainedOptions);
        // This opaque native field receives the same progress in the client's close-chat request.
        // A quest action has not acknowledged any newly added messages on the player's behalf.
        value.cpganappfdm = Math.min(value.cpganappfdm, progress);
    }

    public synchronized void recordRead(int id, int progress, List<Integer> options, int nativeValue) {
        var value = topics.get(id);
        if (value == null) throw new IllegalArgumentException("TPS chat topic is not unlocked");
        value.progress = progress;
        value.chosenOptions = new ArrayList<>(options);
        value.cpganappfdm = nativeValue;
    }
}
