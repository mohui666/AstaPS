package emu.grasscutter.game.gcg.story;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.CodedOutputStream;
import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.MainQuestData;
import emu.grasscutter.data.excels.GadgetData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.entity.EntityGadget;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.SceneType;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.content.ContentServerState;
import emu.grasscutter.game.quest.enums.*;
import emu.grasscutter.game.world.*;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.AddQuestContentProgressReqOuterClass.AddQuestContentProgressReq;
import emu.grasscutter.net.proto.SelectWorktopOptionReqOuterClass.SelectWorktopOptionReq;
import emu.grasscutter.net.proto.GCGStartChallengeRspOuterClass.GCGStartChallengeRsp;
import emu.grasscutter.net.proto.GCGStartChallengeByCheckRewardRspOuterClass.GCGStartChallengeByCheckRewardRsp;
import emu.grasscutter.net.proto.GCGPVEStartGameRspOuterClass.GCGPVEStartGameRsp;
import emu.grasscutter.server.game.*;
import emu.grasscutter.server.packet.recv.*;
import emu.grasscutter.scripts.ScriptLoader;
import emu.grasscutter.utils.*;
import java.lang.reflect.Field;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import sun.misc.Unsafe;

@ExtendWith(ServerResourceFixture.class)
@ResourceLock("GameData")
final class StoryCardRuntimeTest {
    private final Map<Integer, MainQuestData> oldMains = new HashMap<>();
    private final Map<Integer, QuestData> oldQuests = new HashMap<>();
    private final Map<Integer, QuestData> nativeQuests = new HashMap<>();
    private final List<CardPlayer> players = new ArrayList<>();
    private GadgetData oldGadget;

    @BeforeAll
    static void initializeSceneScriptApi() throws Exception { ScriptLoader.init(); }

    @BeforeEach
    void loadOriginalGoals() throws Exception {
        for (int parent : StoryCardCatalog.PARENTS) {
            var main = JsonParser.parseString(Files.readString(FileUtils.getResourcePath("BinOutput/Quest/" + parent + ".json"))).getAsJsonObject();
            var children = new ArrayList<String>();
            for (var value : main.getAsJsonArray("subQuests")) {
                var row = value.getAsJsonObject().deepCopy(); int id = row.get("subId").getAsInt();
                var stage = StoryCardCatalog.installed().stage(id);
                boolean gate = StoryCardCatalog.installed().stages().stream().anyMatch(candidate -> candidate.gate() == id);
                if (stage == null && !gate) continue;
                for (var name : List.of("acceptCond", "finishCond", "failCond", "beginExec", "finishExec", "failExec"))
                    if (!row.has(name)) row.add(name, new JsonArray());
                var data = JsonUtils.decode(row, QuestData.class); data.onLoad();
                oldQuests.put(id, GameData.getQuestDataMap().put(id, data)); nativeQuests.put(id, data);
                children.add("{subId:" + id + "}");
            }
            oldMains.put(parent, GameData.getMainQuestDataMap().put(parent, JsonUtils.decode(
                    "{id:" + parent + ",subQuests:[" + String.join(",", children) + "]}", MainQuestData.class)));
        }
        var gadgets = JsonParser.parseString(Files.readString(FileUtils.getResourcePath("ExcelBinOutput/GadgetExcelConfigData.json"))).getAsJsonArray();
        for (var value : gadgets) if (value.getAsJsonObject().get("id").getAsInt() == 70350081)
            oldGadget = GameData.getGadgetDataMap().put(70350081, JsonUtils.decode(value, GadgetData.class));
    }

    @AfterEach
    void restore() throws Exception {
        drain(); players.forEach(StoryCardRuntime::onLogout);
        oldMains.forEach((id, value) -> { if (value == null) GameData.getMainQuestDataMap().remove(id); else GameData.getMainQuestDataMap().put(id, value); });
        oldQuests.forEach((id, value) -> { if (value == null) GameData.getQuestDataMap().remove(id); else GameData.getQuestDataMap().put(id, value); });
        if (oldGadget == null) GameData.getGadgetDataMap().remove(70350081); else GameData.getGadgetDataMap().put(70350081, oldGadget);
    }

    private static void drain() throws Exception { QuestManager.eventExecutor.submit(() -> {}).get(10, TimeUnit.SECONDS); }

    private CardPlayer player(int questId) throws Exception {
        var player = new CardPlayer(); players.add(player);
        var stage = StoryCardCatalog.installed().stage(questId);
        var world = TestWorld.create(player);
        player.setScene(new TestWorld.TestScene(world, stage.scene(), stage.scene() == 3 ? SceneType.SCENE_WORLD : SceneType.SCENE_ROOM));
        player.setSceneId(stage.scene()); player.setSceneLoadState(Player.SceneLoadState.LOADED);
        player.getPosition().set(stage.anchors().getFirst());
        var main = new TrackingMain(player, questId / 100);
        for (var data : nativeQuests.values()) if (data.getMainId() == questId / 100)
            main.getChildQuests().put(data.getId(), new TrackingQuest(main, data));
        player.getQuestManager().getMainQuests().put(questId / 100, main);
        player.main = main;
        activate(player, questId); if (stage.gate() != 0) activate(player, stage.gate());
        return player;
    }

    private static TrackingQuest activate(CardPlayer player, int id) {
        var quest = (TrackingQuest) player.main.getChildQuestById(id);
        quest.setState(QuestState.QUEST_STATE_UNFINISHED); quest.setStartTime(500);
        return quest;
    }

    private static EntityGadget actor(CardPlayer player, int slot) {
        return (EntityGadget) player.getScene().getEntities().values().stream()
                .filter(entity -> entity.getConfigId() == 900000 + slot).findFirst().orElseThrow();
    }
    private static void click(CardPlayer player, int slot) throws Exception { click(player, actor(player, slot)); }
    private static void click(CardPlayer player, EntityGadget actor) throws Exception {
        player.getPosition().set(actor.getPosition());
        new HandlerSelectWorktopOptionReq().handle(new Session(player), new byte[0],
                SelectWorktopOptionReq.newBuilder().setGadgetEntityId(actor.getId()).setOptionId(7).build().toByteArray());
    }
    private static StoryCardState.Duel start(CardPlayer player) throws Exception {
        StoryCardRuntime.onTick(player); click(player, 0); return player.state.getDuel();
    }

    /** Chooses only existing hand cards, then actually submits their worktop requests. */
    private static void playToEnd(CardPlayer player) throws Exception {
        var game = player.state.getDuel(); int actions = 0;
        while (game.getOutcome() == 0 && actions++ < 200) {
            int selected = -1, score = -1;
            for (int index = 0; index < game.getHand().size(); index++) {
                var card = StoryCardCatalog.installed().card(game.getHand().get(index));
                if (card.cost() > game.getActionPoints() || card.heal() > 0 && game.getPlayerHp() == game.getPlayerMaxHp()) continue;
                int value = card.damage() * 10 + card.shield() + Math.min(card.heal(), game.getPlayerMaxHp() - game.getPlayerHp());
                if (value > score) { selected = index; score = value; }
            }
            if (selected < 0) click(player, 7);
            else {
                int page = selected / 5;
                while (game.getPage() != page) click(player, 6);
                click(player, selected % 5 + 1);
            }
        }
        assertNotEquals(0, game.getOutcome(), "a finite real match must settle"); drain();
    }

    @Test
    void catalogRestrictsOriginalStoriesAndUsesActualGcgHpAndDrawRules() {
        var catalog = StoryCardCatalog.installed();
        assertEquals(22, catalog.stages().size());
        assertNull(catalog.level(99999)); assertEquals(List.of(30101,30102,30104,30105), catalog.tutorialLevels());
        assertEquals(12, catalog.level(30101).opponentHp()); assertEquals(33, catalog.level(50303).opponentHp());
        assertEquals(20, catalog.level(30101).playerHp()); assertEquals(5, catalog.level(30101).firstDraw());
        assertEquals(2, catalog.level(30101).drawPerRound()); assertEquals(10, catalog.level(30101).handLimit());
        assertEquals(15, catalog.roundLimit());
        for (var stage : catalog.stages()) {
            var original = nativeQuests.get(stage.quest());
            for (int level : stage.levels()) assertTrue(original.getFinishCond().stream().anyMatch(condition ->
                    condition.getType() == QuestContent.QUEST_CONTENT_GCG_LEVEL_WIN && condition.getParam()[0] == level));
        }
    }

    @Test
    void requestsSpendRealCardsAndApAndRejectRemoteWrongOptionAndReplayedEntities() throws Exception {
        var player = player(7066602); Utils.random.setSeed(23);
        var game = start(player); assertNotNull(game); assertEquals(5, game.getHand().size());
        assertEquals(8, game.getActionPoints()); assertEquals(0, game.getOutcome());
        var first = actor(player, 1); int hand = game.getHand().size(), ap = game.getActionPoints();
        player.getPosition().addX(100);
        assertTrue(StoryCardRuntime.tryInteract(player, first, 7)); assertEquals(hand, game.getHand().size());
        player.getPosition().set(first.getPosition());
        assertTrue(StoryCardRuntime.tryInteract(player, first, 8)); assertEquals(ap, game.getActionPoints());
        int playable = -1;
        for (int i = 0; i < game.getHand().size(); i++) if (StoryCardCatalog.installed().card(game.getHand().get(i)).damage() > 0) { playable = i; break; }
        assertTrue(playable >= 0);
        var card = StoryCardCatalog.installed().card(game.getHand().get(playable));
        var oldActor = actor(player, playable + 1); int hp = game.getOpponentHp();
        click(player, oldActor);
        assertEquals(hand - 1, game.getHand().size()); assertEquals(ap - card.cost(), game.getActionPoints());
        assertEquals(hp - card.damage(), game.getOpponentHp());
        click(player, oldActor); assertEquals(hand - 1, game.getHand().size());
        assertEquals(0, player.itemsAdded); assertEquals(0, player.main.getChildQuestById(7066602).getFinishProgressList()[0]);
    }

    @Test
    void realLossMatchesNativeTwoAndCannotBeReportedAsWinFromClient() throws Exception {
        var player = player(7066602); var win = activate(player, 7066602); var lose = activate(player, 7066605);
        var session = new Session(player);
        for (int type : List.of(157,159)) new HandlerAddQuestContentProgressReq().handle(session, new byte[0],
                AddQuestContentProgressReq.newBuilder().setContentType(type).setParam(20001).build().toByteArray());
        player.getQuestManager().triggerEvent(QuestContent.QUEST_CONTENT_GCG_LEVEL_WIN, "", 20001, 1);
        drain(); assertEquals(0, win.finishes); assertEquals(0, lose.finishes);
        start(player); click(player, 8); drain();
        assertEquals(2, player.state.getDuel().getOutcome()); assertEquals(0, win.finishes); assertEquals(1, lose.failures);
        assertEquals(0,lose.finishes);
        assertTrue(ContentServerState.matchesCurrent(lose, lose.getQuestData().getFailCond().getFirst()));
        assertFalse(ContentServerState.matchesCurrent(win, win.getQuestData().getFinishCond().getFirst()));
        assertEquals(0, player.state.getTutorialProgress());
    }

    @Test
    void allThirteenOriginalStoryLevelsCanWinThroughActualWorktopPlay() throws Exception {
        var levels = Map.ofEntries(Map.entry(11001,7066511),Map.entry(20001,7066602),Map.entry(20002,7066702),
                Map.entry(20003,7066803),Map.entry(20004,7066903),Map.entry(30101,7066505),Map.entry(30105,7052602),
                Map.entry(30111,7066507),Map.entry(30112,7066516),Map.entry(50101,7321946),
                Map.entry(50301,4010914),Map.entry(50302,4010914),Map.entry(50303,4010914));
        for (var entry : levels.entrySet()) {
            var player = player(entry.getValue()); Utils.random.setSeed(17);
            assertTrue(StoryCardRuntime.offerLevel(player, entry.getKey()));
            click(player,0); assertEquals(entry.getKey().intValue(), player.state.getDuel().getLevelId());
            playToEnd(player); assertEquals(1,player.state.getDuel().getOutcome(),"level " + entry.getKey());
            if (entry.getKey() == 30101) assertEquals(1,player.state.getTutorialProgress());
            else assertEquals(1,((TrackingQuest)player.main.getChildQuestById(entry.getValue())).finishes,"level " + entry.getKey());
            StoryCardRuntime.onLogout(player);
        }
    }

    @Test
    void tutorialRequiresFourDistinctRealWinsAndResumePreservesHandAndDoesNotRepeatSettlement() throws Exception {
        var player = player(7066505); var guide = activate(player,7066505); var milestones = activate(player,7066519);
        assertFalse(StoryCardRuntime.offerLevel(player,30104));
        Utils.random.setSeed(17);
        for (int expected : List.of(30101,30102,30104,30105)) {
            var game = start(player); assertEquals(expected,game.getLevelId());
            assertEquals(0,game.getOutcome()); assertTrue(game.getOpponentHp() > 0);
            if (expected == 30101) {
                click(player,7); click(player,1);
                int hp = game.getPlayerHp(), round = game.getRound(), ap = game.getActionPoints(), drawn = game.getDrawIndex();
                var hand = List.copyOf(game.getHand()); var deck = List.copyOf(game.getDeck()); var discard = List.copyOf(game.getDiscard());
                assertTrue(ap < 8);
                StoryCardRuntime.onLogout(player);
                player.state = JsonUtils.decode(JsonUtils.encode(player.state),StoryCardState.class);
                assertNull(player.main.resumeOnLogin()); StoryCardRuntime.onTick(player);
                assertEquals(round,player.state.getDuel().getRound()); assertEquals(hp,player.state.getDuel().getPlayerHp());
                assertEquals(hand,player.state.getDuel().getHand()); assertEquals(500,guide.getStartTime());
                assertEquals(ap,player.state.getDuel().getActionPoints()); assertEquals(drawn,player.state.getDuel().getDrawIndex());
                assertEquals(deck,player.state.getDuel().getDeck()); assertEquals(discard,player.state.getDuel().getDiscard());
            }
            playToEnd(player);
            int progress = player.state.getTutorialProgress();
            assertEquals(StoryCardCatalog.installed().tutorialLevels().indexOf(expected) + 1,progress);
            StoryCardRuntime.onLogout(player); StoryCardRuntime.onTick(player); drain();
            assertEquals(progress,player.state.getTutorialProgress()); assertEquals(progress == 4 ? 1 : 0,guide.finishes);
        }
        assertEquals(1,milestones.finishes); assertEquals(1,guide.finishes);
    }

    @Test
    void savedUnprocessedWinReplaysOnceAndExplicitQuestRestartCannotReuseOldProof() throws Exception {
        var player = player(7066602); var quest = activate(player,7066602);
        player.state.record(7066602,500,20001,1); // A server settlement persisted immediately before process exit.
        player.state = JsonUtils.decode(JsonUtils.encode(player.state),StoryCardState.class);
        StoryCardRuntime.onTick(player); drain(); assertEquals(1,quest.finishes);
        StoryCardRuntime.onTick(player); drain(); assertEquals(1,quest.finishes);
        quest.setStartTime(501); quest.setState(QuestState.QUEST_STATE_UNFINISHED);
        StoryCardRuntime.onQuestStart(quest);
        assertFalse(ContentServerState.matchesCurrent(quest,quest.getQuestData().getFinishCond().getFirst()));
        assertFalse(player.state.hasOutcome(7066602,500,20001,1));
    }

    @Test
    void threeNativeLaunchRequestsReturnFailureWithOriginalTagsWithoutInventingAGameOrVictory() throws Exception {
        var player = player(7066602); var session = new Session(player);
        var bytes = new ByteArrayOutputStream(); var wire = CodedOutputStream.newInstance(bytes);
        wire.writeUInt32(3,20001); wire.writeUInt32(7,77); wire.writeUInt32(13,3); wire.flush();
        new HandlerGCGStartChallengeReq().handle(session,new byte[0],bytes.toByteArray());
        var packet = session.sent.getLast(); assertEquals(24456,packet.getOpcode());
        var ordinary = GCGStartChallengeRsp.parseFrom(packet.getData());
        assertEquals(8,ordinary.getRetcode()); assertEquals(20001,ordinary.getLevelId());
        assertEquals(77,ordinary.getConfigId()); assertEquals(3,ordinary.getLevelType());

        bytes = new ByteArrayOutputStream(); wire = CodedOutputStream.newInstance(bytes);
        wire.writeUInt32(1,4); wire.writeUInt32(5,20001); wire.writeUInt32(14,88); wire.flush();
        new HandlerGCGStartChallengeByCheckRewardReq().handle(session,new byte[0],bytes.toByteArray());
        packet = session.sent.getLast(); assertEquals(4269,packet.getOpcode());
        var check = GCGStartChallengeByCheckRewardRsp.parseFrom(packet.getData());
        assertEquals(8,check.getRetcode()); assertEquals(20001,check.getLevelId());
        assertEquals(88,check.getConfigId()); assertEquals(4,check.getLevelType());

        bytes = new ByteArrayOutputStream(); wire = CodedOutputStream.newInstance(bytes);
        wire.writeUInt32(1,20001); wire.writeUInt32(6,91);
        wire.writeTag(14,2); wire.writeUInt32NoTag(2); wire.writeUInt32NoTag(3); wire.writeUInt32NoTag(4); wire.flush();
        new HandlerGCGPVEStartGameReq().handle(session,new byte[0],bytes.toByteArray());
        packet = session.sent.getLast(); assertEquals(3903,packet.getOpcode());
        var pve = GCGPVEStartGameRsp.parseFrom(packet.getData());
        assertEquals(8,pve.getRetcode()); assertEquals(91,pve.getLevelId());
        assertNull(player.state.getDuel()); assertTrue(player.state.getOutcomes().isEmpty());
        assertEquals(0,player.state.getTutorialProgress()); assertEquals(0,player.itemsAdded);
        assertEquals(QuestState.QUEST_STATE_UNFINISHED,player.main.getChildQuestById(7066602).getState());
        assertEquals(1,player.getScene().getEntities().size(),"only a start worktop, no claimed native GCG session");
    }

    private static final class TrackingMain extends GameMainQuest {
        TrackingMain(Player player,int parent) { super(player,parent); }
        @Override public void save() {}
        @Override public void checkProgress() {}
    }
    private static final class TrackingQuest extends GameQuest {
        int finishes, failures;
        TrackingQuest(GameMainQuest main,QuestData data) { super(main,data); }
        @Override public void finish() { finishes++; setState(QuestState.QUEST_STATE_FINISHED); }
        @Override public void fail() { failures++; setState(QuestState.QUEST_STATE_FAILED); }
        @Override public void save() {}
    }
    private static final class CardPlayer extends TestWorld.TestPlayer {
        StoryCardState state = new StoryCardState();
        final GameServer server;
        TrackingMain main;
        CardPlayer() throws Exception {
            Field field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
            server = (GameServer)((Unsafe)field.get(null)).allocateInstance(GameServer.class);
            var system = GameServer.class.getDeclaredField("questSystem"); system.setAccessible(true); system.set(server,new QuestSystem(server));
        }
        @Override public GameServer getServer() { return server; }
        @Override public StoryCardState getStoryCards() { return state; }
        @Override public void save() {}
    }
    private static final class Session extends GameSession {
        final Player player;
        final List<BasePacket> sent = new ArrayList<>();
        Session(Player player) { super(null); this.player = player; }
        @Override public Player getPlayer() { return player; }
        @Override public void send(BasePacket packet) { sent.add(packet); }
    }
}
