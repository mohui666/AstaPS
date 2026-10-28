package emu.grasscutter.server.packet.recv;

import emu.grasscutter.game.quest.coop.CoopSystem;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.SetCoopChapterViewedReqOuterClass.SetCoopChapterViewedReq;
import emu.grasscutter.net.proto.SetCoopChapterViewedRspOuterClass.SetCoopChapterViewedRsp;
import emu.grasscutter.server.game.GameSession;

@Opcodes(PacketOpcodes.SetCoopChapterViewedReq)
public final class HandlerSetCoopChapterViewedReq extends PacketHandler {
    @Override
    public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        var req = SetCoopChapterViewedReq.parseFrom(payload);
        var rsp = SetCoopChapterViewedRsp.newBuilder().setChapterId(req.getChapterId())
                .setRetcode(CoopSystem.viewed(session.getPlayer(), req.getChapterId()));
        var packet = new BasePacket(PacketOpcodes.SetCoopChapterViewedRsp);
        packet.setData(rsp);
        session.send(packet);
    }
}
