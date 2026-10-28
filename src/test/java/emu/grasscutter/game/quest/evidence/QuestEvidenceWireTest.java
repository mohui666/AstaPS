package emu.grasscutter.game.quest.evidence;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.CodedOutputStream;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.MainQuestData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.inventory.GameItem;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.GameMainQuest;
import emu.grasscutter.game.quest.QuestManager;
import emu.grasscutter.game.quest.enums.QuestState;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.QuestEventsDataNotifyOuterClass.QuestEventsDataNotify;
import emu.grasscutter.net.proto.QuestEventsGivingRecordOuterClass.QuestEventsGivingRecord;
import emu.grasscutter.net.proto.RetcodeOuterClass.Retcode;
import emu.grasscutter.net.proto.SubmitQuestEventsGivingReqOuterClass.SubmitQuestEventsGivingReq;
import emu.grasscutter.net.proto.SubmitQuestEventsGivingRspOuterClass.SubmitQuestEventsGivingRsp;
import emu.grasscutter.net.proto.UnlockQuestEventsItemReqOuterClass.UnlockQuestEventsItemReq;
import emu.grasscutter.utils.JsonUtils;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.*;

final class QuestEvidenceWireTest {
    private MainQuestData previousMain;
    private QuestData previousSub;

    private static final class SavedPlayer extends Player {
        int saves;
        final List<BasePacket> sent = new ArrayList<>();
        @Override public void save() { saves++; }
        @Override public void sendPacket(BasePacket packet) { sent.add(packet); }
    }

    @BeforeEach
    void nativeQuestionContext() {
        previousMain = GameData.getMainQuestDataMap().put(4002, JsonUtils.decode(
                "{id:4002,subQuests:[{subId:400204}]}", MainQuestData.class));
        previousSub = GameData.getQuestDataMap().put(400204, JsonUtils.decode(
                "{mainId:4002,subId:400204,finishCond:[],failCond:[]}", QuestData.class));
    }

    @AfterEach
    void restoreContext() throws Exception {
        QuestManager.eventExecutor.submit(() -> {}).get();
        if (previousMain == null) GameData.getMainQuestDataMap().remove(4002);
        else GameData.getMainQuestDataMap().put(4002, previousMain);
        if (previousSub == null) GameData.getQuestDataMap().remove(400204);
        else GameData.getQuestDataMap().put(400204, previousSub);
    }

    private static SavedPlayer player(boolean active, boolean book) {
        var player = new SavedPlayer();
        var main = new GameMainQuest(player, 4002);
        if (active) main.getChildQuestById(400204).setState(QuestState.QUEST_STATE_UNFINISHED);
        player.getQuestManager().getMainQuests().put(4002, main);
        if (book) own(player, 1, 120445);
        return player;
    }

    private static void own(Player player, long guid, int itemId) {
        var item = new GameItem();
        item.setItemId(itemId);
        item.setCount(1);
        player.getInventory().getItems().put(guid, item);
    }

    @Test
    void openingAnOwnedRecordBookSendsItsRealEmptySnapshotWithoutGrantingClues() throws Exception {
        var player = player(true, false);
        assertFalse(QuestEvidenceService.openBook(player, 120445));
        own(player, 1, 120445);
        assertTrue(QuestEvidenceService.openBook(player, 120445));
        var packet = QuestEventsDataNotify.parseFrom(player.sent.getLast().getData());
        assertEquals(4001, packet.getEventList(0).getEventId());
        assertTrue(packet.getEventList(0).getUnlockedItemListList().isEmpty());
        assertEquals(0, player.saves);
        assertEquals(1, player.getInventory().getFirstItem(120445).getCount());
    }

    @Test
    void collectionRequiresActiveCaseOwnedBookAndEveryIdToBeValid() throws Exception {
        var player = player(false, true);
        assertNotEquals(0, QuestEvidenceService.acquire(player, 4001, List.of(10110)));
        player.getQuestManager().getQuestById(400204).setState(QuestState.QUEST_STATE_UNFINISHED);
        player.getInventory().getItems().clear();
        assertNotEquals(0, QuestEvidenceService.acquire(player, 4001, List.of(10110)));
        own(player, 1, 120445);
        assertNotEquals(0, QuestEvidenceService.acquire(player, 4001, List.of(10110, Integer.MAX_VALUE)));
        assertEquals(0, player.getQuestEvidence().getStatus(4001, 10110));
        assertEquals(0, QuestEvidenceService.acquire(player, 4001, List.of(10110, 10111)));
        assertEquals(1, player.saves);
        var packet = QuestEventsDataNotify.parseFrom(player.sent.getLast().getData());
        assertEquals(4001, packet.getEventList(0).getEventId());
        assertEquals(List.of(10110, 10111), packet.getEventList(0).getUnlockedItemListList());
        assertEquals(1, player.getInventory().getFirstItem(120445).getCount());
    }

    @Test
    void wrongEvidencePreservesInventoryAndMultiEvidenceAnswerCompletesOnlyAfterBothItems() throws Exception {
        var player = player(true, true);
        own(player, 2, 120393);
        assertEquals(0, QuestEvidenceService.acquire(player, 4001, List.of(10108, 10110, 10111)));
        assertEquals(Retcode.RET_SUBMIT_QUEST_EVENTS_NOT_RIGHT_VALUE, QuestEvidenceService.submit(player, 400201, 10108));
        assertFalse(player.getQuestEvidence().isGivingFinished(400201));
        assertEquals(1, player.getInventory().getFirstItem(120393).getCount());
        assertEquals(0, QuestEvidenceService.submit(player, 400201, 10110));
        assertFalse(player.getQuestEvidence().isGivingFinished(400201));
        assertEquals(0, QuestEvidenceService.submit(player, 400201, 10111));
        assertTrue(player.getQuestEvidence().isGivingFinished(400201));
        var packet = QuestEventsDataNotify.parseFrom(player.sent.getLast().getData());
        var record = packet.getEventList(0).getGivingRecordList(0);
        assertTrue(record.getIsFinished());
        assertEquals(400201, record.getGivingId());
        assertEquals(List.of(10110, 10111), record.getItemIdListList());
        int saves = player.saves;
        assertEquals(0, QuestEvidenceService.submit(player, 400201, 10111));
        assertEquals(saves, player.saves);
        assertEquals(QuestState.QUEST_STATE_UNFINISHED, player.getQuestManager().getQuestById(400204).getState());
        player.getQuestManager().getQuestById(400204).setState(QuestState.QUEST_STATE_FINISHED);
        assertEquals(0, QuestEvidenceService.submit(player, 400201, 10111));
        assertEquals(saves, player.saves);
    }

    @Test
    void submissionRequiresTheNativeQuestionStageAndCollectedCandidate() {
        var player = player(true, true);
        assertNotEquals(0, QuestEvidenceService.submit(player, 400201, 10110));
        player.getQuestEvidence().unlock(4001, List.of(10110));
        player.getQuestManager().getQuestById(400204).setState(QuestState.QUEST_STATE_FINISHED);
        assertNotEquals(0, QuestEvidenceService.submit(player, 400201, 10110));
        assertFalse(player.getQuestEvidence().isGivingFinished(400201));
    }

    @Test
    void generatedMessagesRetainVerifiedNativeFieldTags() throws Exception {
        var bytes = new ByteArrayOutputStream();
        var output = CodedOutputStream.newInstance(bytes);
        output.writeUInt32(14, 400201);
        output.writeUInt32(10, 10110);
        output.flush();
        var request = SubmitQuestEventsGivingReq.parseFrom(bytes.toByteArray());
        assertEquals(400201, request.getGivingId());
        assertEquals(10110, request.getItemId());

        bytes.reset();
        output = CodedOutputStream.newInstance(bytes);
        output.writeUInt32(4, 400201);
        output.writeInt32(6, 408);
        output.writeUInt32(13, 10108);
        output.flush();
        var response = SubmitQuestEventsGivingRsp.parseFrom(bytes.toByteArray());
        assertEquals(408, response.getRetcode());
        assertEquals(400201, response.getGivingId());
        assertEquals(10108, response.getItemId());
        assertEquals(1, UnlockQuestEventsItemReq.EVENT_ID_FIELD_NUMBER);
        assertEquals(8, UnlockQuestEventsItemReq.ITEM_ID_LIST_FIELD_NUMBER);
        assertEquals(4, QuestEventsGivingRecord.GIVING_ID_FIELD_NUMBER);
        assertEquals(8, QuestEventsGivingRecord.IS_FINISHED_FIELD_NUMBER);
        assertEquals(11, QuestEventsGivingRecord.ITEM_ID_LIST_FIELD_NUMBER);
    }
}
