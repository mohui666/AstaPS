package emu.grasscutter.game.quest.exec;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.QuestValueExec;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;
import emu.grasscutter.game.tps.TpsAvatarSystem;

@QuestValueExec(QuestExec.QUEST_EXEC_UNLOCK_TPS_AVATAR)
public final class ExecUnlockTpsAvatar extends QuestExecHandler {
    @Override
    public boolean execute(GameQuest quest, QuestData.QuestExecParam condition, String... params) {
        return params.length == 0 && TpsAvatarSystem.unlock(quest.getOwner());
    }
}
