package emu.grasscutter.game.quest;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.world.WeatherData;
import emu.grasscutter.data.binout.MainQuestData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.*;
import emu.grasscutter.game.quest.exec.ExecModifyWeatherArea;
import emu.grasscutter.game.world.*;
import emu.grasscutter.scripts.ScriptLoader;
import emu.grasscutter.scripts.ScriptLib;
import emu.grasscutter.scripts.data.ScriptArgs;
import emu.grasscutter.utils.*;
import java.util.*;
import org.junit.jupiter.api.*;

final class QuestWeatherAreasTest {
    private Map<Integer, WeatherData> previous;
    private boolean scripts;
    private WeatherPlayer player;

    @BeforeEach
    void prepare() throws Exception {
        ScriptLoader.init();
        scripts = Grasscutter.getConfig().server.game.enableScriptInBigWorld;
        Grasscutter.getConfig().server.game.enableScriptInBigWorld = false;
        previous = new HashMap<>(GameData.getWeatherDataMap());
        GameData.getWeatherDataMap().clear();
        for (var data : JsonUtils.loadToList(FileUtils.getExcelPath("WeatherExcelConfigData.json"), WeatherData.class))
            if (data.getId() == 4037 || data.getId() == 4038 || data.getId() == 4056
                    || data.getId() == 6460 || data.getId() == 2007 || data.getId() == 2107
                    || data.getId() == 2207 || data.getId() == 6461 || data.getId() == 4256
                    || data.getId() == 6020 || data.getId() == 7043)
                GameData.getWeatherDataMap().put(data.getId(), data);
        player = new WeatherPlayer();
        player.setUid(42);
        var world = TestWorld.create(player);
        player.setScene(new TestWorld.TestScene(world, 3, SceneType.SCENE_WORLD));
        player.getScene().getPlayers().add(player);
        player.setSceneId(3);
        player.setSceneLoadState(Player.SceneLoadState.LOADED);
        player.getPosition().set(-161.9726f, 250, 3458.984f);
    }

    @AfterEach
    void restore() {
        GameData.getWeatherDataMap().clear();
        GameData.getWeatherDataMap().putAll(previous);
        Grasscutter.getConfig().server.game.enableScriptInBigWorld = scripts;
    }

    @Test
    void nativeVanaranaWeatherUsesXZPolygonAndItsOwnHeightLimit() {
        var dream = QuestWeatherAreas.areas(3).stream().filter(area -> area.id == 4037).findFirst().orElseThrow();
        var real = QuestWeatherAreas.areas(3).stream().filter(area -> area.id == 4038).findFirst().orElseThrow();
        assertTrue(dream.contains(player.getPosition()));
        assertTrue(real.contains(player.getPosition()));
        var high = new Position(player.getPosition()).setY(320);
        assertTrue(dream.contains(high));
        assertFalse(real.contains(high));
        assertFalse(dream.contains(new Position(10000,250,10000)));
    }

    @Test
    void nativeHeightLayersUseTheirSharedPolygonAndInclusiveCeilings() {
        var area = QuestWeatherAreas.areas(3).stream().filter(value -> value.id == 2007).findFirst().orElseThrow();
        var point = area.points.getFirst();
        var position = new Position(point.x, 250, point.y);
        assertEquals(2007, QuestWeatherAreas.select(player, 3, position));
        assertEquals(2107, QuestWeatherAreas.select(player, 3, position.setY(250.01f)));
        assertEquals(2107, QuestWeatherAreas.select(player, 3, position.setY(265)));
        assertEquals(2207, QuestWeatherAreas.select(player, 3, position.setY(265.01f)));
        player.getQuestWeatherAreas().put(2107, 0);
        assertEquals(0, QuestWeatherAreas.select(player, 3, position.setY(260)));
    }

    @Test
    void dreamStoneEnablesAreSavedAndOnlyAffectPlayersInsideTheArea() {
        assertEquals(4037, QuestWeatherAreas.select(player,3,player.getPosition()));
        assertTrue(QuestWeatherAreas.change(player,new int[]{4037,4038},new int[]{0,1}));
        assertEquals(4038, player.weather);
        assertEquals(1, player.saves);
        assertTrue(QuestWeatherAreas.change(player,new int[]{4037,4038},new int[]{0,1}));
        assertEquals(1, player.saves);
        player.getPosition().set(10000,250,10000);
        QuestWeatherAreas.update(player,false);
        assertEquals(0,player.weather);
        player.getPosition().set(-161.9726f,250,3458.984f);
        QuestWeatherAreas.update(player,false);
        assertEquals(4038,player.weather);
    }

    @Test
    void invalidBatchAndVisitorCannotPartiallyChangeTheHostsWeather() {
        assertFalse(QuestWeatherAreas.change(player,new int[]{4037,999999},new int[]{0,1}));
        assertTrue(player.getQuestWeatherAreas().isEmpty());
        assertEquals(0,player.saves);
        player.setWorld(TestWorld.create(new TestWorld.TestPlayer()));
        assertFalse(QuestWeatherAreas.change(player,new int[]{4037},new int[]{0}));
        assertTrue(player.getQuestWeatherAreas().isEmpty());
    }

    @Test
    void nativeLuaRejectsRedundantChangesAndWeatherOwnedByAnotherScene() {
        var scene = player.getScene();
        assertEquals(-1, QuestWeatherAreas.setState(scene, 4037, 1));
        assertEquals(-1, QuestWeatherAreas.setState(scene, 6460, 1));
        assertEquals(-1, QuestWeatherAreas.setState(scene, 4056, 2));
        assertEquals(-1, QuestWeatherAreas.enter(scene, 42, 4056));
        assertEquals(0, player.saves);
        assertEquals(0, QuestWeatherAreas.setState(scene, 4056, 1));
        assertEquals(1, player.saves);
        assertEquals(-1, QuestWeatherAreas.setState(scene, 4056, 1));
        assertEquals(-1, QuestWeatherAreas.enter(scene, 999, 4056));
        assertEquals(-1, QuestWeatherAreas.enter(scene, 42, 6460));
        assertEquals(1, player.saves);
    }

    @Test
    void unsupportedWeatherModeCannotPartiallyPersistAndReportSuccess() {
        var previousQuest = GameData.getMainQuestDataMap().put(990442,
                JsonUtils.decode("{id:990442,subQuests:[]}", MainQuestData.class));
        try {
            var quest = new GameQuest(new GameMainQuest(player, 990442), JsonUtils.decode(
                    "{mainId:990442,subId:99044201,finishCond:[],failCond:[]}", QuestData.class));
            var action = new ExecModifyWeatherArea();
            assertFalse(action.execute(quest, null, "6460", "1", "2"));
            assertFalse(action.execute(quest, null, "4056", "1", "1"));
            assertTrue(player.getQuestWeatherAreas().isEmpty());
            assertEquals(0, player.saves);
            assertTrue(action.execute(quest, null, "4056", "1", "0"));
            assertEquals(Map.of(4056, 1), player.getQuestWeatherAreas());
            assertEquals(1, player.saves);
        } finally {
            if (previousQuest == null) GameData.getMainQuestDataMap().remove(990442);
            else GameData.getMainQuestDataMap().put(990442, previousQuest);
        }
    }

    @Test
    void reconstructedColdAreaUsesAuthoredStoryLocationAndOnlyActivatesAfterItsQuestAction() {
        player.getPosition().set(7597,232.191f,7715.849f);
        assertEquals(0,QuestWeatherAreas.select(player,3,player.getPosition()));
        assertTrue(QuestWeatherAreas.change(player,new int[]{7043},new int[]{1}));
        assertEquals(7043,player.weather);
        player.getPosition().set(8544.231f,211.414f,7809.266f);
        QuestWeatherAreas.update(player,false);
        assertEquals(0,player.weather);
        assertTrue(QuestWeatherAreas.change(player,new int[]{7043},new int[]{0}));
        player.getPosition().set(7597,232.191f,7715.849f);
        assertEquals(0,QuestWeatherAreas.select(player,3,player.getPosition()));
    }

    @Test
    void reconstructedModeOneSwitchesOnlyItsOwnWeatherFamilyAndValidatesTheWholeBatch() {
        assertTrue(QuestWeatherAreas.changeReconstructedMode1(player,new int[]{6460},new int[]{1}));
        assertTrue(QuestWeatherAreas.changeReconstructedMode1(player,new int[]{6461},new int[]{1}));
        assertEquals(Map.of(6460,0,6461,1),player.getQuestWeatherAreas());
        var saved = Map.copyOf(player.getQuestWeatherAreas());
        assertFalse(QuestWeatherAreas.changeReconstructedMode1(player,new int[]{6460,4056},new int[]{1,1}));
        assertEquals(saved,player.getQuestWeatherAreas());
        assertTrue(QuestWeatherAreas.changeReconstructedMode1(player,new int[]{6460,6461},new int[]{1,1}));
        assertEquals(saved,player.getQuestWeatherAreas());
        assertTrue(QuestWeatherAreas.change(player,new int[]{4037},new int[]{1}));
        assertTrue(QuestWeatherAreas.changeReconstructedMode1(player,new int[]{6460},new int[]{1}));
        assertEquals(1,player.getQuestWeatherAreas().get(4037));
        assertEquals(0,player.getQuestWeatherAreas().get(6461));
    }

    @Test
    void dreamlandEnterSelectsTheTriggerPlayerWithoutChangingTheOtherPlayersSky() {
        var scene = player.getScene();
        var guest = new WeatherPlayer();
        guest.setUid(43);
        guest.setScene(scene);
        guest.setSceneId(3);
        guest.setWorld(player.getWorld());
        guest.setSceneLoadState(Player.SceneLoadState.LOADED);
        guest.getPosition().set(10000, 250, 10000);
        player.getWorld().getPlayers().add(guest);
        scene.getPlayers().add(guest);

        var lib = new ScriptLib();
        lib.setSceneScriptManager(scene.getScriptManager());
        lib.setCurrentCallParams(new ScriptArgs(0, 0).setUid(43));
        assertEquals(0, lib.SetWeatherAreaState(4056, 1));
        int hostWeather = player.weather;
        assertEquals(0, lib.EnterWeatherArea(4056));
        assertEquals(4056, guest.weather);
        assertEquals(hostWeather, player.weather);
        assertEquals(1, player.saves);
        assertEquals(0, guest.saves);
        assertEquals(0, lib.SetWeatherAreaState(4056, 0));
        assertEquals(0, guest.weather);
        assertEquals(-1, lib.EnterWeatherArea(4056));
    }

    private static final class WeatherPlayer extends TestWorld.TestPlayer {
        int weather;
        int saves;
        @Override public void save() { saves++; }
        @Override public int getWeatherId() { return weather; }
        @Override public synchronized void setWeather(int id, ClimateType climate) { weather=id; }
    }
}
