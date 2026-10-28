package emu.grasscutter.game.quest.exec;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;
import lombok.val;

@QuestValueExec(QuestExec.QUEST_EXEC_INIT_TIME_VAR)
public class ExecInitTimeVar extends QuestExecHandler {
    @Override
    public boolean execute(GameQuest quest, QuestData.QuestExecParam condition, String... paramStr) {
        var params = condition.getParam();
        if (params == null || params.length < 1) return false;
        try {
            return quest.getMainQuest().initTimeVar(Integer.parseInt(params[0]));
        } catch (NumberFormatException error) { return false; }
    }
}
