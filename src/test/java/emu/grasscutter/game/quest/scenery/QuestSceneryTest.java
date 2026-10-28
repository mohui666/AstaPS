package emu.grasscutter.game.quest.scenery;

import static org.junit.jupiter.api.Assertions.*;
import static emu.grasscutter.config.Configuration.GAME_OPTIONS;

import com.google.gson.*;
import com.google.protobuf.CodedInputStream;
import emu.grasscutter.*;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.MainQuestData;
import emu.grasscutter.data.excels.GadgetData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.player.*;
import emu.grasscutter.game.props.SceneType;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.content.ContentServerState;
import emu.grasscutter.game.quest.enums.*;
import emu.grasscutter.game.quest.exec.ExecActivateScenery;
import emu.grasscutter.game.quest.reconstruction.StoryReconstructionCatalog;
import emu.grasscutter.game.world.*;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.scripts.ScriptLoader;
import emu.grasscutter.scripts.data.SceneGroup;
import emu.grasscutter.server.game.*;
import emu.grasscutter.server.packet.send.PacketQuestSceneryInfoUpdateNotify;
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
final class QuestSceneryTest {
    private static final java.util.concurrent.atomic.AtomicInteger UID=new java.util.concurrent.atomic.AtomicInteger(89700);
    private final List<Runnable> undo=new ArrayList<>();
    private boolean scripts,questing;
    @BeforeEach void prepare() throws Exception {
        scripts=Grasscutter.getConfig().server.game.enableScriptInBigWorld;
        Grasscutter.getConfig().server.game.enableScriptInBigWorld=false;
        questing=GAME_OPTIONS.questing.enabled;GAME_OPTIONS.questing.enabled=false;
        ScriptLoader.init();
        for(int id:new int[]{40244,40247}) {
            var source=read("BinOutput/Quest/"+id+".json").getAsJsonObject();
            install(GameData.getMainQuestDataMap(),id,JsonUtils.decode(source,MainQuestData.class));
            for(var value:source.getAsJsonArray("subQuests")) {
                var q=value.getAsJsonObject().deepCopy();q.addProperty("mainId",id);
                for(String field:List.of("acceptCond","finishCond","failCond","beginExec","finishExec","failExec","gainItems"))
                    if(!q.has(field))q.add(field,new JsonArray());
                for(String field:List.of("acceptCondComb","finishCondComb","failCondComb"))
                    if(!q.has(field))q.addProperty(field,"LOGIC_NONE");
                var data=JsonUtils.decode(q,QuestData.class);install(GameData.getQuestDataMap(),data.getId(),data);
            }
        }
        install(GameData.getGadgetDataMap(),70350081,JsonUtils.decode("{id:70350081,type:'Worktop',jsonName:'ScenerySelector',tags:[]}",GadgetData.class));
    }
    @AfterEach void restore() throws Exception {
        QuestManager.eventExecutor.submit(()->{}).get(10,TimeUnit.SECONDS);
        Collections.reverse(undo);undo.forEach(Runnable::run);
        GAME_OPTIONS.questing.enabled=questing;Grasscutter.getConfig().server.game.enableScriptInBigWorld=scripts;
    }
    @Test void nativeTablesAndEveryLinkedLuaGoalHaveExplicitRealConsumers() throws Exception {
        var catalog=QuestSceneryCatalog.installed();assertEquals(2,catalog.primaries().size());
        int variants=0;
        for(var value:read("ExcelBinOutput/QuestSubSceneryExcelConfigData.json").getAsJsonArray()) {
            var row=value.getAsJsonObject();var sub=catalog.sub(row.get("EDMGJECHGIB").getAsInt());variants++;
            assertEquals(row.get("AEFJEHFOHOB").getAsInt(),sub.primary());
            for(var pair:sub.suites().entrySet()) {
                var group=SceneGroup.of(pair.getKey()).load(catalog.groupScene(pair.getKey()));
                assertNotNull(group.getSuiteByIndex(pair.getValue()));
                if(pair.getValue()==2) assertTrue(group.getSuiteByIndex(2).sceneGadgets.stream().anyMatch(g->g.config_id==900002));
            }
        }
        assertEquals(7,variants);
        var rebuilt=StoryReconstructionCatalog.installed();int goals=0;
        for(var value:read("ExcelBinOutput/QuestExcelConfigData.json").getAsJsonArray()) {
            var q=value.getAsJsonObject();if(!Set.of(40222,40223,40224,40244,40245,40246,40247,71162).contains(q.get("mainId").getAsInt()))continue;
            for(var condition:q.getAsJsonArray("finishCond")) {
                var c=condition.getAsJsonObject();if(!c.has("type")||!c.get("type").getAsString().equals("QUEST_CONTENT_LUA_NOTIFY"))continue;
                var stage=rebuilt.stage(q.get("subId").getAsInt());assertNotNull(stage);
                assertTrue(stage.getNotifications().stream().anyMatch(n->n.getKey().equals(c.get("param_str").getAsString())));goals++;
            }
        }
        assertEquals(14,goals);
    }
    @Test void content190ReadsTheSavedEnumAndRejectsUnknownStatesAndIds() throws Exception {
        var player=player();var quest=player.quest(4024406);var condition=condition(101002,"1,3");
        assertFalse(ContentServerState.matchesCurrent(quest,condition));
        var saved=player.getScenery().entry(101002);saved.setSubSceneryId(10100201);saved.setState(1);
        assertTrue(ContentServerState.matchesCurrent(quest,condition));saved.setState(2);
        assertFalse(ContentServerState.matchesCurrent(quest,condition));assertTrue(QuestSceneryRuntime.matchesCurrent(quest,condition(101002,"2")));
        assertFalse(QuestSceneryRuntime.matchesCurrent(quest,condition(101002,"2,9")));
        assertFalse(QuestSceneryRuntime.matchesCurrent(quest,condition(999999,"0")));
        assertTrue(QuestContent.QUEST_CONTENT_SCENERY_STATE_EQUAL.isServerStatePredicate());
    }
    @Test void realWorktopStartsOnlyOwnedAcceptedSceneryAndStaleOrDistantActorsAreRejected() throws Exception {
        var player=player();player.quest(4024406).setState(QuestState.QUEST_STATE_UNFINISHED);
        QuestSceneryRuntime.onTick(player);var selector=selector(player);
        assertTrue(QuestSceneryRuntime.tryInteract(player,selector,7)); // Three metres away, within the native interaction radius.
        assertEquals(2,QuestSceneryRuntime.state(player,101002));
        assertEquals(QuestState.QUEST_STATE_UNFINISHED,player.quest(4024406).getState());
        assertTrue(QuestSceneryRuntime.tryInteract(player,selector,7)); // Detached stale entity cannot pause the run.
        assertEquals(2,QuestSceneryRuntime.state(player,101002));
        QuestSceneryRuntime.onTick(player);var pause=selector(player);
        var guest=player();guest.setWorld(player.getWorld());guest.setScene(player.getScene());guest.getPosition().set(pause.getPosition());
        assertTrue(QuestSceneryRuntime.tryInteract(guest,pause,7));assertEquals(2,QuestSceneryRuntime.state(player,101002));
        player.getPosition().set(new Position(100,0,100));
        assertTrue(QuestSceneryRuntime.tryInteract(player,pause,7));assertEquals(2,QuestSceneryRuntime.state(player,101002));
    }
    @Test void original121SelectsItsExactNativeVariantAndRefusesUnrelatedQuestOrId() throws Exception {
        var player=player();var quest=player.quest(4024402);quest.setState(QuestState.QUEST_STATE_FINISHED);
        assertTrue(new ExecActivateScenery().execute(quest,null,"10100202"));
        assertEquals(10100202,player.getScenery().entry(101002).getSubSceneryId());
        assertFalse(new ExecActivateScenery().execute(quest,null,"10100102"));
        assertFalse(new ExecActivateScenery().execute(quest,null,"10100299"));
        assertEquals(10100202,player.getScenery().entry(101002).getSubSceneryId());
        player.getSceneTags().put(3,new HashSet<>(Set.of(203,210,211,999)));
        var visible=QuestSceneryRuntime.effectiveSceneTags(player,player.getSceneTags());
        assertEquals(Set.of(203,999),visible.get(3));assertEquals(Set.of(203,210,211,999),player.getSceneTags().get(3));
        var snapshot=emu.grasscutter.net.proto.SceneDataNotifyOuterClass.SceneDataNotify.parseFrom(
                new emu.grasscutter.server.packet.send.PacketSceneDataNotify(player).getData());
        assertEquals(Set.of(203,999),new HashSet<>(snapshot.getSceneTagIdListList()),
                "scene-init's replacing tag packet must preserve the same temporary/scenery overlay");
        assertEquals(Set.of(203,210,211,999),player.getSceneTags().get(3));
    }
    @Test void realPauseFailureKeepsTheNativeRollbackCheckpointAcrossLoginAndResume() throws Exception {
        var player=player();var checkpoint=player.quest(4024405);checkpoint.setState(QuestState.QUEST_STATE_FINISHED);
        var dialogue=player.quest(4024406);dialogue.setState(QuestState.QUEST_STATE_UNFINISHED);
        assertTrue(QuestSceneryRuntime.select(player,101002,2));assertTrue(QuestSceneryRuntime.select(player,101002,1));
        player.manager.getMainQuestById(40244).tryFailSubQuests(QuestContent.QUEST_CONTENT_SCENERY_STATE_EQUAL,"",101002);
        QuestManager.eventExecutor.submit(()->{}).get(10,TimeUnit.SECONDS);
        assertEquals(QuestState.QUEST_STATE_UNSTARTED,checkpoint.getState());
        assertEquals(4024405,player.state.entry(101002).getResumeStarts().get(40244));
        assertNotEquals(QuestState.QUEST_STATE_FINISHED,dialogue.getState());
        assertFalse(QuestSceneryRuntime.mayAccept(player,dialogue.getQuestData()));
        player.state=JsonUtils.decode(JsonUtils.encode(player.state),QuestSceneryState.class);
        assertTrue(QuestSceneryRuntime.preserveOnLogin(player.manager.getMainQuestById(40244)));
        assertTrue(QuestSceneryRuntime.select(player,101002,2));
        assertEquals(QuestState.QUEST_STATE_UNFINISHED,checkpoint.getState());
        assertNotEquals(QuestState.QUEST_STATE_FINISHED,dialogue.getState());
        assertTrue(player.state.entry(101002).getResumeStarts().isEmpty());
    }
    @Test void notifyUsesActualFdfMessageElementsAndTheExactFiveEightTwoFiveSevenWireTags() throws Exception {
        var player=player();assertTrue(QuestSceneryRuntime.activate(player.quest(4024402),10100202));
        var packet=new PacketQuestSceneryInfoUpdateNotify(player);assertEquals(20490,packet.getOpcode());
        var outer=CodedInputStream.newInstance(packet.getData());assertEquals(42,outer.readTag());
        var info=CodedInputStream.newInstance(outer.readByteArray());assertEquals(66,info.readTag());
        var entry=CodedInputStream.newInstance(info.readByteArray());var fields=new HashMap<Integer,Integer>();
        for(int tag;(tag=entry.readTag())!=0;)fields.put(tag,entry.readUInt32());
        assertEquals(Map.of(16,101002,40,2,56,10100202),fields);
    }
    @Test void finishingRequiresTheActualListedFinalQuestAndCannotBeRequestedByASelector() throws Exception {
        var player=player();assertTrue(QuestSceneryRuntime.activate(player.quest(4024402),10100202));
        assertFalse(QuestSceneryRuntime.select(player,101002,3));QuestSceneryRuntime.onTick(player);
        assertEquals(2,QuestSceneryRuntime.state(player,101002));
        player.quest(4024713).setState(QuestState.QUEST_STATE_FINISHED);QuestSceneryRuntime.onTick(player);
        assertEquals(3,QuestSceneryRuntime.state(player,101002));
        assertFalse(QuestSceneryRuntime.select(player,101002,2));
    }
    private StoryPlayer player() throws Exception {
        var player=new StoryPlayer();player.setUid(UID.incrementAndGet());
        var field=Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);
        var server=(GameServer)((Unsafe)field.get(null)).allocateInstance(GameServer.class);
        var qs=GameServer.class.getDeclaredField("questSystem");qs.setAccessible(true);qs.set(server,new QuestSystem(server));
        player.setSession(new GameSession(server){@Override public Player getPlayer(){return player;}@Override public void send(BasePacket p){}});
        var world=TestWorld.create(player);var scene=new TestWorld.TestScene(world,3,SceneType.SCENE_WORLD);
        player.setScene(scene);player.setSceneId(3);player.setSceneLoadState(Player.SceneLoadState.LOADED);
        for(int id:new int[]{40244,40247}) {
            var main=new GameMainQuest(player,id){@Override public void save(){}@Override public void checkProgress(){}};
            for(var q:List.copyOf(main.getChildQuests().values()))main.getChildQuests().put(q.getSubQuestId(),new GameQuest(main,q.getQuestData()){
                @Override public void save(){}@Override public void finish(){setState(QuestState.QUEST_STATE_FINISHED);}
            });
            player.manager.getMainQuests().put(id,main);
        }
        return player;
    }
    private static EntityGadget selector(StoryPlayer p) {return p.getScene().getEntities().values().stream()
            .filter(EntityGadget.class::isInstance).map(EntityGadget.class::cast).findFirst().orElseThrow();}
    private static QuestData.QuestContentCondition condition(int id,String values) {return JsonUtils.decode(
            "{type:'QUEST_CONTENT_SCENERY_STATE_EQUAL',param:["+id+",0],param_str:'"+values+"'}",QuestData.QuestContentCondition.class);}
    private <V> void install(Map<Integer,V> target,int id,V value) {var old=target.put(id,value);undo.add(()->{if(old==null)target.remove(id);else target.put(id,old);});}
    private static JsonElement read(String file)throws Exception{return JsonParser.parseString(Files.readString(FileUtils.getResourcePath(file)));}
    private static final class StoryPlayer extends TestWorld.TestPlayer {
        QuestSceneryState state=new QuestSceneryState();final QuestManager manager=new QuestManager(this);final TeamManager team=new TeamManager(this);
        @Override public QuestSceneryState getScenery(){return state;}@Override public QuestManager getQuestManager(){return manager;}
        @Override public TeamManager getTeamManager(){return team;}@Override public void save(){}
        @Override public void dropMessage(Object message){}
        GameQuest quest(int id){return manager.getQuestById(id);}
    }
}
