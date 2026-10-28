package emu.grasscutter.server.packet.send;

import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.evidence.QuestEvidenceCatalog;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.packet.PacketOpcodes;
import emu.grasscutter.net.proto.QuestEventsDataNotifyOuterClass.QuestEventsDataNotify;
import emu.grasscutter.net.proto.QuestEventsDataOuterClass.QuestEventsData;
import emu.grasscutter.net.proto.QuestEventsGivingRecordOuterClass.QuestEventsGivingRecord;
import java.util.Collection;

/** The native client replaces each included event's evidence and giving snapshot. */
public final class PacketQuestEventsDataNotify extends BasePacket {
    public PacketQuestEventsDataNotify(Player player, Collection<Integer> eventIds) {
        super(PacketOpcodes.QuestEventsDataNotify);
        var state = player.getQuestEvidence();
        var catalog = QuestEvidenceCatalog.installed();
        var notification = QuestEventsDataNotify.newBuilder();
        for (int eventId : eventIds.stream().sorted().toList()) {
            var event = QuestEventsData.newBuilder().setEventId(eventId);
            state.getItemStatuses(eventId).entrySet().stream().filter(entry -> entry.getValue() == 1)
                    .map(java.util.Map.Entry::getKey).sorted().forEach(event::addUnlockedItemList);
            state.getGivings().stream().filter(progress -> {
                var data = catalog.giving(progress.givingId());
                return data != null && data.eventId() == eventId;
            }).sorted(java.util.Comparator.comparingInt(
                    emu.grasscutter.game.quest.evidence.QuestEvidenceState.GivingProgress::givingId))
                    .forEach(progress -> event.addGivingRecordList(QuestEventsGivingRecord.newBuilder()
                            .setGivingId(progress.givingId()).setIsFinished(progress.finished())
                            .addAllItemIdList(progress.itemIds())));
            notification.addEventList(event);
        }
        setData(notification);
    }
}
