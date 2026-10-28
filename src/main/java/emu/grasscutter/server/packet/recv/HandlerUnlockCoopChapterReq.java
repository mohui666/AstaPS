package emu.grasscutter.server.packet.recv;

import emu.grasscutter.game.quest.coop.CoopSystem;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.UnlockCoopChapterReqOuterClass.UnlockCoopChapterReq;
import emu.grasscutter.net.proto.UnlockCoopChapterRspOuterClass.UnlockCoopChapterRsp;
import emu.grasscutter.server.game.GameSession;

@Opcodes(PacketOpcodes.UnlockCoopChapterReq)
public final class HandlerUnlockCoopChapterReq extends PacketHandler {
    @Override
    public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        var req = UnlockCoopChapterReq.parseFrom(payload);
        var rsp = UnlockCoopChapterRsp.newBuilder().setChapterId(req.getChapterId())
                .setRetcode(CoopSystem.unlock(session.getPlayer(), req.getChapterId()));
        var packet = new BasePacket(PacketOpcodes.UnlockCoopChapterRsp);
        packet.setData(rsp);
        session.send(packet);
    }
}
