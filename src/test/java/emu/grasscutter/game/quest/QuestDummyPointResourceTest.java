package emu.grasscutter.game.quest;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import emu.grasscutter.data.GameData;
import emu.grasscutter.scripts.ScriptLoader;
import emu.grasscutter.utils.FileUtils;
import emu.grasscutter.utils.JsonUtils;
import java.nio.file.Files;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

/** Checks the exact modern points missing from the installed converted full_globals dump. */
final class QuestDummyPointResourceTest {
    @Test
    void nativeSumeruAndAranyakaPointsAreUsableForRangeAndReconnect() throws Exception {
        assumeTrue(Files.isRegularFile(FileUtils.getScriptPath("Scene/3/scene3_dummy_points.lua")));
        ScriptLoader.init();
        var city = QuestSceneConditions.dummyPoint(3, "Q3007CityCenterPos");
        assertNotNull(city);
        assertEquals(-416.954f, city.getX(), 0.001f);
        var guide = QuestSceneConditions.dummyPoint(3, "Q7302521_guide");
        assertNotNull(guide);
        assertEquals(-1150.839f, guide.getX(), 0.001f);
        assertEquals(71.099f, QuestSceneConditions.dummyRotation(3, "Q7302521_guide").getY(), 0.001f);

        int subId = 99001201;
        var previous = GameData.getTeleportDataMap().put(subId, JsonUtils.decode(
                "{transmit_points:[{scene_id:3,pos:'Q7302521_guide'}]}", TeleportData.class));
        try {
            var position = new ArrayList<emu.grasscutter.game.world.Position>();
            assertTrue(new GameMainQuest().hasTeleportPosition(subId, position));
            assertEquals(guide.getX(), position.getFirst().getX(), 0.001f);
            assertEquals(71.099f, position.get(1).getY(), 0.001f);
        } finally {
            if (previous == null) GameData.getTeleportDataMap().remove(subId);
            else GameData.getTeleportDataMap().put(subId, previous);
        }
    }
}
