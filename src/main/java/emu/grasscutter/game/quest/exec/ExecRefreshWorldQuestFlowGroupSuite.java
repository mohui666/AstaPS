package emu.grasscutter.game.quest.exec;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.QuestValueExec;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;

/** The flow action omits the scene argument supplied by REFRESH_GROUP_SUITE. */
@QuestValueExec(QuestExec.QUEST_EXEC_REFRESH_WORLD_QUEST_FLOW_GROUP_SUITE)
public final class ExecRefreshWorldQuestFlowGroupSuite extends QuestExecHandler {
    @Override
    public boolean execute(GameQuest quest, QuestData.QuestExecParam condition, String... params) {
        if (params.length != 1 || quest.getOwner().getScene() == null) return false;
        return new ExecRefreshGroupSuite().execute(quest, condition,
                String.valueOf(quest.getOwner().getSceneId()), params[0]);
    }
}
