package emu.grasscutter.server.packet.send;

import emu.grasscutter.game.player.Player;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.packet.PacketOpcodes;
import emu.grasscutter.net.proto.AvatarTeamUpdateNotifyOuterClass.AvatarTeamUpdateNotify;

/** Native temporary party GUIDs lock the team editor without rewriting any saved party. */
public final class PacketQuestPartyUpdateNotify extends BasePacket {
    public PacketQuestPartyUpdateNotify(Player player) {
        super(PacketOpcodes.AvatarTeamUpdateNotify);
        setData(AvatarTeamUpdateNotify.newBuilder().addAllTempAvatarGuidList(
                player.getTeamManager().getActiveTeam().stream().map(entity -> entity.getAvatar().getGuid()).toList()));
    }
}
