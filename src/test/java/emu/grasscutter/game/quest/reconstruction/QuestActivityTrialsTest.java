package emu.grasscutter.game.quest.reconstruction;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.*;
import emu.grasscutter.data.binout.MainQuestData;
import emu.grasscutter.data.excels.GadgetData;
import emu.grasscutter.data.excels.dungeon.*;
import emu.grasscutter.game.dungeons.DungeonManager;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.activity.ActivityConfigItem;
import emu.grasscutter.game.activity.ActivityManager;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.SceneType;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.*;
import emu.grasscutter.game.world.*;
import emu.grasscutter.net.proto.ActivityInfoOuterClass.ActivityInfo;
import emu.grasscutter.net.proto.AddQuestContentProgressReqOuterClass.AddQuestContentProgressReq;
import emu.grasscutter.net.proto.SelectWorktopOptionReqOuterClass.SelectWorktopOptionReq;
import emu.grasscutter.server.game.*;
import emu.grasscutter.server.packet.recv.*;
import emu.grasscutter.scripts.ScriptLoader;
import emu.grasscutter.utils.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import sun.misc.Unsafe;

@ExtendWith(ServerResourceFixture.class) @ResourceLock("GameData")
final class QuestActivityTrialsTest {
    private static final Set<Integer> ROGUE = Set.of(4002012,4002024,4002027,4002104,4002115,4002116,4002117,4002202,4002207,4002210,4002212);
    private final Map<Integer, MainQuestData> oldMains = new HashMap<>();
    private final Map<Integer, QuestData> oldQuests = new HashMap<>();
    private final Map<Integer, QuestData> nativeQuests = new HashMap<>();
    private final List<TrialPlayer> players = new ArrayList<>();
    private GadgetData oldGadget;
    private final Map<Integer,DungeonPassConfigData> oldPasses = new HashMap<>();
    private Map<Integer,ActivityConfigItem> oldActivityConfigs,oldScheduleConfigs;
    @BeforeAll static void scripts() throws Exception { ScriptLoader.init(); }
    @BeforeEach void load() throws Exception {
        // Archive tests install schedules globally; this fixture has no activity persistence store.
        // Keep the real manager constructor, using only schedules installed by the current test.
        var activities=activityConfigs();var schedules=ActivityManager.getScheduleActivityConfigMap();
        oldActivityConfigs=new HashMap<>(activities);oldScheduleConfigs=new HashMap<>(schedules);
        activities.clear();schedules.clear();
        var parents = new TreeSet<Integer>(); QuestActivityCatalog.installed().trials().forEach(t -> parents.add(t.parent()));
        parents.add(40002);
        parents.addAll(List.of(40020,40021,40022));
        for (int id : parents) {
            var main = JsonParser.parseString(Files.readString(FileUtils.getResourcePath("BinOutput/Quest/"+id+".json"))).getAsJsonObject();
            var children = new ArrayList<String>();
            for (var value : main.getAsJsonArray("subQuests")) {
                var row = value.getAsJsonObject().deepCopy(); int sub = row.get("subId").getAsInt();
                if (QuestActivityCatalog.installed().trial(sub) == null && !ROGUE.contains(sub) && sub != 4014402 && sub != 4016001 && sub != 4000202) continue;
                for (var key : List.of("acceptCond","finishCond","failCond","beginExec","finishExec","failExec")) if (!row.has(key)) row.add(key,new JsonArray());
                var data = JsonUtils.decode(row,QuestData.class); data.onLoad();
                oldQuests.put(sub,GameData.getQuestDataMap().put(sub,data)); nativeQuests.put(sub,data); children.add("{subId:"+sub+"}");
            }
            oldMains.put(id,GameData.getMainQuestDataMap().put(id,JsonUtils.decode("{id:"+id+",subQuests:["+String.join(",",children)+"]}",MainQuestData.class)));
        }
        for (var value : JsonParser.parseString(Files.readString(FileUtils.getExcelPath("GadgetExcelConfigData.json"))).getAsJsonArray())
            if (value.getAsJsonObject().get("id").getAsInt()==70350081) oldGadget=GameData.getGadgetDataMap().put(70350081,JsonUtils.decode(value,GadgetData.class));
    }
    @AfterEach void restore() throws Exception {
        try {
            drain(); players.forEach(QuestActivityRuntime::onLogout);
            oldMains.forEach((id,value)->{if(value==null)GameData.getMainQuestDataMap().remove(id);else GameData.getMainQuestDataMap().put(id,value);});
            oldQuests.forEach((id,value)->{if(value==null)GameData.getQuestDataMap().remove(id);else GameData.getQuestDataMap().put(id,value);});
            if(oldGadget==null)GameData.getGadgetDataMap().remove(70350081);else GameData.getGadgetDataMap().put(70350081,oldGadget);
            oldPasses.forEach((id,value)->{if(value==null)GameData.getDungeonPassConfigDataMap().remove(id);else GameData.getDungeonPassConfigDataMap().put(id,value);});
        } finally {
            var activities=activityConfigs();var schedules=ActivityManager.getScheduleActivityConfigMap();
            activities.clear();activities.putAll(oldActivityConfigs);
            schedules.clear();schedules.putAll(oldScheduleConfigs);
        }
    }
    @SuppressWarnings("unchecked")
    private static Map<Integer,ActivityConfigItem> activityConfigs() throws Exception {
        var field=ActivityManager.class.getDeclaredField("activityConfigItemMap");field.setAccessible(true);
        return (Map<Integer,ActivityConfigItem>)field.get(null);
    }
    private static void drain() throws Exception { QuestManager.eventExecutor.submit(()->{}).get(10,TimeUnit.SECONDS); }
    private TrialPlayer player(int id) throws Exception {
        var p=new TrialPlayer(); players.add(p); var trial=QuestActivityCatalog.installed().trial(id);
        int scene=trial==null?3:trial.scene(),parent=trial==null?id/100:trial.parent();
        var world=TestWorld.create(p); p.setScene(new TestWorld.TestScene(world,scene,scene==3?SceneType.SCENE_WORLD:SceneType.SCENE_ROOM));
        p.setSceneId(scene); p.setSceneLoadState(Player.SceneLoadState.LOADED);
        if(trial!=null&&trial.position()!=null)p.getPosition().set(trial.position());
        p.main=new Main(p,parent);
        for(var data:nativeQuests.values())if(data.getMainId()==parent)p.main.getChildQuests().put(data.getId(),new Quest(p.main,data));
        p.getQuestManager().getMainQuests().put(parent,p.main);
        activate(p,id); return p;
    }
    private Quest activate(TrialPlayer p,int id) {
        var q=(Quest)p.main.getChildQuestById(id); q.setState(QuestState.QUEST_STATE_UNFINISHED);q.setStartTime(500);
        QuestActivityRuntime.onQuestStart(q);return q;
    }
    private static void click(TrialPlayer p,int slot) throws Exception {
        QuestActivityRuntime.onTick(p);var saved=p.getQuestActivityState().getTrials().get(p.main.getActiveQuests().stream()
                .filter(q->!QuestActivityCatalog.installed().trial(q.getSubQuestId()).observer()).findFirst().orElseThrow().getSubQuestId());
        var actor=saved.getActors().get(slot);p.getPosition().set(actor.getPosition());
        new HandlerSelectWorktopOptionReq().handle(new Session(p),new byte[0],SelectWorktopOptionReq.newBuilder().setGadgetEntityId(actor.getId()).setOptionId(7).build().toByteArray());
    }
    private static void unit(TrialPlayer p,int id) throws Exception {
        QuestActivityRuntime.onTick(p);var saved=p.getQuestActivityState().getTrials().get(id);
        while(saved.getStep()<4){int before=saved.getStep();click(p,saved.getSequence().get(before));if(saved.getStep()==0)break;}
        drain();
    }
    @Test void everyAllowListedOrdinaryGoalNeedsActualOrderedActionsAndOriginalCounts() throws Exception {
        assertEquals(74,QuestActivityCatalog.installed().trials().size());int played=0;
        for(var trial:QuestActivityCatalog.installed().trials()) {
            if(trial.observer()||trial.kind().equals("choice"))continue;
            var p=player(trial.quest());var q=p.main.getChildQuestById(trial.quest());
            if(trial.kind().equals("alchemy")){for(int id:List.of(1,4,16))p.getQuestActivityState().getCrops().put(id,10);}
            int rounds=trial.goals().stream().mapToInt(QuestActivityCatalog.Goal::count).sum();
            for(int round=0;round<rounds;round++){assertEquals(QuestState.QUEST_STATE_UNFINISHED,q.getState());unit(p,trial.quest());}
            assertEquals(QuestState.QUEST_STATE_FINISHED,q.getState(),"real original goal "+trial.quest());played++;
            QuestActivityRuntime.onLogout(p);
        }
        assertEquals(71,played);
    }
    @Test void wrongOrderDistantAndReplayedEntitiesCannotCountOrAcceptClientNumbers() throws Exception {
        var p=player(4008106);var q=p.main.getChildQuestById(4008106);QuestActivityRuntime.onTick(p);
        var saved=p.getQuestActivityState().getTrials().get(4008106);int expected=saved.getSequence().getFirst();
        click(p,(expected+1)%3);assertEquals(0,saved.getStep());assertTrue(saved.getCompleted().isEmpty());
        QuestActivityRuntime.onTick(p);var old=saved.getActors().get(expected);p.getPosition().setX(old.getPosition().getX()+100);
        assertTrue(QuestActivityRuntime.tryInteract(p,old,7));assertEquals(0,saved.getStep());
        p.getPosition().set(old.getPosition());assertTrue(QuestActivityRuntime.tryInteract(p,old,7));assertEquals(1,saved.getStep());
        assertFalse(QuestActivityRuntime.tryInteract(p,old,7));assertEquals(1,saved.getStep());
        new HandlerAddQuestContentProgressReq().handle(new Session(p),new byte[0],AddQuestContentProgressReq.newBuilder().setContentType(154).setParam(2017).build().toByteArray());
        drain();assertEquals(QuestState.QUEST_STATE_UNFINISHED,q.getState());
    }
    @Test void alchemyUsesOneTimeActualCropGrantAndConsumesAllThreeIngredients() throws Exception {
        var p=player(4014405);var q=p.main.getChildQuestById(4014405);var grant=p.main.getChildQuestById(4014402);
        assertTrue(QuestActivityRuntime.grantCrops(grant,"1,4,16","10,10,10"));assertTrue(QuestActivityRuntime.grantCrops(grant,"1,4,16","10,10,10"));
        assertEquals(10,p.getQuestActivityState().getCrops().get(1));
        unit(p,4014405);assertEquals(QuestState.QUEST_STATE_UNFINISHED,q.getState());assertEquals(9,p.getQuestActivityState().getCrops().get(1));
        unit(p,4014405);assertEquals(QuestState.QUEST_STATE_FINISHED,q.getState());
        for(int id:List.of(1,4,16))assertEquals(8,p.getQuestActivityState().getCrops().get(id));
        assertFalse(QuestActivityRuntime.grantCrops(q,"1,4,16","10,10,10"));
    }
    @Test void missingIngredientsKeepTheLastStepAndDoNotInventActivitySuccess() throws Exception {
        var p=player(4014403);QuestActivityRuntime.onTick(p);var saved=p.getQuestActivityState().getTrials().get(4014403);
        for(int i=0;i<4;i++)click(p,saved.getSequence().get(i));drain();
        assertEquals(3,saved.getStep());assertTrue(saved.getCompleted().isEmpty());assertEquals(QuestState.QUEST_STATE_UNFINISHED,p.main.getChildQuestById(4014403).getState());
    }
    @Test void explicitChoiceSettlesOnlyOneBranchAndItsOriginalObservers() throws Exception {
        for(int branch=0;branch<2;branch++){
            var p=player(4016708);activate(p,4016709);activate(p,4016712);click(p,branch);unit(p,4016708);
            assertEquals(QuestState.QUEST_STATE_FINISHED,p.main.getChildQuestById(4016708).getState());
            int winner=branch==0?4016709:4016712,loser=branch==0?4016712:4016709;
            assertEquals(QuestState.QUEST_STATE_FINISHED,p.main.getChildQuestById(winner).getState());
            assertEquals(QuestState.QUEST_STATE_FAILED,p.main.getChildQuestById(loser).getState());
            assertEquals(1,p.getQuestActivityState().getTrials().get(4016708).getCompleted().size());
        }
    }
    @Test void relogRetainsSequencePartialActionsAndCropBalancesWithoutRetainingSceneActors() throws Exception {
        var p=player(4014405);p.getQuestActivityState().getCrops().put(1,9);QuestActivityRuntime.onTick(p);
        var before=p.getQuestActivityState().getTrials().get(4014405);click(p,before.getSequence().getFirst());
        var copy=JsonUtils.decode(JsonUtils.encode(p.getQuestActivityState()),QuestActivityState.class);
        QuestActivityRuntime.onLogout(p);p.setQuestActivityState(copy);
        assertNull(p.main.resumeOnLogin());QuestActivityRuntime.onTick(p);
        var after=copy.getTrials().get(4014405);assertEquals(before.getSequence(),after.getSequence());assertEquals(1,after.getStep());
        assertEquals(9,copy.getCrops().get(1));assertEquals(3,after.getActors().size());assertNotSame(before.getActorScene(),after.getActorScene());
    }
    @Test void explicitActivityConditionOverridesReachActualActivityInfoAndKeepOtherConditions() throws Exception {
        var p=player(4008106);var main=new Main(p,40002);var q=new Quest(main,nativeQuests.get(4000202));
        assertTrue(QuestActivityRuntime.setCondition(q,true,"1001","10017"));
        var proto=ActivityInfo.newBuilder().addMeetCondList(8).addMeetCondList(10018);
        QuestActivityRuntime.applyConditionOverrides(p,1001,proto);assertEquals(Set.of(8,10017,10018),new HashSet<>(proto.getMeetCondListList()));
        assertTrue(QuestActivityRuntime.setCondition(q,false,"1001","10017"));
        QuestActivityRuntime.applyConditionOverrides(p,1001,proto);assertEquals(List.of(8,10018),proto.getMeetCondListList());
        assertFalse(QuestActivityRuntime.setCondition(q,true,"invalid","10017"));
    }
    private DungeonData dungeon(int id) throws Exception {
        var rows=JsonParser.parseString(Files.readString(FileUtils.getExcelPath("DungeonExcelConfigData.json"))).getAsJsonArray();
        var row=java.util.stream.StreamSupport.stream(rows.spliterator(),false).map(JsonElement::getAsJsonObject).filter(v->v.get("id").getAsInt()==id).findFirst().orElseThrow();
        var data=JsonUtils.decode(row,DungeonData.class);
        var passes=JsonParser.parseString(Files.readString(FileUtils.getExcelPath("DungeonPassExcelConfigData.json"))).getAsJsonArray();
        var p=java.util.stream.StreamSupport.stream(passes.spliterator(),false).map(JsonElement::getAsJsonObject).filter(v->v.get("id").getAsInt()==data.getPassCond()).findFirst().orElseThrow();
        var pass=JsonUtils.decode(p,DungeonPassConfigData.class);pass.onLoad();
        if(!oldPasses.containsKey(pass.getId()))oldPasses.put(pass.getId(),GameData.getDungeonPassConfigDataMap().get(pass.getId()));
        GameData.getDungeonPassConfigDataMap().put(pass.getId(),pass);return data;
    }
    @Test void allElevenOriginalRogueEntriesRequireAnActualStartedMatchingInstance() throws Exception {
        for(int id:ROGUE){
            var p=player(id);var q=p.main.getChildQuestById(id);int stage=nativeQuests.get(id).getFinishCond().getFirst().getParam()[0];
            var data=dungeon(stage==1?3017:stage==2?3018:3022);var saved=p.getQuestActivityState().getTrials().get(id);
            new HandlerAddQuestContentProgressReq().handle(new Session(p),new byte[0],AddQuestContentProgressReq.newBuilder().setContentType(141).setParam(stage).build().toByteArray());
            drain();assertEquals(QuestState.QUEST_STATE_UNFINISHED,q.getState());
            var scene=new TestWorld.TestScene(p.getWorld(),data.getSceneId(),SceneType.SCENE_DUNGEON);p.setScene(scene);p.setSceneId(scene.getId());scene.getPlayers().add(p);
            scene.setDungeonManager(new DungeonManager(scene,data));saved.setPendingDungeon(data.getId());
            QuestRogueStory.onDungeonStarted(scene);drain();assertEquals(QuestState.QUEST_STATE_UNFINISHED,q.getState());
            scene.getDungeonManager().startDungeon();drain();assertTrue(scene.getDungeonManager().isStarted());
            assertEquals(QuestState.QUEST_STATE_FINISHED,q.getState(),"native rogue entry "+id);
            assertEquals(0,saved.getPendingDungeon());assertEquals(1,saved.getCompleted().get("rogue:"+stage));
        }
    }
    @Test void anUnrequestedDungeonOrAnotherStageCannotInventRogueEntryProof() throws Exception {
        var p=player(4002202);var saved=p.getQuestActivityState().getTrials().get(4002202);var data=dungeon(3017);
        var scene=new TestWorld.TestScene(p.getWorld(),data.getSceneId(),SceneType.SCENE_DUNGEON);p.setScene(scene);p.setSceneId(scene.getId());scene.getPlayers().add(p);
        scene.setDungeonManager(new DungeonManager(scene,data));scene.getDungeonManager().startDungeon();drain();
        assertTrue(saved.getCompleted().isEmpty());saved.setPendingDungeon(3022);QuestRogueStory.onDungeonStarted(scene);drain();assertTrue(saved.getCompleted().isEmpty());
        assertEquals(QuestState.QUEST_STATE_UNFINISHED,p.main.getChildQuestById(4002202).getState());
    }
    @Test void onlyAKnownActuallyEndedScheduleCanFinishAnActivityStory() throws Exception {
        var player=player(4008106);
        player.getActivityManager();
        var field=ActivityManager.class.getDeclaredField("activityConfigItemMap");field.setAccessible(true);
        @SuppressWarnings("unchecked") var activities=(Map<Integer,emu.grasscutter.game.activity.ActivityConfigItem>)field.get(null);
        var schedules=ActivityManager.getScheduleActivityConfigMap();int id=2045001,activity=2045;var old=schedules.remove(id);var oldActivity=activities.remove(activity);
        try {
            var condition=JsonUtils.decode("{type:'QUEST_CONTENT_ACTIVITY_END',param:[2045001,0]}",QuestData.QuestContentCondition.class);
            assertFalse(QuestActivityRuntime.activityEnded(player,condition));
            var config=new emu.grasscutter.game.activity.ActivityConfigItem();config.setActivityId(activity);config.setScheduleId(id);config.setEndTime(new Date(System.currentTimeMillis()+60000));schedules.put(id,config);activities.put(activity,config);
            assertFalse(QuestActivityRuntime.activityEnded(player,condition));config.setEndTime(new Date(System.currentTimeMillis()-1000));assertTrue(QuestActivityRuntime.activityEnded(player,condition));
        } finally {if(old==null)schedules.remove(id);else schedules.put(id,old);if(oldActivity==null)activities.remove(activity);else activities.put(activity,oldActivity);}
    }
    private static final class Main extends GameMainQuest {
        Main(Player p,int id){super(p,id);}@Override public void save(){}@Override public void checkProgress(){}
    }
    private static final class Quest extends GameQuest {
        Quest(GameMainQuest m,QuestData d){super(m,d);}@Override public void save(){}@Override public void finish(){setState(QuestState.QUEST_STATE_FINISHED);}@Override public void fail(){setState(QuestState.QUEST_STATE_FAILED);}
    }
    private static final class TrialPlayer extends TestWorld.TestPlayer {
        final GameServer server;Main main;ActivityManager activity;
        TrialPlayer() throws Exception { Field f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);server=(GameServer)((Unsafe)f.get(null)).allocateInstance(GameServer.class);
            var system=GameServer.class.getDeclaredField("questSystem");system.setAccessible(true);system.set(server,new QuestSystem(server)); }
        @Override public GameServer getServer(){return server;}@Override public void save(){}
        @Override public void dropMessage(Object message){}
        @Override public ActivityManager getActivityManager(){if(activity==null)activity=new ActivityManager(this);return activity;}
    }
    private static final class Session extends GameSession {
        final Player player;Session(Player p){super(null);player=p;}@Override public Player getPlayer(){return player;}
        @Override public void send(emu.grasscutter.net.packet.BasePacket packet){}
    }
}
