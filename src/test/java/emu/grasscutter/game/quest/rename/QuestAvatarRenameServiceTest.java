package emu.grasscutter.game.quest.rename;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.CodedOutputStream;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.MainQuestData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.inventory.GameItem;
import emu.grasscutter.game.inventory.Inventory;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.GameMainQuest;
import emu.grasscutter.game.quest.QuestManager;
import emu.grasscutter.game.quest.content.ContentAvatarRenameComplete;
import emu.grasscutter.game.quest.enums.QuestState;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.AvatarRenameInfoNotifyOuterClass.AvatarRenameInfoNotify;
import emu.grasscutter.net.proto.ItemRenameAvatarReqOuterClass.ItemRenameAvatarReq;
import emu.grasscutter.net.proto.QuestRenameAvatarReqOuterClass.QuestRenameAvatarReq;
import emu.grasscutter.net.proto.QuestRenameAvatarRspOuterClass.QuestRenameAvatarRsp;
import emu.grasscutter.utils.JsonUtils;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;

final class QuestAvatarRenameServiceTest {
    private MainQuestData previousMain;
    private final Map<Integer, QuestData> previousSubs = new HashMap<>();

    private static final class SavedPlayer extends Player {
        int saves;
        int vouchersSpent;
        final List<BasePacket> sent = new ArrayList<>();
        final Inventory testInventory = new Inventory(this) {
            @Override public synchronized boolean removeItem(GameItem item, int count) {
                assertTrue(count > 0 && count <= item.getCount());
                item.setCount(item.getCount() - count);
                vouchersSpent += count;
                if (item.getCount() == 0) getItems().values().remove(item);
                return true;
            }
        };
        @Override public Inventory getInventory() { return testInventory; }
        @Override public void save() { saves++; }
        @Override public void sendPacket(BasePacket packet) { sent.add(packet); }
    }

    @BeforeEach
    void prepareNativeRenameQuest() {
        previousMain = GameData.getMainQuestDataMap().put(3032, JsonUtils.decode(
                "{id:3032,subQuests:[{subId:303202},{subId:303205}]}", MainQuestData.class));
        for (int id : List.of(303202, 303205)) {
            previousSubs.put(id, GameData.getQuestDataMap().put(id, JsonUtils.decode(
                    "{mainId:3032,subId:" + id + ",finishCond:[],failCond:[]}", QuestData.class)));
        }
    }

    @AfterEach
    void restoreNativeRenameQuest() throws Exception {
        QuestManager.eventExecutor.submit(() -> {}).get();
        if (previousMain == null) GameData.getMainQuestDataMap().remove(3032);
        else GameData.getMainQuestDataMap().put(3032, previousMain);
        previousSubs.forEach((id, data) -> {
            if (data == null) GameData.getQuestDataMap().remove(id.intValue());
            else GameData.getQuestDataMap().put(id.intValue(), data);
        });
    }

    private static SavedPlayer player(boolean namingQuestActive) {
        var player = new SavedPlayer();
        var main = new GameMainQuest(player, 3032);
        player.getQuestManager().getMainQuests().put(3032, main);
        if (namingQuestActive) main.getChildQuestById(303202).setState(QuestState.QUEST_STATE_UNFINISHED);
        return player;
    }

    @Test
    void checkingANameDoesNotChangeNamesInventoryOrQuestCompletion() {
        var player = player(true);
        var condition = JsonUtils.decode("{param:[1,0]}", QuestData.QuestContentCondition.class);
        assertEquals(0, QuestAvatarRenameService.questRename(player, 1, "青岚", true));
        assertFalse(ContentAvatarRenameComplete.matchesCurrent(player.getQuestManager().getQuestById(303202), condition));
        assertEquals(0, player.saves);
        assertEquals(0, player.vouchersSpent);
        assertTrue(player.sent.isEmpty());
    }

    @Test
    void firstStoryRenameDoesNotRequireOwnedAvatarOrVoucher() throws Exception {
        var player = player(true);
        assertEquals(0, QuestAvatarRenameService.questRename(player, 1, "青岚", false));
        assertEquals("青岚", player.getQuestAvatarRenames().getName(1));
        assertEquals(1, player.saves);
        assertEquals(0, player.vouchersSpent);
        var notification = AvatarRenameInfoNotify.parseFrom(player.sent.getLast().getData());
        assertEquals(10000075, notification.getAvatarRenameList(0).getAvatarId());
        assertEquals("青岚", notification.getAvatarRenameList(0).getAvatarName());
        assertEquals(0, QuestAvatarRenameService.questRename(player, 1, "青岚", false));
        assertEquals(1, player.saves);
        assertNotEquals(0, QuestAvatarRenameService.questRename(player, 1, "新名", false));
        assertEquals("青岚", player.getQuestAvatarRenames().getName(1));
    }

    @Test
    void wrongStageUnknownIdAndInvalidNameDoNotCompleteTheStory() {
        var player = player(false);
        assertNotEquals(0, QuestAvatarRenameService.questRename(player, 1, "青岚", false));
        assertNotEquals(0, QuestAvatarRenameService.questRename(player, 999, "青岚", false));
        player.getQuestManager().getQuestById(303202).setState(QuestState.QUEST_STATE_UNFINISHED);
        for (String name : List.of("", " ", "<size=100>name", "name\n"))
            assertNotEquals(0, QuestAvatarRenameService.questRename(player, 1, name, false));
        assertFalse(player.getQuestAvatarRenames().hasName(1));
        assertEquals(0, player.saves);
    }

    @Test
    void renameVoucherIsPaidOnlyForAConfirmedAllowedChangeAndNotForAReplay() {
        var player = player(true);
        player.getQuestAvatarRenames().setName(1, "青岚");
        var item = new GameItem();
        item.setItemId(1301);
        item.setCount(1);
        player.getInventory().getItems().put(1, item);
        assertFalse(QuestAvatarRenameService.canOpenRenameDialog(player, 1301));
        assertNotEquals(0, QuestAvatarRenameService.itemRename(player, 1, "远风"));
        player.getQuestManager().getQuestById(303202).setState(QuestState.QUEST_STATE_FINISHED);
        player.getQuestManager().getQuestById(303205).setState(QuestState.QUEST_STATE_FINISHED);
        assertTrue(QuestAvatarRenameService.canOpenRenameDialog(player, 1301));
        assertEquals(0, QuestAvatarRenameService.questRename(player, 1, "远风", true));
        assertEquals(1, item.getCount());
        assertEquals(0, player.saves);
        assertEquals(0, QuestAvatarRenameService.itemRename(player, 1, "远风"));
        assertEquals(1, player.vouchersSpent);
        assertEquals("远风", player.getQuestAvatarRenames().getName(1));
        assertEquals(0, QuestAvatarRenameService.itemRename(player, 1, "远风"));
        assertEquals(1, player.vouchersSpent);
        assertNotEquals(0, QuestAvatarRenameService.itemRename(player, 1, "第三个名字"));
        assertEquals("远风", player.getQuestAvatarRenames().getName(1));
    }

    @Test
    void savedNamesAndTheNativeWireTagsSurviveRoundTrip() throws Exception {
        var state = new QuestAvatarRenameState();
        state.setName(1, "青岚");
        assertEquals("青岚", JsonUtils.decode(JsonUtils.encode(state), QuestAvatarRenameState.class).getName(1));
        var bytes = new ByteArrayOutputStream();
        var output = CodedOutputStream.newInstance(bytes);
        output.writeString(6, "青岚");
        output.writeBool(10, true);
        output.writeUInt32(12, 1);
        output.flush();
        var request = QuestRenameAvatarReq.parseFrom(bytes.toByteArray());
        assertEquals("青岚", request.getAvatarName());
        assertTrue(request.getIsCheckOnly());
        assertEquals(1, request.getRenameId());
        assertEquals(3, QuestRenameAvatarRsp.RETCODE_FIELD_NUMBER);
        assertEquals(6, QuestRenameAvatarRsp.IS_CHECK_ONLY_FIELD_NUMBER);
        assertEquals(7, QuestRenameAvatarRsp.AVATAR_NAME_FIELD_NUMBER);
        assertEquals(12, QuestRenameAvatarRsp.RENAME_ID_FIELD_NUMBER);
        assertEquals(5, ItemRenameAvatarReq.AVATAR_NAME_FIELD_NUMBER);
        assertEquals(7, ItemRenameAvatarReq.RENAME_ID_FIELD_NUMBER);
        assertEquals(15, AvatarRenameInfoNotify.AVATAR_RENAME_LIST_FIELD_NUMBER);
    }
}
