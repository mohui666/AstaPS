package emu.grasscutter.server.packet.recv;

import emu.grasscutter.game.gcg.story.StoryCardRuntime;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.GCGPVEStartGameReqOuterClass.GCGPVEStartGameReq;
import emu.grasscutter.net.proto.GCGPVEStartGameRspOuterClass.GCGPVEStartGameRsp;
import emu.grasscutter.server.game.GameSession;

@Opcodes(PacketOpcodes.GCGPVEStartGameReq)
public final class HandlerGCGPVEStartGameReq extends PacketHandler {
    @Override public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        var req = GCGPVEStartGameReq.parseFrom(payload);
        StoryCardRuntime.offerLevel(session.getPlayer(), req.getGameId());
        var reply = new BasePacket(PacketOpcodes.GCGPVEStartGameRsp);
        reply.setData(GCGPVEStartGameRsp.newBuilder().setLevelId(req.getLevelId()).setRetcode(8));
        session.send(reply);
    }
}
