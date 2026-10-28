package emu.grasscutter.server.packet.send;

import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.TowerTeamSelectRspOuterClass.TowerTeamSelectRsp;

public class PacketTowerTeamSelectRsp extends BasePacket {

    public PacketTowerTeamSelectRsp() {
        this(0);
    }

    public PacketTowerTeamSelectRsp(int retcode) {
        super(PacketOpcodes.TowerTeamSelectRsp);

        TowerTeamSelectRsp proto = TowerTeamSelectRsp.newBuilder().setRetcode(retcode).build();

        this.setData(proto);
    }
}
