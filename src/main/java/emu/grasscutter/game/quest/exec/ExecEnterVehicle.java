package emu.grasscutter.game.quest.exec;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;
import emu.grasscutter.net.proto.VehicleInteractTypeOuterClass.VehicleInteractType;
import emu.grasscutter.server.packet.send.PacketVehicleInteractRsp;

@QuestValueExec(QuestExec.QUEST_EXEC_ENTER_VEHICLE)
public final class ExecEnterVehicle extends QuestExecHandler {
    @Override
    public boolean execute(GameQuest quest, QuestData.QuestExecParam condition, String... params) {
        if (params.length != 3) return false;
        var player = quest.getOwner();
        var scene = player.getScene();
        int gadgetId = Integer.parseInt(params[0]), sceneId = Integer.parseInt(params[1]);
        var data = emu.grasscutter.data.GameData.getGadgetDataMap().get(gadgetId);
        if (data == null || data.getType() != emu.grasscutter.game.props.EntityType.Vehicle) return false;
        var pos = QuestSceneConditions.dummyPoint(sceneId, params[2]);
        var rot = QuestSceneConditions.dummyRotation(sceneId, params[2]);
        if (scene == null || scene.getId() != sceneId || pos == null || rot == null) return false;
        scene.runWhenHostInitialized(() -> {
            if (player.getScene() != scene) return;
            var vehicle = QuestVehicles.create(player, scene, gadgetId, pos, rot);
            if (vehicle == null) {
                emu.grasscutter.Grasscutter.getLogger().error("Could not create quest {} vehicle {} in scene {}",
                        quest.getSubQuestId(), gadgetId, sceneId);
                return;
            }
            player.getStaminaManager().handleVehicleInteractReq(player.getSession(), vehicle.getId(),
                    VehicleInteractType.VehicleInteractType_VEHICLE_INTERACT_IN);
            player.sendPacket(new PacketVehicleInteractRsp(player, vehicle.getId(),
                    VehicleInteractType.VehicleInteractType_VEHICLE_INTERACT_IN));
        });
        return true;
    }
}
