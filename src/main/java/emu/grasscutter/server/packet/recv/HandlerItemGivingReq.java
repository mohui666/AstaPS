package emu.grasscutter.server.packet.recv;

import emu.grasscutter.game.quest.QuestItemGivingService;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.ItemGivingReqOuterClass.ItemGivingReq;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.packet.send.PacketItemGivingRsp;

@Opcodes(PacketOpcodes.ItemGivingReq)
public final class HandlerItemGivingReq extends PacketHandler {
    @Override
    public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        var request = ItemGivingReq.parseFrom(payload);
        var result = QuestItemGivingService.submit(session.getPlayer(), request);
        session.send(new PacketItemGivingRsp(result.givingId(), result.groupId(), result.success()));
    }
}
