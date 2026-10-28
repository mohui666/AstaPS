package emu.grasscutter.game.quest;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.MainQuestData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.data.excels.world.WeatherData;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.*;
import emu.grasscutter.game.quest.enums.*;
import emu.grasscutter.game.quest.exec.ExecSetSceneVar;
import emu.grasscutter.game.world.*;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.SceneAreaWeatherNotifyOuterClass.SceneAreaWeatherNotify;
import emu.grasscutter.scripts.ScriptLoader;
import emu.grasscutter.server.game.*;
import emu.grasscutter.server.packet.send.PacketSceneAreaWeatherNotify;
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
final class QuestSceneVarReconstructionTest {
    private final List<Runnable> undo = new ArrayList<>();
    private Map<Integer, WeatherData> previousWeather;
    private boolean scripts;
    private GameServer server;
    private QuestSystem system;
    private BattlePlayer player;

    @BeforeEach
    void prepare() throws Exception {
        scripts = Grasscutter.getConfig().server.game.enableScriptInBigWorld;
        Grasscutter.getConfig().server.game.enableScriptInBigWorld = false;
        ScriptLoader.init();
        previousWeather = new HashMap<>(GameData.getWeatherDataMap());
        GameData.getWeatherDataMap().clear();
        for (var value : JsonUtils.loadToList(FileUtils.getExcelPath("WeatherExcelConfigData.json"), WeatherData.class)) {
            if (value.getId() == 7048 || value.getId() == 4037 || value.getId() == 4038)
                GameData.getWeatherDataMap().put(value.getId(), value);
        }
        var nativeMain = JsonUtils.decode(Files.readString(
                FileUtils.getResourcePath("BinOutput/Quest/77160.json")), MainQuestData.class);
        install(GameData.getMainQuestDataMap(), 77160, nativeMain);
        var rows = JsonParser.parseString(Files.readString(
                FileUtils.getExcelPath("QuestExcelConfigData.json"))).getAsJsonArray();
        for (var value : rows) {
            var row = value.getAsJsonObject();
            if (row.get("mainId").getAsInt() != 77160) continue;
            var data = JsonUtils.decode(row, QuestData.class);
            install(GameData.getQuestDataMap(), data.getId(), data);
        }
        server = (GameServer) unsafe().allocateInstance(GameServer.class);
        system = new QuestSystem(server);
        player = player(42);
    }

    @AfterEach
    void restore() throws Exception {
        drain();
        Collections.reverse(undo);
        undo.forEach(Runnable::run);
        GameData.getWeatherDataMap().clear();
        GameData.getWeatherDataMap().putAll(previousWeather);
        Grasscutter.getConfig().server.game.enableScriptInBigWorld = scripts;
    }

    @Test
    void originalFinishExecDispatchPersistsTheRealWeatherAndSendsItsNativeNotification() throws Exception {
        var quest = source(player);
        quest.setState(QuestState.QUEST_STATE_FINISHED);
        var action = originalAction(quest, QuestExec.QUEST_EXEC_SET_SCENE_VAR);
        assertArrayEquals(new String[] {"3", "Scenevar_GZJR", "0"}, action.getParam());
        system.triggerExec(quest, action, action.getParam());
        drain();
        assertEquals(Map.of(7048, 1), player.getQuestWeatherAreas());
        assertEquals(Map.of(7048, 1), player.savedAreas);
        assertEquals(1, player.saves);
        assertEquals(7048, player.getWeatherId());
        assertEquals(ClimateType.CLIMATE_SNOW, player.getClimate());
        assertEquals(1, player.packets.size());
        assertInstanceOf(PacketSceneAreaWeatherNotify.class, player.packets.getFirst());
        var wire = SceneAreaWeatherNotify.parseFrom(player.packets.getFirst().getData());
        assertEquals(7048, wire.getWeatherAreaId());
        assertEquals(ClimateType.CLIMATE_SNOW.getValue(), wire.getClimateType());
        assertEquals(0, wire.getWeatherGadgetId());
        assertTrue(new ExecSetSceneVar().execute(quest, action, action.getParam()));
        assertEquals(1, player.saves);
        assertEquals(1, player.packets.size());
    }

    @Test
    void unknownTaskArgumentActionAndUnfinishedStageCannotEnableAnything() {
        var quest = source(player);
        var action = originalAction(quest, QuestExec.QUEST_EXEC_SET_SCENE_VAR);
        var handler = new ExecSetSceneVar();
        assertFalse(handler.execute(quest, action, action.getParam()));
        quest.setState(QuestState.QUEST_STATE_FINISHED);
        for (var params : List.of(new String[] {"3", "Scenevar_GZJR", "1"},
                new String[] {"4", "Scenevar_GZJR", "0"}, new String[] {"3", "Other", "0"},
                new String[] {"3", "Scenevar_GZJR"}, new String[] {"3", "Scenevar_GZJR", "0", "extra"}))
            assertFalse(handler.execute(quest, action, params));
        assertFalse(handler.execute(quest, null, action.getParam()));
        var detachedAction = JsonUtils.decode(JsonUtils.encode(action), QuestData.QuestExecParam.class);
        assertFalse(handler.execute(quest, detachedAction, detachedAction.getParam()));
        assertFalse(handler.execute(player.main.getChildQuests().get(7716006), action, action.getParam()));
        var detachedMain = new GameMainQuest(player, 77160);
        var detachedQuest = detachedMain.getChildQuests().get(7716008);
        detachedQuest.setState(QuestState.QUEST_STATE_FINISHED);
        assertFalse(handler.execute(detachedQuest, action, action.getParam()));
        assertTrue(player.getQuestWeatherAreas().isEmpty());
        assertTrue(player.packets.isEmpty());
        assertEquals(0, player.saves);
    }

    @Test
    void theTwoAuthoredLevelsHaveSeparateXZAndHeightLimits() throws Exception {
        enable(player);
        assertEquals(7048, QuestWeatherAreas.select(player, 3, new Position(10418.6f, 790.569f, 5518.57f)));
        assertEquals(7048, QuestWeatherAreas.select(player, 3, new Position(10761.6f, -605.418f, 5584.598f)));
        for (var position : List.of(new Position(10418, 500, 5518), new Position(10418, -608, 5518),
                new Position(10761, 790, 5584), new Position(10761, -701, 5584),
                new Position(10761, -499, 5584), new Position(10418, 851, 5518),
                new Position(10418, 790, 5800), new Position(11000, -608, 5584)))
            assertEquals(0, QuestWeatherAreas.select(player, 3, position), position.toString());
        player.getPosition().set(10418, 500, 5518);
        QuestWeatherAreas.update(player, false);
        assertEquals(0, player.getWeatherId());
        assertEquals(-1, QuestWeatherAreas.enter(player.getScene(), 42, 7048));
        player.getPosition().set(10761, -608, 5584);
        QuestWeatherAreas.update(player, false);
        assertEquals(7048, player.getWeatherId());
    }

    @Test
    void theOriginalFinalDialogueWeatherActionDisablesThePersistedArea() throws Exception {
        enable(player);
        var cleanup = player.main.getChildQuests().get(7716015);
        var action = originalAction(cleanup, QuestExec.QUEST_EXEC_MODIFY_WEATHER_AREA);
        assertArrayEquals(new String[] {"7048", "0"}, action.getParam());
        cleanup.setState(QuestState.QUEST_STATE_FINISHED);
        // Selection already stops on the real completed stage, before the asynchronous native action runs.
        assertEquals(0, QuestWeatherAreas.select(player, 3, player.getPosition()));
        system.triggerExec(cleanup, action, action.getParam());
        drain();
        assertEquals(Map.of(7048, 0), player.savedAreas);
        assertEquals(0, player.getWeatherId());
        assertEquals(0, lastWeather(player).getWeatherAreaId());
        assertFalse(new ExecSetSceneVar().execute(source(player),
                originalAction(source(player), QuestExec.QUEST_EXEC_SET_SCENE_VAR), "3", "Scenevar_GZJR", "0"));
    }

    @Test
    void rollbackCancellationAndParentRemovalStopVisibleWeatherWithoutRewritingTheEnable() throws Exception {
        enable(player);
        for (var state : List.of(QuestState.QUEST_STATE_UNSTARTED, QuestState.QUEST_STATE_UNFINISHED,
                QuestState.QUEST_STATE_FAILED)) {
            source(player).setState(state);
            QuestWeatherAreas.update(player, false);
            assertEquals(0, player.getWeatherId());
            source(player).setState(QuestState.QUEST_STATE_FINISHED);
            QuestWeatherAreas.update(player, false);
            assertEquals(7048, player.getWeatherId());
        }
        for (var state : List.of(ParentQuestState.PARENT_QUEST_STATE_FAILED,
                ParentQuestState.PARENT_QUEST_STATE_CANCELED, ParentQuestState.PARENT_QUEST_STATE_FINISHED)) {
            parentState(player.main, state);
            QuestWeatherAreas.update(player, false);
            assertEquals(0, player.getWeatherId());
            parentState(player.main, ParentQuestState.PARENT_QUEST_STATE_NONE);
        }
        player.manager.getMainQuests().remove(77160);
        QuestWeatherAreas.update(player, false);
        assertEquals(0, player.getWeatherId());
        assertEquals(Map.of(7048, 1), player.getQuestWeatherAreas());
    }

    @Test
    void persistedEnableResumesOnlyWithTheRealQuestPhaseAndNeverLeaksToAnotherScene() throws Exception {
        enable(player);
        var saved = JsonParser.parseString(JsonUtils.encode(player.savedAreas)).getAsJsonObject();
        var relog = player(43);
        for (var entry : saved.entrySet()) relog.getQuestWeatherAreas().put(
                Integer.parseInt(entry.getKey()), entry.getValue().getAsInt());
        assertEquals(0, QuestWeatherAreas.select(relog, 3, relog.getPosition()));
        source(relog).setState(QuestState.QUEST_STATE_FINISHED);
        QuestWeatherAreas.update(relog, true);
        assertEquals(7048, relog.getWeatherId());
        assertEquals(0, relog.saves);
        relog.setScene(new TestWorld.TestScene(relog.getWorld(), 1, SceneType.SCENE_ROOM));
        relog.setSceneId(1);
        var wire = SceneAreaWeatherNotify.parseFrom(new PacketSceneAreaWeatherNotify(relog).getData());
        assertEquals(0, wire.getWeatherAreaId());
        assertEquals(ClimateType.CLIMATE_SUNNY.getValue(), wire.getClimateType());
    }

    @Test
    void visitorsCannotEnableTheHostsBattleEnvironment() throws Exception {
        source(player).setState(QuestState.QUEST_STATE_FINISHED);
        var guest = player(44);
        source(guest).setState(QuestState.QUEST_STATE_FINISHED);
        guest.setWorld(player.getWorld());
        var action = originalAction(source(guest), QuestExec.QUEST_EXEC_SET_SCENE_VAR);
        assertFalse(new ExecSetSceneVar().execute(source(guest), action, action.getParam()));
        assertTrue(guest.getQuestWeatherAreas().isEmpty());
        assertTrue(player.getQuestWeatherAreas().isEmpty());
    }

    private void enable(BattlePlayer owner) throws Exception {
        var quest = source(owner);
        quest.setState(QuestState.QUEST_STATE_FINISHED);
        var action = originalAction(quest, QuestExec.QUEST_EXEC_SET_SCENE_VAR);
        system.triggerExec(quest, action, action.getParam());
        drain();
        assertEquals(7048, owner.getWeatherId());
    }

    private BattlePlayer player(int uid) {
        var result = new BattlePlayer();
        result.server = server;
        result.setUid(uid);
        var world = TestWorld.create(result);
        result.setScene(new TestWorld.TestScene(world, 3, SceneType.SCENE_ROOM));
        result.getScene().getPlayers().add(result);
        result.setSceneId(3);
        result.setSceneLoadState(Player.SceneLoadState.LOADED);
        result.getPosition().set(10418.6f, 790.569f, 5518.57f);
        result.setSession(new GameSession(server) {
            @Override public Player getPlayer() { return result; }
            @Override public void send(BasePacket packet) { result.packets.add(packet); }
        });
        result.main = new GameMainQuest(result, 77160);
        result.manager.getMainQuests().put(77160, result.main);
        return result;
    }

    private static GameQuest source(BattlePlayer owner) { return owner.main.getChildQuests().get(7716008); }

    private static QuestData.QuestExecParam originalAction(GameQuest quest, QuestExec type) {
        return quest.getQuestData().getFinishExec().stream()
                .filter(value -> value.getType() == type).findFirst().orElseThrow();
    }

    private static SceneAreaWeatherNotify lastWeather(BattlePlayer owner) throws Exception {
        return SceneAreaWeatherNotify.parseFrom(owner.packets.getLast().getData());
    }

    private static void parentState(GameMainQuest main, ParentQuestState state) throws Exception {
        var field = GameMainQuest.class.getDeclaredField("state");
        field.setAccessible(true);
        field.set(main, state);
    }

    private <V> void install(Map<Integer, V> map, int id, V value) {
        var previous = map.put(id, value);
        undo.add(() -> { if (previous == null) map.remove(id); else map.put(id, previous); });
    }

    private static void drain() throws Exception { QuestManager.eventExecutor.submit(() -> {}).get(10, TimeUnit.SECONDS); }

    private static Unsafe unsafe() throws Exception {
        var field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    private static final class BattlePlayer extends TestWorld.TestPlayer {
        final QuestManager manager = new QuestManager(this);
        final List<BasePacket> packets = new ArrayList<>();
        Map<Integer, Integer> savedAreas = Map.of();
        GameServer server;
        GameMainQuest main;
        int saves;
        @Override public QuestManager getQuestManager() { return manager; }
        @Override public GameServer getServer() { return server; }
        @Override public void save() { saves++; savedAreas = Map.copyOf(getQuestWeatherAreas()); }
    }
}
