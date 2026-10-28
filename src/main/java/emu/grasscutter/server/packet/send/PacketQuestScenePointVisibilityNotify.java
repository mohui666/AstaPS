package emu.grasscutter.server.packet.send;

import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.packet.PacketOpcodes;
import emu.grasscutter.net.proto.ScenePointUnlockNotifyOuterClass.ScenePointUnlockNotify;

/** Official 7.1 unhide is field 8; the generated unhidePointList name is actually lock field 4. */
public final class PacketQuestScenePointVisibilityNotify extends BasePacket {
    public PacketQuestScenePointVisibilityNotify(int sceneId, Iterable<Integer> points, boolean visible) {
        super(PacketOpcodes.ScenePointUnlockNotify);
        var proto = ScenePointUnlockNotify.newBuilder().setSceneId(sceneId);
        if (visible) proto.addAllHBKHEKLMEKN(points);
        else proto.addAllHidePointList(points);
        setData(proto);
    }
}
