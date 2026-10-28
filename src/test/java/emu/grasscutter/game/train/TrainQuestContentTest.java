package emu.grasscutter.game.train;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.MainQuestData;
import emu.grasscutter.data.excels.GadgetData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.entity.EntityGadget;
import emu.grasscutter.game.entity.GameEntity;
import emu.grasscutter.game.inventory.Inventory;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.SceneType;
import emu.grasscutter.game.quest.GameMainQuest;
import emu.grasscutter.game.quest.QuestManager;
import emu.grasscutter.game.quest.content.ContentCallPlayerTrainInRange;
import emu.grasscutter.game.quest.enums.QuestContent;
import emu.grasscutter.game.quest.enums.QuestState;
import emu.grasscutter.game.world.Position;
import emu.grasscutter.game.world.Scene;
import emu.grasscutter.game.world.TestWorld;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.AddQuestContentProgressBatchReq._AddQuestContentProgressBatchReq;
import emu.grasscutter.net.proto.AddQuestContentProgressReqOuterClass.AddQuestContentProgressReq;
import emu.grasscutter.net.proto.BMJNJLKCODGOuterClass.BMJNJLKCODG;
import emu.grasscutter.net.proto.QuestProgressInfo._QuestProgressInfo;
import emu.grasscutter.net.proto.SnezhnayaTrainCallReqOuterClass.SnezhnayaTrainCallReq;
import emu.grasscutter.net.proto.SnezhnayaTrainSetRouteReqOuterClass.SnezhnayaTrainSetRouteReq;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.packet.recv.HandlerAddQuestContentProgressBatchReq;
import emu.grasscutter.server.packet.recv.HandlerAddQuestContentProgressReq;
import emu.grasscutter.utils.FileUtils;
import emu.grasscutter.utils.JsonUtils;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.*;

final class TrainQuestContentTest {
    private static final QuestContent CALL = QuestContent.QUEST_CONTENT_CALL_PLAYER_TRAIN_IN_RANGE;
    private static final AtomicInteger UID = new AtomicInteger(80000);
    private final Map<Integer, MainQuestData> previousMains = new HashMap<>();
    private final Map<Integer, QuestData> previousSubs = new HashMap<>();
    private GadgetData previousGadget;

    private static final class SavedPlayer extends Player {
        final Inventory ownedWidget = new Inventory(this) {
            @Override public int getItemCountById(int id) {
                return id == SnezhnayaTrainSystem.GREY_EYES_WIDGET ? 1 : 0;
            }
        };
        @Override public Inventory getInventory() { return ownedWidget; }
        @Override public boolean isOnline() { return true; }
        @Override public void sendPacket(BasePacket packet) {}
        @Override public void save() {}
    }

    private record Report(QuestContent type, int[] parameters) {}

    private static final class RecordingMain extends GameMainQuest {
        final List<Report> reports = new ArrayList<>();
        RecordingMain(Player player, int main) { super(player, main); }
        @Override public void tryFailSubQuests(QuestContent type, String value, int... parameters) {}
        @Override public void tryFinishSubQuests(QuestContent type, String value, int... parameters) {
            reports.add(new Report(type, parameters));
            if (type != CALL) return;
            for (var quest : getActiveQuests()) {
                var condition = quest.getQuestData().getFinishCond().getFirst();
                if (new ContentCallPlayerTrainInRange().execute(quest, condition, value, parameters))
                    quest.getFinishProgressList()[0] = 1;
            }
        }
    }

    private static final class TrainScene extends TestWorld.TestScene {
        TrainScene(TestWorld world, int id) { super(world, id, SceneType.SCENE_ROOM); }
        @Override public synchronized void addEntity(GameEntity entity) {
            getEntities().put(entity.getId(), entity);
        }
    }

    @BeforeEach
    void loadTheTwoNativeTrainObjectives() throws Exception {
        for (int id : new int[] {701326, 701608}) {
            int main = id / 100;
            var data = JsonParser.parseString(Files.readString(
                    FileUtils.getResourcePath("BinOutput/Quest/" + main + ".json"))).getAsJsonObject();
            var sub = java.util.stream.StreamSupport.stream(data.getAsJsonArray("subQuests").spliterator(), false)
                    .map(value -> value.getAsJsonObject())
                    .filter(value -> value.get("subId").getAsInt() == id).findFirst().orElseThrow();
            var condition = java.util.stream.StreamSupport.stream(sub.getAsJsonArray("finishCond").spliterator(), false)
                    .map(value -> value.getAsJsonObject())
                    .filter(value -> CALL.name().equals(value.get("type").getAsString()))
                    .findFirst().orElseThrow();
            var quest = JsonUtils.decode("{mainId:" + main + ",subId:" + id
                    + ",finishCond:[" + condition + "],failCond:[]}", QuestData.class);
            previousSubs.put(id, GameData.getQuestDataMap().put(id, quest));
            previousMains.put(main, GameData.getMainQuestDataMap().put(main, JsonUtils.decode(
                    "{id:" + main + ",subQuests:[{subId:" + id + "}]}", MainQuestData.class)));
        }
        previousGadget = GameData.getGadgetDataMap().put(SnezhnayaTrainSystem.PLAYER_TRAIN_GADGET,
                JsonUtils.decode("{id:73094085,jsonName:'TestTrain'}", GadgetData.class));
    }

    @AfterEach
    void restoreSharedResources() throws Exception {
        flushEvents();
        previousMains.forEach((id, data) -> {
            if (data == null) GameData.getMainQuestDataMap().remove(id.intValue());
            else GameData.getMainQuestDataMap().put(id.intValue(), data);
        });
        previousSubs.forEach((id, data) -> {
            if (data == null) GameData.getQuestDataMap().remove(id.intValue());
            else GameData.getQuestDataMap().put(id.intValue(), data);
        });
        if (previousGadget == null) GameData.getGadgetDataMap().remove(SnezhnayaTrainSystem.PLAYER_TRAIN_GADGET);
        else GameData.getGadgetDataMap().put(SnezhnayaTrainSystem.PLAYER_TRAIN_GADGET, previousGadget);
    }

    private static void flushEvents() throws Exception { QuestManager.eventExecutor.submit(() -> {}).get(); }

    private static SavedPlayer player() {
        var player = new SavedPlayer();
        player.setUid(UID.incrementAndGet());
        player.setScene(new TrainScene(TestWorld.create(player), 3));
        for (int id : new int[] {7013, 7016}) {
            var main = new RecordingMain(player, id);
            player.getQuestManager().getMainQuests().put(id, main);
        }
        player.getQuestManager().getQuestById(701326).setState(QuestState.QUEST_STATE_UNFINISHED);
        return player;
    }

    private static RecordingMain main(Player player) {
        return (RecordingMain) player.getQuestManager().getMainQuestById(7013);
    }

    private static EntityGadget spawn(Player player) {
        SnezhnayaTrainSystem.onEnterSceneDone(player);
        return player.getScene().getEntities().values().stream().map(entity -> (EntityGadget) entity)
                .filter(entity -> entity.getCurveMoveInfo() != null)
                .findFirst().orElseThrow();
    }

    private static SnezhnayaTrainCallReq call() {
        return SnezhnayaTrainCallReq.newBuilder().setCurveInfo(BMJNJLKCODG.newBuilder()
                .setEEFABNDBKCO(371200117).setHMHCGLCNBMK(1)).build();
    }

    private static void tick(Scene scene) {
        assertNotNull(scene.getScheduler().getTask(0));
        scene.getScheduler().getTask(0).run();
    }

    @Test
    void nativeRadiusRequiresTheRealTrainInTheSameLoadedScene() {
        var player = player();
        var quest = player.getQuestManager().getQuestById(701326);
        var condition = quest.getQuestData().getFinishCond().getFirst();
        assertArrayEquals(new int[] {3, 9}, condition.getParam());
        var train = spawn(player);
        var center = new Position(8545.332f, 211.571f, 7803.098f);
        train.getPosition().set(center);
        assertTrue(ContentCallPlayerTrainInRange.matches(quest, condition, train));
        train.getPosition().addY(10);
        assertFalse(ContentCallPlayerTrainInRange.matches(quest, condition, train), "height is part of the radius");
        train.getPosition().set(center).addX(9);
        assertTrue(ContentCallPlayerTrainInRange.matches(quest, condition, train));
        train.getPosition().addX(0.1f);
        assertFalse(ContentCallPlayerTrainInRange.matches(quest, condition, train));
        train.getPosition().set(center);
        train.setGadgetId(73094006);
        assertFalse(ContentCallPlayerTrainInRange.matches(quest, condition, train));
        train.setGadgetId(SnezhnayaTrainSystem.PLAYER_TRAIN_GADGET);
        player.getScene().getEntities().remove(train.getId());
        assertFalse(ContentCallPlayerTrainInRange.matches(quest, condition, train));
        player.getScene().getEntities().put(train.getId(), train);
        player.setScene(new TrainScene((TestWorld) player.getWorld(), 3));
        assertFalse(ContentCallPlayerTrainInRange.matches(quest, condition, train), "same numeric scene in a different instance is insufficient");
    }

    @Test
    void absentMalformedOrWrongSceneCoordinatesCannotSatisfyAnObjective() {
        var player = player();
        var quest = player.getQuestManager().getQuestById(701326);
        var train = spawn(player);
        for (String point : new String[] {"", "missing", "[3_1:1,2]", "[3_1:NaN,0,0]", "[4_1:1,2,3]"}) {
            var condition = JsonUtils.decode("{param:[3,9],param_str:'" + point + "'}", QuestData.QuestContentCondition.class);
            assertFalse(ContentCallPlayerTrainInRange.matches(quest, condition, train), point);
        }
        var noPoint = JsonUtils.decode("{param:[3,9]}", QuestData.QuestContentCondition.class);
        assertFalse(ContentCallPlayerTrainInRange.matches(quest, noPoint, train));
        var condition = quest.getQuestData().getFinishCond().getFirst();
        assertFalse(new ContentCallPlayerTrainInRange().execute(quest, condition, "", 3));
        assertFalse(new ContentCallPlayerTrainInRange().execute(quest, condition, "", 3, train.getId(), 701608));
    }

    @Test
    void callWaitsForActualPositionSyncThenReportsOnlyOnce() throws Exception {
        var player = player();
        var train = spawn(player);
        SnezhnayaTrainSystem.onCall(player, call());
        flushEvents();
        assertTrue(main(player).reports.isEmpty(), "the distant parked train cannot satisfy the call");
        train.getPosition().set(8545.332f, 211.571f, 7803.098f);
        tick(player.getScene());
        flushEvents();
        assertEquals(1, main(player).reports.size());
        assertArrayEquals(new int[] {3, train.getId(), 701326, Float.floatToIntBits(8545.332f),
                Float.floatToIntBits(211.571f), Float.floatToIntBits(7803.098f)}, main(player).reports.getFirst().parameters());
        assertEquals(1, player.getQuestManager().getQuestById(701326).getFinishProgressList()[0]);
        tick(player.getScene());
        flushEvents();
        assertEquals(1, main(player).reports.size());
    }

    @Test
    void laterMovementCannotEraseAnAlreadyVerifiedCallWhileItsEventIsQueued() throws Exception {
        var player = player();
        var train = spawn(player);
        train.getPosition().set(8545.332f, 211.571f, 7803.098f);
        var release = new CountDownLatch(1);
        QuestManager.eventExecutor.submit(() -> {
            try { release.await(); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        });
        try {
            SnezhnayaTrainSystem.onCall(player, call());
            train.getPosition().set(0, 0, 0);
        } finally {
            release.countDown();
        }
        flushEvents();
        assertEquals(1, main(player).reports.size());
        assertEquals(1, player.getQuestManager().getQuestById(701326).getFinishProgressList()[0]);
    }

    @Test
    void missingTrainAndAStaleCallCannotCompleteANewQuest() throws Exception {
        var player = player();
        SnezhnayaTrainSystem.onCall(player, call());
        flushEvents();
        assertTrue(main(player).reports.isEmpty());
        var train = spawn(player);
        SnezhnayaTrainSystem.onCall(player, call());
        player.getQuestManager().getQuestById(701326).setState(QuestState.QUEST_STATE_FINISHED);
        var later = player.getQuestManager().getQuestById(701608);
        later.setState(QuestState.QUEST_STATE_UNFINISHED);
        assertArrayEquals(new int[] {3, 35}, later.getQuestData().getFinishCond().getFirst().getParam());
        train.getPosition().set(8731.417f, 292.800f, 6488.940f);
        tick(player.getScene());
        flushEvents();
        assertTrue(main(player).reports.isEmpty());
        assertEquals(0, later.getFinishProgressList()[0]);
        SnezhnayaTrainSystem.onCall(player, call());
        flushEvents();
        assertEquals(1, later.getFinishProgressList()[0]);
    }

    @Test
    void departureOrSceneExitRevokesThePendingCall() throws Exception {
        var player = player();
        var train = spawn(player);
        SnezhnayaTrainSystem.onCall(player, call());
        SnezhnayaTrainSystem.onSetRoute(player, SnezhnayaTrainSetRouteReq.getDefaultInstance());
        train.getPosition().set(8545.332f, 211.571f, 7803.098f);
        tick(player.getScene());
        flushEvents();
        assertTrue(main(player).reports.isEmpty());
        train.getPosition().set(0, 0, 0);
        SnezhnayaTrainSystem.onCall(player, call());
        var oldScene = player.getScene();
        player.setScene(new TrainScene((TestWorld) player.getWorld(), 4));
        train.getPosition().set(8545.332f, 211.571f, 7803.098f);
        tick(oldScene);
        flushEvents();
        assertTrue(main(player).reports.isEmpty());
        assertNull(oldScene.getScheduler().getTask(0));
    }

    @Test
    void aNewSceneCallReplacesTheOldScenesSchedulerBeforeItsNextTick() throws Exception {
        var player = player();
        var previousTrain = spawn(player);
        SnezhnayaTrainSystem.onCall(player, call());
        var oldScene = player.getScene();
        var staleTick = oldScene.getScheduler().getTask(0);
        player.setScene(new TrainScene((TestWorld) player.getWorld(), 3));
        var anotherOwnersTrain = new EntityGadget(player.getScene(), SnezhnayaTrainSystem.PLAYER_TRAIN_GADGET,
                new Position(), new Position());
        anotherOwnersTrain.setId(previousTrain.getId());
        player.getScene().getEntities().put(anotherOwnersTrain.getId(), anotherOwnersTrain);
        var train = spawn(player);
        assertNotSame(anotherOwnersTrain, train);
        SnezhnayaTrainSystem.onCall(player, call());
        assertNull(oldScene.getScheduler().getTask(0));
        staleTick.run();
        train.getPosition().set(8545.332f, 211.571f, 7803.098f);
        tick(player.getScene());
        flushEvents();
        assertEquals(1, main(player).reports.size());
        assertEquals(1, player.getQuestManager().getQuestById(701326).getFinishProgressList()[0]);
    }

    @Test
    void singleAndBatchClientReportsCannotForgeTrainOrVehicleProgress() throws Exception {
        var player = player();
        var session = new GameSession(null) {
            @Override public Player getPlayer() { return player; }
            @Override public void send(BasePacket packet) {}
        };
        var batch = _AddQuestContentProgressBatchReq.newBuilder();
        for (var type : List.of(CALL, QuestContent.QUEST_CONTENT_ENTER_VEHICLE,
                QuestContent.QUEST_CONTENT_ENTER_FEATURE_TAG_VEHICLE,
                QuestContent.QUEST_CONTENT_LEAVE_FEATURE_TAG_VEHICLE, QuestContent.QUEST_CONTENT_LEAVE_VEHICLE)) {
            var request = AddQuestContentProgressReq.newBuilder().setContentType(type.getValue()).setParam(3).build();
            new HandlerAddQuestContentProgressReq().handle(session, new byte[0], request.toByteArray());
            batch.addProgressInfoList(_QuestProgressInfo.newBuilder().setContentType(type.getValue()).setParam(3));
        }
        new HandlerAddQuestContentProgressBatchReq().handle(session, new byte[0], batch.build().toByteArray());
        flushEvents();
        assertTrue(main(player).reports.isEmpty());
        batch.clearProgressInfoList().addProgressInfoList(_QuestProgressInfo.newBuilder()
                .setContentType(QuestContent.QUEST_CONTENT_LUA_NOTIFY.getValue()).setParam(7));
        new HandlerAddQuestContentProgressBatchReq().handle(session, new byte[0], batch.build().toByteArray());
        flushEvents();
        assertEquals(1, main(player).reports.size());
        assertEquals(QuestContent.QUEST_CONTENT_LUA_NOTIFY, main(player).reports.getFirst().type());
    }
}
