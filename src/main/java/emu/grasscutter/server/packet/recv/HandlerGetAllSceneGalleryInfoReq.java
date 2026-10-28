package emu.grasscutter.server.packet.recv;

import emu.grasscutter.net.packet.*;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.packet.send.PacketGetAllSceneGalleryInfoRsp;

@Opcodes(PacketOpcodes.GetAllSceneGalleryInfoReq)
public class HandlerGetAllSceneGalleryInfoReq extends PacketHandler {

    @Override
    public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        var scene = session.getPlayer().getScene();
        session.send(new PacketGetAllSceneGalleryInfoRsp(
                scene == null ? null : scene.getScriptGalleries().running()));
    }
}
