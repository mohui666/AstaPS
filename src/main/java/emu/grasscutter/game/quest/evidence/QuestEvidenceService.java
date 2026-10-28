package emu.grasscutter.game.quest.evidence;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.enums.QuestContent;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.enums.QuestState;
import emu.grasscutter.net.proto.RetcodeOuterClass.Retcode;
import emu.grasscutter.server.packet.send.PacketQuestEventsDataNotify;
import java.util.Collection;
import java.util.Set;
import java.util.List;

public final class QuestEvidenceService {
    private QuestEvidenceService() {}

    /** Viewing an owned record book changes no evidence or quest progress. */
    public static boolean openBook(Player player, int itemId) {
        var event = QuestEvidenceCatalog.installed().eventForBook(itemId);
        var item = player.getInventory().getFirstItem(itemId);
        if (event == null || item == null || item.getCount() < 1) return false;
        player.sendPacket(new PacketQuestEventsDataNotify(player, Set.of(event.id())));
        return true;
    }

    public static boolean unlock(GameQuest quest, int eventId, List<Integer> itemIds) {
        var event = QuestEvidenceCatalog.installed().event(eventId);
        if (event == null || !event.mainQuestIds().contains(quest.getMainQuestId())) return false;
        var owner = quest.getOwner();
        if (owner.getQuestEvidence().unlock(eventId, itemIds)) {
            owner.save();
            owner.sendPacket(new PacketQuestEventsDataNotify(owner, Set.of(eventId)));
            owner.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_EVENTS_ITEM_STATUS, eventId, 1);
        }
        return true;
    }

    /** The client requests a real clue collection; it cannot grant an unknown or inactive case. */
    public static int acquire(Player player, int eventId, Collection<Integer> itemIds) {
        var catalog = QuestEvidenceCatalog.installed();
        var event = catalog.event(eventId);
        if (event == null || itemIds.isEmpty()
                || itemIds.stream().anyMatch(id -> !catalog.isKnownItem(eventId, id)))
            return Retcode.RET_FAIL_VALUE;
        var state = player.getQuestEvidence();
        synchronized (state) {
            boolean alreadyCollected = itemIds.stream().allMatch(id -> state.getStatus(eventId, id) == 1);
            if (!alreadyCollected && !hasActiveEvent(player, event)) return Retcode.RET_QUEST_NOT_EXIST_VALUE;
            var book = player.getInventory().getFirstItem(event.bookMaterialId());
            if (!alreadyCollected && (book == null || book.getCount() < 1)) return Retcode.RET_ITEM_NOT_EXIST_VALUE;
            if (state.unlock(eventId, itemIds)) {
                player.save();
                player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_EVENTS_ITEM_STATUS, eventId, 1);
            }
            player.sendPacket(new PacketQuestEventsDataNotify(player, Set.of(eventId)));
            return 0;
        }
    }

    /** Native requests submit one evidence at a time, including multi-evidence answers. */
    public static int submit(Player player, int givingId, int itemId) {
        var catalog = QuestEvidenceCatalog.installed();
        var giving = catalog.giving(givingId);
        if (giving == null) return Retcode.RET_QUEST_NOT_EXIST_VALUE;
        var event = catalog.event(giving.eventId());
        var state = player.getQuestEvidence();
        synchronized (state) {
            if (state.isGivingFinished(givingId) && state.hasGiven(givingId, itemId)) {
                player.sendPacket(new PacketQuestEventsDataNotify(player, Set.of(giving.eventId())));
                return 0;
            }
            boolean active = catalog.activeQuestsForGiving(givingId).stream().anyMatch(id -> {
                var quest = player.getQuestManager().getQuestById(id);
                return quest != null && quest.getState().getValue() == QuestState.QUEST_STATE_UNFINISHED.getValue();
            });
            if (!active || !hasActiveEvent(player, event)) return Retcode.RET_QUEST_NOT_EXIST_VALUE;
            if (!giving.candidates().contains(itemId) || state.getStatus(giving.eventId(), itemId) != 1)
                return Retcode.RET_ITEM_NOT_EXIST_VALUE;
            if (state.isGivingFinished(givingId)) return Retcode.RET_SUBMIT_QUEST_EVENTS_NOT_RIGHT_VALUE;
            if (!giving.correctItems().contains(itemId)) {
                state.recordIncorrectItem(givingId);
                player.save();
                player.sendPacket(new PacketQuestEventsDataNotify(player, Set.of(giving.eventId())));
                return Retcode.RET_SUBMIT_QUEST_EVENTS_NOT_RIGHT_VALUE;
            }
            if (state.recordCorrectItem(givingId, itemId, giving.correctItems())) player.save();
            player.sendPacket(new PacketQuestEventsDataNotify(player, Set.of(giving.eventId())));
            return 0;
        }
    }

    private static boolean hasActiveEvent(Player player, QuestEvidenceCatalog.Event event) {
        return event.mainQuestIds().stream().map(id -> player.getQuestManager().getMainQuestById(id))
                .anyMatch(main -> main != null && !main.isFinished()
                        && main.getChildQuests().values().stream()
                        .anyMatch(quest -> quest.getState().getValue() == QuestState.QUEST_STATE_UNFINISHED.getValue()));
    }

    /** Restore only grants whose actual quest action has already run, before predicate snapshots. */
    public static void onLogin(Player player) {
        boolean changed = false;
        for (var main : player.getQuestManager().getMainQuests().values()) {
            for (var quest : main.getChildQuests().values()) {
                var data = quest.getQuestData();
                if (data == null) continue;
                if (quest.getState().getValue() >= QuestState.QUEST_STATE_UNFINISHED.getValue()) {
                    changed |= restoreActions(player, quest, data.getBeginExec());
                }
                if (quest.getState().getValue() == QuestState.QUEST_STATE_FINISHED.getValue()) {
                    changed |= restoreActions(player, quest, data.getFinishExec());
                }
            }
        }
        if (changed) player.save();
        if (!player.getQuestEvidence().getEventIds().isEmpty()) {
            player.sendPacket(new PacketQuestEventsDataNotify(player, player.getQuestEvidence().getEventIds()));
            for (int eventId : player.getQuestEvidence().getEventIds())
                player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_EVENTS_ITEM_STATUS, eventId, 1);
        }
    }

    private static boolean restoreActions(Player player, GameQuest quest, List<QuestData.QuestExecParam> actions) {
        if (actions == null) return false;
        boolean changed = false;
        for (var action : actions) {
            if (action.getType() != QuestExec.QUEST_EXEC_UNLOCK_EVENTS_ITEM) continue;
            var parameters = action.getParam();
            if (parameters == null || parameters.length < 2) continue;
            int eventId = Integer.parseInt(parameters[0]);
            var event = QuestEvidenceCatalog.installed().event(eventId);
            if (event == null || !event.mainQuestIds().contains(quest.getMainQuestId())) continue;
            changed |= player.getQuestEvidence().unlock(eventId, QuestEvidenceCatalog.parseItemIds(parameters[1]));
        }
        return changed;
    }
}
