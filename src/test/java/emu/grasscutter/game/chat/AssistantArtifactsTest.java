package emu.grasscutter.game.chat;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.ItemData;
import emu.grasscutter.data.excels.reliquary.ReliquaryAffixData;
import emu.grasscutter.data.excels.reliquary.ReliquaryMainPropData;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AssistantArtifactsTest {
    private static final Gson GSON = new Gson();
    private static final int PIECE = 9876501, MAIN = 9876502, HIGH = 9876503, LOW = 9876504, SAME_AS_MAIN = 9876505;

    @BeforeEach
    void definitions() {
        GameData.getItemDataMap().put(PIECE, GSON.fromJson("""
                {"id":9876501,"setId":15020,"itemType":"ITEM_RELIQUARY","equipType":"EQUIP_DRESS",
                 "rankLevel":5,"maxLevel":21,"mainPropDepotId":5000,"appendPropDepotId":501,"appendPropNum":0}
                """, ItemData.class));
        GameData.getReliquaryMainPropDataMap().put(MAIN, GSON.fromJson("""
                {"id":9876502,"propDepotId":5000,"propType":"FIGHT_PROP_CRITICAL","weight":100}
                """, ReliquaryMainPropData.class));
        for (int id : List.of(HIGH, LOW, SAME_AS_MAIN)) {
            var row = GSON.fromJson("{\"id\":" + id + ",\"depotId\":501,\"propType\":\""
                    + (id == SAME_AS_MAIN ? "FIGHT_PROP_CRITICAL" : "FIGHT_PROP_CRITICAL_HURT")
                    + "\",\"weight\":100,\"propValue\":" + (id == LOW ? 0.0544 : 0.0777) + "}", ReliquaryAffixData.class);
            GameData.getReliquaryAffixDataMap().put(id, row);
        }
    }

    @AfterEach
    void removeDefinitions() {
        GameData.getItemDataMap().remove(PIECE);
        GameData.getReliquaryMainPropDataMap().remove(MAIN);
        for (int id : List.of(HIGH, LOW, SAME_AS_MAIN)) GameData.getReliquaryAffixDataMap().remove(id);
    }

    @Test
    void nativeMainAndRequestedSubstatTierAreKeptWithoutRandomFill() {
        var item = AssistantArtifacts.create("绝缘 头 暴击率 暴伤:6:1 等级:12");
        assertEquals(PIECE, item.getItemId());
        assertEquals(13, item.getLevel());
        assertEquals(MAIN, item.getMainPropId());
        assertEquals(List.of(LOW, LOW, LOW, LOW, LOW, LOW), item.getAppendPropIdList());
        assertTrue(item.isLocked());
        assertEquals(List.of(HIGH), AssistantArtifacts.create("绝缘 头 暴击率 暴伤:1").getAppendPropIdList());
        assertTrue(AssistantArtifacts.create("绝缘 头 暴击率").getAppendPropIdList().isEmpty());
    }

    @Test
    void invalidSlotsDuplicatePropsAndOutOfRangeRollsCannotCreateAnItem() {
        for (String input : List.of("绝缘 头 火伤", "绝缘 头 暴击率 暴击:1", "绝缘 头 暴击率 暴伤:1 暴伤:2",
                "绝缘 头 暴击率 暴伤:201", "绝缘 头 暴击率 暴伤:1:3", "绝缘 头 暴击率 等级:21", "绝缘 手 暴击率"))
            assertThrows(IllegalArgumentException.class, () -> AssistantArtifacts.create(input), input);
    }
}
