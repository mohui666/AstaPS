package emu.grasscutter.game.achievement;

import static emu.grasscutter.net.proto.AchievementOuterClass.Achievement.Status.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.MainQuestData;
import emu.grasscutter.data.excels.achievement.AchievementData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.content.ContentServerState;
import emu.grasscutter.game.quest.enums.*;
import emu.grasscutter.game.systems.TestPlayers;
import emu.grasscutter.game.world.TestWorld;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.AddQuestContentProgressReqOuterClass.AddQuestContentProgressReq;
import emu.grasscutter.net.proto.AddQuestContentProgressBatchReq._AddQuestContentProgressBatchReq;
import emu.grasscutter.net.proto.QuestProgressInfo._QuestProgressInfo;
import emu.grasscutter.server.packet.recv.*;
import emu.grasscutter.server.game.*;
import emu.grasscutter.utils.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

@ExtendWith(ServerResourceFixture.class)
@ResourceLock("GameData")
final class QuestAchievementProgressTest {
    private final List<Runnable> cleanup=new ArrayList<>();

    @BeforeEach void data() throws Exception {
        for(var row:JsonParser.parseString(Files.readString(FileUtils.getResourcePath(
                "ExcelBinOutput/AchievementExcelConfigData.json"))).getAsJsonArray()) {
            var raw=row.getAsJsonObject();
            int id=raw.get("id").getAsInt();
            if(id==81230||id==81233) install(GameData.getAchievementDataMap(),id,JsonUtils.decode(raw,AchievementData.class));
        }
        install(GameData.getMainQuestDataMap(),804,JsonUtils.decode("{id:804,subQuests:[]}",MainQuestData.class));
    }
    @AfterEach void restore() throws Exception {drain();Collections.reverse(cleanup);cleanup.forEach(Runnable::run);}

    @Test void realAchievementCompletionRaisesTheNativeFailConditionWithoutInventingAMissing81233Record() throws Exception {
        var player=player(); var quest=quest(player); var failure=quest.getQuestData().getFailCond().getFirst();
        var data=GameData.getAchievementDataMap().get(81230);
        assertEquals(1,data.getProgress());
        assertArrayEquals(new String[]{"516","516001,516002,516003,516004,516005","",""},
                data.getTriggerConfig().getParamList());
        assertFalse(ContentServerState.matchesCurrent(quest,failure));
        assertFalse(ContentServerState.matchesCurrent(quest,quest.getQuestData().getFinishCond().getFirst()));
        player.saved.progress(81230,1); drain();
        assertEquals(Status_FINISHED,player.saved.getStatus(81230));
        assertTrue(ContentServerState.matchesCurrent(quest,failure));
        assertEquals(1,quest.failures); assertEquals(0,quest.finishes);
        player.saved.progress(81230,1); drain(); assertEquals(1,quest.failures);
        assertFalse(player.saved.isRewardTaken(81230));
    }

    @Test void actualFinishedAndClaimedStatesWaitUntilRealRevokeReturns81233ToUnfinished() throws Exception {
        var player=player();var quest=quest(player);var finish=quest.getQuestData().getFinishCond().getFirst();
        assertArrayEquals(new int[]{81233,1},finish.getParam());
        assertArrayEquals(new String[]{"6080,3,1","","",""},GameData.getAchievementDataMap().get(81233).getTriggerConfig().getParamList());
        assertEquals(Status_INVALID,player.saved.getStatus(81233));assertFalse(ContentServerState.matchesCurrent(quest,finish));
        player.saved.grant(81233);drain();assertEquals(Status_FINISHED,player.saved.getStatus(81233));
        assertEquals(0,quest.finishes);assertFalse(ContentServerState.matchesCurrent(quest,finish));
        player.saved.revoke(81233);drain();assertEquals(Status_UNFINISHED,player.saved.getStatus(81233));assertEquals(1,quest.finishes);
        assertFalse(player.saved.isRewardTaken(81233));
        assertFalse(ContentServerState.matchesCurrent(quest,JsonUtils.decode("{type:'QUEST_CONTENT_ACHIEVEMENT_STATE_EQUAL',param:[81230,1]}",QuestData.QuestContentCondition.class)));
        assertFalse(ContentServerState.matchesCurrent(quest,JsonUtils.decode("{type:'QUEST_CONTENT_ACHIEVEMENT_STATE_EQUAL',param:[81233,2]}",QuestData.QuestContentCondition.class)));

        var fresh=player();var next=quest(fresh);
        var claimed=new Gson().fromJson("{status:'Status_REWARD_TAKEN',id:81233,totalProgress:1,curProgress:1,finishTimestampSec:7,rewardClaimed:true}",Achievement.class);
        fresh.saved.getAchievementList().put(81233,claimed);
        assertFalse(ContentServerState.matchesCurrent(next,next.getQuestData().getFinishCond().getFirst()));
        fresh.saved.revoke(81233);drain();assertEquals(1,next.finishes);assertEquals(Status_UNFINISHED,claimed.getStatus());assertTrue(claimed.hasClaimedReward());
    }

    @Test void clientReportsCannotDrive165EvenWhenTrueButActualAdmissionAndLoginEvaluateIt() throws Exception {
        var player=player();var quest=quest(player);player.saved.getAchievement(81233);
        assertEquals(Status_UNFINISHED,player.saved.getStatus(81233));
        assertTrue(ContentServerState.matchesCurrent(quest,quest.getQuestData().getFinishCond().getFirst()));
        new HandlerAddQuestContentProgressReq().handle(player.getSession(),new byte[0],
                AddQuestContentProgressReq.newBuilder().setContentType(165).setParam(81233).build().toByteArray());
        new HandlerAddQuestContentProgressBatchReq().handle(player.getSession(),new byte[0],
                _AddQuestContentProgressBatchReq.newBuilder().addProgressInfoList(_QuestProgressInfo.newBuilder().setContentType(165).setParam(81233)).build().toByteArray());
        drain();assertEquals(0,quest.finishes);assertArrayEquals(new int[]{0},quest.getFinishProgressList());
        player.getQuestManager().checkQuestAlreadyFulfilled(quest);assertTrue(quest.settled.await(5,TimeUnit.SECONDS));drain();
        assertEquals(1,quest.finishes);assertEquals(0,quest.failures);assertEquals(Status_UNFINISHED,player.saved.getStatus(81233));

        var logged=player();var restored=quest(logged);assertEquals(Status_INVALID,logged.saved.getStatus(81233));
        logged.saved.onLogin(logged);drain();assertEquals(Status_UNFINISHED,logged.saved.getStatus(81233));
        assertEquals(1,restored.finishes);assertEquals(0,restored.failures);assertFalse(logged.saved.isRewardTaken(81233));
    }

    @Test void original81230FailureWinsWhenBothSavedConditionsAreTrueOnAdmission() throws Exception {
        var player=player();player.saved.getAchievement(81233);player.saved.grant(81230);drain();var quest=quest(player);
        assertEquals(Status_UNFINISHED,player.saved.getStatus(81233));assertTrue(player.saved.isFinished(81230));
        assertFalse(ContentServerState.matchesCurrent(quest,quest.getQuestData().getFinishCond().getFirst()));
        player.getQuestManager().checkQuestAlreadyFulfilled(quest);assertTrue(quest.settled.await(5,TimeUnit.SECONDS));drain();
        assertEquals(1,quest.failures);assertEquals(0,quest.finishes);assertEquals(QuestState.QUEST_STATE_FAILED,quest.getState());
    }

    @Test void persistedClaimedStateAndRealRevokeBulkRevokeAndRecompletionPreserveRewardOwnership() throws Exception {
        var player=player(); var quest=quest(player); var failure=quest.getQuestData().getFailCond().getFirst();
        var saved=new Gson().fromJson("{status:'Status_REWARD_TAKEN',id:81230,totalProgress:1,curProgress:1,finishTimestampSec:7,rewardClaimed:true}",Achievement.class);
        player.saved.getAchievementList().put(81230,saved);
        assertTrue(ContentServerState.matchesCurrent(quest,failure)); assertTrue(saved.hasClaimedReward());
        player.saved.revoke(81230); drain();
        assertFalse(ContentServerState.matchesCurrent(quest,failure)); assertEquals(0,quest.failures);
        assertTrue(saved.hasClaimedReward());
        player.saved.grant(81230); drain();
        assertEquals(Status_REWARD_TAKEN,saved.getStatus()); assertEquals(1,quest.failures); assertTrue(saved.hasClaimedReward());
        var next=quest(player);
        assertEquals(1,player.saved.revokeAll()); drain();
        assertFalse(ContentServerState.matchesCurrent(next,next.getQuestData().getFailCond().getFirst()));
        assertEquals(0,next.failures); assertTrue(saved.hasClaimedReward());
        player.saved.grantAll(); drain(); assertEquals(1,next.failures); assertTrue(saved.hasClaimedReward());
    }

    private StoryPlayer player() throws Exception {
        var player=new StoryPlayer();player.setUid(6901);var server=TestPlayers.server();
        var field=GameServer.class.getDeclaredField("questSystem");field.setAccessible(true);field.set(server,new QuestSystem(server));
        player.setSession(new RecordingSession(server,player));player.saved=new SavedAchievements(player);return player;
    }
    private TrackingQuest quest(StoryPlayer player) throws Exception {
        var parent=JsonParser.parseString(Files.readString(FileUtils.getResourcePath("BinOutput/Quest/804.json"))).getAsJsonObject();
        var row=java.util.stream.StreamSupport.stream(parent.getAsJsonArray("subQuests").spliterator(),false)
                .map(JsonElement::getAsJsonObject).filter(q->q.get("subId").getAsInt()==80402).findFirst().orElseThrow().deepCopy();
        row.addProperty("mainId",804); var data=JsonUtils.decode(row,QuestData.class);install(GameData.getQuestDataMap(),80402,data);
        var main=new GameMainQuest(player,804){@Override public void save(){}};
        var quest=new TrackingQuest(main,data);quest.setState(QuestState.QUEST_STATE_UNFINISHED);
        main.getChildQuests().put(80402,quest);player.getQuestManager().getMainQuests().put(804,main);return quest;
    }
    private <K,V> void install(Map<K,V> map,K key,V value){var old=map.put(key,value);cleanup.add(()->{if(old==null)map.remove(key);else map.put(key,old);});}
    private static void drain() throws Exception {QuestManager.eventExecutor.submit(()->{}).get(10,TimeUnit.SECONDS);}
    private static final class StoryPlayer extends TestWorld.TestPlayer {
        SavedAchievements saved;
        @Override public Achievements getAchievements(){return saved;}
        @Override public void save(){}
    }
    private static final class SavedAchievements extends Achievements {
        SavedAchievements(StoryPlayer player){super(null,player.getUid(),player,new HashMap<>(),0,new ArrayList<>());}
        @Override public void save(){}
    }
    private static final class TrackingQuest extends GameQuest {
        int finishes,failures;
        final CountDownLatch settled=new CountDownLatch(1);
        TrackingQuest(GameMainQuest main,QuestData data){super(main,data);}
        @Override public void save(){}
        @Override public void finish(){finishes++;setState(QuestState.QUEST_STATE_FINISHED);settled.countDown();}
        @Override public void fail(){failures++;setState(QuestState.QUEST_STATE_FAILED);settled.countDown();}
    }
    private static final class RecordingSession extends GameSession {
        final Player player;RecordingSession(GameServer server,Player player){super(server);this.player=player;}
        @Override public Player getPlayer(){return player;}
        @Override public void send(BasePacket packet){}
    }
}
