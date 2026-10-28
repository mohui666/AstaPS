package emu.grasscutter.game.quest.exec;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;

@QuestValueExec(QuestExec.QUEST_EXEC_SET_DAILY_TASK_VAR)
public final class ExecSetDailyTaskVar extends QuestExecHandler {
    @Override public boolean execute(GameQuest quest, QuestData.QuestExecParam condition, String... values) {
        if (values.length != 3 || quest.getOwner().getDailyTaskManager() == null) return false;
        try {
            return quest.getOwner().getDailyTaskManager().setDailyTaskVar(
                    Integer.parseInt(values[0]), Integer.parseInt(values[1]), Integer.parseInt(values[2]));
        } catch (NumberFormatException error) { return false; }
    }
}
