package emu.grasscutter.server.packet.send;

import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.SceneVarInfoOuterClass.SceneVarInfo;
import emu.grasscutter.net.proto.SceneVarListNotifyOuterClass.SceneVarListNotify;
import emu.grasscutter.net.proto.SceneVarUpdateNotifyOuterClass.SceneVarUpdateNotify;
import java.util.Map;

/** Both native messages assign absolute values; a snapshot first clears this scene's server cache. */
public final class PacketSceneVarNotify extends BasePacket {
    public PacketSceneVarNotify(int sceneId, Map<Integer, Float> values, boolean snapshot) {
        super(snapshot ? PacketOpcodes.SceneVarListNotify : PacketOpcodes.SceneVarUpdateNotify);
        var entries=values.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry->SceneVarInfo.newBuilder().setKeyHash(entry.getKey()).setValue(entry.getValue()).build()).toList();
        if (snapshot) setData(SceneVarListNotify.newBuilder().setSceneId(sceneId).addAllSceneVarList(entries));
        else setData(SceneVarUpdateNotify.newBuilder().setSceneId(sceneId).addAllSceneVarList(entries));
    }
}
