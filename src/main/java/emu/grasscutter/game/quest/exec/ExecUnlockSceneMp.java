package emu.grasscutter.game.quest.exec;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;
import emu.grasscutter.game.quest.world.QuestSceneMechanicsRuntime;

@QuestValueExec(QuestExec.QUEST_EXEC_UNLOCK_SCENE_MP)
public final class ExecUnlockSceneMp extends QuestExecHandler {
    @Override public boolean execute(GameQuest quest,QuestData.QuestExecParam condition,String... params) {
        return params.length==1&&QuestSceneMechanicsRuntime.unlockMultiplayer(quest.getOwner(),Integer.parseInt(params[0].trim()));
    }
}
