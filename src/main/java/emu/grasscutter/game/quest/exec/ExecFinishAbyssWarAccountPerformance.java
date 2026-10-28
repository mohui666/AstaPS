package emu.grasscutter.game.quest.exec;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.QuestValueExec;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;
import emu.grasscutter.game.quest.war.QuestWarRuntime;

@QuestValueExec(QuestExec.QUEST_EXEC_FINISH_ABYSS_WAR_ACCOUNT_PERFORMANCE)
public final class ExecFinishAbyssWarAccountPerformance extends QuestExecHandler {
    @Override
    public boolean execute(GameQuest quest, QuestData.QuestExecParam condition, String... params) {
        return params.length == 0 && QuestWarRuntime.finishPerformance(quest);
    }
}
