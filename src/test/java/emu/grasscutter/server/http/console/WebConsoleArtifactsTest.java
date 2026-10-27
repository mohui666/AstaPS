package emu.grasscutter.server.http.console;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.ItemData;
import emu.grasscutter.data.excels.reliquary.ReliquaryAffixData;
import emu.grasscutter.data.excels.reliquary.ReliquaryLevelData;
import emu.grasscutter.data.excels.reliquary.ReliquaryMainPropData;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebConsoleArtifactsTest {
    private static final Gson GSON = new Gson();
    private static final int PIECE = 9000001, PURPLE = 9000002, MAIN = 9100001, WRONG_MAIN = 9100002;
    private static final int CRIT = 9200001, CRIT_LOW = 9200002, HP = 9200003, WRONG_RANK = 9200004;

    @SuppressWarnings("unchecked")
    private static Int2ObjectMap<ReliquaryLevelData> levels() {
        return (Int2ObjectMap<ReliquaryLevelData>) GameData.getMapByResourceDef(ReliquaryLevelData.class);
    }

    @BeforeEach
    void definitions() {
        for (int rank : new int[] {4, 5}) {
            int id = rank == 5 ? PIECE : PURPLE;
            var item = GSON.fromJson("""
                    {"id":%d,"setId":15020,"itemType":"ITEM_RELIQUARY","equipType":"EQUIP_BRACER",
                     "rankLevel":%d,"maxLevel":%d,"mainPropDepotId":4000,"appendPropDepotId":%d,"appendPropNum":4}
                    """.formatted(id, rank, rank == 5 ? 21 : 17, rank * 100 + 1), ItemData.class);
            GameData.getItemDataMap().put(id, item);
        }
        for (int id : new int[] {MAIN, WRONG_MAIN}) {
            var main = GSON.fromJson("{\"id\":" + id + ",\"propDepotId\":" + (id == MAIN ? 4000 : 3000)
                    + ",\"propType\":\"FIGHT_PROP_HP\",\"weight\":100}", ReliquaryMainPropData.class);
            GameData.getReliquaryMainPropDataMap().put(id, main);
        }
        addAffix(CRIT, 501, "FIGHT_PROP_CRITICAL_HURT", .0777);
        addAffix(CRIT_LOW, 501, "FIGHT_PROP_CRITICAL_HURT", .0544);
        addAffix(HP, 501, "FIGHT_PROP_HP", 299);
        addAffix(WRONG_RANK, 401, "FIGHT_PROP_CRITICAL_HURT", .0622);
        for (int rank : new int[] {4, 5}) {
            for (int level : new int[] {1, rank == 5 ? 21 : 17}) {
                var row = GSON.fromJson("""
                        {"rank":%d,"level":%d,"exp":100,"addProps":[{"propType":"FIGHT_PROP_HP","value":%d}]}
                        """.formatted(rank, level, level == 1 ? 717 : rank == 5 ? 4780 : 3571), ReliquaryLevelData.class);
                row.onLoad(); levels().put(row.getId(), row);
            }
        }
    }

    private static void addAffix(int id, int depot, String prop, double value) {
        var row = GSON.fromJson("{\"id\":" + id + ",\"depotId\":" + depot + ",\"propType\":\"" + prop
                + "\",\"propValue\":" + value + ",\"weight\":100,\"upgradeWeight\":1000}", ReliquaryAffixData.class);
        GameData.getReliquaryAffixDataMap().put(id, row);
    }

    @AfterEach
    void removeDefinitions() {
        for (int id : new int[] {PIECE, PURPLE}) GameData.getItemDataMap().remove(id);
        for (int id : new int[] {MAIN, WRONG_MAIN}) GameData.getReliquaryMainPropDataMap().remove(id);
        for (int id : new int[] {CRIT, CRIT_LOW, HP, WRONG_RANK}) GameData.getReliquaryAffixDataMap().remove(id);
        for (int rank : new int[] {4, 5}) for (int level : new int[] {1, rank == 5 ? 21 : 17}) {
            levels().remove((rank << 8) + level);
        }
    }

    private static WebConsoleArtifacts.Request recipe(int level, int main, WebConsoleArtifacts.Sub... subs) {
        return new WebConsoleArtifacts.Request(10001, PIECE, level, main, List.of(subs), true);
    }

    @Test
    void previewAndCreatedItemHaveTheSameExactStatsLevelAndLock() {
        var request = recipe(20, MAIN, new WebConsoleArtifacts.Sub(CRIT, 6));
        var preview = WebConsoleArtifacts.preview(request);
        assertEquals(4780, preview.main().value());
        assertEquals(.0777 * 6, preview.substats().getFirst().value(), .000001);
        assertEquals(6, preview.rolls());
        var item = WebConsoleArtifacts.make(request);
        assertEquals(21, item.getLevel());
        assertEquals(100, item.getTotalExp());
        assertEquals(MAIN, item.getMainPropId());
        assertEquals(List.of(CRIT, CRIT, CRIT, CRIT, CRIT, CRIT), item.getAppendPropIdList());
        assertTrue(item.isLocked());
        assertEquals(0, item.getOwnerId());
        assertEquals(0, item.getGuid());
        assertNull(item.getObjectId());
        assertEquals(6, item.toReliquaryProto().getAppendPropIdListCount());
    }

    @Test
    void rejectsWrongSlotWrongStarAndRepeatedProperties() {
        assertThrows(IllegalArgumentException.class, () -> recipe(0, WRONG_MAIN).validate());
        assertThrows(IllegalArgumentException.class, () -> recipe(0, MAIN, new WebConsoleArtifacts.Sub(WRONG_RANK, 1)).validate());
        assertThrows(IllegalArgumentException.class, () -> recipe(0, MAIN, new WebConsoleArtifacts.Sub(HP, 1)).validate());
        assertThrows(IllegalArgumentException.class, () -> recipe(0, MAIN, new WebConsoleArtifacts.Sub(CRIT, 1), new WebConsoleArtifacts.Sub(CRIT_LOW, 1)).validate());
        assertThrows(IllegalArgumentException.class, () -> recipe(21, MAIN).validate());
    }

    @Test
    void respectsFourStarLevelCapInsteadOfSilentlyClamping() {
        var request = new WebConsoleArtifacts.Request(10001, PURPLE, 16, MAIN, List.of(), false);
        assertEquals(3571, WebConsoleArtifacts.preview(request).main().value());
        assertEquals(17, WebConsoleArtifacts.make(request).getLevel());
        assertThrows(IllegalArgumentException.class,
                () -> new WebConsoleArtifacts.Request(10001, PURPLE, 20, MAIN, List.of(), false).validate());
    }

    @Test
    void keepsEmptySubstatsEmptyAndSupportsTheExistingCustomRollLimit() {
        assertTrue(WebConsoleArtifacts.make(recipe(20, MAIN)).getAppendPropIdList().isEmpty());
        assertEquals(200, WebConsoleArtifacts.make(recipe(0, MAIN, new WebConsoleArtifacts.Sub(CRIT, 200))).getAppendPropIdList().size());
        assertThrows(IllegalArgumentException.class, () -> recipe(0, MAIN, new WebConsoleArtifacts.Sub(CRIT, 201)).validate());
    }

    @Test
    void parsesStrictNumbersAndBooleansWithoutTruncation() {
        String json = "{\"itemId\":" + PIECE + ",\"mainPropId\":" + MAIN + ",\"substats\":[],\"level\":%s}";
        for (String invalid : new String[] {"1.5", "2147483648", "\"20\"", "true", "null"}) {
            assertThrows(IllegalArgumentException.class, () -> WebConsoleArtifacts.Request.parse(json.formatted(invalid)));
        }
        var request = WebConsoleArtifacts.Request.parse(json.formatted("20"));
        assertEquals(20, request.level()); assertEquals(0, request.uid()); assertFalse(request.locked());
        assertThrows(IllegalArgumentException.class, () -> WebConsoleArtifacts.Request.parse(json.formatted("20").replace("\"level\":20", "\"locked\":\"true\"")));
    }
}
