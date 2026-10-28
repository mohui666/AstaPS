package emu.grasscutter.game.quest.reconstruction;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.MainQuestData;
import emu.grasscutter.data.excels.ItemData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.inventory.GameItem;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.ItemUseAction.*;
import emu.grasscutter.game.props.ItemUseOp;
import emu.grasscutter.game.props.ItemUseTarget;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.QuestState;
import emu.grasscutter.game.systems.InventorySystem;
import emu.grasscutter.game.world.TestWorld;
import emu.grasscutter.server.game.*;
import emu.grasscutter.utils.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import sun.misc.Unsafe;

@ExtendWith(ServerResourceFixture.class)
@ResourceLock("GameData")
final class LegacyChannellerSlabCollectionTest {
    private final List<Runnable> restore = new ArrayList<>();
    private QuestData nativeGoal;

    @BeforeEach void setup() throws Exception {
        install(GameData.getMainQuestDataMap(),71652,JsonUtils.decode("{id:71652,subQuests:[]}",MainQuestData.class));
        var materials = JsonParser.parseString(Files.readString(FileUtils.getResourcePath(
                "ExcelBinOutput/MaterialExcelConfigData.json"))).getAsJsonArray();
        for (var value : materials) {
            var row = value.getAsJsonObject();
            int id = row.get("id").getAsInt();
            if (id < 200201 || id > 200237) continue;
            var item = JsonUtils.decode(row,ItemData.class); item.onLoad();
            install(GameData.getItemDataMap(),id,item);
        }
        var parent = JsonParser.parseString(Files.readString(FileUtils.getResourcePath(
                "BinOutput/Quest/71652.json"))).getAsJsonObject();
        nativeGoal = java.util.stream.StreamSupport.stream(parent.getAsJsonArray("subQuests").spliterator(),false)
                .map(q->q.getAsJsonObject()).filter(q->q.get("subId").getAsInt()==7165202)
                .map(q->{
                    var row=q.deepCopy();
                    for (String name:List.of("acceptCond","failCond","beginExec","finishExec","failExec"))
                        if (!row.has(name)) row.add(name,new com.google.gson.JsonArray());
                    row.addProperty("finishCondComb","LOGIC_AND"); row.addProperty("failCondComb","LOGIC_AND");
                    row.addProperty("acceptCondComb","LOGIC_AND");
                    return JsonUtils.decode(row,QuestData.class);
                }).findFirst().orElseThrow();
        install(GameData.getQuestDataMap(),7165202,nativeGoal);
    }

    private <K,V> void install(Map<K,V> map,K key,V value) {
        var previous = map.put(key,value);
        restore.add(()->{if(previous==null)map.remove(key);else map.put(key,previous);});
    }

    @AfterEach void cleanup() throws Exception {
        drain(); Collections.reverse(restore); restore.forEach(Runnable::run);
    }

    @Test void everyActualNativeGoalMaterialCollectsOwnershipAndAdvancesThroughAcceptedInventoryMutation() throws Exception {
        for (int id : List.of(200201,200205,200210,200214,200218,200222,200225,200228,200232)) {
            var player = player(); var quest = start(player);
            var target = nativeGoal.getFinishCond().getFirst();
            assertEquals(0,QuestInventoryProgress.currentCount(quest,target));
            assertTrue(player.getInventory().addItem(new GameItem(GameData.getItemDataMap().get(id),1)));
            drain();
            assertEquals(Set.of(id-200200),player.getLegacyChannellerSlabCollection().ownedBuffs());
            assertEquals(1,QuestInventoryProgress.currentCount(quest,target));
            assertEquals(QuestState.QUEST_STATE_FINISHED,quest.getState());
            assertTrue(player.saves > 0);
            assertTrue(player.getInventory().getItems().values().stream().noneMatch(item->item.getItemId()==id));
        }
    }

    @Test void wrongQuestMappingBulkOrMissingParametersCannotReportACollectedFragment() throws Exception {
        var player = player();
        assertFalse(player.getInventory().addItem(new GameItem(GameData.getItemDataMap().get(200201),1)));
        var quest = start(player);
        assertFalse(player.getInventory().addItem(new GameItem(GameData.getItemDataMap().get(200201),2)));
        var params = new UseItemParams(player,ItemUseTarget.ITEM_USE_TARGET_NONE); params.usedItemId=200201;
        assertFalse(new ItemUseAddChannellerSlabBuff(new String[]{"2"}).useItem(params));
        assertFalse(new ItemUseAddChannellerSlabBuff(new String[]{"bad"}).useItem(params));
        assertFalse(new ItemUseAddChannellerSlabBuff(new String[]{"38"}).useItem(params));
        params.usedItemId=0;
        assertFalse(new ItemUseAddChannellerSlabBuff(new String[]{"1"}).useItem(params));
        drain();
        assertEquals(Set.of(),player.getLegacyChannellerSlabCollection().ownedBuffs());
        assertEquals(0,QuestInventoryProgress.currentCount(quest,nativeGoal.getFinishCond().getFirst()));
        assertEquals(QuestState.QUEST_STATE_UNFINISHED,quest.getState());
    }

    @Test void collectedOwnershipSurvivesSerializationAndCannotBeClaimedAgainAfterQuestRewind() throws Exception {
        var player = player(); start(player);
        assertTrue(player.getInventory().addItem(new GameItem(GameData.getItemDataMap().get(200201),1))); drain();
        var saved = JsonUtils.encode(player.getLegacyChannellerSlabCollection());
        var relogged = player(); relogged.setLegacyChannellerSlabCollection(
                JsonUtils.decode(saved,LegacyChannellerSlabCollection.class));
        var quest = start(relogged);
        assertEquals(Set.of(1),relogged.getLegacyChannellerSlabCollection().ownedBuffs());
        assertFalse(relogged.getInventory().addItem(new GameItem(GameData.getItemDataMap().get(200201),1))); drain();
        assertEquals(0,QuestInventoryProgress.currentCount(quest,nativeGoal.getFinishCond().getFirst()));
        assertTrue(relogged.getInventory().addItem(new GameItem(GameData.getItemDataMap().get(200205),1))); drain();
        assertEquals(Set.of(1,5),relogged.getLegacyChannellerSlabCollection().ownedBuffs());
        assertEquals(QuestState.QUEST_STATE_FINISHED,quest.getState());
    }

    @Test void legacyNullStorageInitializesOnlyWhenTheRealCollectionSucceedsAndActionRegistersNatively() throws Exception {
        var player = player(); player.setLegacyChannellerSlabCollection(null);
        assertFalse(LegacyChannellerSlabCollection.collect(player,200201,1,1));
        assertNull(player.getLegacyChannellerSlabCollection());
        start(player);
        var action = GameData.getItemDataMap().get(200201).getItemUseActions().getFirst();
        assertInstanceOf(ItemUseAddChannellerSlabBuff.class,action);
        assertEquals(ItemUseOp.ITEM_USE_ADD_CHANNELLER_SLAB_BUFF,action.getItemUseOp());
        assertTrue(player.getInventory().addItem(new GameItem(GameData.getItemDataMap().get(200201),1))); drain();
        assertEquals(Set.of(1),player.getLegacyChannellerSlabCollection().ownedBuffs());
    }

    private GameQuest start(CollectionPlayer player) {
        var main = new GameMainQuest(player,71652);
        var quest = new GameQuest(main,nativeGoal) {
            @Override public void finish(){setState(QuestState.QUEST_STATE_FINISHED);}
            @Override public void save(){}
        };
        quest.setState(QuestState.QUEST_STATE_UNFINISHED);
        main.getChildQuests().put(7165202,quest);
        player.getQuestManager().getMainQuests().put(71652,main);
        return quest;
    }

    private static CollectionPlayer player() throws Exception {
        var player = new CollectionPlayer(); player.setUid(89153);
        var field=Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        var server=(GameServer)((Unsafe)field.get(null)).allocateInstance(GameServer.class);
        var inventory=GameServer.class.getDeclaredField("inventorySystem"); inventory.setAccessible(true);
        inventory.set(server,new InventorySystem(server));
        var quests=GameServer.class.getDeclaredField("questSystem"); quests.setAccessible(true);
        quests.set(server,new QuestSystem(server));
        player.setSession(new GameSession(server)); TestWorld.create(player);
        return player;
    }

    private static void drain() throws Exception {QuestManager.eventExecutor.submit(()->{}).get(10,TimeUnit.SECONDS);}
    private static final class CollectionPlayer extends TestWorld.TestPlayer {
        int saves;
        @Override public void save(){saves++;}
    }
}
