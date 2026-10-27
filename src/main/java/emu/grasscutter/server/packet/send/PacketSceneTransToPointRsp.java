package emu.grasscutter.server.packet.send;

import emu.grasscutter.game.player.Player;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.RetcodeOuterClass.Retcode;
import emu.grasscutter.net.proto.SceneTransToPointRspOuterClass.SceneTransToPointRsp;

public class PacketSceneTransToPointRsp extends BasePacket {

    public PacketSceneTransToPointRsp(Player player, int pointId, int sceneId) {
        super(PacketOpcodes.SceneTransToPointRsp);

        SceneTransToPointRsp proto =
                SceneTransToPointRsp.newBuilder()
                        .setRetcode(0)
                        .setPointId(pointId)
                        .setSceneId(sceneId)
                        .build();

        this.setData(proto);
    }

    public PacketSceneTransToPointRsp(Retcode error, int pointId, int sceneId) {
        super(PacketOpcodes.SceneTransToPointRsp);

        SceneTransToPointRsp proto =
                SceneTransToPointRsp.newBuilder()
                        .setRetcode(error.getNumber())
                        .setPointId(pointId)
                        .setSceneId(sceneId)
                        .build();

        this.setData(proto);
    }
}
