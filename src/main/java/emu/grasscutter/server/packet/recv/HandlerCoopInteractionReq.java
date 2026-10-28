package emu.grasscutter.server.packet.recv;

import emu.grasscutter.game.quest.coop.CoopSystem;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.CoopInteractionReqOuterClass.CoopInteractionReq;
import emu.grasscutter.net.proto.CoopInteractionRspOuterClass.CoopInteractionRsp;
import emu.grasscutter.server.game.GameSession;

@Opcodes(PacketOpcodes.CoopInteractionReq)
public final class HandlerCoopInteractionReq extends PacketHandler {
    @Override
    public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        var req = CoopInteractionReq.parseFrom(payload);
        var rsp = CoopInteractionRsp.newBuilder().setChapterId(req.getChapterId()).setInteractionId(req.getInteractionId())
                .setRetcode(CoopSystem.interaction(session.getPlayer(), req.getChapterId(), req.getInteractionId()));
        var packet = new BasePacket(PacketOpcodes.CoopInteractionRsp);
        packet.setData(rsp);
        session.send(packet);
    }
}
