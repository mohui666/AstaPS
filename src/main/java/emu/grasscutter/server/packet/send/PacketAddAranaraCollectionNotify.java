package emu.grasscutter.server.packet.send;

import emu.grasscutter.game.quest.aranara.AranaraCollectionState;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.packet.PacketOpcodes;
import emu.grasscutter.net.proto.AddAranaraCollectionNotifyOuterClass.AddAranaraCollectionNotify;

/** 7.1 native JJJGHNOLJHI parser tags 9/12/13/14, confirmed by its native client state consumer. */
public final class PacketAddAranaraCollectionNotify extends BasePacket {
    public PacketAddAranaraCollectionNotify(int type, AranaraCollectionState.Change change) {
        super(PacketOpcodes.AddAranaraCollectionNotify);
        setData(AddAranaraCollectionNotify.newBuilder()
                .setCollectionType(type).setCollectionId(change.pageId())
                .setFromStateValue(change.fromState()).setTargetStateValue(change.targetState()));
    }
}
