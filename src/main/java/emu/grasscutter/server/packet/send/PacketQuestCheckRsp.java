package emu.grasscutter.server.packet.send;

import emu.grasscutter.game.quest.check.QuestCheckRuntime;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.QuestCheckRspOuterClass.QuestCheckRsp;

public final class PacketQuestCheckRsp extends BasePacket {
    public PacketQuestCheckRsp(QuestCheckRuntime.Response response) {
        super(PacketOpcodes.QuestCheckRsp);
        var wire = QuestCheckRsp.newBuilder().setParentQuestId(response.parent())
                .setCheckId(response.check()).setRetcode(response.retcode());
        if (response.result() != null) wire.setIsSuccess(response.result().isSuccess())
                .setTotalRoll(response.result().getTotalRoll())
                .addAllAppliedBonusIdList(response.result().getAppliedBonusIds());
        setData(wire);
    }
}
