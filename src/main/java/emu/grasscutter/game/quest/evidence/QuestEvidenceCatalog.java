package emu.grasscutter.game.quest.evidence;

import com.google.gson.annotations.SerializedName;
import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import emu.grasscutter.utils.FileUtils;
import emu.grasscutter.utils.JsonUtils;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.nio.file.Files;

/** Native event namespaces and evidence answers from the installed quest resources. */
public final class QuestEvidenceCatalog {
    private final Map<Integer, Event> events;
    private final Map<Integer, Giving> givings;
    private final Map<Integer, Set<Integer>> knownItems;
    private final Map<Integer, Set<Integer>> givingActiveQuests;
    private static volatile QuestEvidenceCatalog installed;

    private QuestEvidenceCatalog(Map<Integer, Event> events, Map<Integer, Giving> givings,
            Map<Integer, Set<Integer>> knownItems, Map<Integer, Set<Integer>> givingActiveQuests) {
        this.events = Map.copyOf(events);
        this.givings = Map.copyOf(givings);
        this.knownItems = Map.copyOf(knownItems);
        this.givingActiveQuests = Map.copyOf(givingActiveQuests);
    }

    public static QuestEvidenceCatalog installed() {
        var current = installed;
        if (current != null) return current;
        synchronized (QuestEvidenceCatalog.class) {
            if (installed == null) installed = load();
            return installed;
        }
    }

    private static QuestEvidenceCatalog load() {
        try {
            var events = new HashMap<Integer, Event>();
            var givings = new HashMap<Integer, Giving>();
            for (var event : JsonUtils.loadToList(
                    FileUtils.getExcelPath("QuestEventsConfigData.json"), Event.class)) {
                events.put(event.id(), event);
            }
            for (var giving : JsonUtils.loadToList(
                    FileUtils.getExcelPath("QuestEventsGivingConfigData.json"), Giving.class)) {
                if (!events.containsKey(giving.eventId())
                        || !giving.candidates().containsAll(giving.correctItems())) {
                    throw new IllegalStateException("Invalid native quest evidence giving " + giving.id());
                }
                givings.put(giving.id(), giving);
            }
            var knownItems = new HashMap<Integer, Set<Integer>>();
            var givingActiveQuests = new HashMap<Integer, Set<Integer>>();
            for (var event : events.values()) {
                var ids = new HashSet<Integer>();
                givings.values().stream().filter(giving -> giving.eventId() == event.id())
                        .forEach(giving -> ids.addAll(giving.candidates()));
                for (int mainId : event.mainQuestIds()) {
                    var main = JsonParser.parseString(Files.readString(FileUtils.getResourcePath(
                            "BinOutput/Quest/" + mainId + ".json"))).getAsJsonObject();
                    for (var subValue : main.getAsJsonArray("subQuests")) {
                        var sub = subValue.getAsJsonObject();
                        for (String phase : List.of("beginExec", "finishExec", "failExec")) {
                            var actions = sub.getAsJsonArray(phase);
                            if (actions == null) continue;
                            for (var actionValue : actions) {
                                var action = actionValue.getAsJsonObject();
                                if (isType(action, "QUEST_EXEC_UNLOCK_EVENTS_ITEM")) {
                                    var parameters = action.getAsJsonArray("param");
                                    if (Integer.parseInt(parameters.get(0).getAsString()) == event.id())
                                        ids.addAll(parseItemIds(parameters.get(1).getAsString()));
                                }
                            }
                        }
                        for (String phase : List.of("finishCond", "failCond")) {
                            var conditions = sub.getAsJsonArray(phase);
                            if (conditions == null) continue;
                            for (var conditionValue : conditions) {
                                var condition = conditionValue.getAsJsonObject();
                                if (isType(condition, "QUEST_CONTENT_EVENTS_ITEM_STATUS")
                                        && condition.getAsJsonArray("param").get(0).getAsInt() == event.id())
                                    ids.addAll(parseItemIds(condition.get("param_str").getAsString()));
                            }
                        }
                    }
                    var talks = main.getAsJsonArray("talks");
                    if (talks == null) continue;
                    for (var talkValue : talks) {
                        var talk = talkValue.getAsJsonObject();
                        int talkId = talk.get("id").getAsInt();
                        for (var giving : givings.values()) {
                            if (giving.eventId() != event.id()
                                    || (giving.successTalkId() != talkId && giving.failureTalkId() != talkId)) continue;
                            for (var conditionValue : talk.getAsJsonArray("beginCond")) {
                                var condition = conditionValue.getAsJsonObject();
                                var parameters = condition.getAsJsonArray("param");
                                if (isType(condition, "QUEST_COND_STATE_EQUAL")
                                        && Integer.parseInt(parameters.get(1).getAsString()) == 2)
                                    givingActiveQuests.computeIfAbsent(giving.id(), ignored -> new HashSet<>())
                                            .add(Integer.parseInt(parameters.get(0).getAsString()));
                            }
                        }
                    }
                }
                knownItems.put(event.id(), Set.copyOf(ids));
            }
            return new QuestEvidenceCatalog(events, givings, knownItems, givingActiveQuests);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load native quest evidence resources", exception);
        }
    }

    public Event event(int id) { return events.get(id); }
    public Event eventForBook(int itemId) {
        return events.values().stream().filter(event -> event.bookMaterialId() == itemId).findFirst().orElse(null);
    }
    public Giving giving(int id) { return givings.get(id); }
    public boolean isKnownItem(int eventId, int itemId) {
        return knownItems.getOrDefault(eventId, Set.of()).contains(itemId);
    }
    public Set<Integer> activeQuestsForGiving(int givingId) {
        return givingActiveQuests.getOrDefault(givingId, Set.of());
    }

    private static boolean isType(JsonObject value, String type) {
        return value.has("type") && type.equals(value.get("type").getAsString());
    }

    public static final class Event {
        private int id;
        @SerializedName("CMFLJAJHIBE") private int bookMaterialId;
        @SerializedName("MGKFNDLKBCI") private List<Integer> mainQuestIds;

        public int id() { return id; }
        public int bookMaterialId() { return bookMaterialId; }
        public List<Integer> mainQuestIds() { return mainQuestIds; }
    }

    public static final class Giving {
        private int id;
        @SerializedName("AOBAMHFBAJE") private int eventId;
        @SerializedName("LBGGFPLLGPI") private List<Integer> candidates;
        @SerializedName("NOIACCCBABM") private List<Integer> correctItems;
        @SerializedName("DMOMCOCDHOC") private int successTalkId;
        @SerializedName("AHOOGJIEDGA") private int failureTalkId;

        public int id() { return id; }
        public int eventId() { return eventId; }
        public List<Integer> candidates() { return candidates; }
        public List<Integer> correctItems() { return correctItems; }
        public int successTalkId() { return successTalkId; }
        public int failureTalkId() { return failureTalkId; }
    }

    public static List<Integer> parseItemIds(String value) {
        return java.util.Arrays.stream(value.split(",", -1))
                .map(String::trim)
                .map(Integer::parseInt)
                .peek(id -> {
                    if (id <= 0) throw new IllegalArgumentException("Invalid native evidence item");
                })
                .toList();
    }
}
