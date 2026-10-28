package emu.grasscutter.game.quest.war;

import com.google.gson.JsonParser;
import emu.grasscutter.utils.FileUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

/** IDs are read from the installed native tables and quest actions, not invented war stages. */
public final class QuestWarCatalog {
    private final Set<Integer> levels;
    private final Set<Integer> regions;
    private final Set<Integer> accounts;
    private final int sceneId;
    private static volatile QuestWarCatalog installed;

    QuestWarCatalog(Set<Integer> levels, Set<Integer> regions, Set<Integer> accounts, int sceneId) {
        this.levels = Set.copyOf(levels);
        this.regions = Set.copyOf(regions);
        this.accounts = Set.copyOf(accounts);
        this.sceneId = sceneId;
    }

    public static QuestWarCatalog installed() {
        var current = installed;
        if (current != null) return current;
        synchronized (QuestWarCatalog.class) {
            if (installed == null) installed = load();
            return installed;
        }
    }

    private static QuestWarCatalog load() {
        try {
            var levels = new HashSet<Integer>();
            var regions = new HashSet<Integer>();
            var accounts = new HashSet<Integer>();
            var levelRows = read("ExcelBinOutput/QuestAbyssWarLevelExcelConfigData.json");
            for (var row : levelRows) {
                var data = row.getAsJsonObject();
                levels.add(data.get("levelId").getAsInt());
                if (data.has("JNOKPDFFFND")) regions.add(data.get("JNOKPDFFFND").getAsInt());
            }
            for (var row : read("ExcelBinOutput/QuestAbyssWarAccountExcelConfigData.json"))
                accounts.add(row.getAsJsonObject().get("EIENKHHCHIA").getAsInt());
            for (int main : new int[] {5011,5012,5013}) {
                var quest = JsonParser.parseString(Files.readString(
                        FileUtils.getResourcePath("BinOutput/Quest/" + main + ".json"))).getAsJsonObject();
                for (var sub : quest.getAsJsonArray("subQuests")) {
                    for (String phase : new String[] {"beginExec","finishExec","failExec"}) {
                        var actions = sub.getAsJsonObject().getAsJsonArray(phase);
                        if (actions == null) continue;
                        for (var action : actions) {
                            var data = action.getAsJsonObject();
                            if (data.has("type") && "QUEST_EXEC_SET_ABYSS_WAR_LIMIT_REGION_STATE"
                                    .equals(data.get("type").getAsString()))
                                for (int id : parseIds(data.getAsJsonArray("param").get(0).getAsString()))
                                    regions.add(id);
                        }
                    }
                }
            }
            int dungeonId = read("ExcelBinOutput/QuestAbyssWarOverallExcelConfigData.json")
                    .get(0).getAsJsonObject().get("dungeonId").getAsInt();
            int sceneId = 0;
            for (var row : read("ExcelBinOutput/DungeonExcelConfigData.json")) {
                var dungeon = row.getAsJsonObject();
                if (dungeon.has("id") && dungeon.get("id").getAsInt() == dungeonId) {
                    sceneId = dungeon.get("sceneId").getAsInt();
                    break;
                }
            }
            if (levels.isEmpty() || accounts.isEmpty() || regions.isEmpty() || sceneId <= 0)
                throw new IllegalStateException("Incomplete native AbyssWar tables");
            return new QuestWarCatalog(levels, regions, accounts, sceneId);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load native AbyssWar tables", exception);
        }
    }

    private static com.google.gson.JsonArray read(String path) throws IOException {
        return JsonParser.parseString(Files.readString(FileUtils.getResourcePath(path))).getAsJsonArray();
    }

    public static int[] parseIds(String value) {
        String[] parts = value.split(",", -1);
        int[] ids = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            ids[i] = Integer.parseInt(parts[i].trim());
            if (ids[i] <= 0) throw new IllegalArgumentException("Invalid native war ID");
        }
        return ids;
    }

    public boolean hasLevel(int id) { return levels.contains(id); }
    public boolean hasRegion(int id) { return regions.contains(id); }
    public boolean hasAccount(int id) { return accounts.contains(id); }
    public Set<Integer> levelIds() { return levels; }
    public Set<Integer> regionIds() { return regions; }
    public Set<Integer> accountIds() { return accounts; }
    public int sceneId() { return sceneId; }
}
