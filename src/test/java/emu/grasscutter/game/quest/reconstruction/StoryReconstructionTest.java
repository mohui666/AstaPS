package emu.grasscutter.game.quest.reconstruction;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import emu.grasscutter.*;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.MainQuestData;
import emu.grasscutter.data.binout.ScenePointEntry;
import emu.grasscutter.data.binout.routes.Route;
import emu.grasscutter.data.common.PointData;
import emu.grasscutter.data.excels.GadgetData;
import emu.grasscutter.data.excels.scene.SceneData;
import emu.grasscutter.data.excels.dungeon.DungeonData;
import emu.grasscutter.data.excels.monster.MonsterData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.dungeons.DungeonSystem;
import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.entity.gadget.GadgetWorktop;
import emu.grasscutter.game.entity.gadget.platform.ConfigRoute;
import emu.grasscutter.game.player.*;
import emu.grasscutter.game.props.*;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.*;
import emu.grasscutter.game.world.*;
import emu.grasscutter.game.world.data.TeleportProperties;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.AddQuestContentProgressReqOuterClass.AddQuestContentProgressReq;
import emu.grasscutter.net.proto.AddQuestContentProgressBatchReq._AddQuestContentProgressBatchReq;
import emu.grasscutter.net.proto.QuestProgressInfo._QuestProgressInfo;
import emu.grasscutter.net.proto.SelectWorktopOptionReqOuterClass.SelectWorktopOptionReq;
import emu.grasscutter.scripts.*;
import emu.grasscutter.scripts.data.*;
import emu.grasscutter.server.game.*;
import emu.grasscutter.server.packet.recv.*;
import emu.grasscutter.utils.*;
import it.unimi.dsi.fastutil.ints.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import sun.misc.Unsafe;

@ExtendWith(ServerResourceFixture.class)
@ResourceLock("GameData")
final class StoryReconstructionTest {
    private static final int MAIN = 990081;
    private static final AtomicInteger UID = new AtomicInteger(89000);
    private static final int[] QUESTS = {400503,400506,401003,401019,402404,402420,402419,402401,7500508,7500517};
    private final Map<Integer, QuestData> oldQuests = new HashMap<>();
    private final Map<Integer, GadgetData> oldGadgets = new HashMap<>();
    private final Map<Integer, MonsterData> oldMonsters = new HashMap<>();
    private final List<StoryPlayer> players = new ArrayList<>();
    private MainQuestData oldMain;
    private DungeonData oldDungeon;
    private SceneData oldDungeonScene;
    private ScenePointEntry oldDungeonEntry;
    private boolean scriptsEnabled;

    @BeforeEach
    void sourceAndCombatFixtures() throws Exception {
        ScriptLoader.init();
        scriptsEnabled = Grasscutter.getConfig().server.game.enableScriptInBigWorld;
        Grasscutter.getConfig().server.game.enableScriptInBigWorld = false;
        oldMain = GameData.getMainQuestDataMap().put(MAIN,
                JsonUtils.decode("{id:990081,subQuests:[]}", MainQuestData.class));
        for (int qid : QUESTS) {
            var parent = JsonParser.parseString(Files.readString(FileUtils.getResourcePath(
                    "BinOutput/Quest/" + qid / 100 + ".json"))).getAsJsonObject();
            var row = java.util.stream.StreamSupport.stream(parent.getAsJsonArray("subQuests").spliterator(), false)
                    .map(value -> value.getAsJsonObject()).filter(value -> value.get("subId").getAsInt() == qid)
                    .findFirst().orElseThrow().deepCopy();
            row.addProperty("mainId", MAIN);
            for (String list : List.of("acceptCond","failCond","beginExec","finishExec","failExec"))
                if (!row.has(list)) row.add(list,new com.google.gson.JsonArray());
            oldQuests.put(qid, GameData.getQuestDataMap().put(qid, JsonUtils.decode(row, QuestData.class)));
        }
        for (int id : new int[] {70350081,70330565,70711094,70330500}) {
            var data = JsonUtils.decode("{id:" + id + ",type:'Worktop',jsonName:'TestStoryProp',tags:[]}", GadgetData.class);
            oldGadgets.put(id, GameData.getGadgetDataMap().put(id, data));
        }
        for (int id : new int[] {25010201,25010301}) {
            var data = JsonUtils.decode("{id:" + id + ",type:'MONSTER_ORDINARY',hpBase:100,attackBase:10,"
                    + "defenseBase:10,affix:[],equips:[],propGrowCurves:[]}", MonsterData.class);
            data.onLoad();
            oldMonsters.put(id, GameData.getMonsterDataMap().put(id, data));
        }
        oldDungeon = GameData.getDungeonDataMap().put(1189,
                JsonUtils.decode("{id:1189,sceneId:20202,type:'DUNGEON_PLOT',showLevel:35,limitLevel:1}", DungeonData.class));
        oldDungeonScene = GameData.getSceneDataMap().put(20202, TestWorld.sceneData(20202, SceneType.SCENE_DUNGEON));
        var nativePoint = JsonParser.parseString(Files.readString(FileUtils.getResourcePath(
                "BinOutput/Scene/Point/scene1085_point.json"))).getAsJsonObject().getAsJsonObject("points").get("2");
        var point = JsonUtils.decode(nativePoint, PointData.class);
        point.setId(2);
        oldDungeonEntry = GameData.getScenePointEntryMap().put((1085 << 16) + 2, new ScenePointEntry(1085, point));
    }

    @AfterEach
    void releaseOnlyThisRunsAndRestoreFixtures() throws Exception {
        for (var player : players) StoryReconstructionRuntime.capture(player);
        flush();
        restore(GameData.getQuestDataMap(), oldQuests);
        restore(GameData.getGadgetDataMap(), oldGadgets);
        restore(GameData.getMonsterDataMap(), oldMonsters);
        if (oldMain == null) GameData.getMainQuestDataMap().remove(MAIN);
        else GameData.getMainQuestDataMap().put(MAIN, oldMain);
        if (oldDungeon == null) GameData.getDungeonDataMap().remove(1189);
        else GameData.getDungeonDataMap().put(1189, oldDungeon);
        if (oldDungeonScene == null) GameData.getSceneDataMap().remove(20202);
        else GameData.getSceneDataMap().put(20202, oldDungeonScene);
        if (oldDungeonEntry == null) GameData.getScenePointEntryMap().remove((1085 << 16) + 2);
        else GameData.getScenePointEntryMap().put((1085 << 16) + 2, oldDungeonEntry);
        Grasscutter.getConfig().server.game.enableScriptInBigWorld = scriptsEnabled;
    }

    private static <T> void restore(Map<Integer,T> target, Map<Integer,T> values) {
        values.forEach((id,value) -> { if (value == null) target.remove(id); else target.put(id,value); });
    }

    @Test
    void explicitSeedHasItsElevenStagesAndNoUnknownQuestFallback() throws Exception {
        var catalog = StoryReconstructionCatalog.installed();
        for (int id : new int[] {400503,400506,401003,401019,402404,402420,402419,402415,400904,402401,402414})
            assertNotNull(catalog.stage(id));
        assertNull(catalog.stage(999999));
        assertTrue(StoryReconstructionRuntime.isServerOwnedLuaNotification("402404"));
        assertFalse(StoryReconstructionRuntime.isServerOwnedLuaNotification("unrelated_native_event"));
        assertEquals(List.of(29090102), catalog.stage(402415).getWaves().getFirst());
        assertThrows(IllegalArgumentException.class, () -> StoryReconstructionCatalog.parse(
                "{reconstructed:false,version:1,groups:[],stages:[]}"));
        var group = catalog.getGroups().stream().filter(g -> g.getGroupId() == 220202001).findFirst().orElseThrow();
        var loaded = SceneGroup.of(group.getGroupId()).load(group.getSceneId());
        assertNotNull(loaded);
        assertFalse(loaded.gadgets.isEmpty());
        assertEquals(1, loaded.init_config.io_type);
        assertEquals(1, loaded.init_config.sub_flow_type);
        for (int suite = 1; suite <= group.getSuiteCount(); suite++) {
            assertTrue(loaded.getSuiteByIndex(suite).gadgets.contains(group.getControllerConfigId()));
            assertTrue(loaded.getSuiteByIndex(suite).triggers.contains("STORY_SELECT_OPTION"));
        }
    }

    @Test
    void everyExplicitResourceLoadsAsFlowAndEveryObjectiveKeepsItsOriginalNativeContract() throws Exception {
        var catalog = StoryReconstructionCatalog.installed();
        var gadgetIds = excelIds("GadgetExcelConfigData.json");
        var monsterIds = excelIds("MonsterExcelConfigData.json");
        var parents = new HashMap<Integer, com.google.gson.JsonObject>();
        for (var definition : catalog.getGroups()) {
            var group = SceneGroup.of(definition.getGroupId()).load(definition.getSceneId());
            String label = definition.getSceneId() + ":" + definition.getGroupId();
            assertNotNull(group.init_config, label);
            assertEquals(1, group.init_config.io_type, label);
            assertEquals(1, group.init_config.sub_flow_type, label);
            assertTrue(gadgetIds.contains(definition.getControllerGadgetId()), label);
            assertNotNull(group.gadgets.get(definition.getControllerConfigId()), label);
            for (int index = 1; index <= definition.getSuiteCount(); index++) {
                var suite = group.getSuiteByIndex(index);
                assertNotNull(suite, label + " suite " + index);
                assertNotNull(suite.flow, label + " suite " + index);
                assertTrue(suite.gadgets.contains(definition.getControllerConfigId()), label);
                for (var event : List.of("STORY_GROUP_LOAD", "STORY_GADGET_CREATE", "STORY_SELECT_OPTION", "STORY_MONSTER_DIE")) {
                    assertTrue(suite.triggers.contains(event), label + " " + event);
                    assertNotNull(group.triggers.get(event), label + " " + event);
                }
            }
        }
        for (var stage : catalog.getStages()) {
            int mainId = stage.getQuestId() / 100;
            if (!parents.containsKey(mainId)) parents.put(mainId, JsonParser.parseString(Files.readString(
                    FileUtils.getResourcePath("BinOutput/Quest/" + mainId + ".json"))).getAsJsonObject());
            var parent = parents.get(mainId);
            var source = java.util.stream.StreamSupport.stream(parent.getAsJsonArray("subQuests").spliterator(), false)
                    .map(value -> value.getAsJsonObject())
                    .filter(value -> value.get("subId").getAsInt() == stage.getQuestId())
                    .findFirst().orElseThrow();
            for (var notification : stage.getNotifications()) {
                assertTrue(java.util.stream.StreamSupport.stream(source.getAsJsonArray("finishCond").spliterator(), false)
                        .map(value -> value.getAsJsonObject()).anyMatch(condition -> "QUEST_CONTENT_LUA_NOTIFY".equals(condition.get("type").getAsString())
                                && condition.has("param_str") && notification.getKey().equals(condition.get("param_str").getAsString())
                                && notification.getCount() == (condition.has("count") ? Math.max(1, condition.get("count").getAsInt()) : 1)),
                        "Original goal " + stage.getQuestId() + " key " + notification.getKey());
            }
            for (var wave : stage.getWaves()) for (int monster : wave)
                assertTrue(monsterIds.contains(monster), "Monster " + monster + " quest " + stage.getQuestId());
            if (Set.of("sequence", "collect").contains(stage.getKind()))
                assertTrue(gadgetIds.contains(stage.getTargetGadgetId()), "Target gadget " + stage.getQuestId());
            if (stage.getKind().equals("travel"))
                assertTrue(gadgetIds.contains(stage.getCarrierGadgetId()), "Carrier " + stage.getQuestId());
            if (stage.getKind().equals("enterDungeon")) {
                var nativePoint = JsonParser.parseString(Files.readString(FileUtils.getResourcePath(
                        "BinOutput/Scene/Point/scene" + stage.getSceneId() + "_point.json")))
                        .getAsJsonObject().getAsJsonObject("points").get(Integer.toString(stage.getPointId()));
                assertNotNull(nativePoint, "Native entry point " + stage.getQuestId());
                assertTrue(Arrays.stream(JsonUtils.decode(nativePoint, PointData.class).getDungeonIds())
                        .anyMatch(id -> id == stage.getDungeonId()), "Native dungeon offering " + stage.getQuestId());
            }
            for (var effect : stage.getGadgetEffects()) {
                var target = SceneGroup.of(effect.getGroupId()).load(stage.getSceneId());
                assertNotNull(target.gadgets.get(effect.getConfigId()),
                        "Effect target " + stage.getQuestId() + " " + effect.getGroupId() + ":" + effect.getConfigId());
                if (stage.getNotifications().isEmpty()) assertTrue(java.util.stream.StreamSupport
                        .stream(source.getAsJsonArray("finishCond").spliterator(), false)
                        .map(value -> value.getAsJsonObject()).anyMatch(condition ->
                                "QUEST_CONTENT_GADGET_STATE_CHANGE".equals(condition.get("type").getAsString())
                                        && condition.getAsJsonArray("param").get(0).getAsInt() == effect.getGroupId()
                                        && condition.getAsJsonArray("param").get(1).getAsInt() == effect.getConfigId()
                                        && Integer.toString(effect.getState()).equals(condition.get("param_str").getAsString())),
                        "Original gadget-only goal " + stage.getQuestId());
            }
        }
    }

    @Test
    void gadgetOnlyObjectiveNeedsThreeActualOperationsAndStillHonorsItsNativePredecessor() throws Exception {
        var player = player(3);
        var predecessor = player.active(7500508, false);
        var quest = player.active(7500517, true);
        var door = new EntityGadget(player.getScene(), 70350081, new Position(), new Position());
        door.setGroupId(133503022); door.setConfigId(22006); door.setState(201);
        player.getScene().addEntity(door);
        StoryReconstructionRuntime.onTick(player);
        for (int index = 0; index < 3; index++) {
            var operator = player.getScene().getEntityByConfigId(7500517 * 100 + 50 + index,133503024);
            player.getPosition().set(operator.getPosition());
            assertTrue(StoryReconstructionRuntime.tryInteract(player,operator,7));
            flush();
            assertEquals(index == 2 ? 0 : 201, door.getState());
            assertEquals(QuestState.QUEST_STATE_UNFINISHED,quest.getState());
        }
        assertTrue(player.state.get(7500517).isComplete());
        assertTrue(player.state.get(7500517).getEmitted().isEmpty());
        predecessor.setState(QuestState.QUEST_STATE_FINISHED);
        player.manager.queueEvent(QuestContent.QUEST_CONTENT_QUEST_STATE_EQUAL,7500508,QuestState.QUEST_STATE_FINISHED.getValue());
        flush();
        assertEquals(QuestState.QUEST_STATE_FINISHED,quest.getState());
    }

    private static Set<Integer> excelIds(String file) throws Exception {
        var rows = JsonParser.parseString(Files.readString(FileUtils.getResourcePath("ExcelBinOutput/" + file))).getAsJsonArray();
        var ids = new HashSet<Integer>();
        rows.forEach(value -> ids.add(value.getAsJsonObject().get("id").getAsInt()));
        return ids;
    }

    @Test
    void anotherStagesGlobalCounterCannotReplaceThisRoundsActualOperations() throws Exception {
        var player = player(20202);
        var quest = player.active(402404, true);
        StoryReconstructionRuntime.onTick(player);
        player.progress.addToCurrentProgress("402404", 3);
        player.manager.queueEvent(QuestContent.QUEST_CONTENT_LUA_NOTIFY, "402404", 0);
        flush();
        assertEquals(QuestState.QUEST_STATE_UNFINISHED, quest.getState());
        assertTrue(player.state.get(402404).getSwitches().isEmpty());
        for (int index = 0; index < 3; index++) select(player, 402404, index);
        flush();
        assertEquals(QuestState.QUEST_STATE_FINISHED, quest.getState());
    }

    @Test
    void earnedActionsWaitForActualEffectTargetAndRestoreItsProvenStateAfterRelog() throws Exception {
        var base = custom(400503, "sequence", true, 0, "[{x:0,y:0,z:0}]", "[{key:'400503',count:1}]");
        var json = JsonParser.parseString(JsonUtils.encode(base)).getAsJsonObject();
        json.getAsJsonArray("stages").get(0).getAsJsonObject().add("gadgetEffects",
                JsonParser.parseString("[{groupId:133503016,configId:16001,state:201}]"));
        var catalog = StoryReconstructionCatalog.parse(json.toString());
        var player = player(3);
        var quest = player.active(400503, false);
        StoryReconstructionRuntime.onQuestStart(quest, catalog);
        StoryReconstructionRuntime.onTick(player, catalog);
        var operator = player.getScene().getEntityByConfigId(400503 * 100 + 50, 0);
        player.getPosition().set(operator.getPosition());
        StoryReconstructionRuntime.tryInteract(player, operator, 7);
        assertTrue(player.state.get(400503).isActionsComplete());
        assertFalse(player.state.get(400503).isComplete());
        assertEquals(0, player.progress.getCurrentProgress("400503"));
        var door = effectDoor(player);
        StoryReconstructionRuntime.onTick(player, catalog);
        flush();
        assertEquals(201, door.getState());
        assertEquals(QuestState.QUEST_STATE_FINISHED, quest.getState());
        StoryReconstructionRuntime.capture(player);
        var resumed = player(3);
        resumed.state = JsonUtils.decode(JsonUtils.encode(player.state), StoryReconstructionState.class);
        resumed.active(400503, false);
        var restoredDoor = effectDoor(resumed);
        StoryReconstructionRuntime.onTick(resumed, catalog);
        flush();
        assertEquals(201, restoredDoor.getState());
        assertEquals(1, resumed.progress.getCurrentProgress("400503"));
    }

    private static EntityGadget effectDoor(StoryPlayer player) {
        var door = new EntityGadget(player.getScene(), 70350081, new Position(), new Position());
        door.setGroupId(133503016);
        door.setConfigId(16001);
        player.getScene().addEntity(door);
        return door;
    }

    @Test
    void actionWaterlinesRequireAllThreeOperationsForOneKeyAndSeparateMultipleKeys() {
        var one = custom(400503, "sequence", false, 0,
                "[{x:0,y:0,z:0},{x:1,y:0,z:0},{x:2,y:0,z:0}]", "[{key:'400503',count:1}]");
        assertEquals(Map.of("400503",0), StoryReconstructionRuntime.waterline(one.stage(400503), 2, 3));
        assertEquals(Map.of("400503",1), StoryReconstructionRuntime.waterline(one.stage(400503), 3, 3));
        var two = custom(400503, "sequence", false, 0,
                "[{x:0,y:0,z:0},{x:1,y:0,z:0},{x:2,y:0,z:0}]", "[{key:'A',count:1},{key:'B',count:1}]");
        assertEquals(Map.of("A",1,"B",0), StoryReconstructionRuntime.waterline(two.stage(400503), 1, 3));
        assertEquals(Map.of("A",1,"B",0), StoryReconstructionRuntime.waterline(two.stage(400503), 2, 3));
        assertEquals(Map.of("A",1,"B",1), StoryReconstructionRuntime.waterline(two.stage(400503), 3, 3));
    }

    @Test
    void privateArrivalNeedsActualDepartureReturnAndContinuousHoldWithoutMovingItsSavedAnchor() throws Exception {
        var player = player(3);
        var catalog = custom(400503, "arrival", true, 2, "[]", "[{key:'400503',count:1}]");
        var quest = player.active(400503, false);
        StoryReconstructionRuntime.onQuestStart(quest, catalog);
        player.getPosition().set(100,10,100);
        StoryReconstructionRuntime.onTick(player, catalog);
        assertEquals(0, player.progress.getCurrentProgress("400503"));
        assertEquals(0, new Position(100,10,100).computeDistance(player.state.get(400503).getAnchor()),0.001);
        player.getPosition().addX(9);
        StoryReconstructionRuntime.onTick(player, catalog);
        player.getPosition().set(100,10,100);
        StoryReconstructionRuntime.onTick(player, catalog);
        player.scene().clock += 1500;
        StoryReconstructionRuntime.onTick(player, catalog);
        assertEquals(0, player.progress.getCurrentProgress("400503"));
        player.getPosition().addX(5);
        StoryReconstructionRuntime.onTick(player, catalog); // leaving resets the continuous hold
        player.getPosition().set(100,10,100);
        StoryReconstructionRuntime.onTick(player, catalog);
        player.scene().clock += 2001;
        StoryReconstructionRuntime.onTick(player, catalog);
        flush();
        assertEquals(1, player.progress.getCurrentProgress("400503"));
        assertEquals(QuestState.QUEST_STATE_FINISHED, quest.getState());
        assertEquals(0, new Position(100,10,100).computeDistance(player.state.get(400503).getAnchor()),0.001);
    }

    @Test
    void spotlightRoundsRequireThreeThenTwoThenOneRealOrderedOperationsAndResetOnlyTheirKey() throws Exception {
        var player = player(20202);
        player.progress.addToCurrentProgress("other_quest", 7);
        for (int qid : new int[] {402404,402420,402419}) {
            var quest = player.active(qid, true);
            StoryReconstructionRuntime.onTick(player);
            int required = StoryReconstructionCatalog.installed().stage(qid).getPoints().size();
            assertEquals(0, player.progress.getCurrentProgress("402404"));
            if (required > 1) {
                select(player, qid, 1);
                assertTrue(player.state.get(qid).getSwitches().isEmpty());
            }
            for (int point = 0; point < required; point++) {
                select(player, qid, point);
                select(player, qid, point); // duplicate selection cannot contribute twice
                flush();
                assertEquals(point + 1, player.progress.getCurrentProgress("402404"));
                assertEquals(202, ((EntityGadget) player.getScene().getEntityByConfigId(qid * 100 + 20 + point,
                        220202001)).getState());
            }
            assertEquals(QuestState.QUEST_STATE_FINISHED, quest.getState());
        }
        assertEquals(7, player.progress.getCurrentProgress("other_quest"));
    }

    @Test
    void twoBattleWavesCountRealDeathsAndRestoreDeadSlotsAndRemainingHpAfterRelog() throws Exception {
        var player = player(3);
        player.active(400503, true);
        var anchor = StoryReconstructionCatalog.installed().stage(400503).getPosition();
        player.getPosition().set(anchor);
        StoryReconstructionRuntime.onTick(player);
        var first = monsters(player);
        assertEquals(2, first.size());
        first.getFirst().damage(1000);
        first.getLast().damage(25);
        StoryReconstructionRuntime.onTick(player);
        assertEquals(0, player.progress.getCurrentProgress("400503"));
        StoryReconstructionRuntime.capture(player);
        var restored = player(3);
        restored.state = JsonUtils.decode(JsonUtils.encode(player.state), StoryReconstructionState.class);
        restored.progress.getQuestProgressCountMap().putAll(player.progress.getQuestProgressCountMap());
        restored.getPosition().set(anchor);
        var quest = restored.active(400503, false);
        var savedBattle = restored.state.get(400503);
        assertNull(restored.main.resumeOnLogin());
        assertSame(savedBattle, restored.state.get(400503));
        StoryReconstructionRuntime.onTick(restored);
        var remaining = monsters(restored);
        assertEquals(1, remaining.size());
        assertEquals(75, remaining.getFirst().getFightProperty(FightProperty.FIGHT_PROP_CUR_HP), 0.01);
        remaining.getFirst().damage(1000);
        StoryReconstructionRuntime.onTick(restored);
        StoryReconstructionRuntime.onTick(restored);
        var second = monsters(restored);
        assertEquals(3, second.size());
        assertEquals(0, restored.progress.getCurrentProgress("400503"));
        second.forEach(monster -> monster.damage(1000));
        StoryReconstructionRuntime.onTick(restored);
        flush();
        assertEquals(1, restored.progress.getCurrentProgress("400503"));
        assertEquals(QuestState.QUEST_STATE_FINISHED, quest.getState());
    }

    @Test
    void boatUsesInlineRealPlatformRouteAndCannotFinishWithoutFollowingItThenRestoresItsCheckpoint() throws Exception {
        var player = player(3);
        player.active(400506, true);
        StoryReconstructionRuntime.onTick(player);
        var boat = boat(player);
        var operator = player.getScene().getEntityByConfigId(400506 * 100 + 79, 133403025);
        player.getPosition().set(operator.getPosition());
        assertTrue(StoryReconstructionRuntime.tryInteract(player, operator, 7));
        assertTrue(boat.getRouteConfig().isStarted());
        assertTrue(boat.getPlatformInfo().hasRoute());
        var route = StoryReconstructionRuntime.route(player.getScene(), ((ConfigRoute) boat.getRouteConfig()).getRouteId());
        follow(player, boat, route, 0, 5);
        assertEquals(5, player.state.get(400506).getBoatNode());
        StoryReconstructionRuntime.capture(player);
        var restored = player(3);
        restored.state = JsonUtils.decode(JsonUtils.encode(player.state), StoryReconstructionState.class);
        restored.progress.getQuestProgressCountMap().putAll(player.progress.getQuestProgressCountMap());
        restored.getPosition().set(route.getPoints()[5].getPos());
        restored.active(400506, false);
        assertNull(restored.main.resumeOnLogin());
        assertEquals(5, restored.state.get(400506).getBoatNode());
        StoryReconstructionRuntime.onTick(restored);
        var resumed = boat(restored);
        assertEquals(0, route.getPoints()[5].getPos().computeDistance(resumed.getPosition()),0.001);
        assertEquals(0, restored.progress.getCurrentProgress("400506"));
        var resumeOperator = restored.getScene().getEntityByConfigId(400506 * 100 + 79, 133403025);
        restored.getPosition().set(resumeOperator.getPosition());
        StoryReconstructionRuntime.tryInteract(restored, resumeOperator, 7);
        restored.getPosition().set(route.getPoints()[route.getPoints().length - 1].getPos());
        StoryReconstructionRuntime.onTick(restored); // teleporting ahead is not riding the carrier
        assertFalse(restored.state.get(400506).isRiding());
        assertEquals(0, restored.progress.getCurrentProgress("400506"));
        restored.getPosition().set(resumed.getPosition().clone().addY(1));
        StoryReconstructionRuntime.tryInteract(restored, resumeOperator, 7);
        follow(restored, resumed, route, 5, route.getPoints().length - 1);
        flush();
        assertEquals(1, restored.progress.getCurrentProgress("400506"));
    }

    @Test
    void boardingRequiresStandingAboveTheActualPlatformForTheWholeInterval() throws Exception {
        var player=player(3);player.active(400503,true);
        var catalog=custom(400503,"board",true,2,"[]","[{key:'400503',count:1}]");
        StoryReconstructionRuntime.onTick(player,catalog);
        var platform=player.getScene().getEntityByConfigId(400503*100+80,0);
        assertInstanceOf(EntityGadget.class,platform);
        player.scene().clock=3000;
        StoryReconstructionRuntime.onTick(player,catalog);
        assertEquals(0,player.progress.getCurrentProgress("400503")); // Nearby on the ground is not boarded.
        player.getPosition().set(platform.getPosition().clone().addY(1));
        StoryReconstructionRuntime.onTick(player,catalog);
        player.scene().clock=4999;
        StoryReconstructionRuntime.onTick(player,catalog);
        assertEquals(0,player.progress.getCurrentProgress("400503"));
        player.getPosition().addX(5);
        player.scene().clock=6000;StoryReconstructionRuntime.onTick(player,catalog);
        player.getPosition().set(platform.getPosition().clone().addY(1));
        StoryReconstructionRuntime.onTick(player,catalog);
        player.scene().clock=7999;StoryReconstructionRuntime.onTick(player,catalog);
        assertEquals(0,player.progress.getCurrentProgress("400503"));
        player.scene().clock=8000;StoryReconstructionRuntime.onTick(player,catalog);flush();
        assertEquals(1,player.progress.getCurrentProgress("400503"));
        assertEquals(QuestState.QUEST_STATE_FINISHED,player.quests.get(400503).getState());
    }

    @Test
    void directAndBatchClientCountersCannotForgeThisListAndGuestCannotOperateHostTargets() throws Exception {
        var host = player(20202);
        host.active(402404, true);
        StoryReconstructionRuntime.onTick(host);
        var guest = player(20202);
        guest.setWorld(host.getWorld()); guest.setScene(host.getScene());
        var target = host.getScene().getEntityByConfigId(402404 * 100 + 50, 220202001);
        host.getPosition().set(target.getPosition()); guest.getPosition().set(target.getPosition());
        assertTrue(StoryReconstructionRuntime.tryInteract(guest,target,7));
        assertTrue(host.state.get(402404).getSwitches().isEmpty());
        var session = session(host);
        new HandlerAddQuestContentProgressReq().handle(session,new byte[0],AddQuestContentProgressReq.newBuilder()
                .setContentType(QuestContent.QUEST_CONTENT_LUA_NOTIFY.getValue()).setParam(402404).build().toByteArray());
        new HandlerAddQuestContentProgressBatchReq().handle(session,new byte[0],_AddQuestContentProgressBatchReq.newBuilder()
                .addProgressInfoList(_QuestProgressInfo.newBuilder().setContentType(QuestContent.QUEST_CONTENT_LUA_NOTIFY.getValue())
                        .setParam(402404)).build().toByteArray());
        flush();
        assertEquals(0,host.progress.getCurrentProgress("402404"));
    }

    @Test
    void dungeonWorktopCallsRealDungeonSystemAndFailedTransportCannotForgeEntry() throws Exception {
        var player = player(1085);
        player.active(402401, true);
        StoryReconstructionRuntime.onTick(player);
        var operator = player.getScene().getEntityByConfigId(402401 * 100 + 99, 0);
        player.getPosition().set(operator.getPosition());
        player.level = 0;
        StoryReconstructionRuntime.tryInteract(player,operator,7);
        assertEquals(1085,player.getScene().getId());
        player.level = 60;
        player.entryLocked = true;
        StoryReconstructionRuntime.tryInteract(player,operator,7);
        assertEquals(1085,player.getScene().getId());
        player.entryLocked = false;
        player.world.allowTransfer = false;
        assertTrue(StoryReconstructionRuntime.tryInteract(player,operator,7));
        assertEquals(1085,player.getScene().getId());
        player.world.allowTransfer = true;
        StoryReconstructionRuntime.tryInteract(player,operator,7);
        assertEquals(20202,player.getScene().getId());
        assertEquals(1189,player.getScene().getDungeonManager().getDungeonData().getId());
        assertEquals(QuestState.QUEST_STATE_UNFINISHED,player.quests.get(402401).getState());
    }

    private static StoryReconstructionCatalog custom(int qid,String kind,boolean privateAnchor,int hold,String points,String signals) {
        return StoryReconstructionCatalog.parse("{reconstructed:true,version:1,groups:[],stages:[{questId:"+qid
                +",sceneId:3,groupId:0,kind:'"+kind+"',position:{x:0,y:0,z:0},anchorAtPlayer:"+privateAnchor
                +",radius:3,holdSeconds:"+hold+",points:"+points+",notifications:"+signals+"}]}");
    }
    private StoryPlayer player(int sceneId) throws Exception {
        var player = new StoryPlayer(); player.setUid(UID.incrementAndGet());
        player.world = StoryWorld.create(player);
        player.setWorld(player.world);
        player.setScene(player.world.scene(sceneId));
        player.setSceneLoadState(Player.SceneLoadState.LOADED);
        player.initialize(); players.add(player); return player;
    }
    private static List<EntityMonster> monsters(StoryPlayer p) {
        return p.getScene().getEntities().values().stream().filter(e->e instanceof EntityMonster && !e.isDead())
                .map(e->(EntityMonster)e).sorted(Comparator.comparingInt(GameEntity::getConfigId)).toList();
    }
    private static EntityGadget boat(StoryPlayer p) {
        return (EntityGadget)p.getScene().getEntityByConfigId(400506 * 100 + 80,133403025);
    }
    private static void select(StoryPlayer p,int qid,int index) throws Exception {
        var entity=p.getScene().getEntityByConfigId(qid*100+50+index,220202001);
        p.getPosition().set(entity.getPosition());
        new HandlerSelectWorktopOptionReq().handle(session(p),new byte[0],SelectWorktopOptionReq.newBuilder()
                .setGadgetEntityId(entity.getId()).setOptionId(7).build().toByteArray());
    }
    private static GameSession session(StoryPlayer p) { return new GameSession(null) {
        @Override public Player getPlayer(){return p;}
        @Override public void send(BasePacket packet){}
    }; }
    private static void follow(StoryPlayer p,EntityGadget boat,Route route,int from,int to) {
        double seconds=0;
        for(int i=from+1;i<=to;i++) {
            seconds+=route.getPoints()[i-1].getPos().computeDistance(route.getPoints()[i].getPos())/6;
            p.scene().clock=305+(int)Math.ceil(seconds*1000);
            var config=(ConfigRoute)boat.getRouteConfig();
            for(int id:List.copyOf(config.getScheduledIndexes())) {
                var task=p.getScene().getScheduler().getTask(id); if(task!=null)task.run();
            }
            p.getPosition().set(route.getPoints()[i].getPos());
            StoryReconstructionRuntime.onTick(p);
        }
    }
    private static void flush() throws Exception {QuestManager.eventExecutor.submit(()->{}).get(5,TimeUnit.SECONDS);}

    private static final class StoryPlayer extends TestWorld.TestPlayer {
        StoryReconstructionState state=new StoryReconstructionState();
        final PlayerProgress progress=new PlayerProgress();
        final QuestManager manager=new QuestManager(this);
        final Map<Integer,GameQuest> quests=new HashMap<>();
        final List<BasePacket> packets=new ArrayList<>();
        StoryWorld world;
        boolean entryLocked;
        GameMainQuest main;
        void initialize() {
            main=new GameMainQuest(this,MAIN) {
                @Override public void tryFailSubQuests(QuestContent type,String value,int...params){}
            };
            manager.getMainQuests().put(MAIN,main);
        }
        GameQuest active(int id,boolean fresh) {
            var quest=new GameQuest(main,GameData.getQuestDataMap().get(id)) {
                @Override public void finish(){setState(QuestState.QUEST_STATE_FINISHED);}
                @Override public void save(){}
            };
            main.getChildQuests().put(id,quest); quests.put(id,quest);
            quest.setState(QuestState.QUEST_STATE_UNFINISHED);
            if(fresh)StoryReconstructionRuntime.onQuestStart(quest);
            return quest;
        }
        StoryScene scene(){return (StoryScene)getScene();}
        @Override public StoryReconstructionState getStoryReconstruction(){return state;}
        @Override public PlayerProgress getPlayerProgress(){return progress;}
        @Override public QuestManager getQuestManager(){return manager;}
        @Override public GameServer getServer(){return world.server;}
        @Override public boolean isScenePointForceLocked(int sceneId, int pointId){return entryLocked;}
        @Override public void save(){}
        @Override public void sendPacket(BasePacket packet){packets.add(packet);}
    }
    private static final class StoryWorld extends World {
        StoryPlayer owner;
        GameServer server;
        Int2ObjectMap<Scene> scenes;
        int next;
        boolean allowTransfer=true;
        StoryWorld(){super((Player)null);}
        static StoryWorld create(StoryPlayer owner)throws Exception {
            var f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);var unsafe=(Unsafe)f.get(null);
            var world=(StoryWorld)unsafe.allocateInstance(StoryWorld.class);
            world.owner=owner;world.scenes=new Int2ObjectOpenHashMap<>();world.allowTransfer=true;
            world.server=(GameServer)unsafe.allocateInstance(GameServer.class);
            var d=GameServer.class.getDeclaredField("dungeonSystem");d.setAccessible(true);d.set(world.server,new DungeonSystem(world.server));
            var q=GameServer.class.getDeclaredField("questSystem");q.setAccessible(true);q.set(world.server,new QuestSystem(world.server));
            GameData.setConfigGlobalCombat(new emu.grasscutter.data.binout.config.ConfigGlobalCombat());
            return world;
        }
        StoryScene scene(int id){return (StoryScene)scenes.computeIfAbsent(id,key->new StoryScene(this,key));}
        @Override public Player getHost(){return owner;}
        @Override public GameServer getServer(){return server;}
        @Override public List<Player> getPlayers(){return List.of(owner);}
        @Override public Int2ObjectMap<Scene> getScenes(){return scenes;}
        @Override public int getNextEntityId(EntityIdType type){return(type.getId()<<24)+ ++next;}
        @Override public int getLevelEntityId(){return 0;}
        @Override public boolean transferPlayerToScene(Player p,TeleportProperties properties){
            if(!allowTransfer)return false;
            p.setScene(scene(properties.getSceneId()));return true;
        }
    }
    private static final class StoryScene extends TestWorld.TestScene {
        int clock;
        SceneScriptManager scripts;
        StoryScene(StoryWorld world,int id){super(world,id,id==20202?SceneType.SCENE_DUNGEON:SceneType.SCENE_ROOM);}
        @Override public int getSceneTime(){return clock;}
        @Override public SceneScriptManager getScriptManager(){
            if(scripts==null)scripts=new SceneScriptManager(this){
                @Override public Future<?> callEvent(ScriptArgs args){return CompletableFuture.completedFuture(null);}
            };
            return scripts;
        }
        @Override public synchronized void addEntity(GameEntity entity){getEntities().put(entity.getId(),entity);StoryReconstructionRuntime.onEntityCreated(entity);}
        @Override public void killEntity(GameEntity entity,int attacker){getEntities().remove(entity.getId());entity.onDeath(attacker);}
        @Override public void broadcastPacket(BasePacket packet){}
        @Override public void saveGroups(){}
    }
}
