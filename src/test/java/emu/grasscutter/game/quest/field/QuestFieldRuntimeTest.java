package emu.grasscutter.game.quest.field;

import static org.junit.jupiter.api.Assertions.*;
import static emu.grasscutter.config.Configuration.GAME_OPTIONS;

import com.google.gson.*;
import com.mongodb.client.result.DeleteResult;
import dev.morphia.Datastore;
import emu.grasscutter.*;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.MainQuestData;
import emu.grasscutter.data.excels.*;
import emu.grasscutter.data.excels.dungeon.DungeonData;
import emu.grasscutter.data.excels.monster.MonsterData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.data.excels.world.WorldAreaData;
import emu.grasscutter.database.*;
import emu.grasscutter.game.avatar.Avatar;
import emu.grasscutter.game.dungeons.DungeonSystem;
import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.inventory.*;
import emu.grasscutter.game.player.*;
import emu.grasscutter.game.props.*;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.content.ContentServerState;
import emu.grasscutter.game.quest.enums.*;
import emu.grasscutter.game.quest.exec.*;
import emu.grasscutter.game.world.*;
import emu.grasscutter.game.world.data.TeleportProperties;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.AddQuestContentProgressReqOuterClass.AddQuestContentProgressReq;
import emu.grasscutter.net.proto.WidgetDoBagReqOuterClass.WidgetDoBagReq;
import emu.grasscutter.scripts.*;
import emu.grasscutter.scripts.data.ScriptArgs;
import emu.grasscutter.server.game.*;
import emu.grasscutter.server.packet.recv.*;
import emu.grasscutter.utils.*;
import it.unimi.dsi.fastutil.ints.*;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import sun.misc.Unsafe;

@ExtendWith(ServerResourceFixture.class)
@ResourceLock("GameData")
@ResourceLock("DatabaseManager")
final class QuestFieldRuntimeTest {
    private static final AtomicInteger UID=new AtomicInteger(89900);
    private static final int[] PARENTS={70661,74042,76189,76190,76191,72672,998,74668,74700};
    private final List<Runnable> undo=new ArrayList<>();private final List<FieldPlayer> players=new ArrayList<>();
    private boolean scripts,questing;
    @BeforeEach void setup()throws Exception{
        scripts=Grasscutter.getConfig().server.game.enableScriptInBigWorld;Grasscutter.getConfig().server.game.enableScriptInBigWorld=false;
        questing=GAME_OPTIONS.questing.enabled;GAME_OPTIONS.questing.enabled=false;ScriptLoader.init();memoryStore();
        var qrows=read("ExcelBinOutput/QuestExcelConfigData.json").getAsJsonArray();
        for(int parent:PARENTS){var subIds=new JsonArray();
            for(var value:qrows){var q=value.getAsJsonObject();if(q.get("mainId").getAsInt()!=parent)continue;
                var data=JsonUtils.decode(q,QuestData.class);install(GameData.getQuestDataMap(),data.getId(),data);
                subIds.add(JsonParser.parseString("{subId:"+data.getId()+"}"));}
            var main=new JsonObject();main.addProperty("id",parent);main.add("subQuests",subIds);
            install(GameData.getMainQuestDataMap(),parent,JsonUtils.decode(main,MainQuestData.class));
        }
        for(int id:new int[]{70350081,70500008,70211101})install(GameData.getGadgetDataMap(),id,JsonUtils.decode(
                "{id:"+id+",type:'"+(id==70211101?"Chest":"Worktop")+"',jsonName:'FieldProp',tags:[]}",GadgetData.class));
        for(int id:new int[]{28040201,28040903,28021403,28010223,28022205,28010101}){
            var data=JsonUtils.decode("{id:"+id+",type:'MONSTER_ORDINARY',hpBase:100,attackBase:1,defenseBase:1,affix:[],equips:[],propGrowCurves:[]}",MonsterData.class);
            data.onLoad();install(GameData.getMonsterDataMap(),id,data);
        }
        for(int id:new int[]{131000,131029,379001,369113,220043,220009})install(GameData.getItemDataMap(),id,JsonUtils.decode(
                "{id:"+id+",itemType:'"+(id==379001||id==369113?"ITEM_FURNITURE":"ITEM_MATERIAL")+"',materialType:'MATERIAL_NONE',stackLimit:"+
                (id==220009?1:9999)+"}",ItemData.class));
        install(GameData.getCityDataMap(),1,JsonUtils.decode("{cityId:1,sceneId:3,areaIdVec:[1]}",CityData.class));
        install(GameData.getWorldAreaDataMap(),1,JsonUtils.decode("{sceneID:3,areaID1:1,areaID2:0}",WorldAreaData.class));
        install(GameData.getDungeonDataMap(),10900,JsonUtils.decode("{id:10900,sceneId:51249,type:'DUNGEON_ACTIVITY',showLevel:1,limitLevel:1}",DungeonData.class));
        install(GameData.getSceneDataMap(),51249,TestWorld.sceneData(51249,SceneType.SCENE_DUNGEON));
    }
    @AfterEach void restore()throws Exception{
        for(var p:players)QuestFieldRuntime.onLogout(p);drain();
        try(var ignored=barrier()){Collections.reverse(undo);undo.forEach(Runnable::run);}
        GAME_OPTIONS.questing.enabled=questing;Grasscutter.getConfig().server.game.enableScriptInBigWorld=scripts;
    }
    @Test void nativeFurnitureClassificationPoolAndCaptureIdentityStayDistinct(){
        var c=QuestFieldCatalog.installed();assertFalse(c.fish(1).furniture());assertTrue(c.fish(1001).furniture());
        assertEquals(131000,c.fish(1).item());assertEquals(379001,c.fish(1001).item());
        assertEquals(10000130,c.capture(7618903).getAvatar());assertEquals(1130,c.capture(7618903).getTrial());
        assertEquals(369113,c.capture(7267203).getReward());assertEquals(21330032,c.capture(7267203).getDrop());
    }
    @Test void castEarlyHookAndRapidReelCannotFabricateFishButTheCompleteRealCatchGrantsOneItem()throws Exception{
        var p=player();p.activate(7066104);p.getPosition().set(QuestFieldCatalog.installed().fishing(1).getBank());QuestFieldRuntime.onTick(p);
        var control=nearestControl(p);interact(p,control);assertEquals(0,progress(p,7066104));assertEquals(0,p.inventory.getItemCountById(131000));
        p.scene().clock=100;interact(p,control);assertEquals(0,progress(p,7066104)); // Early hook ends the attempt.
        interact(p,control);p.scene().clock=3100;QuestFieldRuntime.onTick(p);interact(p,control);
        interact(p,control);interact(p,control);assertEquals(0,progress(p,7066104));
        for(int tick:new int[]{3600,4100,4600}){p.scene().clock=tick;interact(p,control);}
        assertEquals(1,progress(p,7066104));assertEquals(1,p.inventory.getItemCountById(131000));
        interact(p,control);assertEquals(1,p.inventory.getItemCountById(131000));
        assertTrue(ContentServerState.matchesCurrent(p.quest(7066104),p.quest(7066104).getQuestData().getFinishCond().getFirst()));
        p.quest(7066104).getMainQuest().tryFinishSubQuests(QuestContent.QUEST_CONTENT_FISHING_SUCC,"",1,101);
        assertEquals(QuestState.QUEST_STATE_FINISHED,p.quest(7066104).getState());
    }
    @Test void oneFurnitureCatchCanSatisfyBothActualActiveFurnitureObserversAndNeverCountsAsOrdinary()throws Exception{
        var p=player();p.activate(7066107);p.activate(7066110);p.getPosition().set(QuestFieldCatalog.installed().fishing(2).getBank());
        QuestFieldRuntime.onTick(p);catchOne(p,nearestControl(p));
        assertEquals(1,progress(p,7066107));assertEquals(1,progress(p,7066110));assertEquals(1,p.inventory.getItemCountById(379001));
        assertEquals(0,p.inventory.getItemCountById(131000));
    }
    @Test void pool5001IsARealNativePoolAndLogoutNeverTurnsAnUnfinishedCastIntoACatch()throws Exception{
        var p=player();p.activate(7404201);p.getPosition().set(QuestFieldCatalog.installed().fishing(4).getWater().clone().addY(2));
        QuestFieldRuntime.onTick(p);var control=nearestControl(p);interact(p,control);p.scene().clock=3000;QuestFieldRuntime.onLogout(p);
        p.scene().clock=100000;QuestFieldRuntime.onTick(p);assertEquals(0,progress(p,7404201));assertEquals(0,p.inventory.getItemCountById(131029));
        catchOne(p,nearestControl(p));assertEquals(5001,p.state.round(7404201).getPoolId());assertEquals(1,p.inventory.getItemCountById(131029));
        var copy=JsonUtils.decode(JsonUtils.encode(p.state),QuestFieldState.class);p.state=copy;
        assertTrue(QuestFieldRuntime.matchesCurrent(p.quest(7404201),p.quest(7404201).getQuestData().getFinishCond().getFirst()));
    }
    @Test void netArmingConsumesNothingAndFullRewardInventoryCannotConsumeTheNetOrCaptureAnimal()throws Exception{
        var p=player();p.activate(7267203);p.inventory.addItem(220043,2);p.setWidgetId(220043);QuestFieldRuntime.onTick(p);
        assertTrue(QuestFieldRuntime.armNet(p));assertEquals(2,p.inventory.getItemCountById(220043));assertEquals(0,progress(p,7267203));
        new HandlerQuickUseWidgetReq().handle(p.getSession(),new byte[0],new byte[0]);
        assertEquals(2,p.inventory.getItemCountById(220043));assertEquals(0,progress(p,7267203));
        p.inventory.blockRewards=true;var control=nearestControl(p);interact(p,control);p.scene().clock=3000;QuestFieldRuntime.onTick(p);
        assertEquals(2,p.inventory.getItemCountById(220043));assertEquals(0,progress(p,7267203));
        assertTrue(p.getScene().getEntities().values().stream().anyMatch(e->e instanceof EntityMonster&&e.isAlive()));
        p.inventory.blockRewards=false;interact(p,control);p.scene().clock=6000;QuestFieldRuntime.onTick(p);
        assertEquals(1,p.inventory.getItemCountById(220043));assertEquals(1,p.inventory.getItemCountById(369113));assertEquals(1,progress(p,7267203));
        assertFalse(p.getScene().getEntities().values().stream().anyMatch(EntityMonster.class::isInstance));
        p.quest(7267203).getMainQuest().tryFinishSubQuests(QuestContent.QUEST_CONTENT_CAPTURE_USE_MATERIAL_LIST,"",220043);
        assertEquals(QuestState.QUEST_STATE_FINISHED,p.quest(7267203).getState());
    }
    @Test void surveyNeedsTheActualCorrectAvatarAndContinuousProximityToALivingRealAnimal()throws Exception{
        var p=player();p.role=10000130;p.team.getActiveTeam().add(p.actor);p.activate(7618903);p.getPosition().set(QuestFieldCatalog.installed().capture(7618903).getPosition());QuestFieldRuntime.onTick(p);
        var control=nearestControl(p);interact(p,control);p.role=10000007;p.scene().clock=3000;QuestFieldRuntime.onTick(p);assertEquals(0,progress(p,7618903));
        p.role=10000130;interact(p,control);p.getPosition().addX(30);p.scene().clock=6000;QuestFieldRuntime.onTick(p);assertEquals(0,progress(p,7618903));
        QuestFieldRuntime.onLogout(p);p.getPosition().set(QuestFieldCatalog.installed().capture(7618903).getPosition());QuestFieldRuntime.onTick(p);
        control=nearestControl(p);interact(p,control);p.scene().clock=9000;QuestFieldRuntime.onTick(p);
        assertEquals(1,progress(p,7618903));assertEquals(10000130,p.state.round(7618903).getAvatarId());assertEquals(28021403,p.state.round(7618903).getSourceId());
        p.quest(7618903).getMainQuest().tryFinishSubQuests(QuestContent.QUEST_CONTENT_AVATAR_CAPTURE_ANIMAL,"",10000130,28021403);
        assertEquals(QuestState.QUEST_STATE_FINISHED,p.quest(7618903).getState());
    }
    @Test void staleDistantGuestAndDirectClientQuestCountersCannotClaimFieldResults()throws Exception{
        var p=player();p.activate(7066104);p.getPosition().set(QuestFieldCatalog.installed().fishing(1).getBank());QuestFieldRuntime.onTick(p);var control=nearestControl(p);
        var guest=player();guest.setWorld(p.getWorld());guest.setScene(p.getScene());guest.setSceneId(3);guest.getPosition().set(control.getPosition());
        assertTrue(QuestFieldRuntime.tryInteract(guest,control,7));assertEquals(0,progress(p,7066104));
        p.getPosition().addX(20);interact(p,control);assertEquals(0,progress(p,7066104));
        for(var type:List.of(QuestContent.QUEST_CONTENT_FISHING_SUCC,QuestContent.QUEST_CONTENT_AVATAR_CAPTURE_ANIMAL,
                QuestContent.QUEST_CONTENT_CAPTURE_USE_MATERIAL_LIST,QuestContent.QUEST_CONTENT_USE_WIDGET)){
            assertTrue(type.isServerStatePredicate());new HandlerAddQuestContentProgressReq().handle(p.getSession(),new byte[0],
                    AddQuestContentProgressReq.newBuilder().setContentType(type.getValue()).setParam(1).build().toByteArray());}
        assertEquals(0,progress(p,7066104));QuestFieldRuntime.onLogout(p);assertTrue(QuestFieldRuntime.tryInteract(p,control,7));assertEquals(0,progress(p,7066104));
    }
    @Test void compassRequestCreatesTheRealDirectionalEffectAndCooldownRatherThanReusableWidgetAck()throws Exception{
        var p=player();p.activate(99809);p.inventory.addItem(220009,1);p.setWidgetId(220009);
        var chest=new EntityGadget(p.getScene(),70211101,new Position(10,0,0));
        chest.setContent(new emu.grasscutter.game.entity.gadget.GadgetChest(chest));p.getScene().addEntity(chest);
        new HandlerWidgetDoBagReq().handle(p.getSession(),new byte[0],WidgetDoBagReq.newBuilder().setMaterialId(220009).build().toByteArray());
        assertEquals(1,progress(p,99809));assertEquals(1,p.inventory.getItemCountById(220009));
        var effect=p.getScene().getEntities().values().stream().filter(EntityGadget.class::isInstance).map(EntityGadget.class::cast)
                .filter(g->g.getGadgetId()==70500008).findFirst().orElseThrow();assertEquals(90,effect.getRotation().getY(),0.01);
        assertFalse(QuestFieldRuntime.useCompass(p));assertEquals(1,progress(p,99809));p.scene().clock=6000;QuestFieldRuntime.onTick(p);
        assertNull(p.getScene().getEntityById(effect.getId()));
        p.state.setLastCompassUse(0);p.setArea(999,1);assertFalse(QuestFieldRuntime.useCompass(p));
    }
    @Test void diveExecAndLegacyRepairUseActualFinishedQuestAndPreserveALaterDisable()throws Exception{
        var p=player();var quest=p.quest(7466801);p.setProperty(PlayerProperty.PROP_PLAYER_CAN_DIVE,0);
        QuestFieldRuntime.recoverDiveUnlock(p);assertEquals(0,p.getProperty(PlayerProperty.PROP_PLAYER_CAN_DIVE));
        quest.setState(QuestState.QUEST_STATE_FINISHED);QuestFieldRuntime.recoverDiveUnlock(p);assertEquals(1,p.getProperty(PlayerProperty.PROP_PLAYER_CAN_DIVE));
        assertTrue(new ExecSetDiveable().execute(quest,null,"0"));QuestFieldRuntime.recoverDiveUnlock(p);assertEquals(0,p.getProperty(PlayerProperty.PROP_PLAYER_CAN_DIVE));
        assertFalse(new ExecSetDiveable().execute(quest,null,"2"));assertTrue(new ExecSetDiveable().execute(quest,null,"1"));
        assertEquals(1,p.getProperty(PlayerProperty.PROP_PLAYER_CAN_DIVE));
    }
    @Test void aquariumExecUsesRealNativeLevel99DungeonAndCannotSucceedWhenTransportFails()throws Exception{
        var p=player();p.setScene(p.world.scene(1161));p.setSceneId(1161);p.setProperty(PlayerProperty.PROP_PLAYER_CAN_DIVE,1);
        var action=new ExecEnterAquariumDivingDungeon();p.world.allowTransfer=false;
        assertFalse(action.execute(p.quest(7470001),null,"99"));assertEquals(1161,p.getSceneId());
        p.world.allowTransfer=true;assertTrue(action.execute(p.quest(7470001),null,"99"));
        assertEquals(51249,p.getSceneId());assertEquals(10900,p.getScene().getDungeonManager().getDungeonData().getId());
    }
    private static int progress(FieldPlayer p,int quest){var s=p.state.getRounds().get(quest);return s==null?0:s.getCount();}
    private static EntityGadget nearestControl(FieldPlayer p){return p.getScene().getEntities().values().stream().filter(EntityGadget.class::isInstance)
            .map(EntityGadget.class::cast).filter(g->g.getGadgetId()==70350081).min(Comparator.comparingDouble(g->p.getPosition().computeDistance(g.getPosition()))).orElseThrow();}
    private static void interact(FieldPlayer p,EntityGadget c){assertTrue(QuestFieldRuntime.tryInteract(p,c,7));}
    private static void catchOne(FieldPlayer p,EntityGadget control){int now=p.scene().clock;interact(p,control);
        p.scene().clock=now+3000;QuestFieldRuntime.onTick(p);interact(p,control);
        for(int delta:new int[]{3500,4000,4500}){p.scene().clock=now+delta;interact(p,control);}}
    private FieldPlayer player()throws Exception{
        var p=new FieldPlayer();p.setUid(UID.incrementAndGet());p.world=FieldWorld.create(p);p.setWorld(p.world);
        p.setSession(new GameSession(p.world.server){@Override public Player getPlayer(){return p;}@Override public void send(BasePacket b){p.packets.add(b);}});
        p.setScene(p.world.scene(3));p.setSceneId(3);p.setSceneLoadState(Player.SceneLoadState.LOADED);p.setArea(1,1);
        p.getPosition().set(new Position());p.getRotation().set(new Position());
        for(int id:PARENTS){var m=new GameMainQuest(p,id){@Override public void save(){}@Override public void checkProgress(){}};
            for(var q:List.copyOf(m.getChildQuests().values()))m.getChildQuests().put(q.getSubQuestId(),new GameQuest(m,q.getQuestData()){
                @Override public void save(){}@Override public void finish(){setState(QuestState.QUEST_STATE_FINISHED);}});
            p.manager.getMainQuests().put(id,m);}
        p.actor=(FakeActor)unsafe().allocateInstance(FakeActor.class);p.actor.owner=p;players.add(p);return p;
    }
    private <V> void install(Map<Integer,V> map,int id,V value){var old=map.put(id,value);undo.add(()->{if(old==null)map.remove(id);else map.put(id,old);});}
    private static JsonElement read(String path)throws Exception{return JsonParser.parseString(Files.readString(FileUtils.getResourcePath(path)));}
    private void memoryStore()throws Exception{var field=DatabaseManager.class.getDeclaredField("gameDatastore");field.setAccessible(true);var previous=field.get(null);
        field.set(null,Proxy.newProxyInstance(Datastore.class.getClassLoader(),new Class<?>[]{Datastore.class},(proxy,m,args)->{
            if(m.getName().equals("save"))return args[0];if(m.getName().equals("delete"))return DeleteResult.acknowledged(1);
            if(m.getName().equals("toString"))return "FieldMemoryStore";throw new AssertionError("Unexpected datastore "+m.getName());}));
        undo.add(()->{try{field.set(null,previous);}catch(IllegalAccessException e){throw new AssertionError(e);}});}
    private static AutoCloseable barrier()throws Exception{var f=DatabaseHelper.class.getDeclaredField("databaseWriters");f.setAccessible(true);var writers=f.get(null);
        var m=writers.getClass().getDeclaredMethod("acquireBarrier",Duration.class);m.setAccessible(true);return(AutoCloseable)m.invoke(writers,Duration.ofSeconds(10));}
    private static void drain()throws Exception{QuestManager.eventExecutor.submit(()->{}).get(10,TimeUnit.SECONDS);try(var ignored=barrier()){} }
    private static Unsafe unsafe()throws Exception{var f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);return(Unsafe)f.get(null);}
    private static final class FakeActor extends EntityAvatar{
        FieldPlayer owner;FakeActor(){super((Avatar)null);}@Override public int getEntityTypeId(){return owner.role;}
    }
    private static final class FieldInventory extends Inventory{
        boolean blockRewards;FieldInventory(Player p){super(p);}
        @Override public InventoryTab getInventoryTab(ItemType type){return blockRewards&&type==ItemType.ITEM_FURNITURE?new MaterialInventoryTab(0):super.getInventoryTab(type);}
    }
    private static final class FieldPlayer extends TestWorld.TestPlayer{
        QuestFieldState state=new QuestFieldState();final QuestManager manager=new QuestManager(this);final FieldInventory inventory=new FieldInventory(this);
        final TeamManager team=new TeamManager(this);
        final List<BasePacket> packets=new ArrayList<>();FieldWorld world;FakeActor actor;int role=10000007;
        @Override public QuestFieldState getFieldGames(){return state;}@Override public QuestManager getQuestManager(){return manager;}
        @Override public Inventory getInventory(){return inventory;}@Override public TeamManager getTeamManager(){return team;}
        @Override public GameServer getServer(){return world.server;}@Override public void save(){}@Override public void dropMessage(Object text){}
        @Override public void sendPacket(BasePacket packet){packets.add(packet);}
        GameQuest quest(int id){return manager.getQuestById(id);}FieldScene scene(){return(FieldScene)getScene();}
        void activate(int id){var q=quest(id);q.setState(QuestState.QUEST_STATE_UNFINISHED);QuestFieldRuntime.onQuestStarted(q);}
    }
    private static final class FieldWorld extends World{
        FieldPlayer host;GameServer server;Int2ObjectMap<Scene> scenes;int next;boolean allowTransfer;
        FieldWorld(){super((Player)null);}
        static FieldWorld create(FieldPlayer p)throws Exception{GameData.setConfigGlobalCombat(new emu.grasscutter.data.binout.config.ConfigGlobalCombat());
            var w=(FieldWorld)unsafe().allocateInstance(FieldWorld.class);w.host=p;w.scenes=new Int2ObjectOpenHashMap<>();w.allowTransfer=true;
            w.server=(GameServer)unsafe().allocateInstance(GameServer.class);
            var q=GameServer.class.getDeclaredField("questSystem");q.setAccessible(true);q.set(w.server,new QuestSystem(w.server));
            var d=GameServer.class.getDeclaredField("dungeonSystem");d.setAccessible(true);d.set(w.server,new DungeonSystem(w.server));return w;}
        FieldScene scene(int id){return(FieldScene)scenes.computeIfAbsent(id,key->new FieldScene(this,key));}
        @Override public Player getHost(){return host;}@Override public List<Player> getPlayers(){return List.of(host);}
        @Override public GameServer getServer(){return server;}@Override public Int2ObjectMap<Scene> getScenes(){return scenes;}
        @Override public int getNextEntityId(EntityIdType type){return(type.getId()<<24)+ ++next;}@Override public int getLevelEntityId(){return 0;}
        @Override public boolean transferPlayerToScene(Player p,TeleportProperties t){if(!allowTransfer)return false;p.setScene(scene(t.getSceneId()));p.setSceneId(t.getSceneId());return true;}
    }
    private static final class FieldScene extends TestWorld.TestScene{
        int clock;SceneScriptManager scripts;
        FieldScene(FieldWorld world,int id){super(world,id,id==51249?SceneType.SCENE_DUNGEON:SceneType.SCENE_ROOM);}
        @Override public int getSceneTime(){return clock;}
        @Override public SceneScriptManager getScriptManager(){if(scripts==null)scripts=new SceneScriptManager(this){
            @Override public Future<?> callEvent(ScriptArgs args){return CompletableFuture.completedFuture(null);}};return scripts;}
    }
}
