package emu.grasscutter.game.gacha;

import com.google.gson.JsonParser;
import emu.grasscutter.data.GameData;
import emu.grasscutter.utils.JsonUtils;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Operator-selected historical UPs. No timer changes the selected lineup. */
public final class HistoricalGachaSelection {
    static final int SCHEDULE_BASE = 1_000_000_000;
    public record Phase(String key, String label, int[] up5, int[] up4) {}

    private final Path path;
    private final List<Phase> phases;
    private Phase current;

    HistoricalGachaSelection(Path path, List<Phase> phases) {
        if (phases.isEmpty()) throw new IllegalArgumentException("Gacha history is empty.");
        this.path = path;
        this.phases = List.copyOf(phases);
        try {
            String key = Files.exists(path)
                    ? JsonParser.parseString(Files.readString(path)).getAsJsonObject().get("phaseKey").getAsString()
                    : phases.getFirst().key();
            current = find(key);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read historical gacha selection.", e);
        }
    }

    static List<Phase> loadHistory() {
        var stream = HistoricalGachaSelection.class.getResourceAsStream("/gacha/history.json");
        if (stream == null) throw new IllegalStateException("Gacha history is missing.");
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            var result = new ArrayList<Phase>();
            for (var element : JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("phases")) {
                var row = element.getAsJsonObject();
                result.add(new Phase(row.get("key").getAsString(), row.get("label").getAsString(),
                        JsonUtils.decode(row.get("up5"), int[].class), JsonUtils.decode(row.get("up4"), int[].class)));
            }
            return List.copyOf(result);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read gacha history.", e);
        }
    }

    static void validateItems(List<Phase> phases) {
        for (var phase : phases) {
            validateTier(phase, phase.up5(), 5);
            validateTier(phase, phase.up4(), 4);
        }
    }

    private static void validateTier(Phase phase, int[] ids, int rank) {
        if (ids.length == 0) throw new IllegalStateException("Empty UP pool in " + phase.key());
        for (int id : ids) {
            var item = GameData.getItemDataMap().get(id);
            if (item == null || item.getRankLevel() != rank) {
                throw new IllegalStateException("Invalid UP item " + id + " in " + phase.key());
            }
        }
    }

    private Phase find(String key) {
        return phases.stream().filter(p -> p.key().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("请选择列表中的有效卡池。"));
    }

    boolean select(String key) {
        var next = find(key);
        if (next == current) return false;
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, JsonUtils.encode(Map.of("phaseKey", next.key())));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot save historical gacha selection.", e);
        }
        current = next;
        return true;
    }

    List<Phase> phases() { return phases; }
    Phase current() { return current; }
    int scheduleId() { return SCHEDULE_BASE + phases.indexOf(current); }
}
