package emu.grasscutter.server.packet.send;

import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.scenery.QuestSceneryRuntime;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.QuestSceneryInfoUpdateNotifyOuterClass.QuestSceneryInfoUpdateNotify;

public final class PacketQuestSceneryInfoUpdateNotify extends BasePacket {
    public PacketQuestSceneryInfoUpdateNotify(Player player) {
        super(PacketOpcodes._QuestSceneryInfoUpdateNotify);
        setData(QuestSceneryInfoUpdateNotify.newBuilder().setSceneryInfo(QuestSceneryRuntime.toProto(player)));
    }
}
