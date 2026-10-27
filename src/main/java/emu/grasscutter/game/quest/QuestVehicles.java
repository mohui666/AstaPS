package emu.grasscutter.game.quest;

import emu.grasscutter.data.GameData;
import emu.grasscutter.game.entity.EntityVehicle;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.EntityType;
import emu.grasscutter.game.world.Position;
import emu.grasscutter.game.world.Scene;
import emu.grasscutter.net.proto.VehicleInteractTypeOuterClass.VehicleInteractType;
import emu.grasscutter.server.packet.send.PacketVehicleInteractRsp;
import java.util.List;

/** Quest and client creation share a vehicle at the same native spawn point. */
public final class QuestVehicles {
    private QuestVehicles() {}

    public static EntityVehicle create(Player owner, Scene scene, int gadgetId, Position pos, Position rot) {
        var data = GameData.getGadgetDataMap().get(gadgetId);
        if (scene == null || pos == null || rot == null || data == null
                || data.getType() != EntityType.Vehicle) return null;
        synchronized (scene) {
            for (var existing : owned(owner, scene, gadgetId)) {
                if (existing.getPosition().computeDistance(pos) < 0.1) return existing;
            }
            var vehicle = new EntityVehicle(scene, owner, gadgetId, 0, pos, rot);
            scene.addEntity(vehicle);
            return vehicle;
        }
    }

    public static List<EntityVehicle> owned(Player owner, Scene scene, int gadgetId) {
        return scene.getEntities().values().stream().filter(entity -> entity instanceof EntityVehicle)
                .map(EntityVehicle.class::cast)
                .filter(vehicle -> vehicle.getOwner() == owner
                        && (gadgetId == 0 || vehicle.getGadgetId() == gadgetId)).toList();
    }

    public static void eject(Scene scene, EntityVehicle vehicle, Player rider) {
        var members = vehicle.getVehicleMembers().stream()
                .filter(member -> rider == null || member.getUid() == rider.getUid()).toList();
        for (var member : members) {
            for (var player : scene.getPlayers()) {
                if (player.getUid() == member.getUid()) {
                    player.getStaminaManager().handleVehicleInteractReq(player.getSession(), vehicle.getId(),
                            VehicleInteractType.VehicleInteractType_VEHICLE_INTERACT_OUT);
                }
            }
            scene.broadcastPacket(new PacketVehicleInteractRsp(vehicle, member,
                    VehicleInteractType.VehicleInteractType_VEHICLE_INTERACT_OUT));
        }
    }
}
