package emu.grasscutter.server.packet.recv;

import emu.grasscutter.game.quest.coop.CoopSystem;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.SaveMainCoopReqOuterClass.SaveMainCoopReq;
import emu.grasscutter.net.proto.SaveMainCoopRspOuterClass.SaveMainCoopRsp;
import emu.grasscutter.server.game.GameSession;

@Opcodes(PacketOpcodes.SaveMainCoopReq)
public final class HandlerSaveMainCoopReq extends PacketHandler {
    @Override
    public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        var req = SaveMainCoopReq.parseFrom(payload);
        int result = CoopSystem.save(session.getPlayer(), req.getId(), req.getSavePointId(),
                req.getSelfConfidence(), req.getNormalVarMapMap(), req.getTempVarMapMap());
        var rsp = SaveMainCoopRsp.newBuilder().setId(req.getId()).setRetcode(result);
        var run = session.getPlayer().getHangouts().getRun();
        if (result == 0 && run != null) rsp.addAllSavePointIdList(run.getCurrent().getSavePoints());
        var packet = new BasePacket(PacketOpcodes.SaveMainCoopRsp);
        packet.setData(rsp);
        session.send(packet);
    }
}
