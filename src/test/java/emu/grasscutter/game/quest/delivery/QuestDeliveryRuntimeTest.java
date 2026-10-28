package emu.grasscutter.game.quest.delivery;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.MainQuestData;
import emu.grasscutter.data.excels.GadgetData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.entity.gadget.GadgetWorktop;
import emu.grasscutter.game.player.*;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.content.ContentServerState;
import emu.grasscutter.game.quest.enums.*;
import emu.grasscutter.game.systems.TestPlayers;
import emu.grasscutter.game.world.*;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.AddQuestContentProgressReqOuterClass.AddQuestContentProgressReq;
import emu.grasscutter.net.proto.AddQuestContentProgressBatchReq._AddQuestContentProgressBatchReq;
import emu.grasscutter.net.proto.QuestProgressInfo._QuestProgressInfo;
import emu.grasscutter.net.proto.SelectWorktopOptionReqOuterClass.SelectWorktopOptionReq;
import emu.grasscutter.scripts.ScriptLoader;
import emu.grasscutter.server.game.*;
import emu.grasscutter.server.packet.recv.*;
import emu.grasscutter.utils.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

@ExtendWith(ServerResourceFixture.class) @ResourceLock("GameData")
final class QuestDeliveryRuntimeTest {
    private final List<Runnable> cleanup=new ArrayList<>();private GameServer server;
    @BeforeAll static void scripts()throws Exception{ScriptLoader.init();}
    @BeforeEach void load()throws Exception{
        server=TestPlayers.server();var f=GameServer.class.getDeclaredField("questSystem");f.setAccessible(true);f.set(server,new QuestSystem(server));
        for(var row:JsonParser.parseString(Files.readString(FileUtils.getResourcePath("ExcelBinOutput/GadgetExcelConfigData.json"))).getAsJsonArray()){
            var raw=row.getAsJsonObject();if(raw.get("id").getAsInt()==70350081)install(GameData.getGadgetDataMap(),70350081,JsonUtils.decode(raw,GadgetData.class));
        }
    }
    @AfterEach void restore()throws Exception{drain();Collections.reverse(cleanup);cleanup.forEach(Runnable::run);}
    @Test void reconstructionCoversOnlyTwentyOneActual5006FailureGoals()throws Exception{
        assertEquals(21,QuestDeliveryCatalog.entries().size());assertNull(QuestDeliveryCatalog.entry(4112101));
        for(var entry:QuestDeliveryCatalog.entries()){
            var p=player();var q=quest(p,entry.quest());assertEquals(entry.parent(),q.getMainQuestId());
            assertTrue(q.getQuestData().getFailCond().stream().anyMatch(c->c.getType()==QuestContent.QUEST_CONTENT_ACTIVITY_TRIGGER_FAILED&&Arrays.equals(new int[]{5006,0},c.getParam())));
            assertEquals(180,entry.seconds());assertFalse(entry.prohibited().isEmpty());
        }
        assertTrue(QuestDeliveryCatalog.entry(4112102).prohibited().contains("TRIGGER_DASH"));
        assertFalse(QuestDeliveryCatalog.entry(4115302).prohibited().contains("TRIGGER_CUR_AVATAR_HURT"));
    }
    @Test void elapsedDeadlineSurvivesFreshPlayerAndLoginWithoutRestartingTheAttempt()throws Exception{
        var p=player();var q=quest(p,4112102);q.start();drain();
        var round=p.getQuestDeliveryState().getRounds().get(4112102);assertFalse(round.isFailed());
        round.setDeadline(System.currentTimeMillis()-1);
        String stored=new Gson().toJson(p.getQuestDeliveryState());var fresh=player();
        var field=Player.class.getDeclaredField("questDeliveryState");field.setAccessible(true);field.set(fresh,new Gson().fromJson(stored,QuestDeliveryState.class));
        var resumed=quest(fresh,4112102);resumed.setStartTime(q.getStartTime());resumed.setState(QuestState.QUEST_STATE_UNFINISHED);
        assertNull(resumed.getMainQuest().resumeOnLogin());assertEquals(q.getStartTime(),resumed.getStartTime());
        QuestDeliveryRuntime.onTick(fresh);drain();assertEquals(1,resumed.failures);
        assertEquals("timeout",fresh.getQuestDeliveryState().getRounds().get(4112102).getFailure());
    }
    @Test void actualAbandonWorktopRejectsWrongOptionAndOldAttemptThenFailsOnlyTheCurrentRound()throws Exception{
        var p=player();var q=quest(p,4112102);q.start();drain();QuestDeliveryRuntime.onTick(p);
        var round=p.getQuestDeliveryState().getRounds().get(4112102);var actor=round.getAbandon();assertNotNull(actor);
        var content=(GadgetWorktop)actor.getContent();
        assertFalse(content.onSelectWorktopOption(SelectWorktopOptionReq.newBuilder().setOptionId(8).build()));
        assertTrue(content.onSelectWorktopOption(SelectWorktopOptionReq.newBuilder().setOptionId(9).build()));drain();
        assertEquals(1,q.failures);assertEquals("abandon",round.getFailure());assertFalse(p.getScene().getEntities().containsKey(actor.getId()));
        q.start();drain();assertFalse(p.getQuestDeliveryState().getRounds().get(4112102).isFailed());
        assertFalse(content.onSelectWorktopOption(SelectWorktopOptionReq.newBuilder().setOptionId(9).build()));
        assertEquals(1,q.failures);
    }
    @Test void configuredRealHurtEventFailsOnlyItsOwnTaskAndClient127CannotInventIt()throws Exception{
        var p=player();var q=quest(p,4112102);q.start();drain();
        new HandlerAddQuestContentProgressReq().handle(p.getSession(),new byte[0],AddQuestContentProgressReq.newBuilder().setContentType(127).setParam(5006).build().toByteArray());
        new HandlerAddQuestContentProgressBatchReq().handle(p.getSession(),new byte[0],_AddQuestContentProgressBatchReq.newBuilder().addProgressInfoList(_QuestProgressInfo.newBuilder().setContentType(127).setParam(5006)).build().toByteArray());
        drain();assertEquals(0,q.failures);assertFalse(ContentServerState.matchesCurrent(q,q.getQuestData().getFailCond().getFirst()));
        QuestDeliveryRuntime.onAvatarHurt(p);drain();assertEquals(1,q.failures);
        var allowed=player();var second=quest(allowed,4115302);second.start();drain();
        QuestDeliveryRuntime.onAvatarHurt(allowed);drain();assertEquals(0,second.failures);
    }
    private StoryPlayer player(){
        var p=new StoryPlayer();p.setUid(7301);p.setSession(new RecordingSession(server,p));p.setSceneId(3);p.setSceneLoadState(Player.SceneLoadState.LOADED);
        var world=new World(p,false);world.getPlayers().add(p);p.setWorld(world);
        var scene=new TestWorld.TestScene(world,3,emu.grasscutter.game.props.SceneType.SCENE_WORLD);world.getScenes().put(3,scene);scene.getPlayers().add(p);p.setScene(scene);return p;
    }
    private TrackingQuest quest(StoryPlayer p,int id)throws Exception{
        var parent=JsonParser.parseString(Files.readString(FileUtils.getResourcePath("BinOutput/Quest/"+id/100+".json"))).getAsJsonObject();
        var row=java.util.stream.StreamSupport.stream(parent.getAsJsonArray("subQuests").spliterator(),false).map(JsonElement::getAsJsonObject).filter(q->q.get("subId").getAsInt()==id).findFirst().orElseThrow().deepCopy();
        // Isolate the newly owned failure predicate; item loss/plot failure retain their separate existing tests.
        var failure=new JsonArray();for(var c:row.getAsJsonArray("failCond"))if(c.getAsJsonObject().get("type").getAsString().equals("QUEST_CONTENT_ACTIVITY_TRIGGER_FAILED"))failure.add(c);
        row.add("failCond",failure);for(String name:List.of("beginExec","finishExec","failExec","acceptCond"))row.add(name,new JsonArray());row.addProperty("mainId",id/100);
        var data=JsonUtils.decode(row,QuestData.class);install(GameData.getQuestDataMap(),id,data);install(GameData.getMainQuestDataMap(),id/100,JsonUtils.decode("{id:"+id/100+",subQuests:[]}",MainQuestData.class));
        var main=new GameMainQuest(p,id/100){@Override public void save(){}};var q=new TrackingQuest(main,data);main.getChildQuests().put(id,q);p.getQuestManager().getMainQuests().put(id/100,main);return q;
    }
    private <K,V>void install(Map<K,V>map,K key,V value){var old=map.put(key,value);cleanup.add(()->{if(old==null)map.remove(key);else map.put(key,old);});}
    private static void drain()throws Exception{QuestManager.eventExecutor.submit(()->{}).get(10,TimeUnit.SECONDS);}
    private static final class StoryPlayer extends TestWorld.TestPlayer{final TeamManager team=new TeamManager(this);final List<String> messages=new ArrayList<>();@Override public TeamManager getTeamManager(){return team;}@Override public void dropMessage(Object message){messages.add(message.toString());}@Override public void save(){}}
    private static final class RecordingSession extends GameSession{final Player player;RecordingSession(GameServer s,Player p){super(s);player=p;}@Override public Player getPlayer(){return player;}@Override public void send(BasePacket p){}}
    private static final class TrackingQuest extends GameQuest{int failures;TrackingQuest(GameMainQuest m,QuestData d){super(m,d);}@Override public void fail(){failures++;setState(QuestState.QUEST_STATE_FAILED);}@Override public void save(){}}
}
