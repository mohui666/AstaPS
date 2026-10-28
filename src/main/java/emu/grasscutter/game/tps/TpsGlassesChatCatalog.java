package emu.grasscutter.game.tps;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import emu.grasscutter.utils.FileUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

/** The native chat nodes and choices; their explicit indices define the conversation graph. */
public final class TpsGlassesChatCatalog {
    private record Message(int index, int next, int parentOption) {}
    private record Choice(int id, int index, int next, int parentOption) {}
    private static final class Topic {
        final Map<Integer, List<Message>> messages = new HashMap<>();
        final Map<Integer, Choice> choices = new HashMap<>();
    }

    private final Map<Integer, Topic> topics = new HashMap<>();

    private static final class Installed {
        static final TpsGlassesChatCatalog VALUE = load();
    }

    public static TpsGlassesChatCatalog installed() { return Installed.VALUE; }

    private static TpsGlassesChatCatalog load() {
        var catalog = new TpsGlassesChatCatalog();
        try {
            for (var value : rows("TpsGlassesChatTopic")) {
                int id = value.getAsJsonObject().get("KEKHMPOIFHO").getAsInt();
                if (id <= 0 || catalog.topics.put(id, new Topic()) != null)
                    throw new IllegalStateException("Invalid TPS chat topic ID");
            }
            for (var value : rows("TpsGlassesChatMessage")) {
                var row = value.getAsJsonObject();
                var topic = catalog.requireTopic(row);
                int index = row.get("JJGAICEDLNP").getAsInt();
                int next = integer(row, "LICNBELGBCN");
                if (index <= 0 || next != 0 && next <= index)
                    throw new IllegalStateException("Invalid TPS chat message edge");
                topic.messages.computeIfAbsent(index, ignored -> new ArrayList<>())
                        .add(new Message(index, next, integer(row, "PADEPANADLD")));
            }
            for (var value : rows("TpsGlassesChatOption")) {
                var row = value.getAsJsonObject();
                var topic = catalog.requireTopic(row);
                var choice = new Choice(row.get("HANOBLDBAPA").getAsInt(),
                        row.get("BHLEPODADCG").getAsInt(), row.get("LICNBELGBCN").getAsInt(),
                        integer(row, "PADEPANADLD"));
                if (choice.id <= 0 || choice.index <= 0 || choice.next <= choice.index
                        || topic.choices.put(choice.id, choice) != null)
                    throw new IllegalStateException("Invalid TPS chat choice");
            }
            if (catalog.topics.isEmpty()) throw new IllegalStateException("Missing TPS chat topics");
            return catalog;
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read native TPS glasses chat tables", error);
        }
    }

    private static com.google.gson.JsonArray rows(String name) throws IOException {
        return JsonParser.parseString(Files.readString(FileUtils.getResourcePath(
                "ExcelBinOutput/" + name + "ExcelConfigData.json"))).getAsJsonArray();
    }

    private static int integer(JsonObject row, String field) {
        return row.has(field) ? row.get(field).getAsInt() : 0;
    }

    private Topic requireTopic(JsonObject row) {
        var topic = topics.get(row.get("HAOFOHIJENC").getAsInt());
        if (topic == null) throw new IllegalStateException("Unknown native TPS chat topic");
        return topic;
    }

    public boolean hasTopic(int id) { return topics.containsKey(id); }

    /** An authoritative rewind also forgets choices that occur at or beyond that node. */
    public List<Integer> choicesBefore(int topicId, int progress, List<Integer> previous) {
        var topic = topics.get(topicId);
        if (topic == null) return List.of();
        return previous.stream().filter(id -> topic.choices.containsKey(id)
                && topic.choices.get(id).index < progress).toList();
    }

    /**
     * The client batches automatic messages and chosen options until closing the page. Validate
     * that whole path from the saved node, rather than accepting an arbitrary final integer.
     */
    public boolean canAdvance(int topicId, int from, List<Integer> oldChoices,
            int target, List<Integer> submitted) {
        var topic = topics.get(topicId);
        if (topic == null || from <= 0 || target < from || submitted.size() < oldChoices.size()
                || !submitted.subList(0, oldChoices.size()).equals(oldChoices)
                || new HashSet<>(submitted).size() != submitted.size()
                || !topic.choices.keySet().containsAll(submitted)) return false;

        int cursor = from;
        int selectedCount = oldChoices.size();
        var selected = new HashSet<>(oldChoices);
        while (cursor < target) {
            int next = 0;
            // Native options are recorded by config ID, not by their position in the JSON array.
            if (selectedCount < submitted.size()) {
                var choice = topic.choices.get(submitted.get(selectedCount));
                if (choice.index == cursor && (choice.parentOption == 0
                        || selected.contains(choice.parentOption))) {
                    next = choice.next;
                    selected.add(choice.id);
                    selectedCount++;
                }
            }
            if (next == 0) {
                for (var message : topic.messages.getOrDefault(cursor, List.of())) {
                    if (message.parentOption == 0 || selected.contains(message.parentOption)) {
                        if (next != 0 && next != message.next) return false;
                        next = message.next;
                    }
                }
            }
            if (next <= cursor || next > target) return false;
            cursor = next;
        }
        return cursor == target && selectedCount == submitted.size();
    }
}
