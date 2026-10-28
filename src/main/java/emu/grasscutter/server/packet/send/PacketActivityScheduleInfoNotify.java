package emu.grasscutter.server.packet.send;

import emu.grasscutter.game.activity.ActivityConfigItem;
import emu.grasscutter.game.activity.ActivityAvailability;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.*;
import emu.grasscutter.utils.helpers.DateHelper;
import java.util.Collection;

public class PacketActivityScheduleInfoNotify extends BasePacket {

    public PacketActivityScheduleInfoNotify(Collection<ActivityConfigItem> activityConfigItemList) {
        super(PacketOpcodes.ActivityScheduleInfoNotify);

        var proto = ActivityScheduleInfoNotifyOuterClass.ActivityScheduleInfoNotify.newBuilder();

        activityConfigItemList.forEach(
                item -> {
                    if (!ActivityAvailability.isRetained(item.getActivityId())) return;
                    proto.addActivityScheduleList(
                            ActivityScheduleInfoOuterClass.ActivityScheduleInfo.newBuilder()
                                    .setActivityId(item.getActivityId())
                                    .setScheduleId(item.getScheduleId())
                                    .setIsOpen(!item.isDisabled()
                                            && new java.util.Date().after(item.getOpenTime())
                                            && new java.util.Date().before(item.getCloseTime()))
                                    .setBeginTime(DateHelper.getUnixTime(item.getBeginTime()))
                                    .setEndTime(DateHelper.getUnixTime(item.getEndTime()))
                                    .build());
                });

        this.setData(proto);
    }
}
