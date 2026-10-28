package emu.grasscutter.game.quest;

import static org.junit.jupiter.api.Assertions.*;
import static emu.grasscutter.config.Configuration.GAME_OPTIONS;

import com.google.gson.JsonParser;
import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.MainQuestData;
import emu.grasscutter.data.excels.RefreshPolicyExcelConfigData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.conditions.*;
import emu.grasscutter.game.quest.content.*;
import emu.grasscutter.game.quest.enums.*;
import emu.grasscutter.game.world.*;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.AddQuestContentProgressReqOuterClass.AddQuestContentProgressReq;
import emu.grasscutter.net.proto.AddQuestContentProgressBatchReq._AddQuestContentProgressBatchReq;
import emu.grasscutter.net.proto.QuestProgressInfo._QuestProgressInfo;
import emu.grasscutter.server.game.*;
import emu.grasscutter.server.packet.recv.*;
import emu.grasscutter.utils.*;
import java.nio.file.Files;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import sun.misc.Unsafe;

@ExtendWith(ServerResourceFixture.class)
@ResourceLock("GameData")
final class QuestTimeVarTest {
    private final List<Runnable> restore = new ArrayList<>();
    private boolean questing;
    private TimePlayer player;

    @BeforeEach void setup() throws Exception {
        questing = GAME_OPTIONS.questing.enabled;
        GAME_OPTIONS.questing.enabled = false;
        player = new TimePlayer(); player.setUid(89078);
        player.world = TimeWorld.create(player); player.setWorld(player.world);
        install(GameData.getRefreshPolicyExcelConfigDataMap(),1,JsonUtils.decode(
                "{id:1,type:'REFRESH_INTERVAL',time:'86400'}",RefreshPolicyExcelConfigData.class));
    }

    @AfterEach void release() throws Exception {
        drain();
        Collections.reverse(restore); restore.forEach(Runnable::run);
        GAME_OPTIONS.questing.enabled = questing;
    }

    private <K,V> void install(Map<K,V> map,K key,V value) {
        var previous = map.put(key,value);
        restore.add(()->{if(previous==null)map.remove(key);else map.put(key,previous);});
    }

    private TimeMain main(int id,long now) {
        install(GameData.getMainQuestDataMap(),id,JsonUtils.decode("{id:"+id+",subQuests:[]}",MainQuestData.class));
        var main = new TimeMain(player,id); main.now=now;
        player.manager.getMainQuests().put(id,main);
        return main;
    }

    private GameQuest quest(TimeMain main,int id,String finish,String fail,String begin,String accept) {
        var data = JsonUtils.decode("{mainId:"+main.getParentQuestId()+",subId:"+id+",finishCond:"+finish
                +",failCond:"+fail+",beginExec:"+begin+",finishExec:[],failExec:[],acceptCond:"+accept
                +",finishCondComb:'LOGIC_AND',failCondComb:'LOGIC_AND',acceptCondComb:'LOGIC_AND'}",QuestData.class);
        install(GameData.getQuestDataMap(),id,data);
        var quest = new GameQuest(main,data) {
            @Override public void finish(){setState(QuestState.QUEST_STATE_FINISHED);}
            @Override public void fail(){setState(QuestState.QUEST_STATE_FAILED);}
            @Override public void start(){setState(QuestState.QUEST_STATE_UNFINISHED);}
            @Override public void save(){}
        };
        quest.setState(QuestState.QUEST_STATE_UNFINISHED);
        main.getChildQuests().put(id,quest);
        return quest;
    }

    private static String condition(String type,int parent,int index,String amount) {
        return "[{type:'"+type+"',param:["+parent+","+index+"],param_str:'"+amount+"'}]";
    }

    @Test void newTimersPersistUnixSecondsAndCannotBeAdvancedByChangingGameTime() throws Exception {
        var main = main(73034,1_800_000_000L);
        assertTrue(main.initTimeVar(0)); drain();
        assertEquals(1_800_000_000L,main.getTimeVarUnixSeconds().get(0));
        assertEquals(-1,main.getTimeVar()[0]);
        assertEquals(1,main.saves);
        player.world.minutes += 1440 * 20;
        assertEquals(0,main.getHoursSinceTimeVar(0));
        assertEquals(0,main.getDaysSinceTimeVar(0));
        main.now += 3599;
        assertEquals(0,main.getHoursSinceTimeVar(0));
        main.now++;
        assertEquals(1,main.getHoursSinceTimeVar(0));
        var accept = JsonUtils.decode("{type:'QUEST_COND_TIME_VAR_GT_EQ',param:[73034,0,1]}",QuestData.QuestAcceptCondition.class);
        assertTrue(new ConditionTimeVarGreaterOrEqual().execute(player,null,accept,""));
        assertTrue(main.clearTimeVar(0)); drain();
        assertEquals(2,main.saves);
        assertEquals(-1,main.getHoursSinceTimeVar(0));
        assertEquals(-1,main.getDaysSinceTimeVar(0));
        assertTrue(main.getTimeVarUnixSeconds().isEmpty());
        assertFalse(player.getActiveQuestTimers().contains(73034));
        assertFalse(new ConditionTimeVarGreaterOrEqual().execute(player,null,accept,""));
    }

    @Test void nativeDayPassesAtConfiguredFourOClockBoundaryRatherThanMidnightOrTwentyFourElapsedHours() throws Exception {
        long beforeFour = LocalDate.of(2026,10,27).atTime(3,59,59).atZone(ZoneId.systemDefault()).toEpochSecond();
        var main = main(73034,beforeFour);
        main.initTimeVar(0); drain();
        assertEquals(0,main.getDaysSinceTimeVar(0));
        main.now++;
        assertEquals(1,main.getDaysSinceTimeVar(0));
        assertEquals(0,main.getHoursSinceTimeVar(0));
        main.clearTimeVar(0); drain();
        main.now = LocalDate.of(2026,10,27).atTime(23,59,59).atZone(ZoneId.systemDefault()).toEpochSecond();
        main.initTimeVar(0); drain();
        main.now++;
        assertEquals(0,main.getDaysSinceTimeVar(0));
    }

    @Test void legacySaveKeepsGameMinuteUnitsWithoutBeingMisreadAsEpochOrResetOnInit() throws Exception {
        var main = main(73034,1_800_000_000L);
        player.world.minutes = 1450;
        main.getTimeVar()[0] = 1440;
        assertTrue(main.initTimeVar(0)); drain();
        assertEquals(1440,main.getTimeVar()[0]);
        assertTrue(main.getTimeVarUnixSeconds().isEmpty());
        assertEquals(0,main.saves);
        assertEquals(0,main.getHoursSinceTimeVar(0));
        main.now += 86400 * 100;
        assertEquals(0,main.getDaysSinceTimeVar(0));
        player.world.minutes += 1440;
        assertEquals(1,main.getDaysSinceTimeVar(0));
        assertTrue(main.clearTimeVar(0)); drain();
        assertTrue(main.initTimeVar(0)); drain();
        assertEquals(-1,main.getTimeVar()[0]);
        assertEquals(main.now,main.getTimeVarUnixSeconds().get(0));
    }

    @Test void absentFutureInvalidAndMalformedTimersCannotSatisfyEvenAZeroThreshold() throws Exception {
        var main = main(73034,1_800_000_000L);
        var quest = quest(main,7303407,condition("QUEST_CONTENT_TIME_VAR_PASS_DAY",73034,0,"0"),"[]","[]","[]");
        var check = quest.getQuestData().getFinishCond().getFirst();
        assertFalse(QuestTimeConditions.matchesCurrent(quest,check));
        main.initTimeVar(0); drain(); main.now--;
        assertFalse(QuestTimeConditions.matchesCurrent(quest,check));
        assertFalse(main.initTimeVar(-1)); assertFalse(main.clearTimeVar(10));
        assertEquals(-1,main.getHoursSinceTimeVar(-1)); assertEquals(-1,main.getDaysSinceTimeVar(10));
        var malformed = JsonUtils.decode("{type:'QUEST_CONTENT_TIME_VAR_GT_EQ',param:[73034,-1],param_str:'NaN'}",
                QuestData.QuestContentCondition.class);
        assertFalse(QuestTimeConditions.matchesCurrent(quest,malformed));
        var accept = JsonUtils.decode("{type:'QUEST_COND_TIME_VAR_PASS_DAY',param:[73034,0,0]}",QuestData.QuestAcceptCondition.class);
        assertFalse(new ConditionTimeVarPassDay().execute(player,quest.getQuestData(),accept,""));
    }

    @Test void serializedTimerSurvivesOfflineElapsedTimeAndLoginRebuildsFinishedParentRegistrations() throws Exception {
        var owner = main(73055,1_800_000_000L);
        owner.finished = true;
        owner.initTimeVar(1); drain();
        // Serialize the owned storage fields, excluding transient live Player/QuestManager graphs.
        var stored = "{timeVar:"
                +JsonUtils.encode(owner.getTimeVar())+",timeVarUnixSeconds:"+JsonUtils.encode(owner.getTimeVarUnixSeconds())+"}";
        var fields = JsonUtils.decode(stored,TimerFields.class);
        assertNotNull(fields);
        var restored = new TimeMain(player,73055);
        System.arraycopy(fields.timeVar,0,restored.getTimeVar(),0,fields.timeVar.length);
        restored.getTimeVarUnixSeconds().putAll(fields.timeVarUnixSeconds);
        restored.now=owner.now+86400;
        restored.finished=true;
        player.manager.getMainQuests().put(73055,restored);
        player.getActiveQuestTimers().remove(73055);
        var waiting = main(73083,restored.now);
        var nativeRow = nativeQuest(7308329);
        var data = JsonUtils.decode(nativeRow,QuestData.class);
        assertEquals(73055,data.getFinishCond().getFirst().getParam()[0]);
        var quest = quest(waiting,7308329,nativeRow.getAsJsonArray("finishCond").toString(),"[]","[]","[]");
        assertEquals(1,restored.getDaysSinceTimeVar(1));
        player.manager.onLogin(); drain();
        assertTrue(player.getActiveQuestTimers().contains(73055));
        assertEquals(owner.getTimeVarUnixSeconds(),restored.getTimeVarUnixSeconds());
        assertEquals(QuestState.QUEST_STATE_FINISHED,quest.getState());
    }

    @Test void elapsedHourEventsFollowTheTimersStartSecondInsteadOfGlobalClockHours() throws Exception {
        var main = main(73034,1_800_002_220L);
        var quest = quest(main,7303407,condition("QUEST_CONTENT_TIME_VAR_GT_EQ",73034,0,"1"),"[]","[]","[]");
        main.initTimeVar(0); drain();
        main.now += 3599;
        player.manager.checkTimeVars(main.now,false); drain();
        assertEquals(QuestState.QUEST_STATE_UNFINISHED,quest.getState());
        main.now++;
        player.manager.checkTimeVars(main.now,false); drain();
        assertEquals(QuestState.QUEST_STATE_FINISHED,quest.getState());
    }

    @Test void activeTimerBeginExecAndNativeWaitingGoalArePreservedByActualMainLoginPath() throws Exception {
        var main = main(73034,1_800_000_000L);
        var waiting = quest(main,7303407,nativeQuest(7303407).getAsJsonArray("finishCond").toString(),"[]",
                "[{type:'QUEST_EXEC_INIT_TIME_VAR',param:['0']}]","[]");
        main.initTimeVar(0); drain();
        long start = main.getTimeVarUnixSeconds().get(0);
        main.now += 3600;
        assertNull(main.resumeOnLogin());
        drain();
        assertSame(waiting,main.getChildQuestById(7303407));
        assertEquals(start,main.getTimeVarUnixSeconds().get(0));
        assertEquals(QuestState.QUEST_STATE_UNFINISHED,waiting.getState());
    }

    @Test void exactIntervalRefreshPolicyUsesActualElapsedSecondsAndUnknownPoliciesCannotPass() throws Exception {
        var main = main(73034,1_800_000_000L);
        var quest = quest(main,7303407,condition("QUEST_CONTENT_TIME_VAR_PASS_REFRESH_POLICY",73034,0,"1"),"[]","[]","[]");
        main.initTimeVar(0); drain();
        main.now += 86399;
        assertFalse(new ContentTimeVarPassRefreshPolicy().execute(quest,quest.getQuestData().getFinishCond().getFirst(),""));
        main.now++;
        assertTrue(new ContentTimeVarPassRefreshPolicy().execute(quest,quest.getQuestData().getFinishCond().getFirst(),""));
        var unknown = JsonUtils.decode("{type:'QUEST_CONTENT_TIME_VAR_PASS_REFRESH_POLICY',param:[73034,0],param_str:'200004'}",
                QuestData.QuestContentCondition.class);
        assertFalse(QuestTimeConditions.matchesCurrent(quest,unknown));
    }

    @Test void nativeCalendarPoliciesUseTheNextConfiguredRealBoundaryAndRejectMalformedSchedules() {
        long monday = LocalDate.of(2026,10,26).atTime(3,59,59).atZone(ZoneId.systemDefault()).toEpochSecond();
        var daily = policy("REFRESH_DAILY", "14400;43200");
        assertFalse(QuestTimeConditions.hasRefreshed(daily, monday, monday));
        assertTrue(QuestTimeConditions.hasRefreshed(daily, monday, monday + 1));
        assertFalse(QuestTimeConditions.hasRefreshed(daily, monday + 1, monday + 2));
        assertFalse(QuestTimeConditions.hasRefreshed(daily, monday + 1, monday + 8 * 3600));
        assertTrue(QuestTimeConditions.hasRefreshed(daily, monday + 1, monday + 8 * 3600 + 1));
        var weekly = policy("REFRESH_WEEKlY", "14400");
        assertTrue(QuestTimeConditions.hasRefreshed(weekly, monday, monday + 1));
        assertFalse(QuestTimeConditions.hasRefreshed(weekly, monday + 1, monday + 86400 * 7));
        assertTrue(QuestTimeConditions.hasRefreshed(weekly, monday + 1, monday + 86400 * 7 + 1));
        long nearMidnight = LocalDate.of(2026,10,26).atTime(23,59,59).atZone(ZoneId.systemDefault()).toEpochSecond();
        assertTrue(QuestTimeConditions.hasRefreshed(policy("REFRESH_DAYBEGIN_INTERVAL", "1"), nearMidnight, nearMidnight + 1));
        assertFalse(QuestTimeConditions.hasRefreshed(policy("REFRESH_DAYBEGIN_INTERVAL", "3"), nearMidnight, nearMidnight + 2 * 86400));
        assertTrue(QuestTimeConditions.hasRefreshed(policy("REFRESH_DAYBEGIN_INTERVAL", "3"), nearMidnight, nearMidnight + 2 * 86400 + 1));
        assertFalse(QuestTimeConditions.hasRefreshed(policy("REFRESH_DAILY", "0"), nearMidnight, nearMidnight));
        assertTrue(QuestTimeConditions.hasRefreshed(policy("REFRESH_DAILY", "0"), nearMidnight, nearMidnight + 1));
        assertTrue(QuestTimeConditions.hasRefreshed(policy("REFRESH_DAILY", "14400;0"), nearMidnight, nearMidnight + 1));
        assertFalse(QuestTimeConditions.hasRefreshed(policy("REFRESH_INTERVAL", "0"), monday, monday + 86400));
        for (String time : List.of("", "-1", "86400", "14400;", "NaN"))
            assertFalse(QuestTimeConditions.hasRefreshed(policy("REFRESH_DAILY", time), monday, monday + 86400 * 30));
        assertFalse(QuestTimeConditions.hasRefreshed(policy("REFRESH_INTERVAL", "1;2"), monday, monday + 86400));
        assertFalse(QuestTimeConditions.hasRefreshed(daily, 0, monday));
        assertFalse(QuestTimeConditions.hasRefreshed(daily, monday, monday - 1));
    }

    @Test void nativeCalendarQuestCannotBeAdvancedByGameClockAndFinishesThroughRealTimerChecks() throws Exception {
        long beforeFour = LocalDate.of(2026,10,27).atTime(3,59,59).atZone(ZoneId.systemDefault()).toEpochSecond();
        install(GameData.getRefreshPolicyExcelConfigDataMap(), 101000, policy("REFRESH_DAILY", "14400"));
        var main = main(73034, beforeFour);
        var waiting = quest(main,7303407,condition("QUEST_CONTENT_TIME_VAR_PASS_REFRESH_POLICY",73034,0,"101000"),"[]","[]","[]");
        main.initTimeVar(0); drain();
        player.world.minutes += 1440 * 100;
        player.manager.checkTimeVars(main.now, true); drain();
        assertEquals(QuestState.QUEST_STATE_UNFINISHED, waiting.getState());
        main.now++;
        player.manager.checkTimeVars(main.now, true); drain();
        assertEquals(QuestState.QUEST_STATE_FINISHED, waiting.getState());
    }

    @Test void allNineExplicitMissingPolicyGoalsWaitForTheNextServerDayUsingTheirActualNativeTimerOwner() throws Exception {
        long beforeFour = LocalDate.of(2026,10,27).atTime(3,59,59).atZone(ZoneId.systemDefault()).toEpochSecond();
        for (int id : List.of(7604603,7621501,7621601,7621701,7765905,7766005,7766105,7766205,7766305)) {
            var row = nativeQuest(id);
            var check = JsonUtils.decode(row.getAsJsonArray("finishCond").get(0),QuestData.QuestContentCondition.class);
            var owner = main(check.getParam()[0],beforeFour);
            var main = owner.getParentQuestId() == id / 100 ? owner : main(id / 100,beforeFour);
            var waiting = quest(main,id,row.getAsJsonArray("finishCond").toString(),"[]","[]","[]");
            assertFalse(QuestTimeConditions.matchesCurrent(waiting,check));
            owner.initTimeVar(check.getParam()[1]); drain();
            assertFalse(QuestTimeConditions.matchesCurrent(waiting,check));
            player.world.minutes += 1440 * 100;
            assertFalse(QuestTimeConditions.matchesCurrent(waiting,check));
            owner.now++;
            assertTrue(QuestTimeConditions.matchesCurrent(waiting,check));
            assertNull(main.resumeOnLogin());
            assertTrue(QuestTimeConditions.matchesCurrent(waiting,check));
        }
    }

    @Test void reconstructionCannotUnlockOtherQuestsWrongTimerOwnersOrLegacyGameMinuteTimers() throws Exception {
        long beforeFour = LocalDate.of(2026,10,27).atTime(3,59,59).atZone(ZoneId.systemDefault()).toEpochSecond();
        var main = main(76046,beforeFour);
        var waiting = quest(main,7604603,condition("QUEST_CONTENT_TIME_VAR_PASS_REFRESH_POLICY",76046,1,"990210"),"[]","[]","[]");
        main.getTimeVar()[1] = 0;
        player.world.minutes = 1440 * 100;
        main.now += 86400;
        assertFalse(QuestTimeConditions.matchesCurrent(waiting,waiting.getQuestData().getFinishCond().getFirst()));
        main.clearTimeVar(1); drain(); main.initTimeVar(1); drain(); main.now += 86400;
        assertTrue(QuestTimeConditions.matchesCurrent(waiting,waiting.getQuestData().getFinishCond().getFirst()));
        for (String json : List.of(
                "{type:'QUEST_CONTENT_TIME_VAR_PASS_REFRESH_POLICY',param:[76046,0],param_str:'990210'}",
                "{type:'QUEST_CONTENT_TIME_VAR_PASS_REFRESH_POLICY',param:[76046,1],param_str:'200004'}",
                "{type:'QUEST_CONTENT_TIME_VAR_PASS_REFRESH_POLICY',param:[76046,1],param_str:'9999999'}"))
            assertFalse(QuestTimeConditions.matchesCurrent(waiting,JsonUtils.decode(json,QuestData.QuestContentCondition.class)));
        var foreign = quest(main,7604699,condition("QUEST_CONTENT_TIME_VAR_PASS_REFRESH_POLICY",76046,1,"990210"),"[]","[]","[]");
        assertFalse(QuestTimeConditions.matchesCurrent(foreign,foreign.getQuestData().getFinishCond().getFirst()));
    }

    private static RefreshPolicyExcelConfigData policy(String type, String time) {
        return JsonUtils.decode("{id:101000,type:'" + type + "',time:'" + time + "'}", RefreshPolicyExcelConfigData.class);
    }

    @Test void realTimeConditionsRejectBothSingleAndBatchClientCompletionReports() throws Exception {
        var main = main(73034,1_800_000_000L);
        var quest = quest(main,7303407,condition("QUEST_CONTENT_TIME_VAR_GT_EQ",73034,0,"1"),"[]","[]","[]");
        main.initTimeVar(0); drain();
        var session = new GameSession(null) {
            @Override public Player getPlayer(){return player;}
            @Override public void send(BasePacket packet){}
        };
        for (var type : List.of(QuestContent.QUEST_CONTENT_TIME_VAR_GT_EQ,QuestContent.QUEST_CONTENT_TIME_VAR_PASS_DAY,
                QuestContent.QUEST_CONTENT_TIME_VAR_PASS_REFRESH_POLICY)) {
            new HandlerAddQuestContentProgressReq().handle(session,new byte[0],AddQuestContentProgressReq.newBuilder()
                    .setContentType(type.getValue()).setParam(73034).build().toByteArray());
            new HandlerAddQuestContentProgressBatchReq().handle(session,new byte[0],_AddQuestContentProgressBatchReq.newBuilder()
                    .addProgressInfoList(_QuestProgressInfo.newBuilder().setContentType(type.getValue()).setParam(73034))
                    .build().toByteArray());
        }
        drain();
        assertArrayEquals(new int[]{0},quest.getFinishProgressList());
        assertEquals(QuestState.QUEST_STATE_UNFINISHED,quest.getState());
    }

    private static com.google.gson.JsonObject nativeQuest(int id) throws Exception {
        var parent = JsonParser.parseString(Files.readString(FileUtils.getResourcePath("BinOutput/Quest/"+id/100+".json"))).getAsJsonObject();
        var row = java.util.stream.StreamSupport.stream(parent.getAsJsonArray("subQuests").spliterator(),false)
                .map(value->value.getAsJsonObject()).filter(q->q.get("subId").getAsInt()==id).findFirst().orElseThrow().deepCopy();
        row.addProperty("mainId",id/100);
        return row;
    }

    private static void drain() throws Exception {QuestManager.eventExecutor.submit(()->{}).get(5,TimeUnit.SECONDS);}

    private static final class TimeMain extends GameMainQuest {
        transient long now;
        transient int saves;
        transient boolean finished;
        TimeMain(){}
        TimeMain(Player player,int id){super(player,id);}
        @Override protected long timeVarNowSeconds(){return now;}
        @Override public boolean isFinished(){return finished;}
        @Override public void save(){saves++;}
        @Override public void checkProgress(){}
    }
    private static final class TimerFields {
        long[] timeVar;
        Map<Integer,Long> timeVarUnixSeconds;
    }
    private static final class TimePlayer extends TestWorld.TestPlayer {
        final QuestManager manager = new QuestManager(this);
        TimeWorld world;
        @Override public QuestManager getQuestManager(){return manager;}
        @Override public GameServer getServer(){return world.server;}
        @Override public void save(){}
    }
    private static final class TimeWorld extends World {
        Player owner;
        GameServer server;
        long minutes;
        TimeWorld(){super((Player)null);}
        static TimeWorld create(Player player) throws Exception {
            var field=Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);var unsafe=(Unsafe)field.get(null);
            var world=(TimeWorld)unsafe.allocateInstance(TimeWorld.class);world.owner=player;
            world.server=(GameServer)unsafe.allocateInstance(GameServer.class);
            var quests=GameServer.class.getDeclaredField("questSystem");quests.setAccessible(true);quests.set(world.server,new QuestSystem(world.server));
            return world;
        }
        @Override public Player getHost(){return owner;}
        @Override public GameServer getServer(){return server;}
        @Override public long getTotalGameTimeMinutes(){return minutes;}
        @Override public long getTotalGameTimeHours(){return minutes/60;}
        @Override public long getTotalGameTimeDays(){return minutes/1440;}
    }
}
