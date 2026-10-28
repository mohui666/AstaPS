package emu.grasscutter.game.quest.war;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.utils.JsonUtils;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class QuestWarStateTest {
    @Test
    void changingAnExistingLevelReportsPersistenceIsRequired() {
        var state = new QuestWarState();
        assertTrue(state.setLevels(new int[] {1}, 1));
        assertTrue(state.setLevels(new int[] {1}, 3));
        assertEquals(3, state.getLevelState(1));
        assertFalse(state.setLevels(new int[] {1}, 3));
    }

    @Test
    void invalidBatchCannotPartiallyChangeAnotherLevelOrRegion() {
        var state = new QuestWarState();
        state.setLevels(new int[] {1}, 1);
        state.setRegions(new int[] {20238002}, 2);
        assertThrows(IllegalArgumentException.class, () -> state.setLevels(new int[] {1, -1}, 3));
        assertThrows(IllegalArgumentException.class, () -> state.setRegions(new int[] {20238002, 0}, 4));
        assertEquals(1, state.getLevelState(1));
        assertEquals(2, state.getRegionState(20238002));
    }

    @Test
    void signedAccountActionsAreAppliedOnceAndSurviveSerialization() {
        var state = new QuestWarState();
        assertTrue(state.addAccountOnce(501101, 1001, 18));
        assertFalse(state.addAccountOnce(501101, 1001, 99));
        assertTrue(state.addAccountOnce(501303, 1001, -12));
        var restored = JsonUtils.decode(JsonUtils.encode(state), QuestWarState.class);
        assertEquals(6, restored.getAccountValue(1001));
        assertFalse(restored.addAccountOnce(501303, 1001, -12));
        assertEquals(6, restored.getAccountValue(1001));
        assertEquals(0, new QuestWarState().getAccountValue(1001));
    }

    @Test
    void overflowDoesNotConsumeTheActionReceipt() {
        var state = new QuestWarState();
        state.addAccountOnce(501101, 1002, Integer.MAX_VALUE);
        assertThrows(ArithmeticException.class, () -> state.addAccountOnce(501104, 1002, 1));
        assertTrue(state.addAccountOnce(501104, 1002, -1));
        assertEquals(Integer.MAX_VALUE - 1, state.getAccountValue(1002));
    }

    @Test
    void performanceSnapshotsOnlyActualTotalsAndCannotInventCompletionStates() {
        var state = new QuestWarState();
        state.addAccountOnce(501101, 1001, 18);
        assertTrue(state.finishPerformance());
        state.addAccountOnce(501303, 1001, -12);
        assertEquals(Map.of(1001, 18), state.getSettledAccountValues());
        assertFalse(state.finishPerformance());
        assertEquals(0, state.getLevelState(1));
        assertEquals(0, state.getRegionState(20238002));
    }

    @Test
    void nativeSignedRangesRejectInvertedOrIncompleteValues() {
        assertArrayEquals(new int[] {-25, -18}, QuestWarRuntime.parseRange("-25,-18"));
        assertArrayEquals(new int[] {Integer.MIN_VALUE, Integer.MAX_VALUE},
                QuestWarRuntime.parseRange("-2147483648,2147483647"));
        assertThrows(IllegalArgumentException.class, () -> QuestWarRuntime.parseRange("18,1"));
        assertThrows(IllegalArgumentException.class, () -> QuestWarRuntime.parseRange("1,2,3"));
    }
}
