package emu.grasscutter.game.quest.exec;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;
import lombok.val;

@QuestValueExec(QuestExec.QUEST_EXEC_CLEAR_TIME_VAR)
public class ExecClearTimeVar extends QuestExecHandler {
    @Override
    public boolean execute(GameQuest quest, QuestData.QuestExecParam condition, String... paramStr) {
        var params = condition.getParam();
        if (params == null || params.length < 2) return false;
        try {
            var mainQuest = quest.getOwner().getQuestManager().getMainQuestById(Integer.parseInt(params[0]));
            return mainQuest != null && mainQuest.clearTimeVar(Integer.parseInt(params[1]));
        } catch (NumberFormatException error) { return false; }
    }
}
