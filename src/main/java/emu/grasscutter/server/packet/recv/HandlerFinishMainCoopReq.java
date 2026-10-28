package emu.grasscutter.server.packet.recv;

import emu.grasscutter.game.quest.coop.CoopSystem;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.FinishMainCoopReqOuterClass.FinishMainCoopReq;
import emu.grasscutter.net.proto.FinishMainCoopRspOuterClass.FinishMainCoopRsp;
import emu.grasscutter.server.game.GameSession;

@Opcodes(PacketOpcodes.FinishMainCoopReq)
public final class HandlerFinishMainCoopReq extends PacketHandler {
    @Override
    public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        var req = FinishMainCoopReq.parseFrom(payload);
        int result = CoopSystem.finish(session.getPlayer(), req.getId(), req.getEndingSavePointId());
        var rsp = FinishMainCoopRsp.newBuilder().setId(req.getId()).setEndingSavePointId(req.getEndingSavePointId())
                .setRetcode(result);
        var packet = new BasePacket(PacketOpcodes.FinishMainCoopRsp);
        packet.setData(rsp);
        session.send(packet);
    }
}
