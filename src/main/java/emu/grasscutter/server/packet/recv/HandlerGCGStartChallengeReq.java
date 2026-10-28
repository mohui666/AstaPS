package emu.grasscutter.server.packet.recv;

import emu.grasscutter.game.gcg.story.StoryCardRuntime;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.GCGStartChallengeReqOuterClass.GCGStartChallengeReq;
import emu.grasscutter.net.proto.GCGStartChallengeRspOuterClass.GCGStartChallengeRsp;
import emu.grasscutter.server.game.GameSession;

/** The story reconstruction uses its real world table; it never claims a native GCG session exists. */
@Opcodes(PacketOpcodes.GCGStartChallengeReq)
public final class HandlerGCGStartChallengeReq extends PacketHandler {
    @Override public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        var req = GCGStartChallengeReq.parseFrom(payload);
        StoryCardRuntime.offerLevel(session.getPlayer(), req.getLevelId());
        var reply = new BasePacket(PacketOpcodes.GCGStartChallengeRsp);
        reply.setData(GCGStartChallengeRsp.newBuilder().setLevelId(req.getLevelId())
                .setConfigId(req.getConfigId()).setLevelType(req.getLevelType()).setRetcode(8));
        session.send(reply);
    }
}
