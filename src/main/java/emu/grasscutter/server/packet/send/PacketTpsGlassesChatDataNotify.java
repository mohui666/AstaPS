package emu.grasscutter.server.packet.send;

import emu.grasscutter.game.tps.TpsGlassesChatState;
import emu.grasscutter.game.tps.TpsGlassesChatSystem;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.TpsGlassesChatDataOuterClass.TpsGlassesChatData;
import emu.grasscutter.net.proto.TpsGlassesChatDataNotifyOuterClass.TpsGlassesChatDataNotify;
import emu.grasscutter.net.proto.TpsGlassesChatTopicInfoOuterClass.TpsGlassesChatTopicInfo;

public final class PacketTpsGlassesChatDataNotify extends BasePacket {
    public PacketTpsGlassesChatDataNotify(TpsGlassesChatState state) {
        super(TpsGlassesChatSystem.DATA_NOTIFY);
        var data = TpsGlassesChatData.newBuilder();
        for (var topic : state.snapshot()) {
            data.addTopicList(TpsGlassesChatTopicInfo.newBuilder()
                    .setTopicId(topic.id()).setProgress(topic.progress())
                    .addAllChosenOptionList(topic.chosenOptions())
                    .setCPGANAPPFDM(topic.cpganappfdm()).setKGBBAACLKLB(topic.kgbbaaclklb()));
        }
        setData(TpsGlassesChatDataNotify.newBuilder().setChatData(data));
    }
}
