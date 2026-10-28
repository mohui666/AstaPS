package emu.grasscutter.game.quest;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.giving.GivingData;
import emu.grasscutter.data.excels.giving.GivingGroupData;
import emu.grasscutter.game.inventory.GameItem;
import emu.grasscutter.game.inventory.Inventory;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.ItemGivingReqOuterClass.ItemGivingReq;
import emu.grasscutter.net.proto.ItemGivingRspOuterClass.ItemGivingRsp;
import emu.grasscutter.net.proto.ItemParamOuterClass.ItemParam;
import emu.grasscutter.server.packet.send.PacketItemGivingRsp;
import emu.grasscutter.utils.FileUtils;
import emu.grasscutter.utils.JsonUtils;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.*;

final class QuestItemGivingServiceTest {
    private final Map<Integer, GivingData> previousData = new HashMap<>();
    private final Map<Integer, GivingGroupData> previousGroups = new HashMap<>();

    private static final class SavedPlayer extends Player {
        int saves;
        int removed;
        private final Inventory testInventory = new Inventory(this) {
            @Override public synchronized boolean removeItem(GameItem item, int count) {
                assertTrue(count > 0 && count <= item.getCount());
                item.setCount(item.getCount() - count);
                removed += count;
                if (item.getCount() == 0) getItems().values().remove(item);
                return true;
            }
        };
        @Override public Inventory getInventory() { return testInventory; }
        @Override public void save() { saves++; }
        @Override public void sendPacket(BasePacket packet) {}
    }

    @BeforeEach
    void loadNativeGivingRecipes() throws Exception {
        for (var data : JsonUtils.loadToList(FileUtils.getExcelPath("GivingExcelConfigData.json"), GivingData.class)) {
            if (data.getId() != 400101 && data.getId() != 400201 && data.getId() != 400202
                    && data.getId() != 1916105 && data.getId() != 7008701) continue;
            previousData.put(data.getId(), GameData.getGivingDataMap().put(data.getId(), data));
        }
        for (var group : JsonUtils.loadToList(FileUtils.getExcelPath("GivingGroupExcelConfigData.json"), GivingGroupData.class)) {
            if (group.getId() / 100 != 1916105 && group.getId() != 40010101 && group.getId() != 40010102) continue;
            previousGroups.put(group.getId(), GameData.getGivingGroupDataMap().put(group.getId(), group));
        }
    }

    @AfterEach
    void restoreNativeGivingRecipes() throws Exception {
        QuestManager.eventExecutor.submit(() -> {}).get();
        previousData.forEach((id, data) -> {
            if (data == null) GameData.getGivingDataMap().remove(id.intValue());
            else GameData.getGivingDataMap().put(id.intValue(), data);
        });
        previousGroups.forEach((id, data) -> {
            if (data == null) GameData.getGivingGroupDataMap().remove(id.intValue());
            else GameData.getGivingGroupDataMap().put(id.intValue(), data);
        });
    }

    private static void own(SavedPlayer player, long guid, int id, int count) {
        var item = new GameItem() { @Override public long getGuid() { return guid; } };
        item.setItemId(id);
        item.setCount(count);
        player.getInventory().getItems().put(guid, item);
    }

    private static ItemGivingReq request(int givingId, int id, int count) {
        return ItemGivingReq.newBuilder().setGivingId(givingId)
                .addItemParamList(ItemParam.newBuilder().setItemId(id).setCount(count)).build();
    }

    private static void activate(SavedPlayer player, int givingId) {
        player.getPlayerProgress().getItemGivings().put(givingId, ItemGiveRecord.resolve(givingId));
    }

    @Test
    void inactiveWrongRecipeAndEvidenceIdsCannotConsumeOrdinaryItems() {
        var player = new SavedPlayer();
        own(player, 1, 120399, 2);
        assertFalse(QuestItemGivingService.submit(player, request(400202, 120399, 1)).success());
        activate(player, 400202);
        assertFalse(QuestItemGivingService.submit(player, request(400202, 120399, 2)).success());
        assertFalse(QuestItemGivingService.submit(player, request(400202, 10105, 1)).success());
        assertEquals(2, player.getInventory().getItemByGuid(1).getCount());
        assertEquals(0, player.removed);
        assertEquals(0, player.saves);
        assertFalse(player.getPlayerProgress().getItemGivings().get(400202).isFinished());
    }

    @Test
    void guidAndIdRepresentTheSameGiftAndReplayDoesNotChargeAgain() {
        var player = new SavedPlayer();
        own(player, 1, 120393, 1);
        activate(player, 400201);
        var request = request(400201, 120393, 1).toBuilder().putItemGuidCountMap(1, 1).build();
        var result = QuestItemGivingService.submit(player, request);
        assertTrue(result.success());
        assertEquals(0, result.groupId());
        assertEquals(1, player.removed);
        assertTrue(player.getPlayerProgress().getItemGivings().get(400201).isFinished());
        assertEquals(Map.of(120393, 1), player.getPlayerProgress().getItemGivings().get(400201).getGivenItems());
        assertTrue(QuestItemGivingService.submit(player, request).success());
        assertEquals(1, player.removed);
        assertEquals(1, player.saves);
    }

    @Test
    void unownedGuidConflictingRepresentationsAndZeroCountsAreRejectedBeforeRemoval() {
        var player = new SavedPlayer();
        own(player, 1, 120393, 3);
        activate(player, 400201);
        assertFalse(QuestItemGivingService.submit(player,
                ItemGivingReq.newBuilder().setGivingId(400201).putItemGuidCountMap(2, 1).build()).success());
        assertFalse(QuestItemGivingService.submit(player,
                request(400201, 120393, 1).toBuilder().putItemGuidCountMap(1, 2).build()).success());
        assertFalse(QuestItemGivingService.submit(player, request(400201, 120393, 0)).success());
        assertFalse(QuestItemGivingService.submit(player, request(400201, 120393, -1)).success());
        assertEquals(3, player.getInventory().getItemByGuid(1).getCount());
        assertEquals(0, player.removed);
    }

    @Test
    void vagueGroupKeepsNativeBranchInsteadOfChoosingTheFirstConfiguredGroup() {
        var player = new SavedPlayer();
        own(player, 1, 120392, 1);
        activate(player, 400101);
        var result = QuestItemGivingService.submit(player, request(400101, 120392, 1));
        assertTrue(result.success());
        assertEquals(40010102, result.groupId());
        assertEquals(400111, GameData.getGivingGroupDataMap().get(result.groupId()).getFinishTalkId());
        assertEquals(1, player.removed);
    }

    @Test
    void groupCombinationMatchesCountsAndFastMethodUsesItsExactRecipe() {
        var groupGiving = GameData.getGivingDataMap().get(1916105);
        assertEquals(191610502, QuestItemGivingService.matchingGroup(groupGiving, Map.of(120223, 2, 120224, 1)));
        assertEquals(-1, QuestItemGivingService.matchingGroup(groupGiving, Map.of(120223, 1, 120224, 1)));
        assertEquals(-1, QuestItemGivingService.matchingGroup(groupGiving, Map.of(120223, 3, 99999, 1)));
        var fast = GameData.getGivingDataMap().get(7008701);
        assertEquals(0, QuestItemGivingService.matchingGroup(fast, Map.of(121536, 1)));
        assertEquals(Map.of(121536, 0), ItemGiveRecord.resolve(7008701).getGivenItems());
    }

    @Test
    void responseCarriesBothNativeGivingIdentifiersAndRealFailure() throws Exception {
        var result = ItemGivingRsp.parseFrom(new PacketItemGivingRsp(400101, 40010102, true).getData());
        assertEquals(400101, result.getGivingId());
        assertEquals(40010102, result.getGivingGroupId());
        assertEquals(0, result.getRetcode());
        assertNotEquals(0, ItemGivingRsp.parseFrom(new PacketItemGivingRsp(400101, 0, false).getData()).getRetcode());
    }
}
