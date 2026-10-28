package emu.grasscutter.server.packet.recv;

import emu.grasscutter.game.quest.coop.CoopSystem;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.TakeCoopRewardReqOuterClass.TakeCoopRewardReq;
import emu.grasscutter.net.proto.TakeCoopRewardRspOuterClass.TakeCoopRewardRsp;
import emu.grasscutter.server.game.GameSession;

@Opcodes(PacketOpcodes.TakeCoopRewardReq)
public final class HandlerTakeCoopRewardReq extends PacketHandler {
    @Override
    public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        var req = TakeCoopRewardReq.parseFrom(payload);
        var rsp = TakeCoopRewardRsp.newBuilder().setRewardId(req.getRewardId())
                .setRetcode(CoopSystem.takeReward(session.getPlayer(), req.getRewardId()));
        var packet = new BasePacket(PacketOpcodes.TakeCoopRewardRsp);
        packet.setData(rsp);
        session.send(packet);
    }
}
