package emu.grasscutter.game.quest.exec;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;

/** [vehicle gadget, scene, native dummy point] used by Inazuma and Aranyaka. */
@QuestValueExec(QuestExec.QUEST_EXEC_CREATE_VEHICLE)
public final class ExecCreateVehicle extends QuestExecHandler {
    @Override
    public boolean execute(GameQuest quest, QuestData.QuestExecParam condition, String... params) {
        if (params.length != 3 || quest.getOwner().getWorld() == null) return false;
        int gadgetId = Integer.parseInt(params[0].trim());
        int sceneId = Integer.parseInt(params[1].trim());
        var pos = QuestSceneConditions.dummyPoint(sceneId, params[2]);
        var rot = QuestSceneConditions.dummyRotation(sceneId, params[2]);
        if (pos == null || rot == null) return false;
        return QuestVehicles.create(quest.getOwner(), quest.getOwner().getWorld().getSceneById(sceneId),
                gadgetId, pos, rot) != null;
    }
}
