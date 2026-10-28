package emu.grasscutter.server.packet.send;

import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.rename.QuestMiscRenameCatalog;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.MiscRenameDataNotifyOuterClass.MiscRenameDataNotify;
import emu.grasscutter.net.proto.MiscRenameDataOuterClass.MiscRenameData;
import emu.grasscutter.net.proto.MiscRenameInfoOuterClass.MiscRenameInfo;

public final class PacketMiscRenameDataNotify extends BasePacket {
    public PacketMiscRenameDataNotify(Player player) {
        super(PacketOpcodes._MiscRenameDataNotify);
        var data = MiscRenameData.newBuilder();
        player.getQuestMiscRenames().getNames().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                .filter(entry -> QuestMiscRenameCatalog.byId(entry.getKey()) != null).forEach(entry ->
                        data.addRenameList(MiscRenameInfo.newBuilder().setRenameId(entry.getKey()).setName(entry.getValue())));
        setData(MiscRenameDataNotify.newBuilder().setData(data));
    }
}
