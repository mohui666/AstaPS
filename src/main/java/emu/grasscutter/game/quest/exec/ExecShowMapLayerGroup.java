package emu.grasscutter.game.quest.exec;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.QuestValueExec;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;
import emu.grasscutter.game.quest.world.QuestSceneOverrideRuntime;

/** Native action 83 displays a group; action 84 separately unlocks its individual layers. */
@QuestValueExec(QuestExec.QUEST_EXEC_SHOW_MAP_LAYER_GROUP)
public final class ExecShowMapLayerGroup extends QuestExecHandler {
    @Override
    public boolean execute(GameQuest quest, QuestData.QuestExecParam condition, String... params) {
        if (params == null || params.length != 1 || params[0] == null) return false;
        try {
            return QuestSceneOverrideRuntime.showMapLayerGroup(quest.getOwner(),Integer.parseInt(params[0].trim()));
        } catch (NumberFormatException error) {
            return false;
        }
    }
}
