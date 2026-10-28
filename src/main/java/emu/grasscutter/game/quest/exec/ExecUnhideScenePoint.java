package emu.grasscutter.game.quest.exec;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.QuestValueExec;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;
import emu.grasscutter.game.quest.world.QuestSceneOverrideRuntime;

@QuestValueExec(QuestExec.QUEST_EXEC_UNHIDE_SCENE_POINT)
public final class ExecUnhideScenePoint extends QuestExecHandler {
    @Override
    public boolean execute(GameQuest quest, QuestData.QuestExecParam condition, String... params) {
        return params.length == 2 && QuestSceneOverrideRuntime.setPointVisibility(quest.getOwner(),
                Integer.parseInt(params[0]), QuestSceneOverrideRuntime.parsePointIds(params[1]), true);
    }
}
