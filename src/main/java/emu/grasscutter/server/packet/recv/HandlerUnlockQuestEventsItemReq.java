package emu.grasscutter.server.packet.recv;

import emu.grasscutter.game.quest.evidence.QuestEvidenceService;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.UnlockQuestEventsItemReqOuterClass.UnlockQuestEventsItemReq;
import emu.grasscutter.net.proto.UnlockQuestEventsItemRspOuterClass.UnlockQuestEventsItemRsp;
import emu.grasscutter.server.game.GameSession;

@Opcodes(PacketOpcodes.UnlockQuestEventsItemReq)
public final class HandlerUnlockQuestEventsItemReq extends PacketHandler {
    @Override
    public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        var request = UnlockQuestEventsItemReq.parseFrom(payload);
        int result = QuestEvidenceService.acquire(session.getPlayer(), request.getEventId(), request.getItemIdListList());
        var response = new BasePacket(PacketOpcodes.UnlockQuestEventsItemRsp);
        response.setData(UnlockQuestEventsItemRsp.newBuilder().setRetcode(result));
        session.send(response);
    }
}
