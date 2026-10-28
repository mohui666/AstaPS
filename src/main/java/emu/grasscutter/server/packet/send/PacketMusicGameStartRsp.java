package emu.grasscutter.server.packet.send;

import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.MusicGameStartRspOuterClass;

public class PacketMusicGameStartRsp extends BasePacket {

    public PacketMusicGameStartRsp(int musicBasicId, long musicShareId) {
        this(musicBasicId, musicShareId, 0);
    }

    public PacketMusicGameStartRsp(int musicBasicId, long musicShareId, int retcode) {
        super(PacketOpcodes.MusicGameStartRsp);

        var proto = MusicGameStartRspOuterClass.MusicGameStartRsp.newBuilder();

        proto.setMusicBasicId(musicBasicId).setUgcGuid(musicShareId).setRetcode(retcode);

        this.setData(proto);
    }
}
