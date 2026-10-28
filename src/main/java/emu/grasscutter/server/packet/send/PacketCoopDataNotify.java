package emu.grasscutter.server.packet.send;

import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.coop.CoopSystem;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.CoopChapterOuterClass;
import emu.grasscutter.net.proto.CoopDataNotifyOuterClass;
import emu.grasscutter.net.proto.CoopPointOuterClass;

public class PacketCoopDataNotify extends BasePacket {

    public PacketCoopDataNotify(Player player) {
        super(PacketOpcodes.CoopDataNotify);

        this.setData(CoopSystem.toProto(player));
    }
}
