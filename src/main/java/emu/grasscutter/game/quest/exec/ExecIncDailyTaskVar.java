package emu.grasscutter.game.quest.exec;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;

@QuestValueExec(QuestExec.QUEST_EXEC_INC_DAILY_TASK_VAR)
public final class ExecIncDailyTaskVar extends QuestExecHandler {
    @Override public boolean execute(GameQuest quest, QuestData.QuestExecParam condition, String... values) {
        if (values.length != 2 || quest.getOwner().getDailyTaskManager() == null) return false;
        try {
            return quest.getOwner().getDailyTaskManager().incDailyTaskVar(
                    Integer.parseInt(values[0]), Integer.parseInt(values[1]));
        } catch (NumberFormatException error) { return false; }
    }
}
