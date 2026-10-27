package emu.grasscutter.server.packet.recv;

import com.google.protobuf.CodedInputStream;
import emu.grasscutter.net.proto.DungeonQuickOpenReq;
import emu.grasscutter.net.proto.DungeonQuickOpenRsp;
import emu.grasscutter.net.proto.RetcodeOuterClass.Retcode;
import emu.grasscutter.game.dungeons.DomainDungeonHelper;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.packet.Opcodes;
import emu.grasscutter.net.packet.PacketHandler;
import emu.grasscutter.net.packet.PacketOpcodes;
import emu.grasscutter.server.game.GameSession;

/**
 * Handbook preparation "quick open" path. The field number follows the generated 7.1 request;
 * malformed or rejected requests receive a failure response rather than an empty success.
 */
@Opcodes(PacketOpcodes.DungeonQuickOpenReq)
public class HandlerDungeonQuickOpenReq extends PacketHandler {
    @Override
    public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        int entryConfigId = 0;
        if (payload != null && payload.length > 0) {
            try {
                CodedInputStream input = CodedInputStream.newInstance(payload);
                while (!input.isAtEnd()) {
                    int tag = input.readTag();
                    int field = tag >>> 3;
                    if (field == DungeonQuickOpenReq._DungeonQuickOpenReq.DUNGEON_ENTRY_CONFIG_ID_FIELD_NUMBER) {
                        entryConfigId = input.readUInt32();
                        break;
                    }
                    input.skipField(tag);
                }
            } catch (Exception ignored) {
            }
        }
        boolean entered = DomainDungeonHelper.handleQuickOpen(session.getPlayer(), entryConfigId);
        var response = new BasePacket(PacketOpcodes.DungeonQuickOpenRsp);
        response.setData(DungeonQuickOpenRsp._DungeonQuickOpenRsp.newBuilder()
                .setDungeonEntryConfigId(entryConfigId)
                .setRetcode(entered ? Retcode.RET_SUCC_VALUE : Retcode.RET_DUNGEON_ENTER_FAIL_VALUE));
        session.send(response);
    }
}
