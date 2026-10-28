package emu.grasscutter.game.quest.check;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.UnknownFieldSet;
import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.MainQuestData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.SceneType;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.content.ContentServerState;
import emu.grasscutter.game.quest.enums.*;
import emu.grasscutter.game.world.*;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.QuestCheckReqOuterClass.QuestCheckReq;
import emu.grasscutter.net.proto.QuestCheckRspOuterClass.QuestCheckRsp;
import emu.grasscutter.server.game.*;
import emu.grasscutter.server.packet.recv.HandlerQuestCheckReq;
import emu.grasscutter.server.packet.send.PacketQuestCheckRsp;
import emu.grasscutter.utils.*;
import java.nio.file.Files;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import sun.misc.Unsafe;

@ExtendWith(ServerResourceFixture.class)
@ResourceLock("GameData")
final class QuestCheckRuntimeTest {
    private MainQuestData oldMain;
    private final Map<Integer, QuestData> previous = new HashMap<>();
    private final Map<Integer, QuestData> installed = new HashMap<>();

    @BeforeEach
    void loadNativeQuests() throws Exception {
        var nativeQuest = JsonParser.parseString(Files.readString(FileUtils.getResourcePath(
                "BinOutput/Quest/19182.json"))).getAsJsonObject();
        var children = new ArrayList<String>();
        for (var value : nativeQuest.getAsJsonArray("subQuests")) {
            var row = value.getAsJsonObject().deepCopy(); int id = row.get("subId").getAsInt();
            if (id < 1918215 || id > 1918224) continue;
            for (var name : List.of("acceptCond", "failCond", "beginExec", "finishExec", "failExec"))
                if (!row.has(name)) row.add(name, new com.google.gson.JsonArray());
            var data = JsonUtils.decode(row, QuestData.class);
            data.onLoad();
            previous.put(id, GameData.getQuestDataMap().put(id, data));
            installed.put(id, data);
            children.add("{subId:" + id + "}");
        }
        oldMain = GameData.getMainQuestDataMap().put(19182, JsonUtils.decode(
                "{id:19182,subQuests:[" + String.join(",", children) + "]}", MainQuestData.class));
    }

    @AfterEach
    void restore() throws Exception {
        drain();
        if (oldMain == null) GameData.getMainQuestDataMap().remove(19182);
        else GameData.getMainQuestDataMap().put(19182, oldMain);
        previous.forEach((id, value) -> {
            if (value == null) GameData.getQuestDataMap().remove(id);
            else GameData.getQuestDataMap().put(id, value);
        });
    }

    private static void drain() throws Exception { QuestManager.eventExecutor.submit(() -> {}).get(10, TimeUnit.SECONDS); }

    private CheckPlayer player() throws Exception {
        var player = new CheckPlayer();
        var world = TestWorld.create(player);
        player.setScene(new TestWorld.TestScene(world, 3, SceneType.SCENE_WORLD));
        player.setSceneId(3);
        player.setSceneLoadState(Player.SceneLoadState.LOADED);
        player.getPosition().set(QuestCheckCatalog.installed().check(1005).position());
        var main = new TrackingMain(player);
        for (var data : installed.values()) main.getChildQuests().put(data.getId(), new TrackingQuest(main, data));
        player.getQuestManager().getMainQuests().put(19182, main);
        player.main = main;
        for (int id = 1918215; id <= 1918219; id++) {
            main.getChildQuestById(id).setState(QuestState.QUEST_STATE_UNFINISHED);
            main.getChildQuestById(id).setStartTime(500);
        }
        return player;
    }

    @Test
    void actualBonusAndDiceResourceDriveBothOutcomesAndMissingIsNotFailure() throws Exception {
        var player = player();
        var outcome = player.main.getChildQuestById(1918220);
        var success = outcome.getQuestData().getFinishCond().getFirst();
        var failure = outcome.getQuestData().getFailCond().getFirst();
        assertEquals(174, success.getType().getValue());
        assertTrue(success.getType().isServerStatePredicate());
        assertFalse(ContentServerState.matchesCurrent(outcome, success));
        assertFalse(ContentServerState.matchesCurrent(outcome, failure));
        var result = QuestCheckRuntime.roll(player, 19182, 1005, faces -> { assertEquals(20, faces); return 10; });
        assertEquals(0, result.retcode());
        assertEquals(10, result.result().getDieRoll());
        assertEquals(15, result.result().getTotalRoll());
        assertEquals(List.of(100501), result.result().getAppliedBonusIds());
        assertTrue(result.result().isSuccess());
        assertTrue(ContentServerState.matchesCurrent(outcome, success));
        assertFalse(ContentServerState.matchesCurrent(outcome, failure));

        var failed = player();
        failed.getQuestGlobalVariables().put(1918206, 1);
        var losing = QuestCheckRuntime.roll(failed, 19182, 1005, faces -> 10);
        assertEquals(10, losing.result().getTotalRoll());
        assertTrue(losing.result().getAppliedBonusIds().isEmpty());
        assertFalse(losing.result().isSuccess());
        assertTrue(ContentServerState.matchesCurrent(failed.main.getChildQuestById(1918220), failure));
    }

    @Test
    void finishingDialogueAloneCannotManufactureSuccessfulOrFailedOutcome() throws Exception {
        for (boolean win : List.of(true, false)) {
            var player = player();
            var outcome = (TrackingQuest) player.main.getChildQuestById(1918220);
            outcome.setState(QuestState.QUEST_STATE_UNFINISHED);
            assertEquals(LogicType.LOGIC_AND, outcome.getQuestData().getFinishCondComb());
            assertEquals(LogicType.LOGIC_AND, outcome.getQuestData().getFailCondComb());
            QuestCheckRuntime.roll(player, 19182, 1005, faces -> win ? 20 : 1);
            drain();
            assertEquals(0, outcome.finishes + outcome.failures);
            player.main.getChildQuestById(1918215).setState(QuestState.QUEST_STATE_FINISHED);
            player.getQuestManager().triggerEvent(QuestContent.QUEST_CONTENT_QUEST_STATE_EQUAL, "", 1918215, 3);
            assertEquals(win ? 1 : 0, outcome.finishes);
            assertEquals(win ? 0 : 1, outcome.failures);
        }
    }

    @Test
    void nativeRequestsRejectUnknownParentRemotePointAndUnstartedDialogueWithoutRolling() throws Exception {
        var player = player(); int[] rolls = {0};
        java.util.function.IntUnaryOperator dice = faces -> { rolls[0]++; return 20; };
        assertNotEquals(0, QuestCheckRuntime.roll(player, 19180, 1005, dice).retcode());
        assertNotEquals(0, QuestCheckRuntime.roll(player, 19182, 999999, dice).retcode());
        player.getPosition().setX(player.getPosition().getX() + 100);
        assertNotEquals(0, QuestCheckRuntime.roll(player, 19182, 1005, dice).retcode());
        player.getPosition().set(QuestCheckCatalog.installed().check(1005).position());
        player.main.getChildQuestById(1918215).setState(QuestState.QUEST_STATE_UNSTARTED);
        assertNotEquals(0, QuestCheckRuntime.roll(player, 19182, 1005, dice).retcode());
        assertEquals(0, rolls[0]);
        assertEquals(0, player.saves);
    }

    @Test
    void retryAndForgedClientRollFieldsReturnOriginalReceiptWithoutAnotherRoll() throws Exception {
        var player = player();
        var first = QuestCheckRuntime.roll(player, 19182, 1005, faces -> 1);
        assertFalse(first.result().isSuccess());
        assertSame(first.result(), QuestCheckRuntime.roll(player, 19182, 1005,
                faces -> { fail("duplicate request must not roll again"); return 20; }).result());
        var session = new RecordingSession(player);
        var request = QuestCheckReq.newBuilder().setParentQuestId(19182).setCheckId(1005)
                .setUnknownFields(UnknownFieldSet.newBuilder().addField(14,
                        UnknownFieldSet.Field.newBuilder().addVarint(99999).build()).build()).build();
        new HandlerQuestCheckReq().handle(session, new byte[0], request.toByteArray());
        var reply = QuestCheckRsp.parseFrom(session.sent.getFirst().getData());
        assertFalse(reply.getIsSuccess());
        assertEquals(6, reply.getTotalRoll());
        assertEquals(List.of(100501), reply.getAppliedBonusIdListList());
        assertEquals(1, player.saves);
    }

    @Test
    void savedRoundSurvivesRealLoginResumeAndExplicitRestartDiscardsItsReceipt() throws Exception {
        var player = player();
        QuestCheckRuntime.roll(player, 19182, 1005, faces -> 1);
        player.state = JsonUtils.decode(JsonUtils.encode(player.state), QuestCheckState.class);
        assertNull(player.main.resumeOnLogin());
        assertEquals(500, player.main.getChildQuestById(1918215).getStartTime());
        assertFalse(QuestCheckRuntime.roll(player, 19182, 1005,
                faces -> { fail("login must not re-roll"); return 20; }).result().isSuccess());
        var source = player.main.getChildQuestById(1918215);
        source.setStartTime(501);
        QuestCheckRuntime.onQuestStart(source);
        assertNull(player.state.get(1005));
        assertTrue(QuestCheckRuntime.roll(player, 19182, 1005, faces -> 20).result().isSuccess());
    }

    @Test
    void recoveredNativeWireKeepsItsVerifiedRequestAndResponseTags() throws Exception {
        var request = QuestCheckReq.newBuilder().setCheckId(1005).setParentQuestId(19182).build();
        var bytes = CodedInputStream.newInstance(request.toByteArray());
        assertEquals(32, bytes.readTag()); assertEquals(1005, bytes.readUInt32());
        assertEquals(120, bytes.readTag()); assertEquals(19182, bytes.readUInt32());
        assertEquals(0, bytes.readTag());
        var player = player();
        var packet = new PacketQuestCheckRsp(QuestCheckRuntime.roll(player, 19182, 1005, faces -> 20));
        assertEquals(23568, packet.getOpcode());
        var response = QuestCheckRsp.parseFrom(packet.getData());
        assertEquals(1005, response.getCheckId()); assertEquals(19182, response.getParentQuestId());
        assertEquals(25, response.getTotalRoll()); assertTrue(response.getIsSuccess());
        assertEquals(List.of(100501), response.getAppliedBonusIdListList());
    }

    private static final class TrackingMain extends GameMainQuest {
        TrackingMain(Player player) { super(player, 19182); }
        @Override public void save() {}
        @Override public void checkProgress() {}
    }
    private static final class TrackingQuest extends GameQuest {
        int finishes, failures;
        TrackingQuest(GameMainQuest main, QuestData data) { super(main, data); }
        @Override public void finish() { finishes++; setState(QuestState.QUEST_STATE_FINISHED); }
        @Override public void fail() { failures++; setState(QuestState.QUEST_STATE_FAILED); }
        @Override public void save() {}
    }
    private static final class CheckPlayer extends TestWorld.TestPlayer {
        QuestCheckState state = new QuestCheckState();
        final GameServer server;
        TrackingMain main;
        int saves;
        CheckPlayer() throws Exception {
            Field field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
            server = (GameServer) ((Unsafe) field.get(null)).allocateInstance(GameServer.class);
            var questSystem = GameServer.class.getDeclaredField("questSystem"); questSystem.setAccessible(true);
            questSystem.set(server, new QuestSystem(server));
        }
        @Override public GameServer getServer() { return server; }
        @Override public QuestCheckState getQuestCheckState() { return state; }
        @Override public void save() { saves++; }
    }
    private static final class RecordingSession extends GameSession {
        final Player player;
        final List<BasePacket> sent = new ArrayList<>();
        RecordingSession(Player player) { super(null); this.player = player; }
        @Override public Player getPlayer() { return player; }
        @Override public void send(BasePacket packet) { sent.add(packet); }
    }
}
