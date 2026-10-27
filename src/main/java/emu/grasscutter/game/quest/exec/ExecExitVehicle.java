package emu.grasscutter.game.quest.exec;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.entity.EntityVehicle;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;

@QuestValueExec(QuestExec.QUEST_EXEC_EXIT_VEHICLE)
public final class ExecExitVehicle extends QuestExecHandler {
    @Override
    public boolean execute(GameQuest quest, QuestData.QuestExecParam condition, String... params) {
        if (params.length != 1 || quest.getOwner().getScene() == null) return false;
        int gadgetId = Integer.parseInt(params[0].trim());
        if (gadgetId < 0) return false;
        var scene = quest.getOwner().getScene();
        for (var entity : scene.getEntities().values()) {
            if (entity instanceof EntityVehicle vehicle
                    && (gadgetId == 0 || vehicle.getGadgetId() == gadgetId))
                QuestVehicles.eject(scene, vehicle, quest.getOwner());
        }
        return true;
    }
}
