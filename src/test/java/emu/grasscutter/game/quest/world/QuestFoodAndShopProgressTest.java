package emu.grasscutter.game.quest.world;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.CodedOutputStream;
import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.MainQuestData;
import emu.grasscutter.data.excels.*;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.entity.EntityGadget;
import emu.grasscutter.game.inventory.*;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.ActionReason;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.content.ContentServerState;
import emu.grasscutter.game.quest.enums.*;
import emu.grasscutter.game.shop.*;
import emu.grasscutter.game.systems.TestPlayers;
import emu.grasscutter.game.world.*;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.AddQuestContentProgressReqOuterClass.AddQuestContentProgressReq;
import emu.grasscutter.net.proto.AddQuestContentProgressBatchReq._AddQuestContentProgressBatchReq;
import emu.grasscutter.net.proto.QuestProgressInfo._QuestProgressInfo;
import emu.grasscutter.net.proto.BuyGoodsReqOuterClass.BuyGoodsReq;
import emu.grasscutter.net.proto.ShopGoodsOuterClass.ShopGoods;
import emu.grasscutter.scripts.ScriptLoader;
import emu.grasscutter.server.game.*;
import emu.grasscutter.server.packet.recv.*;
import emu.grasscutter.utils.*;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

@ExtendWith(ServerResourceFixture.class)
@ResourceLock("GameData")
final class QuestFoodAndShopProgressTest {
    private static final Position COOKER = new Position(3708.437988f,221.75f,9205.514648f);
    private static final Map<Integer,Integer> SHOPS = Map.of(
            7411101,1103,7419304,1108,7614502,1143,7614504,1144,7614507,1145);
    private final List<Runnable> cleanup = new ArrayList<>();
    private GameServer server;
    private ShopSystem shopSystem;
    private final Set<Integer> ingredientIds=new HashSet<>();

    @BeforeAll static void scripts() throws Exception { ScriptLoader.init(); }

    @BeforeEach void resources() throws Exception {
        server=TestPlayers.server();
        field(server,"questSystem",new QuestSystem(server));
        shopSystem=new ShopSystem(server); field(server,"shopSystem",shopSystem);
        SHOPS.values().forEach(id->shopSystem.getShopData().put(id,new ArrayList<>()));
        var needed=new HashSet<Integer>(Set.of(108861,108862,108863,108874,108875,108880,108881,108886,108887,108895));
        for (var row:rows("ShopGoodsExcelConfigData.json")) {
            var data=JsonUtils.decode(row,ShopGoodsData.class); data.onLoad();
            if (!SHOPS.containsValue(data.getShopType())) continue;
            needed.add(data.getItemId());
            data.getCostItems().forEach(cost->needed.add(cost.getId()));
            var goods=shopSystem.getShopData().get(data.getShopType());
            if (goods.stream().noneMatch(g->g.getGoodsId()==data.getGoodsId())) goods.add(new ShopInfo(data));
        }
        for (var row:rows("CookRecipeExcelConfigData.json")) {
            int id=row.getAsJsonObject().get("id").getAsInt();
            if (Set.of(1601,2602,2603,2604).contains(id)) {
                var data=JsonUtils.decode(row,CookRecipeData.class); data.onLoad(); install(GameData.getCookRecipeDataMap(),id,data);
                data.getInputVec().forEach(input->{needed.add(input.getId());ingredientIds.add(input.getId());});
            }
        }
        for (String file:List.of("MaterialExcelConfigData.json","HomeWorldFurnitureExcelConfigData.json"))
            for (var row:rows(file)) {
                int id=row.getAsJsonObject().get("id").getAsInt();
                if (needed.contains(id)) {
                    var data=JsonUtils.decode(row,ItemData.class); data.onLoad(); install(GameData.getItemDataMap(),id,data);
                }
            }
        for (var row:rows("GadgetExcelConfigData.json"))
            if (row.getAsJsonObject().get("id").getAsInt()==73076009)
                install(GameData.getGadgetDataMap(),73076009,JsonUtils.decode(row,GadgetData.class));
    }

    @AfterEach void restore() throws Exception { drain(); Collections.reverse(cleanup); cleanup.forEach(Runnable::run); }

    @Test void nativeEightConditionsUseFreshAcceptedDishesAndRealCookRequestFields() throws Exception {
        int[] ids={7603301,7603303,7603305,7603307,7603311,7605905,7605906};
        int[] recipes={2603,2603,2602,2604,1601,0,1601};
        int[] dishes={108881,108881,108875,108887,108863,108895,108863};
        int conditions=0;
        for (int i=0;i<ids.length;i++) {
            var player=player(6000+i); var quest=quest(player,ids[i]); cooker(player,133601030,30001);
            conditions+=(int)java.util.stream.Stream.concat(quest.getQuestData().getFinishCond().stream(),
                    quest.getQuestData().getFailCond().stream()).filter(c->c.getType()==QuestContent.QUEST_CONTENT_FINISH_ANY_FOOD_COOK).count();
            // Existing dishes in the bag are never evidence of this round's cooking.
            own(player,dishes[i],20);
            player.getQuestManager().checkQuestAlreadyFulfilled(quest); drain();
            assertEquals(0,quest.finishes);
            if (recipes[i]==0) acceptedCook(player,List.of(new GameItem(dishes[i],1)));
            else new HandlerPlayerCookReq().handle(player.getSession(),new byte[0],cookRequest(recipes[i],3,2));
            drain();
            assertEquals(1,quest.finishes,"native quest "+ids[i]); assertEquals(0,quest.failures);
            if (recipes[i]!=0) assertEquals(22,player.getInventory().getItemCountById(dishes[i]));
        }
        assertEquals(8,conditions);
    }

    @Test void ingredientRefusalOutputRefusalWrongCookerAndDistanceDoNotAdvance() throws Exception {
        var player=player(6101); var quest=quest(player,7603303); var gadget=cooker(player,133601030,30001);
        ingredientIds.forEach(id->own(player,id,0)); cook(player,2603); assertEquals(0,quest.finishes);
        ingredientIds.forEach(id->own(player,id,999));
        own(player,108881,GameData.getItemDataMap().get(108881).getStackLimit()); cook(player,2603); assertEquals(0,quest.finishes);
        own(player,108881,1); gadget.setGroupId(133601031); cook(player,2603); assertEquals(0,quest.finishes);
        gadget.setGroupId(133601030); player.getPosition().addX(5); cook(player,2603); assertEquals(0,quest.finishes);
        player.getPosition().set(COOKER); player.setSceneLoadState(Player.SceneLoadState.LOADING); cook(player,2603);
        assertEquals(0,quest.finishes);
        player.setSceneLoadState(Player.SceneLoadState.LOADED); cook(player,1601); assertEquals(0,quest.finishes);
        cook(player,2603); assertEquals(1,quest.finishes);
    }

    @Test void mixedSpecialtyBatchFailsBeforeTheAllowedNormalDishCanFinish() throws Exception {
        var player=player(6201); var quest=quest(player,7605906); cooker(player,133601030,30001);
        acceptedCook(player,List.of(new GameItem(108863,1),new GameItem(108895,1)));
        drain(); assertEquals(1,quest.failures); assertEquals(0,quest.finishes);
    }

    @Test void queuedMealCannotCompleteAFutureStageOrANewAttemptOfTheSameQuest() throws Exception {
        var player=player(6301); var old=quest(player,7603301); var future=quest(player,7603303);
        future.setState(QuestState.QUEST_STATE_UNSTARTED); cooker(player,133601030,30001);
        var waiting=new CountDownLatch(1); var release=new CountDownLatch(1);
        QuestManager.eventExecutor.submit(()->{ waiting.countDown(); try {release.await(10,TimeUnit.SECONDS);} catch(InterruptedException e){Thread.currentThread().interrupt();} });
        assertTrue(waiting.await(10,TimeUnit.SECONDS));
        try {
            acceptedCook(player,List.of(new GameItem(108881,1)));
            int previous=old.getStartSequence(); old.start(); assertEquals(previous+1,old.getStartSequence());
            future.start();
        } finally { release.countDown(); }
        drain(); assertEquals(0,old.finishes); assertEquals(0,future.finishes);
        old.setState(QuestState.QUEST_STATE_FINISHED);
        cook(player,2603); assertEquals(1,future.finishes);
    }

    @Test void nextStageStartedInsideTheAcceptedOutputCallbackCannotConsumeTheCurrentMeal() throws Exception {
        var player=player(6351); var old=quest(player,7603301); var future=quest(player,7603303);
        future.setState(QuestState.QUEST_STATE_UNSTARTED); cooker(player,133601030,30001);
        var frozen=QuestCookingProgress.captureWaitingAttempts(player);
        assertEquals(List.of(7603301),frozen.stream().map(QuestCookingProgress.WaitingAttempt::subQuestId).toList());
        assertThrows(UnsupportedOperationException.class,()->frozen.add(frozen.getFirst()));
        player.inventory.afterAcceptedOutput=()->{old.finish();future.start();};
        cook(player,2603);
        assertEquals(1,old.finishes); assertEquals(0,future.finishes);
        assertEquals(QuestState.QUEST_STATE_UNFINISHED,future.getState());
        player.inventory.afterAcceptedOutput=null;
        cook(player,2603); assertEquals(1,future.finishes);
    }

    @Test void allFiveRealShopCatalogsRequireEveryFiniteGoodAndPreserveTheSavedReceipt() throws Exception {
        for (var entry:SHOPS.entrySet()) {
            var player=player(6400+entry.getValue()); var quest=quest(player,entry.getKey());
            var condition=quest.getQuestData().getFinishCond().getFirst();
            assertFalse(ContentServerState.matchesCurrent(quest,condition));
            var goods=shopSystem.getShopData().get(entry.getValue()); assertFalse(goods.isEmpty());
            for (var good:goods) player.addShopLimit(good.getGoodsId(),good.getBuyLimit(),0);
            var last=player.getGoodsLimit(goods.getLast().getGoodsId()); last.setHasBoughtInPeriod(last.getHasBoughtInPeriod()-1);
            assertFalse(ContentServerState.matchesCurrent(quest,condition));
            last.setHasBoughtInPeriod(last.getHasBoughtInPeriod()+1);
            assertTrue(ContentServerState.matchesCurrent(quest,condition));
            var restored=player(6500+entry.getValue());
            ShopLimit[] saved=new Gson().fromJson(JsonUtils.encode(player.getShopLimit()),ShopLimit[].class);
            restored.getShopLimit().addAll(Arrays.asList(saved)); var resumed=quest(restored,entry.getKey());
            assertTrue(ContentServerState.matchesCurrent(resumed,resumed.getQuestData().getFinishCond().getFirst()));
        }
    }

    @Test void actualBuyHandlerCountsOnlyAcceptedOutputAndCompletesOnTheLastGood() throws Exception {
        var player=player(6601); var quest=quest(player,7419304); var goods=shopSystem.getShopData().get(1108);
        var first=goods.getFirst(); int currency=player.getInventory().getItemCountById(101234);
        own(player,first.getGoodsItem().getId(),GameData.getItemDataMap().get(first.getGoodsItem().getId()).getStackLimit());
        buy(player,1108,first,first.getBuyLimit());
        assertNull(player.getGoodsLimit(first.getGoodsId())); assertEquals(0,quest.finishes);
        assertEquals(currency,player.getInventory().getItemCountById(101234),"stack admission must run before payment");
        own(player,first.getGoodsItem().getId(),1);
        for (int i=0;i<goods.size();i++) {
            var good=goods.get(i);
            buy(player,1108,good,good.getBuyLimit());
            assertEquals(i==goods.size()-1?1:0,quest.finishes);
        }
        var good=goods.getLast(); int mora=player.getMora();
        buy(player,1108,good,1); assertEquals(mora,player.getMora()); assertEquals(1,quest.finishes);
    }

    @Test void emptyUnknownUnlimitedAndExpiredShopsCannotProveSoldOut() throws Exception {
        var player=player(6701); var quest=quest(player,7614502); var condition=quest.getQuestData().getFinishCond().getFirst();
        var goods=shopSystem.getShopData().get(1143); goods.forEach(g->player.addShopLimit(g.getGoodsId(),g.getBuyLimit(),0));
        assertTrue(ContentServerState.matchesCurrent(quest,condition));
        shopSystem.getShopData().put(1143,List.of()); assertFalse(ContentServerState.matchesCurrent(quest,condition));
        shopSystem.getShopData().put(1143,goods); goods.getFirst().setBuyLimit(0); assertFalse(ContentServerState.matchesCurrent(quest,condition));
        var refresh=JsonUtils.decode("{goodsId:814001,buyLimit:10,refreshType:'SHOP_REFRESH_DAILY'}",ShopInfo.class);
        shopSystem.getShopData().put(1143,List.of(refresh)); player.getGoodsLimit(814001).setNextRefreshTime(Utils.getCurrentSeconds()-1);
        assertFalse(ContentServerState.matchesCurrent(quest,condition));
        assertFalse(ContentServerState.matchesCurrent(quest,JsonUtils.decode("{type:'QUEST_CONTENT_SHOP_SELL_OUT',param:[999999,1]}",QuestData.QuestContentCondition.class)));
    }

    @Test void actualBuyHandlerRejectsAFullMaterialBagBeforeCountingThePurchase() throws Exception {
        var player=player(6751); var quest=quest(player,7614502); var good=shopSystem.getShopData().get(1143).getFirst();
        var tab=player.getInventory().getInventoryTab(ItemType.ITEM_MATERIAL);
        var item=tab.getItemById(good.getGoodsItem().getId()); tab.onRemoveItem(item);
        player.getInventory().getItems().remove(item.getGuid());
        var capacity=tab.getClass().getDeclaredField("maxCapacity"); capacity.setAccessible(true); capacity.setInt(tab,0);
        buy(player,1143,good,good.getBuyLimit());
        assertNull(player.getGoodsLimit(good.getGoodsId())); assertEquals(0,quest.finishes);
        assertEquals(0,player.getInventory().getItemCountById(good.getGoodsItem().getId()));
    }

    @Test void singleAndBatchClientReportsCannotInventACookOrShopReceipt() throws Exception {
        for (int id:new int[]{7603303,7614502}) {
            var player=player(6800+id); var quest=quest(player,id); cooker(player,133601030,30001);
            var type=quest.getQuestData().getFinishCond().getFirst().getType();
            new HandlerAddQuestContentProgressReq().handle(player.getSession(),new byte[0],
                    AddQuestContentProgressReq.newBuilder().setContentType(type.getValue()).setParam(133601030).build().toByteArray());
            new HandlerAddQuestContentProgressBatchReq().handle(player.getSession(),new byte[0],
                    _AddQuestContentProgressBatchReq.newBuilder().addProgressInfoList(_QuestProgressInfo.newBuilder()
                            .setContentType(type.getValue()).setParam(133601030)).build().toByteArray());
            drain(); assertEquals(0,quest.finishes); assertArrayEquals(new int[quest.getFinishProgressList().length],quest.getFinishProgressList());
        }
    }

    private static JsonArray rows(String name) throws Exception {
        return JsonParser.parseString(Files.readString(FileUtils.getResourcePath("ExcelBinOutput/"+name))).getAsJsonArray();
    }
    private StoryPlayer player(int uid) {
        var player=new StoryPlayer(); player.setUid(uid); player.setSession(new RecordingSession(server,player));
        player.setSceneId(3); player.setSceneLoadState(Player.SceneLoadState.LOADED);
        var world=TestWorld.create(player); var scene=new TestWorld.TestScene(world,3,emu.grasscutter.game.props.SceneType.SCENE_WORLD);
        scene.getPlayers().add(player); player.setScene(scene); player.getPosition().set(COOKER);
        for (int id:new int[]{108861,108862,108863,108874,108875,108880,108881,108886,108887,108895,101234}) own(player,id,1);
        for (var shop:SHOPS.values()) for (var good:shopSystem.getShopData().get(shop)) {
            var item=GameData.getItemDataMap().get(good.getGoodsItem().getId());
            if(item!=null && item.getItemType()==ItemType.ITEM_MATERIAL && !item.isUseOnGain()) own(player,item.getId(),1);
        }
        ingredientIds.forEach(id->own(player,id,999)); own(player,101234,999);
        return player;
    }
    private TrackingQuest quest(StoryPlayer player,int id) throws Exception {
        var parent=JsonParser.parseString(Files.readString(FileUtils.getResourcePath("BinOutput/Quest/"+id/100+".json"))).getAsJsonObject();
        var row=java.util.stream.StreamSupport.stream(parent.getAsJsonArray("subQuests").spliterator(),false)
                .map(JsonElement::getAsJsonObject).filter(q->q.get("subId").getAsInt()==id).findFirst().orElseThrow().deepCopy();
        row.addProperty("mainId",id/100);
        for (String key:List.of("acceptCond","beginExec","finishExec","failExec")) row.add(key,new JsonArray());
        if (!row.has("failCond")) row.add("failCond",new JsonArray());
        var data=JsonUtils.decode(row,QuestData.class); install(GameData.getQuestDataMap(),id,data);
        install(GameData.getMainQuestDataMap(),id/100,JsonUtils.decode("{id:"+id/100+",subQuests:[]}",MainQuestData.class));
        var main=player.getQuestManager().getMainQuests().computeIfAbsent(id/100,k->new GameMainQuest(player,k){@Override public void save(){}});
        var quest=new TrackingQuest(main,data); quest.setState(QuestState.QUEST_STATE_UNFINISHED); main.getChildQuests().put(id,quest);
        return quest;
    }
    private static EntityGadget cooker(Player player,int group,int config) {
        var gadget=new EntityGadget(player.getScene(),73076009,COOKER); gadget.setGroupId(group); gadget.setConfigId(config);
        player.getScene().getEntities().put(gadget.getId(),gadget); return gadget;
    }
    private static byte[] cookRequest(int recipe,int quality,int count) throws Exception {
        var bytes=new ByteArrayOutputStream(); var out=CodedOutputStream.newInstance(bytes);
        // 7.1 GFDLJECHCOO native writer: quality 1, count 4, assist 6, recipe 14.
        out.writeUInt32(1,quality); out.writeUInt32(4,count); out.writeUInt32(14,recipe); out.flush(); return bytes.toByteArray();
    }
    private static void cook(Player player,int recipe) throws Exception {
        new HandlerPlayerCookReq().handle(player.getSession(),new byte[0],cookRequest(recipe,3,1)); drain();
    }
    private static void acceptedCook(Player player,List<GameItem> outputs) {
        var waiting=QuestCookingProgress.captureWaitingAttempts(player);
        QuestCookingProgress.onAcceptedCook(player,waiting,outputs);
    }
    private static void buy(Player player,int shop,ShopInfo good,int count) throws Exception {
        var req=BuyGoodsReq.newBuilder().setShopType(shop).setGoods(ShopGoods.newBuilder().setGoodsId(good.getGoodsId())).setBuyCount(count).build();
        new HandlerBuyGoodsReq().handle(player.getSession(),new byte[0],req.toByteArray()); drain();
    }
    private static void drain() throws Exception { QuestManager.eventExecutor.submit(()->{}).get(10,TimeUnit.SECONDS); }
    private static void own(Player player,int id,int count) {
        var existing=player.getInventory().getInventoryTab(ItemType.ITEM_MATERIAL).getItemById(id);
        if(existing!=null){existing.setCount(count);return;}
        var item=new GameItem(GameData.getItemDataMap().get(id),count){@Override public void save(){}};
        item.setOwner(player); player.getInventory().getItems().put(item.getGuid(),item);
        player.getInventory().getInventoryTab(item.getItemData().getItemType()).onAddItem(item);
    }
    private <K,V> void install(Map<K,V> map,K key,V value) { var old=map.put(key,value); cleanup.add(()->{if(old==null)map.remove(key);else map.put(key,old);}); }
    private static void field(Object instance,String name,Object value) throws Exception {
        var field=instance.getClass().getDeclaredField(name); field.setAccessible(true); field.set(instance,value);
    }
    private static final class StoryPlayer extends TestWorld.TestPlayer {
        final CallbackInventory inventory=new CallbackInventory(this);
        @Override public Inventory getInventory(){return inventory;}
        @Override public void save(){}
    }
    private static final class CallbackInventory extends Inventory {
        Runnable afterAcceptedOutput;
        CallbackInventory(Player player){super(player);}
        @Override public boolean addItem(GameItem item,ActionReason reason) {
            boolean accepted=super.addItem(item,reason);
            if(accepted && afterAcceptedOutput!=null) afterAcceptedOutput.run();
            return accepted;
        }
    }
    private static final class RecordingSession extends GameSession {
        private final Player player;
        RecordingSession(GameServer server,Player player){super(server);this.player=player;}
        @Override public Player getPlayer(){return player;}
        @Override public void send(BasePacket packet){}
    }
    private static final class TrackingQuest extends GameQuest {
        int finishes,failures;
        TrackingQuest(GameMainQuest main,QuestData data){super(main,data);}
        @Override public void save(){}
        @Override public void finish(){finishes++;setState(QuestState.QUEST_STATE_FINISHED);}
        @Override public void fail(){failures++;setState(QuestState.QUEST_STATE_FAILED);}
    }
}
