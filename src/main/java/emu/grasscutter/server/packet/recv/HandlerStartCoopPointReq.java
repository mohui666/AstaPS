package emu.grasscutter.server.packet.recv;

import emu.grasscutter.game.quest.coop.CoopSystem;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.StartCoopPointReqOuterClass;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.packet.send.PacketStartCoopPointRsp;

@Opcodes(PacketOpcodes.StartCoopPointReq)
public class HandlerStartCoopPointReq extends PacketHandler {

    @Override
    public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        StartCoopPointReqOuterClass.StartCoopPointReq req =
                StartCoopPointReqOuterClass.StartCoopPointReq.parseFrom(payload);
        var coopPoint = req.getCoopPoint();

        session.send(new PacketStartCoopPointRsp(coopPoint, CoopSystem.start(session.getPlayer(), coopPoint)));
    }
}
