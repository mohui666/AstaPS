package emu.grasscutter.game.quest;

import emu.grasscutter.data.GameData;
import emu.grasscutter.game.entity.EntityVehicle;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.EntityType;
import emu.grasscutter.game.world.Position;
import emu.grasscutter.game.world.Scene;
import emu.grasscutter.net.proto.VehicleInteractTypeOuterClass.VehicleInteractType;
import emu.grasscutter.net.proto.VehicleMemberOuterClass.VehicleMember;
import emu.grasscutter.game.quest.enums.QuestContent;
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

    public static List<Integer> featureTags(int gadgetId) {
        var vehicle = GameData.getVehicleDataMap().get(gadgetId);
        var group = vehicle == null ? null : GameData.getFeatureTagGroupDataMap().get(vehicle.getFeatureTagGroupID());
        return group == null ? List.of() : group.getTagIDs().stream().filter(id -> id > 0).distinct().toList();
    }

    public static boolean board(EntityVehicle vehicle, VehicleMember member) {
        synchronized (vehicle) {
            if (vehicle.getVehicleMembers().stream().anyMatch(existing -> existing.getUid() == member.getUid()))
                return false;
            vehicle.getVehicleMembers().add(member);
        }
        for (var player : vehicle.getScene().getPlayers()) {
            if (player.getUid() != member.getUid()) continue;
            player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_ENTER_VEHICLE, vehicle.getGadgetId());
            for (int tag : featureTags(vehicle.getGadgetId())) player.getQuestManager().queueEvent(
                    QuestContent.QUEST_CONTENT_ENTER_FEATURE_TAG_VEHICLE, tag);
        }
        return true;
    }

    public static boolean dismount(EntityVehicle vehicle, VehicleMember member) {
        synchronized (vehicle) {
            if (!vehicle.getVehicleMembers().removeIf(existing -> existing.getUid() == member.getUid())) return false;
        }
        for (var player : vehicle.getScene().getPlayers()) {
            if (player.getUid() != member.getUid()) continue;
            player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_LEAVE_VEHICLE, vehicle.getGadgetId());
            for (int tag : featureTags(vehicle.getGadgetId())) player.getQuestManager().queueEvent(
                    QuestContent.QUEST_CONTENT_LEAVE_FEATURE_TAG_VEHICLE, tag);
        }
        return true;
    }
}
