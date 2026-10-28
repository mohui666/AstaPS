package emu.grasscutter.server.packet.recv;

import emu.grasscutter.game.quest.rename.QuestAvatarRenameService;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.ItemRenameAvatarReqOuterClass.ItemRenameAvatarReq;
import emu.grasscutter.net.proto.ItemRenameAvatarRspOuterClass.ItemRenameAvatarRsp;
import emu.grasscutter.server.game.GameSession;

@Opcodes(PacketOpcodes.ItemRenameAvatarReq)
public final class HandlerItemRenameAvatarReq extends PacketHandler {
    @Override
    public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        var request = ItemRenameAvatarReq.parseFrom(payload);
        int result = QuestAvatarRenameService.itemRename(session.getPlayer(), request.getRenameId(), request.getAvatarName());
        var response = new BasePacket(PacketOpcodes.ItemRenameAvatarRsp);
        response.setData(ItemRenameAvatarRsp.newBuilder().setRenameId(request.getRenameId()).setRetcode(result));
        session.send(response);
    }
}
