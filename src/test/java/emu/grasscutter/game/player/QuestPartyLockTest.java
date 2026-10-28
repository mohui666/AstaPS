package emu.grasscutter.game.player;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.GameConstants;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class QuestPartyLockTest {
    @Test
    void choosesTrialRosterByTravelerInsteadOfAlternativeOrder() {
        assertEquals(List.of(10035, 10032), QuestPartyLock.selectRoster(
                "10035,10031;10035,10032", GameConstants.MAIN_CHARACTER_FEMALE,
                id -> id == 10031 ? GameConstants.MAIN_CHARACTER_MALE
                        : id == 10032 ? GameConstants.MAIN_CHARACTER_FEMALE : 10000114));
        assertEquals(List.of(10035, 10031), QuestPartyLock.selectRoster(
                "10035,10032;10035,10031", GameConstants.MAIN_CHARACTER_MALE,
                id -> id == 10031 ? GameConstants.MAIN_CHARACTER_MALE
                        : id == 10032 ? GameConstants.MAIN_CHARACTER_FEMALE : 10000114));
    }

    @Test
    void absentOrAmbiguousGenderNeverFallsBackToTheFirstRoster() {
        assertThrows(IllegalArgumentException.class, () -> QuestPartyLock.selectRoster("1;2", 7, id -> 5));
        assertThrows(IllegalArgumentException.class, () -> QuestPartyLock.selectRoster("1;2", 7, id -> 7));
        assertThrows(IllegalArgumentException.class, () -> QuestPartyLock.selectRoster("1,1", 7, id -> id));
        assertThrows(IllegalArgumentException.class, () -> QuestPartyLock.selectRoster("1,0", 7, id -> id));
    }

    @Test
    void persistedRosterOwnsItsListAndRestoresNonnegativeSelectedIndex() {
        var roster = new ArrayList<>(List.of(10000005, 10000021));
        var lock = new QuestPartyLock(6016, QuestPartyLock.Kind.MIRROR_OWNED, roster, -1, 1333);
        roster.clear();
        assertEquals(List.of(10000005, 10000021), lock.getAvatarIds());
        assertEquals(0, lock.getPreviousCharacterIndex());
        assertTrue(lock.isMirror());
        assertFalse(lock.isTrial());
    }
}
