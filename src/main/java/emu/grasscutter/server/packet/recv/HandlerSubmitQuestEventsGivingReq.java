package emu.grasscutter.server.packet.recv;

import emu.grasscutter.game.quest.evidence.QuestEvidenceService;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.SubmitQuestEventsGivingReqOuterClass.SubmitQuestEventsGivingReq;
import emu.grasscutter.net.proto.SubmitQuestEventsGivingRspOuterClass.SubmitQuestEventsGivingRsp;
import emu.grasscutter.server.game.GameSession;

@Opcodes(PacketOpcodes.SubmitQuestEventsGivingReq)
public final class HandlerSubmitQuestEventsGivingReq extends PacketHandler {
    @Override
    public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        var request = SubmitQuestEventsGivingReq.parseFrom(payload);
        int result = QuestEvidenceService.submit(session.getPlayer(), request.getGivingId(), request.getItemId());
        var response = new BasePacket(PacketOpcodes.SubmitQuestEventsGivingRsp);
        response.setData(SubmitQuestEventsGivingRsp.newBuilder().setGivingId(request.getGivingId())
                .setItemId(request.getItemId()).setRetcode(result));
        session.send(response);
    }
}
