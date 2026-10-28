package emu.grasscutter.game.quest.coop;

import static org.junit.jupiter.api.Assertions.*;
import static emu.grasscutter.config.Configuration.GAME_OPTIONS;

import com.google.gson.*;
import com.mongodb.client.result.DeleteResult;
import dev.morphia.Datastore;
import emu.grasscutter.*;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.MainQuestData;
import emu.grasscutter.data.excels.*;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.database.*;
import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.player.*;
import emu.grasscutter.game.props.SceneType;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.*;
import emu.grasscutter.game.world.*;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.scripts.ScriptLoader;
import emu.grasscutter.server.game.*;
import emu.grasscutter.utils.*;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import sun.misc.Unsafe;

/** Admission, genuine content predicates, and physical selection; persistence is in-memory. */
@ExtendWith(ServerResourceFixture.class)
@ResourceLock("GameData")
@ResourceLock("DatabaseManager")
final class CoopProgressionTest {
    private final List<Runnable> undo=new ArrayList<>();
    private final Map<Integer,MainQuestData> mains=new HashMap<>();
    private boolean scripts,questing;

    @BeforeEach void prepare() throws Exception {
        scripts=Grasscutter.getConfig().server.game.enableScriptInBigWorld;
        Grasscutter.getConfig().server.game.enableScriptInBigWorld=false;
        questing=GAME_OPTIONS.questing.enabled; GAME_OPTIONS.questing.enabled=false;
        ScriptLoader.init(); memoryStore();
        var chapters=new HashSet<Integer>();
        for(var value:rows("CoopChapterExcelConfigData")) {
            var data=JsonUtils.decode(value,CoopChapterData.class); chapters.add(data.getId());
            install(GameData.getCoopChapterDataMap(),data.getId(),data);
        }
        for(var value:rows("CoopPointExcelConfigData")) {
            var data=JsonUtils.decode(value,CoopPointData.class);
            install(GameData.getCoopPointDataMap(),data.getId(),data);
        }
        for(var value:rows("MainQuestExcelConfigData")) {
            var row=value.getAsJsonObject(); if(!row.has("series")||!chapters.contains(row.get("series").getAsInt()))continue;
            int id=row.get("id").getAsInt();
            var original=read("BinOutput/Quest/"+id+".json").getAsJsonObject();
            var main=JsonUtils.decode(original,MainQuestData.class); mains.put(id,main);
            install(GameData.getMainQuestDataMap(),id,main);
            for(var element:original.getAsJsonArray("subQuests")) {
                var quest=element.getAsJsonObject().deepCopy(); quest.addProperty("mainId",id);
                for(String key:List.of("acceptCond","failCond","beginExec","finishExec","failExec"))quest.add(key,new JsonArray());
                if(!quest.has("finishCond"))quest.add("finishCond",new JsonArray());
                if(!quest.has("gainItems"))quest.add("gainItems",new JsonArray());
                for(String key:List.of("acceptCondComb","finishCondComb","failCondComb"))
                    if(!quest.has(key))quest.addProperty(key,"LOGIC_NONE");
                // Keep original finish combinations. An omitted native combination means LOGIC_NONE.
                var data=JsonUtils.decode(quest,QuestData.class);
                install(GameData.getQuestDataMap(),data.getId(),data);
            }
        }
        install(GameData.getGadgetDataMap(),70350081,JsonUtils.decode(
                "{id:70350081,type:'Worktop',jsonName:'TestCoopBranch',tags:[]}",GadgetData.class));
    }
    @AfterEach void restore() throws Exception {
        drain();
        try(var ignored=databaseBarrier()) {
            Collections.reverse(undo);undo.forEach(Runnable::run);
            GAME_OPTIONS.questing.enabled=questing;
            Grasscutter.getConfig().server.game.enableScriptInBigWorld=scripts;
        }
    }

    @Test void explicitRoutesCoverAllOriginalIdsAndCheckpointsHaveNoNativeFinishPredicates() {
        var catalog=CoopProgressionCatalog.installed();assertEquals(313,catalog.points().size());
        var configured=new HashSet<Integer>();var chapterIds=new HashSet<Integer>();int checkpoints=0;
        for(var point:catalog.points()) {
            chapterIds.add(point.chapter());
            assertEquals(GameData.getCoopPointDataMap().get(point.id()).getAcceptQuest(),point.entryQuest());
            assertTrue(point.stages().containsKey(point.forwardEntryQuest()));
            for(var stage:point.stages().values()) {
                configured.add(stage.quest());var original=GameData.getQuestDataMap().get(stage.quest());
                assertNotNull(original);assertEquals(original.getFinishCond().isEmpty(),stage.kind().equals("checkpoint"));
                if(stage.kind().equals("checkpoint"))checkpoints++;
                for(int save:stage.reportSaves())assertTrue(CoopCatalog.chapter(point.chapter()).savePoints().containsKey(save));
                for(int next:stage.nextPoints())assertTrue(Arrays.stream(GameData.getCoopPointDataMap()
                        .get(point.id()).getPostPointList()).anyMatch(id->id==next));
            }
            for(var auxiliary:point.auxiliary().values()) {
                configured.add(auxiliary.quest());assertNotNull(GameData.getQuestDataMap().get(auxiliary.quest()));
            }
        }
        var actual=new HashSet<Integer>();for(var main:mains.values())
            for(var q:main.getSubQuests())actual.add(q.getSubId());
        assertEquals(19,chapterIds.size());assertEquals(1174,actual.size());assertEquals(actual,configured);
        assertEquals(14,checkpoints);
    }

    @Test void realTalkPlotAndSaveReachAForkWithoutStartingEitherSibling() throws Exception {
        var player=start(10140101);fork(player);
        var run=player.getHangouts().getRun();
        assertEquals(0,run.getStageQuestId());
        assertEquals(List.of(10140102,10140105),run.getPendingPointIds());
        assertEquals(QuestState.QUEST_STATE_UNSTARTED,quest(player,1900104).getState());
        assertEquals(QuestState.QUEST_STATE_UNSTARTED,quest(player,1900108).getState());
        assertEquals(2,selectors(player).size());
        assertFalse(CoopProgression.mayAccept(player,GameData.getQuestDataMap().get(1900108)));
        var unrelated=JsonUtils.decode("{mainId:99999,subId:9999901}",QuestData.class);
        assertTrue(CoopProgression.mayAccept(player,unrelated));
    }

    @Test void onlyAnOwnedNearbyRealSelectorStartsOneBranchAndDoesNotCompleteItsPlot() throws Exception {
        var player=start(10140101);fork(player);
        var first=selectors(player).getFirst();
        var visitor=new StoryPlayer();visitor.setUid(88902);visitor.setWorld(player.getWorld());
        visitor.setScene(player.getScene());visitor.setSceneId(3);visitor.setSceneLoadState(Player.SceneLoadState.LOADED);
        assertTrue(CoopProgression.tryInteract(visitor,first,7));
        assertEquals(0,player.getHangouts().getRun().getStageQuestId());
        player.getPosition().set(10000,0,10000);CoopProgression.tryInteract(player,first,7);
        assertEquals(0,player.getHangouts().getRun().getStageQuestId());
        player.getPosition().set(first.getPosition());
        assertTrue(CoopProgression.tryInteract(player,first,7));drain();
        assertEquals(10140102,player.getHangouts().getRun().getPointId());
        assertEquals(1900108,player.getHangouts().getRun().getStageQuestId());
        assertEquals(QuestState.QUEST_STATE_UNFINISHED,quest(player,1900108).getState());
        assertEquals(QuestState.QUEST_STATE_UNSTARTED,quest(player,1900104).getState());
        assertEquals(List.of(10201),player.getHangouts().getRun().getCurrent().getSavePoints());
        assertTrue(selectors(player).isEmpty());
    }

    @Test void nativePointUiForwardKeepsHistoryAndAnUnfinishedPlotStillNeedsItsRealEvent() throws Exception {
        var player=start(10140101);fork(player);var before=player.getQuestManager().getMainQuestById(19001);
        assertEquals(0,CoopSystem.start(player,10140102).retcode());drain();
        assertSame(before,player.getQuestManager().getMainQuestById(19001));
        assertEquals(QuestState.QUEST_STATE_FINISHED,quest(player,1900101).getState());
        assertEquals(QuestState.QUEST_STATE_UNFINISHED,quest(player,1900108).getState());
        event(player,QuestContent.QUEST_CONTENT_FINISH_PLOT,1900104);
        assertEquals(1900108,player.getHangouts().getRun().getStageQuestId());
        event(player,QuestContent.QUEST_CONTENT_FINISH_PLOT,1900108);
        assertEquals(1900201,player.getHangouts().getRun().getStageQuestId());
    }

    @Test void pendingChoiceSurvivesSerializationAndSceneRecreationWithoutSelectingAnything() throws Exception {
        var player=start(10140101);fork(player);var saved=JsonUtils.encode(player.getHangouts());
        CoopProgression.onLogout(player);assertTrue(selectors(player).isEmpty());
        player.setHangouts(JsonUtils.decode(saved,CoopState.class));CoopProgression.onTick(player);
        assertEquals(2,selectors(player).size());assertEquals(0,player.getHangouts().getRun().getStageQuestId());
        assertEquals(QuestState.QUEST_STATE_UNSTARTED,quest(player,1900104).getState());
        assertEquals(QuestState.QUEST_STATE_UNSTARTED,quest(player,1900108).getState());
        assertTrue(player.getQuestManager().getMainQuestById(19001).resumeOnLogin()==null);
        assertEquals(0,player.getHangouts().getRun().getStageQuestId());
    }

    @Test void emptyNativeObjectiveWaitsForPhysicalCheckpointAndRejectsNonemptyNativePlot() throws Exception {
        var player=start(10340101);
        player.getPlayerProgress().addToCurrentProgress("1330044421",1);
        player.getQuestManager().triggerEvent(QuestContent.QUEST_CONTENT_LUA_NOTIFY,"1330044421",0,0);drain();
        assertEquals(1906103,player.getHangouts().getRun().getStageQuestId());
        assertEquals(0,CoopSystem.save(player,103401,190610301,5,Map.of(),Map.of()));drain();
        assertEquals(1906104,player.getHangouts().getRun().getStageQuestId());
        var checkpoint=quest(player,1906104);assertEquals(QuestState.QUEST_STATE_UNFINISHED,checkpoint.getState());
        CoopProgression.onTick(player);CoopProgression.onTick(player);
        assertEquals(QuestState.QUEST_STATE_UNFINISHED,checkpoint.getState());
        var selector=selectors(player).getFirst();
        var old=checkpoint.getQuestData();var changed=JsonParser.parseString(JsonUtils.encode(old)).getAsJsonObject();
        changed.add("finishCond",JsonParser.parseString("[{type:'QUEST_CONTENT_FINISH_PLOT',param:[1906104,0],param_str:''}]"));
        checkpoint.setConfig(JsonUtils.decode(changed,QuestData.class));
        player.getPosition().set(selector.getPosition());CoopProgression.tryInteract(player,selector,7);
        assertEquals(QuestState.QUEST_STATE_UNFINISHED,checkpoint.getState());
        checkpoint.setConfig(old);CoopProgression.tryInteract(player,selector,7);drain();
        assertEquals(QuestState.QUEST_STATE_FINISHED,checkpoint.getState());
        assertEquals(List.of(10340102,10340105,10340108),player.getHangouts().getRun().getPendingPointIds());
        for(int id:List.of(1906201,1906401,1906701))assertEquals(QuestState.QUEST_STATE_UNSTARTED,quest(player,id).getState());
    }

    @Test void eachDeclaredEndingBindingAwardsOneEndpointAndNeverAnotherPointsOrFailureSave() {
        var catalog=CoopProgressionCatalog.installed();
        assertEquals(10320193,catalog.endingPoint(103201,10320109,301));
        assertEquals(10320194,catalog.endingPoint(103201,10320109,302));
        assertEquals(10320195,catalog.endingPoint(103201,10320110,401));
        assertEquals(10320196,catalog.endingPoint(103201,10320110,402));
        assertEquals(0,catalog.endingPoint(103201,10320110,301));
        assertEquals(0,catalog.endingPoint(103201,10320110,901));
        assertEquals(0,catalog.endingPoint(103901,10390104,101));
        assertEquals(10390191,catalog.endingPoint(103901,10390104,102));
        assertEquals(10340191,catalog.endingPoint(103401,10340103,190620502));
        assertEquals(0,catalog.endingPoint(103401,10340104,190620502));
        assertEquals(10340192,catalog.endingPoint(103401,10340104,190620701));
        assertEquals(1903101,catalog.point(10360114).forwardEntryQuest());
        assertEquals(1903102,catalog.point(10360114).entryQuest());
        assertEquals(List.of(1903105),catalog.point(10360114).stages().get(1903101).nextStages());
    }

    @Test void aNewBattleRequiresFourNewLuaFactsAndRetainsItsBaselineAcrossSerialization() throws Exception {
        var player=start(10140108);
        player.getPlayerProgress().addToCurrentProgress("133004434",100);
        event(player,QuestContent.QUEST_CONTENT_COMPLETE_TALK,1900303);
        assertEquals(1900304,player.getHangouts().getRun().getStageQuestId());
        assertEquals(100,player.getHangouts().getRun().getStageLuaBaselines().get("133004434"));
        player.getQuestManager().triggerEvent(QuestContent.QUEST_CONTENT_LUA_NOTIFY,"unrelated",0);drain();
        assertEquals(QuestState.QUEST_STATE_UNFINISHED,quest(player,1900304).getState());
        player.getPlayerProgress().addToCurrentProgress("133004434",3);
        player.getQuestManager().triggerEvent(QuestContent.QUEST_CONTENT_LUA_NOTIFY,"133004434",0);drain();
        assertEquals(QuestState.QUEST_STATE_UNFINISHED,quest(player,1900304).getState());
        player.setHangouts(JsonUtils.decode(JsonUtils.encode(player.getHangouts()),CoopState.class));
        assertNull(player.getQuestManager().getMainQuestById(19003).resumeOnLogin());
        assertEquals(100,player.getHangouts().getRun().getStageLuaBaselines().get("133004434"));
        player.getPlayerProgress().addToCurrentProgress("133004434",1);
        player.getQuestManager().triggerEvent(QuestContent.QUEST_CONTENT_LUA_NOTIFY,"133004434",0);drain();
        assertEquals(QuestState.QUEST_STATE_FINISHED,quest(player,1900304).getState());
        var previousBattle=quest(player,1900304);
        var previousCondition=previousBattle.getQuestData().getFinishCond().getFirst();
        assertTrue(CoopProgression.ownsLuaCondition(previousBattle,previousCondition));
        assertFalse(CoopProgression.matchesLuaCondition(previousBattle,previousCondition));
        assertEquals(10140109,player.getHangouts().getRun().getPointId());
        assertEquals(1900305,player.getHangouts().getRun().getStageQuestId());
        assertEquals(QuestState.QUEST_STATE_UNFINISHED,quest(player,1900305).getState());
        assertFalse(CoopProgression.advanceToPoint(player,10320101));
    }

    private StoryPlayer start(int point) throws Exception {
        var player=new StoryPlayer();player.setUid(88901);
        var f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);
        var server=(GameServer)((Unsafe)f.get(null)).allocateInstance(GameServer.class);
        var qs=GameServer.class.getDeclaredField("questSystem");qs.setAccessible(true);qs.set(server,new QuestSystem(server));
        player.setSession(new GameSession(server){@Override public Player getPlayer(){return player;}@Override public void send(BasePacket p){player.packets.add(p);}});
        var world=TestWorld.create(player);player.setScene(new TestWorld.TestScene(world,3,SceneType.SCENE_WORLD));
        player.getScene().getPlayers().add(player);player.setSceneId(3);player.setSceneLoadState(Player.SceneLoadState.LOADED);
        var config=CoopProgressionCatalog.installed().point(point);var chapter=CoopCatalog.chapter(config.chapter());
        player.getHangouts().getAcceptedChapters().add(config.chapter());
        player.getHangouts().point(point).setState(1);
        player.getHangouts().point(point).setStart(new CoopState.Snapshot(config.chapter(),chapter.initialConfidence(),chapter.normalVars(),chapter.tempVars()));
        assertEquals(0,CoopSystem.start(player,point).retcode());drain();
        // Use the real start/content paths; replace the unrelated parent/reward/DB finish machinery.
        for(var definition:mains.values()) {
            var previous=player.getQuestManager().getMainQuestById(definition.getId());
            var memory=new MemoryMain(player,definition.getId());
            for(var entry:new ArrayList<>(memory.getChildQuests().entrySet())) {
                var quest=new MemoryQuest(memory,entry.getValue().getQuestData());
                if(previous!=null&&previous.getChildQuestById(entry.getKey())!=null)
                    quest.setState(previous.getChildQuestById(entry.getKey()).getState());
                memory.getChildQuests().put(entry.getKey(),quest);
            }
            player.getQuestManager().getMainQuests().put(definition.getId(),memory);
        }
        return player;
    }
    private void fork(StoryPlayer player) throws Exception {
        event(player,QuestContent.QUEST_CONTENT_COMPLETE_TALK,1900101);
        assertEquals(1900102,player.getHangouts().getRun().getStageQuestId());
        event(player,QuestContent.QUEST_CONTENT_FINISH_PLOT,1900102);
        assertEquals(1900103,player.getHangouts().getRun().getStageQuestId());
        assertEquals(0,CoopSystem.save(player,101401,10201,4,Map.of(),Map.of(3,2)));drain();
    }
    private static void event(StoryPlayer player,QuestContent type,int id) throws Exception {
        player.getQuestManager().triggerEvent(type,"",id,0);drain();
    }
    private static GameQuest quest(StoryPlayer player,int id){return player.getQuestManager().getQuestById(id);}
    private static List<EntityGadget> selectors(StoryPlayer player){return player.getScene().getEntities().values().stream()
            .filter(EntityGadget.class::isInstance).map(EntityGadget.class::cast)
            .filter(g->g.getGadgetId()==70350081).sorted(Comparator.comparingDouble(g->g.getPosition().getX())).toList();}
    private <V> void install(Map<Integer,V> map,int id,V value){var old=map.put(id,value);undo.add(()->{if(old==null)map.remove(id);else map.put(id,old);});}
    private static JsonElement read(String path)throws Exception{return JsonParser.parseString(Files.readString(FileUtils.getResourcePath(path)));}
    private static JsonArray rows(String name)throws Exception{return read("ExcelBinOutput/"+name+".json").getAsJsonArray();}
    private static void drain()throws Exception{QuestManager.eventExecutor.submit(()->{}).get(10,TimeUnit.SECONDS);try(var ignored=databaseBarrier()) {}}
    private void memoryStore()throws Exception{
        var f=DatabaseManager.class.getDeclaredField("gameDatastore");f.setAccessible(true);var old=f.get(null);
        f.set(null,Proxy.newProxyInstance(Datastore.class.getClassLoader(),new Class<?>[]{Datastore.class},(proxy,m,args)->{
            if(m.getName().equals("save")&&args!=null&&args[0] instanceof GameMainQuest)return args[0];
            if(m.getName().equals("delete")&&args!=null&&args[0] instanceof GameMainQuest)return DeleteResult.acknowledged(1);
            if(m.getName().equals("toString"))return "CoopProgressionMemoryStore";
            throw new AssertionError("Unexpected datastore call "+m.getName());
        }));undo.add(()->{try{f.set(null,old);}catch(IllegalAccessException e){throw new AssertionError(e);}});
    }
    private static AutoCloseable databaseBarrier()throws Exception{
        var f=DatabaseHelper.class.getDeclaredField("databaseWriters");f.setAccessible(true);var writer=f.get(null);
        var m=writer.getClass().getDeclaredMethod("acquireBarrier",Duration.class);m.setAccessible(true);
        return (AutoCloseable)m.invoke(writer,Duration.ofSeconds(10));
    }
    private static final class StoryPlayer extends TestWorld.TestPlayer {
        final QuestManager manager=new QuestManager(this);final TeamManager team=new TeamManager(this);
        final List<BasePacket> packets=new ArrayList<>();final List<String> messages=new ArrayList<>();int saves;
        @Override public QuestManager getQuestManager(){return manager;}
        @Override public TeamManager getTeamManager(){return team;}
        @Override public void save(){saves++;}
        @Override public void sendPacket(BasePacket packet){packets.add(packet);}
        @Override public void dropMessage(Object message){messages.add(message.toString());}
    }
    private static final class MemoryMain extends GameMainQuest {
        MemoryMain(Player player,int id){super(player,id);}
        @Override public void save(){}@Override public void delete(){}@Override public void checkProgress(){}
    }
    private static final class MemoryQuest extends GameQuest {
        MemoryQuest(GameMainQuest parent,QuestData data){super(parent,data);}
        @Override public void save(){}
        @Override public void finish(){setState(QuestState.QUEST_STATE_FINISHED);CoopProgression.onQuestFinished(this);}
    }
}
