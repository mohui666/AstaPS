package emu.grasscutter.game.quest;

import static emu.grasscutter.game.props.PlayerProperty.*;
import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.MainQuestData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.SceneType;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.exec.ExecModifyClimateArea;
import emu.grasscutter.game.quest.world.QuestSceneOverrides;
import emu.grasscutter.game.world.*;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.packet.PacketOpcodes;
import emu.grasscutter.net.proto.PlayerPropNotifyOuterClass.PlayerPropNotify;
import emu.grasscutter.scripts.ScriptLoader;
import emu.grasscutter.utils.*;
import java.util.*;
import org.junit.jupiter.api.*;

final class QuestClimateAreasTest {
    private boolean scripts;
    private ClimatePlayer owner;

    @BeforeEach
    void prepare() throws Exception {
        ScriptLoader.init();
        scripts = Grasscutter.getConfig().server.game.enableScriptInBigWorld;
        Grasscutter.getConfig().server.game.enableScriptInBigWorld = false;
        owner = new ClimatePlayer();
        owner.setUid(42);
        attach(owner, TestWorld.create(owner));
    }

    @AfterEach
    void restore() {
        Grasscutter.getConfig().server.game.enableScriptInBigWorld = scripts;
    }

    @Test
    void actualFourteenQuestActionsReferenceRealJsonClimateAreasAndTypes() throws Exception {
        var calls = new ArrayList<QuestData.QuestExecParam>();
        for (var quest : JsonUtils.loadToList(FileUtils.getExcelPath("QuestExcelConfigData.json"), QuestData.class)) {
            for (var actions : Arrays.asList(quest.getBeginExec(), quest.getFinishExec(), quest.getFailExec())) {
                if (actions != null) for (var action : actions)
                    if (action.getType() == QuestExec.QUEST_EXEC_MODIFY_CLIMATE_AREA) calls.add(action);
            }
        }
        assertEquals(14, calls.size());
        var areas = QuestClimateAreas.areas(3);
        for (var action : calls) {
            var params = action.getParam();
            assertEquals("3", params[0]);
            int id = Integer.parseInt(params[1]);
            var area = areas.stream().filter(value -> value.id == id).findFirst().orElseThrow();
            assertTrue(area.points.size() >= 3);
            assertTrue(params[2].equals("0") || params[2].equals("11"));
        }
        assertEquals(9, area(4).defaultType());
        assertEquals(8, area(10).defaultType());
        assertEquals(11, area(7).defaultType());
        assertEquals(60, ExecModifyClimateArea.class.getAnnotation(QuestValueExec.class).value().getValue());
    }

    @Test
    void closingTheSeiraiOuterHazardSavesARealClosureAndPublishesItWithoutChangingSkyWeather() throws Exception {
        position(owner, area(10));
        QuestClimateAreas.update(owner, false);
        assertEquals(10, owner.getProperty(PROP_CUR_CLIMATE_AREA_ID));
        assertEquals(8, owner.getProperty(PROP_CUR_CLIMATE_AREA_CLIMATE_TYPE));
        owner.packets.clear();
        assertTrue(QuestClimateAreas.change(owner, 3, 10, 0));
        assertEquals(Map.of(10, 0), owner.overrides.climateAreaTypes(3));
        assertEquals(1, owner.saves);
        assertEquals(0, owner.getProperty(PROP_CUR_CLIMATE_AREA_ID));
        assertEquals(0, owner.getProperty(PROP_CUR_CLIMATE_AREA_CLIMATE_TYPE));
        assertEquals(0, owner.getProperty(PROP_CUR_CLIMATE_TYPE));
        var notify = last(owner);
        assertEquals(0, notify.getPropMapOrThrow(PROP_CUR_CLIMATE_AREA_ID.getId()).getIval());
        assertEquals(0, notify.getPropMapOrThrow(PROP_CUR_CLIMATE_AREA_CLIMATE_TYPE.getId()).getIval());
        assertTrue(owner.getQuestWeatherAreas().isEmpty());
        assertTrue(QuestClimateAreas.change(owner, 3, 10, 0));
        assertEquals(1, owner.saves);
        assertEquals(1, owner.packets.size());
    }

    @Test
    void mistClosureAndReopeningSurviveColdSerializationAndTheSceneEnterSnapshot() throws Exception {
        position(owner, area(7));
        assertTrue(QuestClimateAreas.change(owner, 3, 7, 0));
        assertTrue(QuestClimateAreas.change(owner, 3, 7, 11));
        assertEquals(2, owner.saves);
        var restored = new ClimatePlayer();
        restored.overrides = JsonUtils.decode(JsonUtils.encode(owner.overrides), QuestSceneOverrides.class);
        restored.setUid(43);
        attach(restored, TestWorld.create(restored));
        position(restored, area(7));
        restored.setSceneLoadState(Player.SceneLoadState.INIT);
        QuestClimateAreas.update(restored, false);
        assertTrue(restored.packets.isEmpty());
        QuestClimateAreas.update(restored, true);
        assertEquals(7, restored.getProperty(PROP_CUR_CLIMATE_AREA_ID));
        assertEquals(11, restored.getProperty(PROP_CUR_CLIMATE_AREA_CLIMATE_TYPE));
        assertEquals(4, last(restored).getPropMapCount());
        assertTrue(QuestClimateAreas.change(restored, 3, 7, 0));
        var closed = JsonUtils.decode(JsonUtils.encode(restored.overrides), QuestSceneOverrides.class);
        assertEquals(Map.of(7, 0), closed.climateAreaTypes(3));
        restored.overrides = closed;
        QuestClimateAreas.update(restored, true);
        assertEquals(0, restored.getProperty(PROP_CUR_CLIMATE_AREA_ID));
        assertEquals(0, last(restored).getPropMapOrThrow(PROP_CUR_CLIMATE_AREA_CLIMATE_TYPE.getId()).getIval());
    }

    @Test
    void visitorUsesTheHostsStateAndCannotWriteItOrAffectPlayersOutsideTheArea() {
        owner.getPosition().set(10000, 100, 10000);
        var guest = new ClimatePlayer();
        guest.setUid(43);
        guest.setWorld(owner.getWorld());
        guest.setScene(owner.getScene());
        guest.setSceneId(3);
        guest.setSceneLoadState(Player.SceneLoadState.LOADED);
        owner.getWorld().getPlayers().add(guest);
        owner.getScene().getPlayers().add(guest);
        position(guest, area(7));
        guest.overrides.setClimateAreaType(3, 7, 0);
        QuestClimateAreas.update(guest, false);
        assertEquals(11, guest.getProperty(PROP_CUR_CLIMATE_AREA_CLIMATE_TYPE));
        assertFalse(QuestClimateAreas.change(guest, 3, 7, 0));
        assertTrue(owner.overrides.climateAreaTypes(3).isEmpty());
        assertTrue(QuestClimateAreas.change(owner, 3, 7, 0));
        assertEquals(0, guest.getProperty(PROP_CUR_CLIMATE_AREA_CLIMATE_TYPE));
        assertEquals(0, owner.getProperty(PROP_CUR_CLIMATE_AREA_CLIMATE_TYPE));
        assertEquals(0, guest.saves);
        assertTrue(owner.packets.isEmpty());
    }

    @Test
    void nativeVerticalBoundsAndPreviousTypeResolveTheRealOverlappingMistPolygons() {
        var broad = area(7);
        var regional = area(15);
        var position = position(owner, regional);
        assertTrue(broad.contains(position));
        assertEquals(regional, QuestClimateAreas.select(List.of(broad, regional), Map.of(), position, 13));
        assertEquals(broad, QuestClimateAreas.select(List.of(broad, regional), Map.of(), position, 11));
        assertTrue(broad.contains(position.setY(0)));
        assertTrue(broad.contains(position.setY(400)));
        assertFalse(broad.contains(position.setY(400.01f)));
        assertFalse(broad.contains(position.setY(-0.01f)));
        assertEquals(regional, QuestClimateAreas.select(List.of(broad, regional), Map.of(7, 0),
                position.setY(100), 11));
    }

    @Test
    void nativeForceClearFlagClearsAnIncompatibleClimateAtTheRealMistBoundary() throws Exception {
        position(owner, area(7));
        owner.setProperty(PROP_CUR_CLIMATE_METER, 1234, false);
        owner.setProperty(PROP_CUR_CLIMATE_TYPE, 8, false);
        QuestClimateAreas.update(owner, true);
        assertEquals(11, owner.getProperty(PROP_CUR_CLIMATE_AREA_CLIMATE_TYPE));
        assertEquals(11, owner.getProperty(PROP_CUR_CLIMATE_TYPE));
        assertEquals(0, owner.getProperty(PROP_CUR_CLIMATE_METER));
        assertEquals(0, last(owner).getPropMapOrThrow(PROP_CUR_CLIMATE_METER.getId()).getIval());
    }

    @Test
    void actionRejectsWrongAreasInvalidTypesAndExtraArgumentsBeforeSaving() {
        var old = GameData.getMainQuestDataMap().put(72155,
                JsonUtils.decode("{id:72155,subQuests:[]}", MainQuestData.class));
        try {
            var quest = new GameQuest(new GameMainQuest(owner, 72155), JsonUtils.decode(
                    "{mainId:72155,subId:7215523,finishCond:[],failCond:[]}", QuestData.class));
            var action = new ExecModifyClimateArea();
            assertFalse(action.execute(quest, null, "3", "99999", "0"));
            assertFalse(action.execute(quest, null, "20115", "10", "0"));
            assertFalse(action.execute(quest, null, "3", "10", "14"));
            assertFalse(action.execute(quest, null, "3", "10", "-1"));
            assertFalse(action.execute(quest, null, "3", "10", "nonnumeric"));
            assertFalse(action.execute(quest, null, "3", "10", "0", "1"));
            assertTrue(owner.overrides.climateAreaTypes(3).isEmpty());
            assertEquals(0, owner.saves);
            assertTrue(action.execute(quest, null, "3", "10", "0"));
            assertEquals(Map.of(10, 0), owner.overrides.climateAreaTypes(3));
            assertEquals(1, owner.saves);
        } finally {
            if (old == null) GameData.getMainQuestDataMap().remove(72155);
            else GameData.getMainQuestDataMap().put(72155, old);
        }
    }

    private static QuestClimateAreas.Area area(int id) {
        return QuestClimateAreas.areas(3).stream().filter(value -> value.id == id).findFirst().orElseThrow();
    }

    private static Position position(Player player, QuestClimateAreas.Area area) {
        var point = area.points.getFirst();
        return player.getPosition().set(point.x, Math.max(area.bottom, Math.min(100, area.top)), point.y);
    }

    private static void attach(ClimatePlayer player, World world) {
        player.setScene(new TestWorld.TestScene(world, 3, SceneType.SCENE_WORLD));
        player.getScene().getPlayers().add(player);
        player.setSceneId(3);
        player.setSceneLoadState(Player.SceneLoadState.LOADED);
    }

    private static PlayerPropNotify last(ClimatePlayer player) throws Exception {
        var packet = player.packets.getLast();
        assertEquals(PacketOpcodes.PlayerPropNotify, packet.getOpcode());
        return PlayerPropNotify.parseFrom(packet.getData());
    }

    private static final class ClimatePlayer extends TestWorld.TestPlayer {
        QuestSceneOverrides overrides = new QuestSceneOverrides();
        final List<BasePacket> packets = new ArrayList<>();
        int saves;
        @Override public QuestSceneOverrides getQuestSceneOverrides() { return overrides; }
        @Override public void save() { saves++; }
        @Override public void sendPacket(BasePacket packet) { packets.add(packet); }
    }
}
