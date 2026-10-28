package emu.grasscutter.game.quest.exec;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;
import emu.grasscutter.game.quest.world.QuestSceneMechanicsRuntime;

@QuestValueExec(QuestExec.QUEST_EXEC_SET_LIMIT_REGION_STATE)
public final class ExecSetLimitRegionState extends QuestExecHandler {
    @Override public boolean execute(GameQuest quest,QuestData.QuestExecParam condition,String... params) {
        return params.length==2&&QuestSceneMechanicsRuntime.setLimitRegion(quest.getOwner(),
                Integer.parseInt(params[0].trim()),Integer.parseInt(params[1].trim()));
    }
}
