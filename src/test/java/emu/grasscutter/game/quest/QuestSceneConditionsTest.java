package emu.grasscutter.game.quest;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.ScriptSceneData;
import emu.grasscutter.game.world.Position;
import emu.grasscutter.utils.JsonUtils;
import org.junit.jupiter.api.Test;

final class QuestSceneConditionsTest {
    @Test
    void leavingQuestRangeRequiresActualDistanceOrAnotherScene() {
        String point = "[3_18185:100,20,30,0,80.512,0]";
        assertFalse(QuestSceneConditions.outsideRange(3, new Position(150,20,30),
                new int[] {3,50}, point, false));
        assertTrue(QuestSceneConditions.outsideRange(3, new Position(151,20,30),
                new int[] {3,50}, point, false));
        assertTrue(QuestSceneConditions.outsideRange(1075, new Position(100,20,30),
                new int[] {3,50}, point, false));
    }

    @Test
    void sumeruDreamRoomsDoNotTriggerTheCityRangeFailure() {
        // 300814 / 300908 allow rooms 1072..1075 while the city-center radius is active.
        String point = "[3_1:100,20,30];1072,1073,1074,1075";
        for (int scene : new int[] {1072,1073,1074,1075})
            assertFalse(QuestSceneConditions.outsideRange(scene, new Position(),
                    new int[] {3,230}, point, true));
        assertTrue(QuestSceneConditions.outsideRange(1076, new Position(),
                new int[] {3,230}, point, true));
    }

    @Test
    void namedDummyPointUsesTheRequiredScene() {
        String key = "flat.luas.scenes.full_globals.lua.json";
        var previous = GameData.getScriptSceneDataMap().put(key, JsonUtils.decode("""
                {"scriptObjectList":{"3/scene3_dummy_points.lua":{
                  "dummy_points":{"Q3007CityCenterPos.pos":[100,20,30]}}}}
                """, ScriptSceneData.class));
        try {
            assertTrue(QuestSceneConditions.outsideRange(3, new Position(400,20,30),
                    new int[] {3,230}, "Q3007CityCenterPos;1072,1073,1074,1075", true));
            assertNull(QuestSceneConditions.dummyPoint(1072, "Q3007CityCenterPos"));
        } finally {
            if (previous == null) GameData.getScriptSceneDataMap().remove(key);
            else GameData.getScriptSceneDataMap().put(key, previous);
        }
    }

    @Test
    void malformedOrUnresolvedCoordinatesCannotFailAQuest() {
        for (String point : new String[] {"missing", "[3_1:NaN,0,0]", "[3_1:1,2]", "[4_1:1,2,3]"})
            assertFalse(QuestSceneConditions.outsideRange(3, new Position(999,0,0),
                    new int[] {3,50}, point, false));
        assertFalse(QuestSceneConditions.outsideRange(3, new Position(), new int[] {3}, "", false));
    }
}
