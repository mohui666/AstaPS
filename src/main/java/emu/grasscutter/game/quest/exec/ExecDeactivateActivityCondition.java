package emu.grasscutter.game.quest.exec;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;
import emu.grasscutter.game.quest.reconstruction.QuestActivityRuntime;
@QuestValueExec(QuestExec.QUEST_EXEC_INACTIVE_ACTIVITY_COND_STATE)
public final class ExecDeactivateActivityCondition extends QuestExecHandler {
    @Override public boolean execute(GameQuest quest, QuestData.QuestExecParam condition, String... params) { return QuestActivityRuntime.setCondition(quest,false,params); }
}
