package emu.grasscutter.server.packet.recv;

import emu.grasscutter.game.quest.check.QuestCheckRuntime;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.QuestCheckReqOuterClass.QuestCheckReq;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.packet.send.PacketQuestCheckRsp;

@Opcodes(PacketOpcodes.QuestCheckReq)
public final class HandlerQuestCheckReq extends PacketHandler {
    @Override
    public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        var request = QuestCheckReq.parseFrom(payload);
        session.send(new PacketQuestCheckRsp(QuestCheckRuntime.roll(session.getPlayer(),
                request.getParentQuestId(), request.getCheckId())));
    }
}
