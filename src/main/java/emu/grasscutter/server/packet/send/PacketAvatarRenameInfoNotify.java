package emu.grasscutter.server.packet.send;

import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.rename.QuestAvatarRenameCatalog;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.packet.PacketOpcodes;
import emu.grasscutter.net.proto.AvatarRenameInfoNotifyOuterClass.AvatarRenameInfoNotify;
import emu.grasscutter.net.proto.AvatarRenameInfoOuterClass.AvatarRenameInfo;

public final class PacketAvatarRenameInfoNotify extends BasePacket {
    public PacketAvatarRenameInfoNotify(Player player) {
        super(PacketOpcodes.AvatarRenameInfoNotify);
        var packet = AvatarRenameInfoNotify.newBuilder();
        player.getQuestAvatarRenames().getNames().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> {
                    var data = QuestAvatarRenameCatalog.byId(entry.getKey());
                    if (data != null) packet.addAvatarRenameList(AvatarRenameInfo.newBuilder()
                            .setAvatarId(data.avatarId()).setAvatarName(entry.getValue()));
                });
        setData(packet);
    }
}
