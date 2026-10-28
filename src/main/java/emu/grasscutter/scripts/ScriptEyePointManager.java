package emu.grasscutter.scripts;

import emu.grasscutter.game.entity.EntityRegion;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.world.Position;
import emu.grasscutter.game.world.Scene;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.packet.PacketOpcodes;
import emu.grasscutter.net.proto.PlayerEyePointStateNotifyOuterClass.PlayerEyePointStateNotify;
import emu.grasscutter.scripts.constants.ScriptRegionShape;
import emu.grasscutter.scripts.data.SceneGroup;
import emu.grasscutter.scripts.data.SceneRegion;
import java.util.HashMap;
import java.util.Map;

/** A player's scene-streaming eye follows a real script region, without moving their avatar. */
public final class ScriptEyePointManager {
    private record Eye(EntityRegion target, SceneRegion boundary, boolean stream, int lod) {}
    private final Scene scene;
    private final Map<Integer, Eye> eyes = new HashMap<>();

    public ScriptEyePointManager(Scene scene) { this.scene = scene; }

    public int set(int uid, SceneGroup group, int targetId, int boundaryId, boolean stream, int lod) {
        synchronized (scene) {
            var player = scene.getPlayers().stream().filter(value -> value.getUid() == uid).findFirst().orElse(null);
            if (player == null || player.getScene() != scene || group == null || group.regions == null) return -1;
            var target = scene.getScriptManager().getRegionByConfigId(group.id, targetId);
            var boundary = group.regions.get(boundaryId);
            if (target == null || boundary == null || !validRegion(target.getMetaRegion()) || !validRegion(boundary)) return -1;
            var previous = eyes.get(uid);
            if (previous != null && previous.target() == target) return 0;
            var eye = new Eye(target, boundary, stream, lod);
            eyes.put(uid, eye);
            send(player, eye);
            return 0;
        }
    }

    public int clear(int uid, int groupId, int targetId) {
        synchronized (scene) {
            var player = scene.getPlayers().stream().filter(value -> value.getUid() == uid).findFirst().orElse(null);
            if (player == null || scene.getScriptManager().getRegionByConfigId(groupId, targetId) == null) return -1;
            var eye = eyes.get(uid);
            if (eye == null) return 0;
            if (eye.target().getGroupId() != groupId || eye.target().getConfigId() != targetId) return -1;
            eyes.remove(uid);
            send(player, null);
            return 0;
        }
    }

    public Position viewPosition(Player player) {
        synchronized (scene) {
            var eye = eyes.get(player.getUid());
            if (eye == null) return player.getPosition();
            if (player.getScene() != scene
                    || scene.getScriptManager().getRegionById(eye.target().getId()) != eye.target()
                    || !eye.boundary().contains(player.getPosition())) {
                eyes.remove(player.getUid());
                if (player.getScene() == scene) send(player, null);
                return player.getPosition();
            }
            return eye.target().getPosition();
        }
    }

    public void removePlayer(int uid) {
        synchronized (scene) { eyes.remove(uid); }
    }

    private static boolean validRegion(SceneRegion region) {
        if (region == null || region.pos == null || !finite(region.pos)) return false;
        return switch (region.shape) {
            case ScriptRegionShape.SPHERE -> region.radius > 0;
            case ScriptRegionShape.CUBIC -> region.size != null && finite(region.size)
                    && region.size.getX() > 0 && region.size.getY() > 0 && region.size.getZ() > 0;
            default -> false;
        };
    }

    private static boolean finite(Position position) {
        return Float.isFinite(position.getX()) && Float.isFinite(position.getY()) && Float.isFinite(position.getZ());
    }

    private static void send(Player player, Eye eye) {
        var data = PlayerEyePointStateNotify.newBuilder().setIsUseEyePoint(eye != null);
        if (eye != null) {
            var region = eye.target().getMetaRegion();
            data.setFixLodLevel(eye.lod()).setIsFilterStreamPos(eye.stream())
                    .setEyePointPos(eye.target().getPosition().toProto()).setRegionShape(region.shape)
                    .setRegionConfigId(region.config_id);
            if (region.shape == ScriptRegionShape.SPHERE) data.setSphereRadius(region.radius);
            else data.setCubicSize(region.size.toProto());
        }
        var packet = new BasePacket(PacketOpcodes.PlayerEyePointStateNotify);
        packet.setData(data);
        player.sendPacket(packet);
    }
}
