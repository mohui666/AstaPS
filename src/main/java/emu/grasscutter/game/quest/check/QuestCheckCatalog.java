package emu.grasscutter.game.quest.check;

import com.google.gson.*;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.world.Position;
import emu.grasscutter.utils.FileUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

/** The five Lynette outcome checks and their genuine die/difficulty/bonus/guide resources. */
public final class QuestCheckCatalog {
    public record Bonus(int id, int amount, int globalVariable, int expectedValue) {
        boolean applies(Player player) {
            return player.getQuestManager().getQuestGlobalVarValue(globalVariable) == expectedValue;
        }
    }
    public record Check(int id, int parent, int dialogue, int outcome, int faces, int difficulty,
            int sceneId, Position position, double radius, List<Bonus> bonuses) {}
    private final Map<Integer, Check> checks;
    private QuestCheckCatalog(Map<Integer, Check> checks) { this.checks = Map.copyOf(checks); }
    private static final class Installed { static final QuestCheckCatalog VALUE = load(); }
    public static QuestCheckCatalog installed() { return Installed.VALUE; }
    public Check check(int id) { return checks.get(id); }
    public Collection<Check> checks() { return checks.values(); }

    private static JsonArray table(String name) throws IOException {
        return JsonParser.parseString(Files.readString(FileUtils.getResourcePath(
                "ExcelBinOutput/" + name + ".json"))).getAsJsonArray();
    }
    private static QuestCheckCatalog load() {
        try {
            var definitions = new HashMap<Integer, JsonObject>();
            for (var value : table("QuestCheckExcelConfigData")) {
                var row = value.getAsJsonObject(); definitions.put(row.get("id").getAsInt(), row);
            }
            var bonuses = new HashMap<Integer, JsonObject>();
            for (var value : table("QuestCheckBonusExcelConfigData")) {
                var row = value.getAsJsonObject(); bonuses.put(row.get("id").getAsInt(), row);
            }
            var nativeQuest = JsonParser.parseString(Files.readString(FileUtils.getResourcePath(
                    "BinOutput/Quest/19182.json"))).getAsJsonObject();
            var children = new HashMap<Integer, JsonObject>();
            for (var value : nativeQuest.getAsJsonArray("subQuests")) {
                var row = value.getAsJsonObject(); children.put(row.get("subId").getAsInt(), row);
            }
            var result = new HashMap<Integer, Check>();
            for (int outcome = 1918220; outcome <= 1918224; outcome++) {
                var child = children.get(outcome);
                int checkId = 0, dialogue = 0;
                for (var value : child.getAsJsonArray("finishCond")) {
                    var condition = value.getAsJsonObject();
                    var args = condition.getAsJsonArray("param");
                    switch (condition.get("type").getAsString()) {
                        case "QUEST_CONTENT_QUEST_CHECK_EQUAL" -> {
                            if (args.get(1).getAsInt() != 1) throw new IllegalStateException("Invalid check outcome");
                            checkId = args.get(0).getAsInt();
                        }
                        case "QUEST_CONTENT_QUEST_STATE_EQUAL" -> {
                            if (args.get(1).getAsInt() != 3) throw new IllegalStateException("Invalid dialogue gate");
                            dialogue = args.get(0).getAsInt();
                        }
                        default -> throw new IllegalStateException("Unknown Lynette outcome gate");
                    }
                }
                var definition = definitions.get(checkId);
                if (definition == null || definition.get("parentQuestId").getAsInt() != 19182)
                    throw new IllegalStateException("Missing Lynette dice definition");
                var guide = children.get(dialogue).getAsJsonObject("guide");
                var location = guide.getAsJsonArray("param").get(0).getAsString();
                var parts = location.substring(1, location.indexOf(']')).split(":", 2);
                int scene = Integer.parseInt(parts[0].split("_", 2)[0]);
                var xyz = parts[1].split(",");
                var position = new Position(Float.parseFloat(xyz[0]), Float.parseFloat(xyz[1]), Float.parseFloat(xyz[2]));
                double radius = guide.getAsJsonArray("param").get(1).getAsDouble();
                var available = new ArrayList<Bonus>();
                for (var id : definition.getAsJsonArray("bonusList")) {
                    var bonus = bonuses.get(id.getAsInt());
                    if (bonus == null || bonus.get("PEIMCPPHEKF").getAsInt() != checkId)
                        throw new IllegalStateException("Missing configured dice bonus");
                    int variable = 0, expected = 0;
                    for (var cond : bonus.getAsJsonArray("condList")) {
                        var condition = cond.getAsJsonObject();
                        if (!condition.has("type")) continue; // Native NONE slot.
                        if (!condition.get("type").getAsString().equals("QUEST_GLOBAL_VAR_EQUAL") || variable != 0)
                            throw new IllegalStateException("Unsupported configured Lynette dice bonus");
                        variable = condition.getAsJsonArray("paramList").get(0).getAsInt();
                        expected = condition.getAsJsonArray("paramList").get(1).getAsInt();
                    }
                    if (variable <= 0) throw new IllegalStateException("Missing dice bonus condition");
                    available.add(new Bonus(id.getAsInt(), bonus.get("LDDLEFLFIJB").getAsInt(), variable, expected));
                }
                int faces = definition.get("FAJMHKPJDDG").getAsInt();
                int difficulty = definition.get("OOFAGAJDOJG").getAsInt();
                if (faces != 20 || difficulty <= 0 || scene != guide.get("guideScene").getAsInt()
                        || radius <= 0 || !Double.isFinite(radius))
                    throw new IllegalStateException("Invalid native Lynette dice geometry or rule");
                result.put(checkId, new Check(checkId, 19182, dialogue, outcome, faces, difficulty,
                        scene, position, radius, List.copyOf(available)));
            }
            if (result.size() != 5) throw new IllegalStateException("Incomplete Lynette outcome catalog");
            return new QuestCheckCatalog(result);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read native Lynette dice resources", error);
        }
    }
}
