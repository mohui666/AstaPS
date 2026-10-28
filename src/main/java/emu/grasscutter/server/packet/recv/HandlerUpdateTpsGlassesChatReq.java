package emu.grasscutter.server.packet.recv;

import emu.grasscutter.game.tps.TpsGlassesChatSystem;
import emu.grasscutter.net.packet.Opcodes;
import emu.grasscutter.net.packet.PacketHandler;
import emu.grasscutter.net.proto.UpdateTpsGlassesChatReqOuterClass.UpdateTpsGlassesChatReq;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.packet.send.PacketUpdateTpsGlassesChatRsp;

@Opcodes(TpsGlassesChatSystem.UPDATE_REQ)
public final class HandlerUpdateTpsGlassesChatReq extends PacketHandler {
    @Override
    public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        var request = UpdateTpsGlassesChatReq.parseFrom(payload);
        session.send(new PacketUpdateTpsGlassesChatRsp(
                TpsGlassesChatSystem.update(session.getPlayer(), request)));
    }
}
