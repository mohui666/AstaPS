package emu.grasscutter.server.packet.send;

import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.aranara.AranaraCollectionCatalog;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.packet.PacketOpcodes;
import emu.grasscutter.net.proto.AranaraCollectionDataNotifyOuterClass.AranaraCollectionDataNotify;
import emu.grasscutter.net.proto.AranaraCollectionSuiteOuterClass.AranaraCollectionSuite;

/** 7.1 full state: suite list field 3; suite type field 7 and page-state map field 4. */
public final class PacketAranaraCollectionDataNotify extends BasePacket {
    public PacketAranaraCollectionDataNotify(Player player) {
        super(PacketOpcodes.AranaraCollectionDataNotify);
        var data = AranaraCollectionDataNotify.newBuilder();
        var pages = player.getAranaraCollection().getPages();
        var suite = AranaraCollectionSuite.newBuilder().setCollectionType(AranaraCollectionCatalog.CATALOG_V1);
        for (int id : AranaraCollectionCatalog.installed().pageIds().stream().sorted().toList()) {
            int state = pages.getOrDefault(id, 0);
            if (state > 0) suite.putCollectionIdStateMapValue(id, state);
        }
        if (suite.getCollectionIdStateMapCount() > 0) data.addCollectionSuiteList(suite);
        setData(data);
    }
}
