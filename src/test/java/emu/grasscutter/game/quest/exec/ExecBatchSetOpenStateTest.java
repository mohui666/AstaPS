package emu.grasscutter.game.quest.exec;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

final class ExecBatchSetOpenStateTest {
    @Test
    void parsesNativeMainQuest701209BatchWithoutTreatingItAsOneState() {
        var batch = ExecBatchSetOpenState.parse("7010,7026,7060", "0,0,0");
        assertArrayEquals(new int[] {7010,7026,7060}, batch[0]);
        assertArrayEquals(new int[] {0,0,0}, batch[1]);
    }

    @Test
    void invalidBatchIsRejectedBeforeAnyStateCanBeChanged() {
        assertNull(ExecBatchSetOpenState.parse("7010,7026", "1"));
        assertNull(ExecBatchSetOpenState.parse("0,7026", "1,1"));
        assertThrows(NumberFormatException.class,
                () -> ExecBatchSetOpenState.parse("7010,bad", "1,1"));
    }
}
