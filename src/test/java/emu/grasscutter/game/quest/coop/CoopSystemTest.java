package emu.grasscutter.game.quest.coop;

import static org.junit.jupiter.api.Assertions.*;
import static emu.grasscutter.config.Configuration.GAME_OPTIONS;

import com.google.gson.*;
import com.google.protobuf.*;
import com.mongodb.client.result.DeleteResult;
import dev.morphia.Datastore;
import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.MainQuestData;
import emu.grasscutter.data.binout.ScenePointEntry;
import emu.grasscutter.data.common.PointData;
import emu.grasscutter.data.excels.*;
import emu.grasscutter.data.excels.dungeon.DungeonData;
import emu.grasscutter.data.excels.dungeon.DungeonPassConfigData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.database.DatabaseHelper;
import emu.grasscutter.database.DatabaseManager;
import emu.grasscutter.game.inventory.*;
import emu.grasscutter.game.dungeons.DungeonManager;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.PlayerProperty;
import emu.grasscutter.game.props.SceneType;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.*;
import emu.grasscutter.game.quest.persistent.QuestPersistentDungeonRuntime;
import emu.grasscutter.game.quest.persistent.QuestPersistentDungeonState;
import emu.grasscutter.game.world.Scene;
import emu.grasscutter.game.world.TestWorld;
import emu.grasscutter.game.world.World;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.AddQuestContentProgressReqOuterClass.AddQuestContentProgressReq;
import emu.grasscutter.net.proto.AddQuestContentProgressBatchReq._AddQuestContentProgressBatchReq;
import emu.grasscutter.net.proto.QuestProgressInfo._QuestProgressInfo;
import emu.grasscutter.net.proto.CoopDataNotifyOuterClass.CoopDataNotify;
import emu.grasscutter.net.proto.MainCoopUpdateNotifyOuterClass.MainCoopUpdateNotify;
import emu.grasscutter.server.game.*;
import emu.grasscutter.server.packet.recv.*;
import emu.grasscutter.scripts.ScriptLoader;
import emu.grasscutter.utils.*;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import sun.misc.Unsafe;

/** Real owned-run/request paths; only persistence and the unrelated dialogue/scene fixture are replaced. */
@ExtendWith(ServerResourceFixture.class)
@ResourceLock("GameData")
@ResourceLock("DatabaseManager")
final class CoopSystemTest {
    private static final int CHAPTER = 101401, START = 10140101;
    private final List<Runnable> restore = new ArrayList<>();
    private final Map<Integer, QuestData> prepared = new HashMap<>();
    private final List<GameMainQuest> deleted = Collections.synchronizedList(new ArrayList<>());
    private final List<Scene> scenes = new ArrayList<>();
    private boolean questing;

    @BeforeAll static void scripts() throws Exception { ScriptLoader.init(); }

    @BeforeEach void setup() throws Exception {
        questing = GAME_OPTIONS.questing.enabled;
        GAME_OPTIONS.questing.enabled = false;
        installMemoryDatastore();
        for (var row : rows("CoopChapterExcelConfigData")) {
            var data = JsonUtils.decode(row,CoopChapterData.class);
            install(GameData.getCoopChapterDataMap(),data.getId(),data);
        }
        for (var row : rows("CoopPointExcelConfigData")) {
            var data = JsonUtils.decode(row,CoopPointData.class);
            install(GameData.getCoopPointDataMap(),data.getId(),data);
        }
        for (int id : List.of(39604,1900101,1900102,1900103,1900104,1900105,1900106,1900107,
                1900108,1900202,1900203,1900204,1900905,1900906,1900907,1904403,1904404,1904409,1904411,
                1904501,1904502))
            prepare(id);
    }

    @AfterEach void cleanup() throws Exception {
        scenes.forEach(scene->scene.getScriptManager().onDestroy());
        drain();
        try (var ignored=databaseBarrier()) {
            Collections.reverse(restore); restore.forEach(Runnable::run);
            GAME_OPTIONS.questing.enabled = questing;
        }
    }

    private <K,V> void install(Map<K,V> map, K key, V value) {
        var previous=map.put(key,value);
        restore.add(()->{if(previous==null)map.remove(key);else map.put(key,previous);});
    }

    @Test void nineteenOriginalConfigurationsAndAllCurrentQuestSaveReferencesLoadWithoutDroppingTargets() throws Exception {
        var mainRows=rows("MainCoopExcelConfigData");
        assertEquals(19,mainRows.size());
        var ids=new HashSet<Integer>();
        for (var value:mainRows) {
            var row=value.getAsJsonObject(); int id=row.get("id").getAsInt(); ids.add(id);
            var original=read("BinOutput/Coop/"+row.get("mainCoopCfg").getAsString()+".json").getAsJsonObject();
            var chapter=CoopCatalog.chapter(id);
            assertNotNull(chapter,"chapter "+id);
            assertEquals(original.get("confidenceValue").getAsInt(),chapter.initialConfidence());
            assertEquals(original.get("maxConfidenceValue").getAsInt(),chapter.maxConfidence());
            for (var point:original.getAsJsonObject("savePointMap").entrySet()) {
                int key=Integer.parseInt(point.getKey()); var data=point.getValue().getAsJsonObject();
                assertTrue(chapter.savePoints().containsKey(key),id+"/"+key);
                assertEquals(data.has("isEnd")&&data.get("isEnd").getAsBoolean(),chapter.isEnd(key));
            }
            if (original.has("coopTempValueMap")) for (var entry:original.getAsJsonObject("coopTempValueMap").entrySet()) {
                int key=Integer.parseInt(entry.getKey()); var variable=entry.getValue().getAsJsonObject();
                assertTrue(chapter.tempVars().containsKey(key));
                assertFalse(chapter.normalVars().containsKey(key),"isCoopVar does not select normal_var_map");
                assertEquals(variable.has("isCoopVar")&&variable.get("isCoopVar").getAsBoolean(),
                        chapter.retainedTempVars().contains(key));
            }
        }
        int references=0;
        try (var paths=Files.list(FileUtils.getResourcePath("BinOutput/Quest"))) {
            for (var path:paths.filter(p->p.toString().endsWith(".json")).toList()) {
                var parent=JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                if (!parent.has("subQuests")) continue;
                for (var value:parent.getAsJsonArray("subQuests")) {
                    var quest=value.getAsJsonObject();
                    for (String list:List.of("finishCond","failCond")) {
                        if (!quest.has(list)) continue;
                        for (var condition:quest.getAsJsonArray(list)) {
                            var c=condition.getAsJsonObject();
                            if (!c.has("type")||!c.get("type").getAsString().equals("QUEST_CONTENT_MAIN_COOP_ENTER_SAVE_POINT")) continue;
                            var p=c.getAsJsonArray("param"); int id=p.get(0).getAsInt(), save=p.get(1).getAsInt();
                            if (!ids.contains(id)) continue;
                            references++;
                            assertTrue(CoopCatalog.chapter(id).savePoints().containsKey(save),
                                    quest.get("subId").getAsInt()+" references missing save "+id+"/"+save);
                        }
                    }
                }
            }
        }
        assertEquals(665,references); // 663 recovered originals and two explicitly reconstructed 7.1 waits.
    }

    @Test void realUnlockRequiresTheNativeQuestLevelAndKeyCostAndNeverChargesTwice() throws Exception {
        var player=player(); player.level=25; player.addLegendaryKey(3);
        assertEquals(CoopSystem.CLOSED,CoopSystem.unlock(player,CHAPTER));
        finished(player,39604); // Actual installed prerequisite; still below adventure rank 26.
        assertEquals(CoopSystem.CLOSED,CoopSystem.unlock(player,CHAPTER));
        assertEquals(3,player.getLegendaryKey());
        player.level=26; player.useLegendaryKey(2);
        assertNotEquals(0,CoopSystem.unlock(player,CHAPTER));
        assertEquals(1,player.getLegendaryKey());
        player.addLegendaryKey(2);
        assertEquals(0,CoopSystem.unlock(player,CHAPTER));
        assertEquals(1,player.getLegendaryKey());
        assertNotEquals(0,CoopSystem.unlock(player,CHAPTER));
        assertEquals(1,player.getLegendaryKey());
        assertEquals(Map.of(3,3),player.getHangouts().getPoints().get(START).getStart().getTempVars());
        assertTrue(player.getHangouts().getPoints().get(START).getStart().getNormalVars().isEmpty());
        assertNotEquals(0,CoopSystem.unlock(player,999999));
    }

    @Test void saveRequiresAnOwnedActiveRunNativeSourceAndKnownNonEndingPointAndRejectsDuplicateOrMp() throws Exception {
        var player=unlocked();
        assertNotEquals(0,CoopSystem.start(player,99999999).retcode());
        assertNotEquals(0,CoopSystem.start(player,10140191).retcode());
        assertNotEquals(0,CoopSystem.save(player,CHAPTER,10201,4,Map.of(),Map.of()));
        assertEquals(0,CoopSystem.start(player,START).retcode()); drain();
        assertNotEquals(0,CoopSystem.start(player,START).retcode());
        assertNotEquals(0,CoopSystem.save(player,CHAPTER,10201,4,Map.of(),Map.of()));
        started(player,1900102);
        var normal=new HashMap<>(Map.of(77,-7)); var temporary=new HashMap<>(Map.of(3,2));
        assertEquals(0,CoopSystem.save(player,CHAPTER,10201,4,normal,temporary)); drain();
        normal.put(77,100); temporary.put(3,100);
        var saved=player.getHangouts().getRun().getCurrent();
        assertEquals(Map.of(77,-7),saved.getNormalVars());
        assertEquals(Map.of(3,2),saved.getTempVars());
        assertNotEquals(0,CoopSystem.save(player,CHAPTER,10201,4,Map.of(),Map.of()));
        assertNotEquals(0,CoopSystem.save(player,CHAPTER,99999999,4,Map.of(),Map.of()));
        assertNotEquals(0,CoopSystem.save(player,CHAPTER,201,4,Map.of(),Map.of()));
        assertNotEquals(0,CoopSystem.save(player,101501,10202,4,Map.of(),Map.of()));
        assertNotEquals(0,CoopSystem.save(player,CHAPTER,10202,-1,Map.of(),Map.of()));
        assertNotEquals(0,CoopSystem.save(player,CHAPTER,10202,6,Map.of(),Map.of()));
        multiplayer(player,true);
        assertNotEquals(0,CoopSystem.save(player,CHAPTER,10202,4,Map.of(),Map.of()));
        assertNotEquals(0,CoopSystem.start(player,10140102).retcode());
        assertNotEquals(0,CoopSystem.finish(player,CHAPTER,201));
        assertEquals(List.of(10201),saved.getSavePoints());
        assertEquals(Map.of(77,-7),saved.getNormalVars());
    }

    @Test void anySaveGoalRequiresANewSaveAfterThatSubquestStartedAndClientReportsCannotSupplyIt() throws Exception {
        var player=unlocked(); assertEquals(0,CoopSystem.start(player,START).retcode());
        started(player,1900102);
        assertEquals(0,CoopSystem.save(player,CHAPTER,10201,4,Map.of(),Map.of(3,2))); drain();
        var data=prepare(1900103);
        var modified=JsonUtils.decode(JsonUtils.encode(data),JsonObject.class);
        modified.add("finishCond",JsonParser.parseString("[{type:'QUEST_CONTENT_MAIN_COOP_ENTER_ANY_SAVE_POINT',param:[101401,0]}]"));
        var any=JsonUtils.decode(modified,QuestData.class);
        replaceQuest(player,any);
        var quest=started(player,1900103); var condition=any.getFinishCond().getFirst();
        assertFalse(CoopSystem.matchesCurrent(quest,condition));
        for (var type:List.of(QuestContent.QUEST_CONTENT_MAIN_COOP_ENTER_SAVE_POINT,
                QuestContent.QUEST_CONTENT_MAIN_COOP_ENTER_ANY_SAVE_POINT)) {
            assertTrue(type.isServerStatePredicate());
            new HandlerAddQuestContentProgressReq().handle(player.session,new byte[0],AddQuestContentProgressReq.newBuilder()
                    .setContentType(type.getValue()).setParam(CHAPTER).build().toByteArray());
            new HandlerAddQuestContentProgressBatchReq().handle(player.session,new byte[0],_AddQuestContentProgressBatchReq.newBuilder()
                    .addProgressInfoList(_QuestProgressInfo.newBuilder().setContentType(type.getValue()).setParam(CHAPTER))
                    .build().toByteArray());
        }
        drain(); assertEquals(QuestState.QUEST_STATE_UNFINISHED,quest.getState());
        assertFalse(CoopSystem.matchesCurrent(quest,condition));
        assertEquals(0,CoopSystem.save(player,CHAPTER,10202,4,Map.of(),Map.of(3,1))); drain();
        assertTrue(CoopSystem.matchesCurrent(quest,condition));
        assertEquals(QuestState.QUEST_STATE_FINISHED,quest.getState());
        assertFalse(CoopSystem.matchesCurrent(quest,JsonUtils.decode(
                "{type:'QUEST_CONTENT_MAIN_COOP_ENTER_ANY_SAVE_POINT',param:[101501,0]}",QuestData.QuestContentCondition.class)));
    }

    @Test void savedStateSurvivesTheActualQuestLoginPathAndReplaysUseIndependentSnapshotMaps() throws Exception {
        var player=unlocked(); assertEquals(0,CoopSystem.start(player,START).retcode());
        started(player,1900102);
        assertEquals(0,CoopSystem.save(player,CHAPTER,10201,4,Map.of(17,-5),Map.of(3,2))); drain();
        started(player,1900108); // Native first -> middle edge stores the real achieved start snapshot.
        var stored=player.getHangouts().getPoints().get(10140102).getStart();
        assertEquals(List.of(10201),stored.getSavePoints());
        String serialized=JsonUtils.encode(player.getHangouts());
        var relogged=player(); relogged.setHangouts(JsonUtils.decode(serialized,CoopState.class));
        var live=attach(relogged,prepare(1900108),QuestState.QUEST_STATE_UNFINISHED);
        var before=relogged.getHangouts().getRun().getCurrent().copy();
        assertTrue(CoopSystem.preserveOnLogin(live.getMainQuest()));
        relogged.getQuestManager().onLogin(); drain();
        assertEquals(QuestState.QUEST_STATE_UNFINISHED,live.getState());
        assertEquals(before.toProto(Map.of()),relogged.getHangouts().getRun().getCurrent().toProto(Map.of()));
        assertEquals(0,CoopSystem.cancel(relogged,CHAPTER));
        assertEquals(0,CoopSystem.start(relogged,10140102).retcode()); drain();
        var replay=relogged.getHangouts().getRun().getCurrent();
        var original=relogged.getHangouts().getPoints().get(10140102).getStart();
        assertNotSame(original,replay);
        replay.getNormalVars().put(17,-50); replay.getTempVars().put(3,99); replay.getSavePoints().add(10202);
        assertEquals(Map.of(17,-5),original.getNormalVars());
        assertEquals(Map.of(3,2),original.getTempVars());
        assertEquals(List.of(10201),original.getSavePoints());
    }

    @Test void explicitQuestRewindKeepsCoopVariablesButRestoresOrdinaryTempAndNormalValues() throws Exception {
        var player=unlocked(); assertEquals(0,CoopSystem.start(player,START).retcode());
        started(player,1900102);
        assertEquals(0,CoopSystem.save(player,CHAPTER,10201,4,Map.of(7,-8),Map.of(3,4,99,11))); drain();
        var stage=started(player,1900103);
        assertEquals(0,CoopSystem.save(player,CHAPTER,10301,3,Map.of(7,-99),Map.of(3,8,99,22))); drain();
        stage.start(); drain();
        var reset=player.getHangouts().getRun().getCurrent();
        assertEquals(Map.of(3,8,99,11),reset.getTempVars());
        assertEquals(Map.of(7,-8),reset.getNormalVars());
        assertEquals(List.of(10201),reset.getSavePoints());
    }

    @Test void cancellationRemovesOnlyItsNativeQuestSeriesAndKeepsOtherChaptersAndAlbum() throws Exception {
        var player=unlocked(); assertEquals(0,CoopSystem.start(player,START).retcode());
        var own=started(player,1900102); var unrelated=player.getQuestManager().getQuestById(39604);
        int other=GameData.getCoopPointDataMap().values().stream()
                .filter(p->p.getChapterId()==101501&&"POINT_START".equals(p.getType())).findFirst().orElseThrow().getAcceptQuest();
        var foreign=attach(player,prepare(other),QuestState.QUEST_STATE_UNFINISHED);
        player.getQuestManager().getAcceptProgressLists().put(own.getSubQuestId(),new int[]{1});
        player.getQuestManager().getAcceptProgressLists().put(other,new int[]{1});
        player.getHangouts().point(10140191).setState(2);
        player.getHangouts().endings(CHAPTER).put(101,1);
        player.getHangouts().getViewedChapters().add(CHAPTER);
        assertNotEquals(0,CoopSystem.cancel(player,101501));
        assertTrue(CoopSystem.running(player,CHAPTER));
        assertEquals(0,CoopSystem.cancel(player,CHAPTER)); drain();
        assertNull(player.getHangouts().getRun());
        assertNull(player.getQuestManager().getQuestById(own.getSubQuestId()));
        assertSame(foreign,player.getQuestManager().getQuestById(other));
        assertSame(unrelated,player.getQuestManager().getQuestById(39604));
        assertFalse(player.getQuestManager().getAcceptProgressLists().containsKey(own.getSubQuestId()));
        assertTrue(player.getQuestManager().getAcceptProgressLists().containsKey(other));
        assertEquals(1,deleted.stream().filter(main->main==own.getMainQuest()).count());
        assertEquals(0,deleted.stream().filter(main->main==foreign.getMainQuest()).count());
        assertEquals(2,player.getHangouts().point(10140191).getState());
        assertEquals(Map.of(101,1),player.getHangouts().endings(CHAPTER));
        assertTrue(player.getHangouts().getViewedChapters().contains(CHAPTER));
        assertFalse(CoopSystem.toProto(player).getIsHaveProgress());
    }

    @Test void aRealEndingAwardsOnlyItsAlbumEntryAndTheInstalledRewardCanBeClaimedOnce() throws Exception {
        var player=unlocked(); installReward(); var book=own(player,104302,1);
        assertNotEquals(0,CoopSystem.takeReward(player,10140101));
        assertEquals(0,CoopSystem.start(player,START).retcode());
        started(player,1900108); started(player,1900202);
        assertNotEquals(0,CoopSystem.finish(player,CHAPTER,999999));
        assertEquals(0,CoopSystem.finish(player,CHAPTER,101)); drain();
        assertFalse(CoopSystem.running(player,CHAPTER));
        assertFalse(CoopSystem.toProto(player).getIsHaveProgress());
        assertEquals(2,player.getHangouts().point(10140191).getState());
        for (int end:List.of(10140192,10140193,10140194,10140195))
            assertNotEquals(2,player.getHangouts().point(end).getState());
        assertEquals(Map.of(101,1),player.getHangouts().endings(CHAPTER));
        assertNotEquals(0,CoopSystem.finish(player,CHAPTER,101));
        assertEquals(0,CoopSystem.takeReward(player,10140101));
        assertEquals(20,player.getPrimogems());
        assertEquals(100,player.getExp());
        assertEquals(4,book.getCount());
        assertNotEquals(0,CoopSystem.takeReward(player,10140101));
        assertEquals(20,player.getPrimogems()); assertEquals(4,book.getCount());
        assertEquals(Set.of(10140101),player.getHangouts().getTakenRewards());
        var oldRun=player.getHangouts().getRun();
        assertEquals(0,CoopSystem.start(player,10140103).retcode()); drain();
        assertNotSame(oldRun,player.getHangouts().getRun());
        assertTrue(CoopSystem.running(player,CHAPTER));
        assertEquals(Map.of(101,1),player.getHangouts().endings(CHAPTER));
    }

    @Test void nativeForkEndingNeverCreditsBothTerminalNeighbours() throws Exception {
        var player=player(); int chapter=103201, point=10320109;
        player.getHangouts().getAcceptedChapters().add(chapter);
        var config=CoopCatalog.chapter(chapter);
        player.getHangouts().point(point).setState(1);
        player.getHangouts().point(point).setStart(new CoopState.Snapshot(chapter,config.initialConfidence(),
                config.normalVars(),config.tempVars()));
        assertEquals(0,CoopSystem.start(player,point).retcode());
        started(player,1904404); // Its real interaction contains the 301 branch end.
        assertEquals(0,CoopSystem.finish(player,chapter,301)); drain();
        long endings=Arrays.stream(GameData.getCoopPointDataMap().get(point).getPostPointList())
                .filter(id->player.getHangouts().getPoints().containsKey(id)
                        &&player.getHangouts().getPoints().get(id).getState()==2).count();
        assertEquals(1,endings,"one real branch end cannot award both mutually exclusive album entries");
        assertEquals(Map.of(301,1),player.getHangouts().endings(chapter));
    }

    @Test void bothRealBennettStartedDungeonsRoundTripIntoAFreshSceneAndResumeTheInternalQuest() throws Exception {
        for (int dungeonId:List.of(1111,1112)) {
            var data=dungeon(dungeonId);
            var player=bennett(data);
            int entryId=dungeonId==1111?1904403:1904501;
            int internalId=dungeonId==1111?1904404:1904502;
            var entry=player.getQuestManager().getQuestById(entryId);
            entry.setState(QuestState.QUEST_STATE_FINISHED);
            var internal=started(player,internalId);
            var run=player.getHangouts().getRun();
            run.getCurrent().getNormalVars().put(77,-7);
            var scene=dungeonScene(player,data);
            scene.setKilledMonsterCount(3);
            assertFalse(QuestPersistentDungeonRuntime.canRestoreLogin(player));
            scene.getDungeonManager().startDungeon(); drain();
            assertTrue(scene.getDungeonManager().capturePersistentProgress().isStarted());
            assertTrue(QuestPersistentDungeonRuntime.canRestoreLogin(player));
            int group=data.getSceneId()==20035?220035002:220036002;
            QuestPersistentDungeonRuntime.suiteAdded(scene,group,2);
            assertTrue(QuestPersistentDungeonRuntime.capture(scene));
            var saved=scene.getDungeonManager().capturePersistentProgress();
            var state=JsonUtils.decode(JsonUtils.encode(player.getPersistentDungeons()),QuestPersistentDungeonState.class);
            var persistent=Player.class.getDeclaredField("persistentDungeons"); persistent.setAccessible(true);
            persistent.set(player,state);
            var before=run.getCurrent().toProto(Map.of());
            int start=internal.getStartTime();
            var fresh=dungeonScene(player,data); fresh.setDungeonManager(null);
            assertNull(fresh.getDungeonManager());
            assertTrue(QuestPersistentDungeonRuntime.canRestoreLogin(player));
            assertTrue(QuestPersistentDungeonRuntime.restoreLogin(player));
            assertTrue(fresh.getDungeonManager().isPersistentProgressRestored());
            var restored=fresh.getDungeonManager().capturePersistentProgress();
            assertEquals(saved.getClaimRunToken(),restored.getClaimRunToken());
            assertArrayEquals(saved.getFinishedConditions(),restored.getFinishedConditions());
            assertEquals(3,fresh.getKilledMonsterCount());
            assertEquals(3,fresh.getPrevScene()); assertEquals(1,fresh.getPrevScenePoint());
            assertEquals(Set.of(2),QuestPersistentDungeonRuntime.savedSuites(fresh,group));
            assertTrue(CoopDungeonRuntime.hasRestoredInstance(internal.getMainQuest()));
            assertNull(internal.getMainQuest().resumeOnLogin());
            assertEquals(QuestState.QUEST_STATE_FINISHED,entry.getState());
            assertEquals(QuestState.QUEST_STATE_UNFINISHED,internal.getState());
            assertEquals(start,internal.getStartTime());
            assertEquals(internalId,run.getStageQuestId());
            assertEquals(before,run.getCurrent().toProto(Map.of()));
        }
    }

    @Test void bennettRestorationRejectsUnstartedManagerForeignChapterInactiveEntryWrongOriginAndOrdinaryPlot() throws Exception {
        var data=dungeon(1111); var player=bennett(data); var scene=dungeonScene(player,data);
        // A native entry quest may arm capture before ENTER_DUNGEON; that alone cannot restore a manager.
        assertTrue(QuestPersistentDungeonRuntime.capture(scene));
        assertFalse(QuestPersistentDungeonRuntime.canRestoreLogin(player));
        assertFalse(QuestPersistentDungeonRuntime.restoreLogin(player));
        scene.getDungeonManager().startDungeon(); drain();
        assertTrue(QuestPersistentDungeonRuntime.canRestoreLogin(player));
        var valid=scene.getDungeonManager().capturePersistentProgress();
        var entry=player.getQuestManager().getQuestById(1904403);
        entry.setState(QuestState.QUEST_STATE_UNSTARTED);
        assertFalse(QuestPersistentDungeonRuntime.canRestoreLogin(player));
        assertFalse(new DungeonManager(scene,data).restorePersistentProgress(valid));
        entry.setState(QuestState.QUEST_STATE_UNFINISHED);
        player.getHangouts().getRun().setChapterId(CHAPTER);
        assertFalse(QuestPersistentDungeonRuntime.canRestoreLogin(player));
        assertFalse(new DungeonManager(scene,data).restorePersistentProgress(valid));
        player.getHangouts().getRun().setChapterId(103201);
        scene.setPrevScenePoint(2);
        assertTrue(QuestPersistentDungeonRuntime.capture(scene));
        assertFalse(QuestPersistentDungeonRuntime.canRestoreLogin(player));
        assertFalse(CoopDungeonRuntime.validOrigin(data,3,2));
        assertFalse(CoopDungeonRuntime.validOrigin(data,20035,1));
        var ordinary=dungeon(1113);
        assertFalse(CoopDungeonRuntime.isDungeon(ordinary));
        var other=dungeonScene(player,ordinary); other.getDungeonManager().startDungeon(); drain();
        assertFalse(QuestPersistentDungeonRuntime.activate(entry,ordinary.getSceneId()));
        player.getPersistentDungeons().activate(ordinary.getSceneId());
        player.getPersistentDungeons().update(other.getDungeonManager().capturePersistentProgress());
        assertFalse(QuestPersistentDungeonRuntime.canRestoreLogin(player));
        assertFalse(QuestPersistentDungeonRuntime.restoreLogin(player));
    }

    @Test void realStartSaveCancelHandlersAcceptTheNativeTagsAndEmitTheNativeResponseShapes() throws Exception {
        var player=unlocked();
        new HandlerStartCoopPointReq().handle(player.session,new byte[0],wire(out->out.writeUInt32(6,START)));
        drain();
        var start=last(player,2925).getData();
        assertEquals(START,varint(start,4));
        assertEquals(1,varint(start,11));
        assertTrue(fields(start).contains(8));
        started(player,1900102);
        byte[] request=wire(out->{
            out.writeUInt32(6,CHAPTER); out.writeUInt32(2,4); out.writeUInt32(3,10201);
            out.writeByteArray(4,wire(entry->{entry.writeUInt32(1,77);entry.writeInt32(2,-7);}));
            out.writeByteArray(12,wire(entry->{entry.writeUInt32(1,3);entry.writeInt32(2,2);}));
        });
        new HandlerSaveMainCoopReq().handle(player.session,new byte[0],request); drain();
        assertEquals(Map.of(77,-7),player.getHangouts().getRun().getCurrent().getNormalVars());
        assertEquals(Map.of(3,2),player.getHangouts().getRun().getCurrent().getTempVars());
        var response=last(player,22426).getData();
        assertEquals(CHAPTER,varint(response,8));
        assertEquals(10201,CodedInputStream.newInstance(bytes(response,6)).readUInt32());
        var main=last(player,4641).getData();
        assertTrue(fields(main).contains(5));
        var mainBody=bytes(main,5);
        assertEquals(CHAPTER,varint(mainBody,4));
        assertTrue(fields(mainBody).containsAll(Set.of(8,14,15)));
        assertEquals(77,varint(bytes(mainBody,8),1));
        assertEquals(-7,(int)varint(bytes(mainBody,8),2));
        assertEquals(3,varint(bytes(mainBody,14),1));
        assertEquals(START,varint(last(player,5436).getData(),7));
        assertEquals(1,varint(last(player,5436).getData(),15));
        new HandlerCancelCoopTaskReq().handle(player.session,new byte[0],wire(out->out.writeUInt32(5,CHAPTER)));
        assertNull(player.getHangouts().getRun());
        assertEquals(CHAPTER,varint(last(player,20629).getData(),14));
        assertEquals(0,varint(last(player,5436).getData(),15));
        new HandlerCancelCoopTaskReq().handle(player.session,new byte[0],wire(out->out.writeUInt32(5,CHAPTER)));
        assertNotEquals(0,varint(last(player,20629).getData(),9));
        // Wrong old field 9 must not become a valid chapter cancellation.
        assertEquals(0,CoopSystem.start(player,START).retcode());
        new HandlerCancelCoopTaskReq().handle(player.session,new byte[0],wire(out->out.writeUInt32(9,CHAPTER)));
        assertTrue(CoopSystem.running(player,CHAPTER));
    }

    private void installReward() throws Exception {
        for (var value:rows("RewardExcelConfigData")) {
            var data=JsonUtils.decode(value,RewardData.class);
            if (data.getId()!=4001411) continue;
            data.onLoad(); install(GameData.getRewardDataMap(),data.getId(),data); break;
        }
        for (var value:rows("MaterialExcelConfigData")) {
            int id=value.getAsJsonObject().get("id").getAsInt();
            if (!Set.of(102,201,104302).contains(id)) continue;
            var data=JsonUtils.decode(value,ItemData.class); data.onLoad(); install(GameData.getItemDataMap(),id,data);
        }
    }

    private static GameItem own(CoopPlayer player,int itemId,int count) {
        var item=new GameItem(GameData.getItemDataMap().get(itemId),count) { @Override public void save(){} };
        item.setOwner(player); player.getInventory().getItems().put(item.getGuid(),item);
        player.getInventory().getInventoryTab(ItemType.ITEM_MATERIAL).onAddItem(item);
        return item;
    }

    private CoopPlayer unlocked() throws Exception {
        var player=player(); finished(player,39604); player.addLegendaryKey(3);
        assertEquals(0,CoopSystem.unlock(player,CHAPTER)); return player;
    }

    private CoopPlayer player() throws Exception {
        var player=new CoopPlayer(); player.setUid(89271);
        var field=Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        var server=(GameServer)((Unsafe)field.get(null)).allocateInstance(GameServer.class);
        var quests=GameServer.class.getDeclaredField("questSystem"); quests.setAccessible(true); quests.set(server,new QuestSystem(server));
        player.session=new RecordingSession(server,player); player.setSession(player.session);
        var world=TestWorld.create(player);
        var sceneMap=World.class.getDeclaredField("scenes"); sceneMap.setAccessible(true);
        sceneMap.set(world,new Int2ObjectOpenHashMap<Scene>());
        return player;
    }

    private DungeonData dungeon(int id) throws Exception {
        var row=java.util.stream.StreamSupport.stream(rows("DungeonExcelConfigData").spliterator(),false)
                .map(JsonElement::getAsJsonObject).filter(value->value.get("id").getAsInt()==id).findFirst().orElseThrow();
        var data=JsonUtils.decode(row,DungeonData.class);
        install(GameData.getDungeonDataMap(),id,data);
        var passRow=java.util.stream.StreamSupport.stream(rows("DungeonPassExcelConfigData").spliterator(),false)
                .map(JsonElement::getAsJsonObject).filter(value->value.get("id").getAsInt()==data.getPassCond()).findFirst().orElseThrow();
        var pass=JsonUtils.decode(passRow,DungeonPassConfigData.class); pass.onLoad();
        assertFalse(pass.getConds().isEmpty());
        install(GameData.getDungeonPassConfigDataMap(),pass.getId(),pass);
        install(GameData.getSceneDataMap(),data.getSceneId(),TestWorld.sceneData(data.getSceneId(),SceneType.SCENE_DUNGEON));
        install(GameData.getSceneDataMap(),3,TestWorld.sceneData(3,SceneType.SCENE_WORLD));
        var point=JsonUtils.decode(read("BinOutput/Scene/Point/scene3_point.json").getAsJsonObject()
                .getAsJsonObject("points").getAsJsonObject("1"),PointData.class); point.setId(1);
        install(GameData.getScenePointEntryMap(),(3<<16)+1,new ScenePointEntry(3,point));
        return data;
    }

    private CoopPlayer bennett(DungeonData data) throws Exception {
        var player=player(); int point=data.getId()==1111?10320109:10320110;
        var config=CoopCatalog.chapter(103201);
        player.getHangouts().getAcceptedChapters().add(103201);
        var progress=player.getHangouts().point(point); progress.setState(1);
        progress.setStart(new CoopState.Snapshot(103201,config.initialConfidence(),config.normalVars(),config.tempVars()));
        assertEquals(0,CoopSystem.start(player,point).retcode()); drain();
        started(player,data.getId()==1111?1904403:1904501);
        return player;
    }

    private Scene dungeonScene(CoopPlayer player,DungeonData data) {
        var scene=new TestWorld.TestScene(player.getWorld(),data.getSceneId(),SceneType.SCENE_DUNGEON);
        scenes.add(scene); player.getWorld().getScenes().put(scene.getId(),scene);
        player.setScene(scene); player.setSceneId(scene.getId()); scene.getPlayers().add(player);
        scene.setPrevScene(3); scene.setPrevScenePoint(1);
        scene.setDungeonManager(new DungeonManager(scene,data));
        return scene;
    }

    private QuestData prepare(int id) throws Exception {
        if (prepared.containsKey(id)) return prepared.get(id);
        var parent=read("BinOutput/Quest/"+id/100+".json").getAsJsonObject();
        var data=parent.deepCopy(); data.add("talks",new JsonArray());
        var row=java.util.stream.StreamSupport.stream(parent.getAsJsonArray("subQuests").spliterator(),false)
                .map(v->v.getAsJsonObject()).filter(q->q.get("subId").getAsInt()==id).findFirst().orElseThrow().deepCopy();
        row.addProperty("mainId",id/100);
        // The tests exercise owned hangout state, not unrelated scene actions or the damaged
        // flattened acceptance table. Original finish targets, identities and series remain intact.
        for (String name:List.of("acceptCond","failCond","beginExec","finishExec","failExec")) row.add(name,new JsonArray());
        if (!row.has("finishCond")) row.add("finishCond",new JsonArray());
        for (String name:List.of("acceptCondComb","failCondComb","finishCondComb"))
            if (!row.has(name)) row.addProperty(name,"LOGIC_AND");
        var result=JsonUtils.decode(row,QuestData.class);
        prepared.put(id,result); install(GameData.getQuestDataMap(),id,result);
        var children=new JsonArray();
        prepared.values().stream().filter(q->q.getMainId()==id/100).forEach(q->{
            var child=new JsonObject(); child.addProperty("subId",q.getId()); child.addProperty("order",q.getOrder()); children.add(child);
        });
        data.add("subQuests",children);
        install(GameData.getMainQuestDataMap(),id/100,JsonUtils.decode(data,MainQuestData.class));
        return result;
    }

    private void replaceQuest(CoopPlayer player,QuestData data) {
        prepared.put(data.getId(),data); install(GameData.getQuestDataMap(),data.getId(),data);
        var main=player.getQuestManager().getMainQuestById(data.getMainId());
        if (main!=null) main.getChildQuests().put(data.getId(),new MemoryQuest(main,data));
    }

    private MemoryQuest started(CoopPlayer player,int id) throws Exception {
        var data=prepare(id); attach(player,data,QuestState.QUEST_STATE_UNSTARTED);
        int previous=player.getHangouts().getRun()==null?0:player.getHangouts().getRun().getPointId();
        var quest=(MemoryQuest)player.getQuestManager().addQuest(id); drain();
        // Save-contract tests start inside a selected original stage. Route selection has its own
        // controller tests; merely attaching another quest never authorizes an unrelated save here.
        var run=player.getHangouts().getRun();
        if (run!=null) {
            if (previous!=run.getPointId()) assertTrue(CoopProgression.onPointStarted(player,run.getPointId()));
            var point=CoopProgressionCatalog.installed().point(run.getPointId());
            assertTrue(point.stages().containsKey(id)||point.auxiliary().containsKey(id));
            run.setStageQuestId(id);
        }
        return quest;
    }

    private MemoryQuest finished(CoopPlayer player,int id) throws Exception {
        return attach(player,prepare(id),QuestState.QUEST_STATE_FINISHED);
    }

    private static MemoryQuest attach(CoopPlayer player,QuestData data,QuestState state) {
        var manager=player.getQuestManager(); var main=manager.getMainQuestById(data.getMainId());
        if (main==null) {main=new MemoryMain(player,data.getMainId());manager.getMainQuests().put(data.getMainId(),main);}
        var quest=new MemoryQuest(main,data); quest.setState(state); main.getChildQuests().put(data.getId(),quest);
        return quest;
    }

    private static void multiplayer(Player player,boolean value) throws Exception {
        var field=World.class.getDeclaredField("isMultiplayer"); field.setAccessible(true); field.set(player.getWorld(),value);
    }

    private static JsonElement read(String path) throws Exception {
        return JsonParser.parseString(Files.readString(FileUtils.getResourcePath(path)));
    }
    private static JsonArray rows(String name) throws Exception {return read("ExcelBinOutput/"+name+".json").getAsJsonArray();}
    private static void drain() throws Exception {
        QuestManager.eventExecutor.submit(()->{}).get(10,TimeUnit.SECONDS);
        try (var ignored=databaseBarrier()) {}
    }

    private void installMemoryDatastore() throws Exception {
        var field=DatabaseManager.class.getDeclaredField("gameDatastore");field.setAccessible(true);
        var previous=field.get(null);
        var store=(Datastore)Proxy.newProxyInstance(Datastore.class.getClassLoader(),new Class<?>[]{Datastore.class},
                (proxy,method,args)->{
                    if (method.getName().equals("save")&&args!=null&&args[0] instanceof GameMainQuest) return args[0];
                    if (method.getName().equals("delete")&&args!=null&&args[0] instanceof GameMainQuest main) {
                        deleted.add(main);return DeleteResult.acknowledged(1);
                    }
                    if (method.getName().equals("toString")) return "CoopMemoryDatastore";
                    throw new AssertionError("Unexpected persistence operation "+method.getName());
                });
        field.set(null,store);
        restore.add(()->{try{field.set(null,previous);}catch(IllegalAccessException e){throw new AssertionError(e);}});
    }

    private static AutoCloseable databaseBarrier() throws Exception {
        var field=DatabaseHelper.class.getDeclaredField("databaseWriters");field.setAccessible(true);var manager=field.get(null);
        var acquire=manager.getClass().getDeclaredMethod("acquireBarrier",Duration.class);acquire.setAccessible(true);
        return (AutoCloseable)acquire.invoke(manager,Duration.ofSeconds(10));
    }

    @FunctionalInterface private interface WireWriter {void write(CodedOutputStream out)throws Exception;}
    private static byte[] wire(WireWriter writer)throws Exception {
        var result=new ByteArrayOutputStream(); var out=CodedOutputStream.newInstance(result);
        writer.write(out); out.flush(); return result.toByteArray();
    }
    private static BasePacket last(CoopPlayer player,int opcode) {
        return player.packets.reversed().stream().filter(p->p.getOpcode()==opcode).findFirst().orElseThrow();
    }
    private static Set<Integer> fields(byte[] data)throws Exception {
        var result=new HashSet<Integer>();var in=CodedInputStream.newInstance(data);
        for(int tag;(tag=in.readTag())!=0;){result.add(tag>>>3);in.skipField(tag);}return result;
    }
    private static long varint(byte[] data,int field)throws Exception {
        var in=CodedInputStream.newInstance(data);
        for(int tag;(tag=in.readTag())!=0;){if(tag==(field<<3))return in.readUInt64();in.skipField(tag);}return 0;
    }
    private static byte[] bytes(byte[] data,int field)throws Exception {
        var in=CodedInputStream.newInstance(data);
        for(int tag;(tag=in.readTag())!=0;){if(tag==((field<<3)|2))return in.readByteArray();in.skipField(tag);}
        throw new AssertionError("missing native field "+field);
    }

    private static final class CoopPlayer extends TestWorld.TestPlayer {
        final List<BasePacket> packets=new ArrayList<>();
        final QuestManager manager=new QuestManager(this);
        RecordingSession session;
        int saves;
        @Override public QuestManager getQuestManager(){return manager;}
        @Override public void save(){saves++;}
        @Override public void sendPacket(BasePacket packet){packets.add(packet);}
    }
    private static final class RecordingSession extends GameSession {
        final CoopPlayer player;
        RecordingSession(GameServer server,CoopPlayer player){super(server);this.player=player;}
        @Override public Player getPlayer(){return player;}
        @Override public void send(BasePacket packet){player.packets.add(packet);}
    }
    private static final class MemoryMain extends GameMainQuest {
        int deletes;
        MemoryMain(Player player,int id){super(player,id);}
        @Override public void save(){}
        @Override public void delete(){deletes++;}
        @Override public void checkProgress(){}
    }
    private static final class MemoryQuest extends GameQuest {
        MemoryQuest(GameMainQuest main,QuestData data){super(main,data);}
        @Override public void finish(){setState(QuestState.QUEST_STATE_FINISHED);}
        @Override public void fail(){setState(QuestState.QUEST_STATE_FAILED);}
        @Override public void save(){}
    }
}
