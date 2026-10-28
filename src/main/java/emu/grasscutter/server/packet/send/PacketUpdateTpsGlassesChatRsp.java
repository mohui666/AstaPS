package emu.grasscutter.server.packet.send;

import emu.grasscutter.game.tps.TpsGlassesChatSystem;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.UpdateTpsGlassesChatRspOuterClass.UpdateTpsGlassesChatRsp;

public final class PacketUpdateTpsGlassesChatRsp extends BasePacket {
    public PacketUpdateTpsGlassesChatRsp(int retcode) {
        super(TpsGlassesChatSystem.UPDATE_RSP);
        setData(UpdateTpsGlassesChatRsp.newBuilder().setRetcode(retcode));
    }
}
