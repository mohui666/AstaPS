package emu.grasscutter.server.packet.send;

import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.SceneMpUnlockNotifyOuterClass.SceneMpUnlockNotify;
import java.util.Collection;

/** Native 9135 replaces the client's complete set of quest-unlocked multiplayer scenes. */
public final class PacketSceneMpUnlockNotify extends BasePacket {
    public PacketSceneMpUnlockNotify(Collection<Integer> sceneIds) {
        super(PacketOpcodes._SceneMpUnlockNotify);
        setData(SceneMpUnlockNotify.newBuilder().addAllSceneIdList(sceneIds));
    }
}
