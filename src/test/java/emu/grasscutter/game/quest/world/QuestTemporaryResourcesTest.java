package emu.grasscutter.game.quest.world;

import static emu.grasscutter.game.quest.world.QuestSceneOverrides.*;
import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.ScenePointEntry;
import emu.grasscutter.data.common.PointData;
import emu.grasscutter.data.excels.quest.QuestData.QuestExecParam;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;
import emu.grasscutter.game.world.TestWorld.TestPlayer;
import emu.grasscutter.utils.JsonUtils;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

@ExtendWith(ServerResourceFixture.class)
@ResourceLock("GameData")
final class QuestTemporaryResourcesTest {
    private final Map<Integer, ScenePointEntry> originalPoints = new HashMap<>(GameData.getScenePointEntryMap());

    @AfterEach
    void restore() {
        GameData.getScenePointEntryMap().clear();
        GameData.getScenePointEntryMap().putAll(originalPoints);
    }

    @Test
    void nativeResourceOperationAcceptsNamesAndNumbersWhileAbsentRemainsPermanent() {
        var named = JsonUtils.decode("{\"_resourceOpType\":\"QUEST_EXEC_RES_OP_TEMP\"}", QuestExecParam.class);
        var numeric = JsonUtils.decode("{\"resourceOpType\":2}", QuestExecParam.class);
        assertEquals(QuestResourceOperation.TEMPORARY, QuestResourceOperation.parse(named.getResourceOpType()));
        assertEquals(QuestResourceOperation.TEMPORARY_TO_PERMANENT, QuestResourceOperation.parse(numeric.getResourceOpType()));
        assertEquals(QuestResourceOperation.PERMANENT, QuestResourceOperation.parse(new QuestExecParam().getResourceOpType()));
        assertThrows(IllegalArgumentException.class, () -> QuestResourceOperation.parse("unknown"));
    }

    @Test
    void explicitTemporaryUnlockNeverWritesPermanentProgressAndDeletePreservesNewPermanentLock() {
        point(3, 1409);
        var owner = new TemporaryPlayer();
        var quest = quest(owner, 15026);
        var permanentCalls = new AtomicInteger();
        var handler = new QuestExecHandler() {
            @Override public boolean execute(GameQuest quest, QuestExecParam param, String... values) {
                permanentCalls.incrementAndGet();
                owner.getForceLockedScenePoints(3).add(1409);
                return true;
            }
        };
        assertTrue(QuestTemporaryResourceRuntime.execute(quest,
                condition(QuestExec.QUEST_EXEC_UNLOCK_POINT, "QUEST_EXEC_RES_OP_TEMP"), handler, "3", "1409"));
        assertEquals(0, permanentCalls.get());
        assertTrue(owner.getUnlockedScenePoints(3).isEmpty());
        assertTrue(QuestTemporaryResourceRuntime.isPointUnlocked(owner, 3, 1409));
        assertTrue(QuestTemporaryResourceRuntime.execute(quest,
                condition(QuestExec.QUEST_EXEC_LOCK_POINT, null), handler, "3", "1409"));
        assertTrue(owner.getForceLockedScenePoints(3).contains(1409));
        assertTrue(QuestTemporaryResourceRuntime.delete(owner, 3, TEMP_POINT, 1409));
        assertTrue(QuestTemporaryResourceRuntime.isPointLocked(owner, 3, 1409));
        assertFalse(QuestTemporaryResourceRuntime.isPointUnlocked(owner, 3, 1409));
        assertEquals(1, permanentCalls.get());
    }

    @Test
    void promotionCreatesPermanentProgressAndRetiresOnlyItsOwnTemporaryLayer() {
        point(3, 831);
        var owner = new TemporaryPlayer();
        owner.overrides.putTemporaryResource(4011, 3, TEMP_POINT, 831, true);
        owner.overrides.putTemporaryResource(4012, 3, TEMP_POINT, 831, false);
        var permanent = new QuestExecHandler() {
            @Override public boolean execute(GameQuest quest, QuestExecParam param, String... values) {
                owner.getUnlockedScenePoints(3).add(831);
                return true;
            }
        };
        assertTrue(QuestTemporaryResourceRuntime.execute(quest(owner, 4011),
                condition(QuestExec.QUEST_EXEC_UNLOCK_POINT, "2"), permanent, "3", "831"));
        assertTrue(owner.getUnlockedScenePoints(3).contains(831));
        assertEquals(1, owner.overrides.temporaryResources().size());
        assertEquals(4012, owner.overrides.temporaryResources().getFirst().getOwnerQuest());
        assertTrue(QuestTemporaryResourceRuntime.isPointLocked(owner, 3, 831));
        QuestTemporaryResourceRuntime.delete(owner, 3, TEMP_POINT, 831);
        assertTrue(QuestTemporaryResourceRuntime.isPointUnlocked(owner, 3, 831));
    }

    @Test
    void clearingMissingOrAllTemporaryResourcesCannotRemovePermanentTagsOrOtherScenes() {
        var owner = new TemporaryPlayer();
        owner.getSceneTags().put(3, new java.util.HashSet<>(Set.of(190)));
        owner.overrides.putTemporaryResource(602, 3, TEMP_SCENE_TAG, 1771, true);
        owner.overrides.putTemporaryResource(5011, 1099, TEMP_POINT, 1, false);
        assertEquals(Set.of(190, 1771), QuestTemporaryResourceRuntime.effectiveSceneTags(owner).get(3));
        assertTrue(QuestTemporaryResourceRuntime.delete(owner, 3, TEMP_SCENE_TAG, 190));
        assertTrue(owner.getSceneTags().get(3).contains(190));
        assertTrue(QuestTemporaryResourceRuntime.delete(owner, 3, 0, 0));
        assertEquals(Set.of(190), QuestTemporaryResourceRuntime.effectiveSceneTags(owner).get(3));
        assertEquals(1, owner.overrides.temporaryResources().size());
        assertEquals(1099, owner.overrides.temporaryResources().getFirst().getSceneId());
        assertFalse(QuestTemporaryResourceRuntime.delete(owner, 1099, 4, 1));
    }

    @Test
    void temporaryWeatherLayersRestoreThePermanentChoiceAndDoNotMutateWeatherEnables() {
        var owner = new TemporaryPlayer();
        owner.getQuestWeatherAreas().put(190, 1);
        owner.overrides.rememberWeatherBeforeTemporary(3, 190);
        owner.overrides.putTemporaryResource(15027, 3, TEMP_WEATHER_GADGET, 6164, true);
        assertEquals(6164, QuestTemporaryResourceRuntime.effectiveWeatherGadget(owner, 3, 190));
        owner.overrides.putTemporaryResource(15028, 3, TEMP_WEATHER_GADGET, 6164, false);
        assertEquals(190, QuestTemporaryResourceRuntime.effectiveWeatherGadget(owner, 3, 190));
        QuestTemporaryResourceRuntime.delete(owner, 3, TEMP_WEATHER_GADGET, 6164);
        assertEquals(190, QuestTemporaryResourceRuntime.effectiveWeatherGadget(owner, 3, 190));
        assertEquals(Map.of(190, 1), owner.getQuestWeatherAreas());
    }

    private static void point(int scene, int id) {
        var data = JsonUtils.decode("{\"id\":" + id + ",\"unlocked\":true}", PointData.class);
        GameData.getScenePointEntryMap().put((scene << 16) + id, new ScenePointEntry(scene, data));
    }

    private static QuestExecParam condition(QuestExec type, String operation) {
        var condition = new QuestExecParam();
        condition.setType(type);
        condition.setResourceOpType(operation);
        return condition;
    }

    private static GameQuest quest(Player player, int mainId) {
        return new GameQuest() {
            @Override public Player getOwner() { return player; }
            @Override public int getMainQuestId() { return mainId; }
            @Override public int getSubQuestId() { return mainId * 100 + 1; }
        };
    }

    private static final class TemporaryPlayer extends TestPlayer {
        private final QuestSceneOverrides overrides = new QuestSceneOverrides();
        @Override public QuestSceneOverrides getQuestSceneOverrides() { return overrides; }
        @Override public void save() {}
    }
}
