package emu.grasscutter.server.packet.recv;

import emu.grasscutter.game.quest.rename.QuestMiscRenameService;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.QuestRenameMiscReqOuterClass.QuestRenameMiscReq;
import emu.grasscutter.net.proto.QuestRenameMiscRspOuterClass.QuestRenameMiscRsp;
import emu.grasscutter.server.game.GameSession;

@Opcodes(PacketOpcodes._QuestRenameMiscReq)
public final class HandlerQuestRenameMiscReq extends PacketHandler {
    @Override public void handle(GameSession session,byte[] header,byte[] payload) throws Exception {
        var request = QuestRenameMiscReq.parseFrom(payload); var info = request.getRenameInfo();
        int result = QuestMiscRenameService.questRename(session.getPlayer(),info.getRenameId(),info.getName(),request.getIsCheckOnly());
        var response = new BasePacket(PacketOpcodes._QuestRenameMiscRsp);
        response.setData(QuestRenameMiscRsp.newBuilder().setName(info.getName()).setRenameId(info.getRenameId())
                .setIsCheckOnly(request.getIsCheckOnly()).setRetcode(result));
        session.send(response);
    }
}
