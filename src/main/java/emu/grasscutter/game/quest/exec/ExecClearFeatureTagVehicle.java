package emu.grasscutter.game.quest.exec;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;
import emu.grasscutter.net.proto.VisionTypeOuterClass.VisionType;

@QuestValueExec(QuestExec.QUEST_EXEC_CLEAR_FEATURE_TAG_VEHICLE)
public final class ExecClearFeatureTagVehicle extends QuestExecHandler {
    @Override
    public boolean execute(GameQuest quest, QuestData.QuestExecParam condition, String... params) {
        if (params.length != 2 || quest.getOwner().getWorld() == null) return false;
        int tag = Integer.parseInt(params[0]), sceneId = Integer.parseInt(params[1]);
        if (tag <= 0 || sceneId <= 0) return false;
        var scene = quest.getOwner().getWorld().getScenes().get(sceneId);
        if (scene == null) return true;
        for (var vehicle : QuestVehicles.owned(quest.getOwner(), scene, 0)) {
            if (!QuestVehicles.featureTags(vehicle.getGadgetId()).contains(tag)) continue;
            QuestVehicles.eject(scene, vehicle, null);
            scene.removeEntity(vehicle, VisionType.VisionType_VISION_REMOVE);
        }
        return true;
    }
}
