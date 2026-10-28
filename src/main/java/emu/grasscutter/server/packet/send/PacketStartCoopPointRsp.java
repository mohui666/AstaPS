package emu.grasscutter.server.packet.send;

import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.StartCoopPointRspOuterClass;

public class PacketStartCoopPointRsp extends BasePacket {

    public PacketStartCoopPointRsp(int coopPoint, emu.grasscutter.game.quest.coop.CoopSystem.Result result) {
        super(PacketOpcodes.StartCoopPointRsp);

        var proto = StartCoopPointRspOuterClass.StartCoopPointRsp.newBuilder().setCoopPoint(coopPoint)
                .setRetcode(result.retcode()).setIsStart(result.firstPoint());
        if (result.snapshot() != null) proto.setStartMainCoop(result.snapshot());

        this.setData(proto);
    }
}
