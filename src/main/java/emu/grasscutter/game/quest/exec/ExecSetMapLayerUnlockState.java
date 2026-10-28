package emu.grasscutter.game.quest.exec;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.QuestValueExec;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;
import emu.grasscutter.game.quest.world.QuestSceneOverrideRuntime;

@QuestValueExec(QuestExec.QUEST_EXEC_SET_MAP_LAYER_UNLOCK_STATE)
public final class ExecSetMapLayerUnlockState extends QuestExecHandler {
    @Override
    public boolean execute(GameQuest quest, QuestData.QuestExecParam condition, String... params) {
        return params.length == 2 && QuestSceneOverrideRuntime.setMapLayerState(quest.getOwner(),
                QuestSceneOverrideRuntime.parseMapLayerIds(params[0]), Integer.parseInt(params[1]));
    }
}
