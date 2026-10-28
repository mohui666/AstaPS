package emu.grasscutter.server.packet.send;

import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.CutSceneBeginNotifyOuterClass.CutSceneBeginNotify;
import emu.grasscutter.net.proto.CutSceneExtraParamOuterClass.CutSceneExtraParam;
import java.util.List;

public class PacketCutsceneBeginNotify extends BasePacket {

    public PacketCutsceneBeginNotify(int cutsceneId) {
        this(cutsceneId, false);
    }

    public PacketCutsceneBeginNotify(int cutsceneId, boolean waitForFinish) {
        this(cutsceneId, waitForFinish, List.of());
    }

    public PacketCutsceneBeginNotify(int cutsceneId, boolean waitForFinish, List<List<Double>> parameters) {
        super(PacketOpcodes.CutSceneBeginNotify, true);
        var data = CutSceneBeginNotify.newBuilder().setCutsceneId(cutsceneId).setIsWaitOthers(waitForFinish);
        for (var row : parameters)
            data.addExtraParamList(CutSceneExtraParam.newBuilder().addAllDetailParamList(row));
        setData(data);
    }
}
