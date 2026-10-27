package emu.grasscutter.server.packet.recv;

import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.ScenePointEntry;
import emu.grasscutter.game.world.Position;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.PersonalSceneJumpReqOuterClass.PersonalSceneJumpReq;
import emu.grasscutter.net.proto.RetcodeOuterClass.Retcode;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.packet.send.PacketPersonalSceneJumpRsp;

@Opcodes(PacketOpcodes.PersonalSceneJumpReq)
public class HandlerPersonalSceneJumpReq extends PacketHandler {

    @Override
    public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        PersonalSceneJumpReq req = PersonalSceneJumpReq.parseFrom(payload);
        var player = session.getPlayer();
        var prevSceneId = player.getSceneId();

        // get the scene point
        ScenePointEntry scenePointEntry =
                GameData.getScenePointEntryById(prevSceneId, req.getPointId());

        if (scenePointEntry == null || scenePointEntry.getPointData() == null
                || scenePointEntry.getPointData().getTranPos() == null) {
            session.send(new PacketPersonalSceneJumpRsp(Retcode.RET_NOT_FOUND_CONFIG));
            return;
        }
        Position pos = scenePointEntry.getPointData().getTranPos().clone();
        int sceneId = scenePointEntry.getPointData().getTranSceneId();
        if (!player.getWorld().transferPlayerToScene(player, sceneId, pos) || player.getScene() == null) {
            session.send(new PacketPersonalSceneJumpRsp(Retcode.RET_ENTER_SCENE_FAIL));
            return;
        }
        player.getScene().setPrevScene(prevSceneId);
        session.send(new PacketPersonalSceneJumpRsp(sceneId, pos));
    }
}
