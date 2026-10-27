package emu.grasscutter.game.quest.exec;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.QuestValueExec;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;
import emu.grasscutter.net.proto.GrantReasonOuterClass.GrantReason;

/** Restore the normal roster without retiring another quest's or an activity's trial avatars. */
@QuestValueExec(QuestExec.QUEST_EXEC_UNLOCK_AVATAR_TEAM)
public final class ExecUnlockAvatarTeam extends QuestExecHandler {
    @Override
    public boolean execute(GameQuest quest, QuestData.QuestExecParam condition, String... params) {
        var team = quest.getOwner().getTeamManager();
        var trials = team.getTrialAvatars().values().stream()
                .filter(avatar -> avatar.getGrantReason() == GrantReason.GRANT_REASON_BY_QUEST.getNumber()
                        && avatar.getFromParentQuestId() == quest.getMainQuestId())
                .map(avatar -> avatar.getTrialAvatarId()).toList();
        team.removeTrialAvatar(trials);
        return true;
    }
}
