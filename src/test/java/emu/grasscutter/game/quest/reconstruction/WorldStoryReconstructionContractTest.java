package emu.grasscutter.game.quest.reconstruction;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.scripts.ScriptLoader;
import emu.grasscutter.scripts.data.SceneGroup;
import emu.grasscutter.utils.FileUtils;
import java.nio.file.Files;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

/** Cross-check the authored important-story manifest against the unchanged native quest contracts. */
@ExtendWith(ServerResourceFixture.class)
@ResourceLock("GameData")
final class WorldStoryReconstructionContractTest {
    private static final Set<Integer> PARENTS = Set.of(
            73296,74098,74110,74202,74206,75005,76163,79046,79047,79055,79058,79059);

    @Test
    void everyReconstructedWorldSignalStillMatchesItsOriginalQuestAndCount() throws Exception {
        var catalog = StoryReconstructionCatalog.installed();
        var originals = new HashMap<Integer,JsonObject>();
        for (int id : PARENTS) originals.put(id, JsonParser.parseString(Files.readString(
                FileUtils.getResourcePath("BinOutput/Quest/" + id + ".json"))).getAsJsonObject());
        var worldStages = catalog.getStages().stream().filter(stage -> PARENTS.contains(stage.getQuestId() / 100)).toList();
        assertEquals(42, worldStages.size());
        for (var stage : worldStages) {
            var parent = originals.get(stage.getQuestId() / 100);
            var original = java.util.stream.StreamSupport.stream(parent.getAsJsonArray("subQuests").spliterator(), false)
                    .map(value -> value.getAsJsonObject()).filter(q -> q.get("subId").getAsInt() == stage.getQuestId())
                    .findFirst().orElseThrow();
            var expected = new LinkedHashMap<String,Integer>();
            for (var value : original.getAsJsonArray("finishCond")) {
                var condition = value.getAsJsonObject();
                if (condition.get("type").getAsString().equals("QUEST_CONTENT_LUA_NOTIFY"))
                    expected.put(condition.get("param_str").getAsString(), Math.max(1, condition.has("count")
                            ? condition.get("count").getAsInt() : 0));
            }
            var actual = new LinkedHashMap<String,Integer>();
            for (var signal : stage.getNotifications()) actual.put(signal.getKey(), signal.getCount());
            assertEquals(expected, actual, "Original native finish gates for " + stage.getQuestId());
            assertNotNull(catalog.group(stage.getSceneId(), stage.getGroupId()));
        }
        // Original dialogue, cutscene and giving callbacks remain client actor operations.
        for (int id : new int[] {7329604,7411014,7420205,7500511,7616304,7904609,7904610,7904705})
            assertNull(catalog.stage(id), "Do not replace original dialogue or performance " + id);
        assertEquals("battle", catalog.stage(7904706).getKind());
        assertTrue(catalog.stage(7904605).getPoints().getFirst().computeDistance(
                catalog.stage(7904605).getPoints().getLast()) > 5);
        assertEquals(0, catalog.stage(7904605).getPoints().getLast().computeDistance(
                catalog.stage(7904606).getPoints().getFirst()), 0.001);
    }

    @Test
    void allWorldFlowGroupsLoadNativeSuiteSlotsAndPreserveTheExistingAltarObjects() throws Exception {
        ScriptLoader.init();
        var catalog = StoryReconstructionCatalog.installed();
        var groupIds = Set.of(133317241,133408059,133408273,133408024,133408030,133408201,133408222,
                511020128,511020017,610215025,510020009,510020071,510020145,510020173,510020175,133503024);
        for (var group : catalog.getGroups()) {
            if (!groupIds.contains(group.getGroupId())) continue;
            var loaded = SceneGroup.of(group.getGroupId()).load(group.getSceneId());
            assertNotNull(loaded, "Reconstructed group file " + group.getGroupId());
            assertEquals(1, loaded.init_config.io_type);
            assertEquals(1, loaded.init_config.sub_flow_type);
            for (int slot = 1; slot <= group.getSuiteCount(); slot++) {
                var suite = loaded.getSuiteByIndex(slot);
                assertNotNull(suite, "Native71 slot " + group.getGroupId() + "/" + slot);
                assertTrue(suite.triggers.contains("STORY_SELECT_OPTION"));
            }
            if (group.getGroupId() == 133503024) {
                for (int config : new int[] {24001,24004,24005}) assertTrue(loaded.gadgets.containsKey(config));
                assertEquals(70801075, loaded.gadgets.get(24001).gadget_id);
                assertEquals(-2564.328f, loaded.gadgets.get(24001).pos.getX(), 0.001f);
            }
        }
        var originalGateGroup = SceneGroup.of(133503022).load(3);
        assertEquals(350300022, originalGateGroup.gadgets.get(22001).route_id);
        assertEquals(201, originalGateGroup.gadgets.get(22006).state);
        assertTrue(originalGateGroup.getSuiteByIndex(1).gadgets.contains(22006));
    }
}
