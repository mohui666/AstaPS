package emu.grasscutter.game.quest.exec;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.player.TeamManager;
import emu.grasscutter.game.world.TestWorld.TestPlayer;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(ServerResourceFixture.class)
final class QuestPartyExecTest {
    @Test
    void v2TrialDoesNotMistakeLockSourceForTheTrialAvatarId() {
        assertEquals(List.of(63), QuestPartyExec.roster(new TestPlayer(),
                QuestExec.QUEST_EXEC_GRANT_TRIAL_AVATAR_AND_LOCK_TEAM_V2, "2", "63"));
        assertEquals(List.of(10013, 72, 73, 74), QuestPartyExec.roster(new TestPlayer(),
                QuestExec.QUEST_EXEC_GRANT_TRIAL_AVATAR_BATCH_AND_LOCK_TEAM_V2,
                "1", "10013,72,73,74", "0"));
    }

    @Test
    void v2OwnedRosterConsumesAllAvatarSlotsAndDeduplicatesTravelerAliases() {
        var player = new TestPlayer() { @Override public int getMainCharacterId() { return 10000007; } };
        assertEquals(List.of(10000025, 10000080, 10000034), QuestPartyExec.roster(player,
                QuestExec.QUEST_EXEC_LOCK_AVATAR_TEAM_V2, "1", "10000025", "10000080", "10000034"));
        assertEquals(List.of(10000007), QuestPartyExec.roster(player,
                QuestExec.QUEST_EXEC_LOCK_AVATAR_TEAM_V2, "1", "10000005", "10000007"));
    }

    @Test
    void zeroMirrorRosterCopiesTheCurrentRosterInsteadOfInventingAnAvatarZero() {
        var player = new TestPlayer() {
            private final TeamManager team = new TeamManager(this);
            @Override public TeamManager getTeamManager() { return team; }
        };
        player.getTeamManager().getCurrentTeamInfo().getAvatars().addAll(List.of(10000021, 10000016));
        assertEquals(List.of(10000021, 10000016), QuestPartyExec.roster(player,
                QuestExec.QUEST_EXEC_LOCK_MIRROR_AVATAR_TEAM, "0", "0", "0", "1"));
        assertThrows(IllegalArgumentException.class, () -> QuestPartyExec.roster(player,
                QuestExec.QUEST_EXEC_GRANT_TRIAL_AVATAR_AND_LOCK_TEAM_V2, "2"));
    }
}
