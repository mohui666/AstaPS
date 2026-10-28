package emu.grasscutter.game.gcg.story;

import com.google.gson.*;
import emu.grasscutter.game.world.Position;
import emu.grasscutter.utils.FileUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

/** Explicit task/level allowlist and custom rules, with HP/draw/round limits from original GCG rows. */
public final class StoryCardCatalog {
    public static final Set<Integer> PARENTS = Set.of(40109,70526,70665,70666,70667,70668,70669,73219);
    public record Card(int id, String name, int cost, int damage, int shield, int heal, int copies) {}
    public record Stage(int quest, int scene, int gate, List<Integer> levels, boolean tutorial,
            int priority, List<Position> anchors) {}
    public record Level(int id, int playerHp, int opponentHp, int firstDraw, int drawPerRound, int handLimit) {}
    private final Map<Integer, Card> cards;
    private final Map<Integer, Stage> stages;
    private final Map<Integer, Level> levels;
    private final List<Integer> tutorialLevels;
    private final int actionPoints, enemyDamage, enemyHeavyDamage, roundLimit;
    private final String notice;

    private StoryCardCatalog(JsonObject config) throws IOException {
        if (!config.get("reconstructed").getAsBoolean()) throw new IllegalStateException("Story cards need explicit reconstruction");
        actionPoints = config.get("actionPoints").getAsInt();
        enemyDamage = config.get("enemyDamage").getAsInt();
        enemyHeavyDamage = config.get("enemyHeavyDamage").getAsInt();
        notice = config.get("notice").getAsString();
        var constants = table("ConstValueExcelConfigData");
        roundLimit = constant(constants, "CONST_VALUE_GCG_ROUND_LIMIT");
        if (actionPoints <= 0 || enemyDamage <= 0 || enemyHeavyDamage < enemyDamage || roundLimit <= 0)
            throw new IllegalStateException("Invalid story card rules");
        cards = new LinkedHashMap<>();
        for (var value : config.getAsJsonArray("cards")) {
            var row = value.getAsJsonObject();
            var card = new Card(row.get("id").getAsInt(), row.get("name").getAsString(), row.get("cost").getAsInt(),
                    row.get("damage").getAsInt(), row.get("shield").getAsInt(), row.get("heal").getAsInt(), row.get("copies").getAsInt());
            if (card.id <= 0 || card.cost <= 0 || card.cost > actionPoints || card.copies <= 0
                    || card.damage < 0 || card.shield < 0 || card.heal < 0
                    || card.damage + card.shield + card.heal <= 0 || cards.put(card.id, card) != null)
                throw new IllegalStateException("Invalid custom story card");
        }
        var games = byId(table("GCGGameExcelConfigData"), "id");
        var decks = byId(table("GCGDeckExcelConfigData"), "id");
        var characters = byId(table("GCGCharExcelConfigData"), "id");
        // This constant contains character IDs, not a GCGDeckExcelConfigData row ID.
        var initialCharacters = new JsonArray();
        for (var id : constantValue(constants, "CONST_VALUE_GCG_DECK_SYSTEM_INIT_DECK").split(";"))
            initialCharacters.add(Integer.parseInt(id.trim()));
        int initialHp = characterHp(initialCharacters, characters);
        var rules = byId(table("GCGRuleExcelConfigData"), "id");
        tutorialLevels = integers(config.getAsJsonArray("tutorialLevels"));
        var group = byId(table("GCGGameGroupExcelConfigData"), "id").get(config.get("tutorialGroup").getAsInt());
        if (group == null || !integers(group.getAsJsonArray("levelList")).equals(tutorialLevels)
                || tutorialLevels.size() != 4) throw new IllegalStateException("Missing original four guide games");
        var allowed = new HashSet<>(integers(config.getAsJsonArray("levelAllowlist")));
        if (!allowed.equals(Set.of(11001,20001,20002,20003,20004,30101,30105,30111,30112,50101,50301,50302,50303)))
            throw new IllegalStateException("Unsupported story GCG level expansion");
        allowed.addAll(tutorialLevels);
        levels = new HashMap<>();
        for (int id : allowed) {
            var game = games.get(id); if (game == null) throw new IllegalStateException("Missing original GCG game " + id);
            var rule = rules.get(game.get("ruleId").getAsInt());
            int playerHp = game.has("cardGroupId") ? hp(decks.get(game.get("cardGroupId").getAsInt()), characters) : initialHp;
            var level = new Level(id, playerHp,
                    hp(decks.get(game.get("enemyCardGroupId").getAsInt()), characters),
                    rule.get("firstDrawNum").getAsInt(), rule.get("drawCardNum").getAsInt(), rule.get("handCardLimit").getAsInt());
            if (level.playerHp <= 0 || level.opponentHp <= 0 || level.firstDraw <= 0
                    || level.drawPerRound <= 0 || level.handLimit < level.firstDraw)
                throw new IllegalStateException("Invalid GCG HP or draw source " + id);
            levels.put(id, level);
        }
        stages = new LinkedHashMap<>();
        for (var value : config.getAsJsonArray("stages")) {
            var row = value.getAsJsonObject(); int quest = row.get("questId").getAsInt();
            var anchors = new ArrayList<Position>();
            for (var anchor : row.getAsJsonArray("anchors")) {
                var xyz = anchor.getAsJsonObject().getAsJsonArray("position");
                var position = new Position(xyz.get(0).getAsFloat(), xyz.get(1).getAsFloat(), xyz.get(2).getAsFloat());
                if (!Float.isFinite(position.getX()) || !Float.isFinite(position.getY()) || !Float.isFinite(position.getZ()))
                    throw new IllegalStateException("Invalid explicit card-table anchor");
                anchors.add(position);
            }
            var stage = new Stage(quest, row.get("sceneId").getAsInt(), row.get("gateQuestId").getAsInt(),
                    integers(row.getAsJsonArray("levels")), row.get("tutorial").getAsBoolean(), row.get("priority").getAsInt(), List.copyOf(anchors));
            if (!PARENTS.contains(quest / 100) || anchors.isEmpty() || stage.scene <= 0
                    || stage.levels.stream().anyMatch(id -> !levels.containsKey(id)) || stages.put(quest, stage) != null)
                throw new IllegalStateException("Invalid explicit story GCG stage " + quest);
        }
        if (stages.size() != 22) throw new IllegalStateException("Missing explicit story card stages");
    }

    private static final class Installed { static final StoryCardCatalog VALUE = load(); }
    public static StoryCardCatalog installed() { return Installed.VALUE; }
    public Card card(int id) { return cards.get(id); }
    public Collection<Card> cards() { return cards.values(); }
    public Stage stage(int quest) { return stages.get(quest); }
    public Collection<Stage> stages() { return stages.values(); }
    public Level level(int id) { return levels.get(id); }
    public List<Integer> tutorialLevels() { return tutorialLevels; }
    public int actionPoints() { return actionPoints; }
    public int enemyDamage() { return enemyDamage; }
    public int enemyHeavyDamage() { return enemyHeavyDamage; }
    public int roundLimit() { return roundLimit; }
    public String notice() { return notice; }

    private static StoryCardCatalog load() {
        try { return new StoryCardCatalog(JsonParser.parseString(Files.readString(FileUtils.getDataPath("QuestStoryCards.json"))).getAsJsonObject()); }
        catch (IOException error) { throw new IllegalStateException("Cannot read story card reconstruction", error); }
    }
    private static JsonArray table(String name) throws IOException {
        return JsonParser.parseString(Files.readString(FileUtils.getResourcePath("ExcelBinOutput/" + name + ".json"))).getAsJsonArray();
    }
    private static Map<Integer, JsonObject> byId(JsonArray rows, String name) {
        var map = new HashMap<Integer, JsonObject>();
        for (var value : rows) { var row = value.getAsJsonObject(); map.put(row.get(name).getAsInt(), row); }
        return map;
    }
    private static List<Integer> integers(JsonArray array) {
        var result = new ArrayList<Integer>(); for (var value : array) result.add(value.getAsInt()); return List.copyOf(result);
    }
    private static int constant(JsonArray rows, String name) {
        return Integer.parseInt(constantValue(rows, name));
    }
    private static String constantValue(JsonArray rows, String name) {
        for (var value : rows) { var row = value.getAsJsonObject(); if (row.has("name") && row.get("name").getAsString().equals(name))
            return row.getAsJsonArray("value").get(0).getAsString(); }
        throw new IllegalStateException("Missing native GCG constant " + name);
    }
    private static int hp(JsonObject deck, Map<Integer, JsonObject> characters) {
        if (deck == null) throw new IllegalStateException("Missing original GCG deck");
        return characterHp(deck.getAsJsonArray("characterList"), characters);
    }
    private static int characterHp(JsonArray ids, Map<Integer, JsonObject> characters) {
        int total = 0;
        for (var id : ids) {
            var character = characters.get(id.getAsInt());
            if (character == null || !character.has("hp")) throw new IllegalStateException("Missing original GCG character HP");
            total += character.get("hp").getAsInt();
        }
        return total;
    }
}
