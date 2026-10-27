package emu.grasscutter.server.packet.send;

import emu.grasscutter.data.excels.dungeon.DungeonEntryData.DungeonEntryCondition;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.DungeonEntryBlockReasonOuterClass.DungeonEntryBlockReason;
import emu.grasscutter.net.proto.DungeonEntryCondOuterClass.DungeonEntryCond;
import emu.grasscutter.net.proto.GetDungeonEntryExploreConditionRspOuterClass.GetDungeonEntryExploreConditionRsp;
import emu.grasscutter.net.proto.RetcodeOuterClass.Retcode;

public class PacketGetDungeonEntryExploreConditionRsp extends BasePacket {
    public PacketGetDungeonEntryExploreConditionRsp(DungeonEntryCondition unmet) {
        super(PacketOpcodes.GetDungeonEntryExploreConditionRsp);
        var response = GetDungeonEntryExploreConditionRsp.newBuilder();
        if (unmet != null) {
            var reason = switch (unmet.getType()) {
                case DUNGEON_ENTRY_CONDITION_LEVEL ->
                        DungeonEntryBlockReason.DungeonEntryBlockReason_DUNGEON_ENTRY_REASON_LEVEL;
                case DUNGEON_ENTRY_CONDITION_QUEST ->
                        DungeonEntryBlockReason.DungeonEntryBlockReason_DUNGEON_ENTRY_REASON_QUEST;
                case DUNGEON_ENTRY_CONDITION_NONE ->
                        DungeonEntryBlockReason.DungeonEntryBlockReason_DUNGEON_ENTRY_REASON_NONE;
            };
            response.setDungeonEntryCond(DungeonEntryCond.newBuilder()
                    .setCondReason(reason).setParam1(unmet.getParam1()));
        }
        this.setData(response.build());
    }

    public PacketGetDungeonEntryExploreConditionRsp(Retcode error) {
        super(PacketOpcodes.GetDungeonEntryExploreConditionRsp);
        this.setData(GetDungeonEntryExploreConditionRsp.newBuilder().setRetcode(error.getNumber()));
    }
}
