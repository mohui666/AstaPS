package emu.grasscutter.server.packet.recv;

import emu.grasscutter.game.quest.rename.QuestAvatarRenameService;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.QuestRenameAvatarReqOuterClass.QuestRenameAvatarReq;
import emu.grasscutter.net.proto.QuestRenameAvatarRspOuterClass.QuestRenameAvatarRsp;
import emu.grasscutter.server.game.GameSession;

@Opcodes(PacketOpcodes.QuestRenameAvatarReq)
public final class HandlerQuestRenameAvatarReq extends PacketHandler {
    @Override
    public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        var request = QuestRenameAvatarReq.parseFrom(payload);
        int result = QuestAvatarRenameService.questRename(session.getPlayer(), request.getRenameId(),
                request.getAvatarName(), request.getIsCheckOnly());
        var response = new BasePacket(PacketOpcodes.QuestRenameAvatarRsp);
        response.setData(QuestRenameAvatarRsp.newBuilder().setRenameId(request.getRenameId())
                .setAvatarName(request.getAvatarName()).setIsCheckOnly(request.getIsCheckOnly()).setRetcode(result));
        session.send(response);
    }
}
