package emu.grasscutter.server.packet.recv;

import emu.grasscutter.game.quest.rename.QuestMiscRenameService;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.ItemRenameMiscReqOuterClass.ItemRenameMiscReq;
import emu.grasscutter.net.proto.ItemRenameMiscRspOuterClass.ItemRenameMiscRsp;
import emu.grasscutter.server.game.GameSession;

@Opcodes(PacketOpcodes.ItemRenameMiscReq)
public final class HandlerItemRenameMiscReq extends PacketHandler {
    @Override public void handle(GameSession session,byte[] header,byte[] payload) throws Exception {
        var request = ItemRenameMiscReq.parseFrom(payload); var info = request.getRenameInfo();
        int result = QuestMiscRenameService.itemRename(session.getPlayer(),info.getRenameId(),info.getName());
        var response = new BasePacket(PacketOpcodes.ItemRenameMiscRsp);
        response.setData(ItemRenameMiscRsp.newBuilder().setRenameId(info.getRenameId()).setRetcode(result));
        session.send(response);
    }
}
