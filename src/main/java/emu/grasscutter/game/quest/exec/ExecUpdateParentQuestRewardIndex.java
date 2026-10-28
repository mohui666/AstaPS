package emu.grasscutter.game.quest.exec;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;

@QuestValueExec(QuestExec.QUEST_EXEC_UPDATE_PARENT_QUEST_REWARD_INDEX)
public final class ExecUpdateParentQuestRewardIndex extends QuestExecHandler {
    @Override
    public boolean execute(GameQuest quest, QuestData.QuestExecParam condition, String... params) {
        if (params.length != 1) return false;
        try {
            return quest.getMainQuest().setRewardIndex(Integer.parseInt(params[0].trim()));
        } catch (NumberFormatException invalidIndex) {
            return false;
        }
    }
}
