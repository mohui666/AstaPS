package emu.grasscutter.server.packet.recv;

import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.ScenePointEntry;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.RetcodeOuterClass.Retcode;
import emu.grasscutter.net.proto.SceneTransToPointReqOuterClass.SceneTransToPointReq;
import emu.grasscutter.server.event.player.PlayerTeleportEvent.TeleportType;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.packet.send.PacketSceneTransToPointRsp;

@Opcodes(PacketOpcodes.SceneTransToPointReq)
public class HandlerSceneTransToPointReq extends PacketHandler {

    @Override
    public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        SceneTransToPointReq req = SceneTransToPointReq.parseFrom(payload);
        var player = session.getPlayer();

        ScenePointEntry scenePointEntry =
                GameData.getScenePointEntryById(req.getSceneId(), req.getPointId());
        emu.grasscutter.Grasscutter.getLogger().info(
                "传送点请求 uid={} 源场景={} 目标场景={} 点={} 落点={}", player.getUid(),
                player.getSceneId(), req.getSceneId(), req.getPointId(),
                scenePointEntry == null || scenePointEntry.getPointData() == null
                        ? null : scenePointEntry.getPointData().getTranPos());
        if (scenePointEntry == null || scenePointEntry.getPointData() == null
                || scenePointEntry.getPointData().getTranPos() == null) {
            session.send(new PacketSceneTransToPointRsp(
                    Retcode.RET_NOT_FOUND_CONFIG, req.getPointId(), req.getSceneId()));
            return;
        }

        // Only points the player has unlocked and that no quest has locked again, or a waypoint
        // active in the dungeon the player is in.
        var scene = player.getScene();
        var dungeonManager = scene == null ? null : scene.getDungeonManager();
        boolean dungeonPoint =
                dungeonManager != null
                        && player.getSceneId() == req.getSceneId()
                        && dungeonManager.isWayPointActive(req.getPointId());
        if (!dungeonPoint
                && (!emu.grasscutter.game.quest.world.QuestTemporaryResourceRuntime.isPointUnlocked(
                        player, req.getSceneId(), req.getPointId())
                        || player.isScenePointForceLocked(req.getSceneId(), req.getPointId()))) {
            session.send(new PacketSceneTransToPointRsp(
                    Retcode.RET_POINT_NOT_UNLOCKED, req.getPointId(), req.getSceneId()));
            return;
        }
        if (player.getCurHomeWorld().isInHome(player)) {
            session
                    .getServer()
                    .getHomeWorldMPSystem()
                    .leaveCoop(
                            player, req.getSceneId(), scenePointEntry.getPointData().getTranPos().clone());
            session.send(new PacketSceneTransToPointRsp(player, req.getPointId(), req.getSceneId()));
            return;
        } else if (player
                .getWorld()
                .transferPlayerToScene(
                        player,
                        req.getSceneId(),
                        TeleportType.WAYPOINT,
                        scenePointEntry.getPointData().getTranPos().clone())) {
            session.send(new PacketSceneTransToPointRsp(player, req.getPointId(), req.getSceneId()));
            return;
        }
        session.send(new PacketSceneTransToPointRsp(
                Retcode.RET_ENTER_SCENE_FAIL, req.getPointId(), req.getSceneId()));
    }
}
