package emu.grasscutter.server.packet.recv;

import emu.grasscutter.game.gcg.story.StoryCardRuntime;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.GCGStartChallengeByCheckRewardReqOuterClass.GCGStartChallengeByCheckRewardReq;
import emu.grasscutter.net.proto.GCGStartChallengeByCheckRewardRspOuterClass.GCGStartChallengeByCheckRewardRsp;
import emu.grasscutter.server.game.GameSession;

@Opcodes(PacketOpcodes.GCGStartChallengeByCheckRewardReq)
public final class HandlerGCGStartChallengeByCheckRewardReq extends PacketHandler {
    @Override public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        var req = GCGStartChallengeByCheckRewardReq.parseFrom(payload);
        StoryCardRuntime.offerLevel(session.getPlayer(), req.getLevelId());
        var reply = new BasePacket(PacketOpcodes.GCGStartChallengeByCheckRewardRsp);
        reply.setData(GCGStartChallengeByCheckRewardRsp.newBuilder().setLevelId(req.getLevelId())
                .setConfigId(req.getConfigId()).setLevelType(req.getLevelType()).setRetcode(8));
        session.send(reply);
    }
}
