package emu.grasscutter.game.quest.exec;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;

@QuestValueExec(QuestExec.QUEST_EXEC_NOTIFY_DAILY_TASK)
public final class ExecNotifyDailyTask extends QuestExecHandler {
    @Override public boolean execute(GameQuest quest, QuestData.QuestExecParam condition, String... values) {
        if (values.length != 1 || quest.getOwner().getDailyTaskManager() == null) return false;
        try {
            return quest.getOwner().getDailyTaskManager().onQuestNotify(Integer.parseInt(values[0]),quest.getState());
        } catch (NumberFormatException error) { return false; }
    }
}
