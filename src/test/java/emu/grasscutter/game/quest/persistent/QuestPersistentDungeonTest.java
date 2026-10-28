package emu.grasscutter.game.quest.persistent;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.MainQuestData;
import emu.grasscutter.data.binout.ScenePointEntry;
import emu.grasscutter.data.common.PointData;
import emu.grasscutter.data.excels.dungeon.DungeonData;
import emu.grasscutter.data.excels.dungeon.DungeonPassConfigData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.data.excels.scene.SceneData;
import emu.grasscutter.game.dungeons.DungeonManager;
import emu.grasscutter.game.dungeons.PersistentDungeonProgress;
import emu.grasscutter.game.entity.EntityGadget;
import emu.grasscutter.game.entity.EntityMonster;
import emu.grasscutter.game.props.SceneType;
import emu.grasscutter.game.quest.GameMainQuest;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.world.SceneGroupInstance;
import emu.grasscutter.game.world.TestWorld;
import emu.grasscutter.game.world.World;
import emu.grasscutter.scripts.SceneScriptManager;
import emu.grasscutter.scripts.ScriptLoader;
import emu.grasscutter.scripts.data.*;
import emu.grasscutter.utils.JsonUtils;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import org.junit.jupiter.api.*;

final class QuestPersistentDungeonTest {
    private static boolean scripts;
    private SceneData oldWorld, oldScene;
    private DungeonData oldDungeon, data;
    private DungeonPassConfigData oldPass;
    private ScenePointEntry oldPoint;

    private static final class SavedPlayer extends TestWorld.TestPlayer {
        int saves;
        @Override public void save() { saves++; }
    }

    @BeforeAll
    static void prepareScripts() throws Exception {
        ScriptLoader.init();
        scripts = Grasscutter.getConfig().server.game.enableScriptInBigWorld;
        Grasscutter.getConfig().server.game.enableScriptInBigWorld = false;
    }

    @AfterAll
    static void restoreScripts() { Grasscutter.getConfig().server.game.enableScriptInBigWorld = scripts; }

    @BeforeEach
    void data() {
        oldWorld = GameData.getSceneDataMap().put(3, TestWorld.sceneData(3, SceneType.SCENE_WORLD));
        oldScene = GameData.getSceneDataMap().put(20238, TestWorld.sceneData(20238, SceneType.SCENE_DUNGEON));
        data = JsonUtils.decode("{id:1222,sceneId:20238,passCond:107,type:'DUNGEON_BIGWORLD_MIRROR',"
                + "involveType:'INVOLVE_ONLY_SINGLE'}", DungeonData.class);
        oldDungeon = GameData.getDungeonDataMap().put(1222, data);
        oldPass = GameData.getDungeonPassConfigDataMap().put(107, JsonUtils.decode(
                "{id:107,conds:[{},{}]}", DungeonPassConfigData.class));
        oldPoint = GameData.getScenePointEntryMap().put((20238 << 16) + 7,
                new ScenePointEntry(20238, JsonUtils.decode("{id:7}", PointData.class)));
    }

    private static <T> void restore(Map<Integer, T> map, int key, T old) {
        if (old == null) map.remove(key); else map.put(key, old);
    }

    @AfterEach
    void restoreData() {
        restore(GameData.getSceneDataMap(), 3, oldWorld);
        restore(GameData.getSceneDataMap(), 20238, oldScene);
        restore(GameData.getDungeonDataMap(), 1222, oldDungeon);
        restore(GameData.getDungeonPassConfigDataMap(), 107, oldPass);
        restore(GameData.getScenePointEntryMap(), (20238 << 16) + 7, oldPoint);
    }

    private SavedPlayer player() {
        var owner = new SavedPlayer();
        owner.setUid(42);
        var world = TestWorld.create(owner);
        var scene = new TestWorld.TestScene(world, 20238, SceneType.SCENE_DUNGEON);
        owner.setScene(scene);
        owner.setSceneId(20238);
        scene.setDungeonManager(new DungeonManager(scene, data));
        return owner;
    }

    private static GameQuest quest(SavedPlayer player) {
        var previous = GameData.getMainQuestDataMap().put(990311,
                JsonUtils.decode("{id:990311,subQuests:[]}", MainQuestData.class));
        try {
            return new GameQuest(new GameMainQuest(player, 990311), JsonUtils.decode(
                    "{mainId:990311,subId:99031101,finishCond:[],failCond:[]}", QuestData.class));
        } finally { restore(GameData.getMainQuestDataMap(), 990311, previous); }
    }

    @Test
    void managerRoundTripKeepsActualPassProgressWaypointsAndExistingClaimReceipts() throws Exception {
        var owner = player();
        var manager = owner.getScene().getDungeonManager();
        manager.getFinishedConditions()[0] = 1;
        owner.getScene().setKilledMonsterCount(7);
        assertTrue(manager.activateRespawnPoint(7));
        Field rewarded = DungeonManager.class.getDeclaredField("rewardedPlayers");
        rewarded.setAccessible(true);
        ((Set<Integer>) rewarded.get(manager)).add(42);
        manager.startDungeon();
        var clock = DungeonManager.class.getDeclaredField("startSceneTime");
        clock.setAccessible(true);
        clock.setInt(manager, -20);
        var saved = JsonUtils.decode(JsonUtils.encode(manager.capturePersistentProgress()), PersistentDungeonProgress.class);
        var restored = new DungeonManager(owner.getScene(), data);
        assertTrue(restored.restorePersistentProgress(saved));
        assertArrayEquals(new int[] {1, 0}, restored.getFinishedConditions());
        assertTrue(restored.isWayPointActive(7));
        assertEquals(7, owner.getScene().getKilledMonsterCount());
        assertEquals(saved.getClaimRunToken(), restored.capturePersistentProgress().getClaimRunToken());
        assertEquals(Set.of(42), restored.capturePersistentProgress().getRewardedPlayers());
        int start = restored.getStartSceneTime();
        restored.startDungeon();
        assertEquals(start, restored.getStartSceneTime(), "scene-entry acknowledgement cannot restart restored time");
    }

    @Test
    void anotherOwnersRunCannotChangeTheManagerOrItsReceiptIdentity() {
        var owner = player();
        var saved = owner.getScene().getDungeonManager().capturePersistentProgress();
        var foreign = com.google.gson.JsonParser.parseString(JsonUtils.encode(saved)).getAsJsonObject();
        foreign.addProperty("ownerUid", 43);
        var invalid = JsonUtils.decode(foreign, PersistentDungeonProgress.class);
        assertEquals(43, invalid.getOwnerUid());
        var manager = new DungeonManager(owner.getScene(), data);
        var originalToken = manager.capturePersistentProgress().getClaimRunToken();
        assertFalse(manager.restorePersistentProgress(invalid));
        assertArrayEquals(new int[] {0, 0}, manager.getFinishedConditions());
        assertEquals(originalToken, manager.capturePersistentProgress().getClaimRunToken());
    }

    @Test
    void loginRestoresOnlyAnActivatedOwnedRealManagerAndClearRevokesIt() throws Exception {
        var owner = player();
        assertFalse(QuestPersistentDungeonRuntime.canRestoreLogin(owner));
        owner.getPersistentDungeons().activate(20238);
        assertFalse(QuestPersistentDungeonRuntime.canRestoreLogin(owner));
        assertTrue(QuestPersistentDungeonRuntime.onEntered(owner.getScene()));
        assertTrue(owner.getScene().isDontDestroyWhenEmpty());
        assertTrue(QuestPersistentDungeonRuntime.canRestoreLogin(owner));
        owner.getScene().setPrevScenePoint(88);
        assertTrue(QuestPersistentDungeonRuntime.onEntered(owner.getScene()));
        assertEquals(88, owner.getScene().getPrevScenePoint(), "new native entry origin must not be overwritten by an old snapshot");
        var copy = JsonUtils.decode(JsonUtils.encode(owner.getPersistentDungeons()), QuestPersistentDungeonState.class);
        var state = emu.grasscutter.game.player.Player.class.getDeclaredField("persistentDungeons");
        state.setAccessible(true);
        state.set(owner, copy);
        owner.getScene().setDungeonManager(null);
        assertTrue(QuestPersistentDungeonRuntime.restoreLogin(owner));
        assertEquals(1222, owner.getScene().getDungeonManager().getDungeonData().getId());
        var scenes = World.class.getDeclaredField("scenes");
        scenes.setAccessible(true);
        var map = new Int2ObjectOpenHashMap<emu.grasscutter.game.world.Scene>();
        map.put(20238, owner.getScene());
        scenes.set(owner.getWorld(), map);
        owner.getScene().getPlayers().add(owner);
        assertTrue(QuestPersistentDungeonRuntime.clear(quest(owner), 20238));
        assertFalse(owner.getScene().isDontDestroyWhenEmpty());
        assertFalse(QuestPersistentDungeonRuntime.canRestoreLogin(owner));
    }

    @Test
    void onlyNativeActivationInYourWorldEnablesTheSceneAndItDoesNotInventAnInstance() {
        var owner = player();
        owner.setSceneId(3);
        owner.setScene(new TestWorld.TestScene(owner.getWorld(), 3, SceneType.SCENE_ROOM));
        assertTrue(QuestPersistentDungeonRuntime.activate(quest(owner), 20238));
        assertNull(owner.getPersistentDungeons().getRun(20238).getProgress());
        assertFalse(QuestPersistentDungeonRuntime.canRestoreLogin(owner));
        owner.setWorld(TestWorld.create(new SavedPlayer()));
        assertFalse(QuestPersistentDungeonRuntime.activate(quest(owner), 20238));
    }

    @Test
    void suiteChangesAreSavedEvenWhenTheManagerCountersHaveNotChanged() {
        var owner = player();
        var state = owner.getPersistentDungeons();
        state.activate(20238);
        assertTrue(QuestPersistentDungeonRuntime.onEntered(owner.getScene()));
        int saves = owner.saves;
        QuestPersistentDungeonRuntime.suiteAdded(owner.getScene(), 202380001, 1);
        QuestPersistentDungeonRuntime.suiteAdded(owner.getScene(), 202380001, 2);
        QuestPersistentDungeonRuntime.suiteRemoved(owner.getScene(), 202380001, 1);
        assertTrue(QuestPersistentDungeonRuntime.capture(owner.getScene()));
        assertTrue(owner.saves > saves);
        var restored = JsonUtils.decode(JsonUtils.encode(state), QuestPersistentDungeonState.class);
        assertEquals(Set.of(2), restored.getRun(20238).getSuites(202380001));
        restored.clear(20238);
        restored.activate(20238);
        assertFalse(restored.getRun(20238).hasGroup(202380001));
    }

    private static final class CountingScripts extends SceneScriptManager {
        final List<Integer> monsters = new ArrayList<>(), gadgets = new ArrayList<>();
        CountingScripts(emu.grasscutter.game.world.Scene scene) { super(scene); }
        @Override public Future<?> callEvent(ScriptArgs args) { return CompletableFuture.completedFuture(null); }
        @Override public EntityMonster restoreMonster(int groupId, int block, SceneMonster monster) {
            monsters.add(monster.config_id);
            return null;
        }
        @Override public List<EntityGadget> getGadgetsInGroupSuite(SceneGroupInstance group, SceneSuite suite) {
            suite.sceneGadgets.forEach(gadget -> gadgets.add(gadget.config_id));
            return List.of();
        }
    }

    @Test
    void actualGroupRestorationKeepsExtraSuitesVariablesAndDeadEntityFilters() {
        var owner = new SavedPlayer();
        owner.setUid(42);
        var world = TestWorld.create(owner);
        GameData.getSceneDataMap().put(3, TestWorld.sceneData(3, SceneType.SCENE_DUNGEON));
        var scene = new TestWorld.TestScene(world, 3, SceneType.SCENE_DUNGEON);
        owner.setScene(scene);
        var room = JsonUtils.decode("{id:900001,sceneId:3,type:'DUNGEON_BIGWORLD_MIRROR',"
                + "involveType:'INVOLVE_ONLY_SINGLE'}", DungeonData.class);
        var previous = GameData.getDungeonDataMap().put(900001, room);
        scene.setDungeonManager(new DungeonManager(scene, room));
        owner.getPersistentDungeons().activate(3);
        var group = SceneGroup.of(202380001);
        var first = new SceneSuite();
        var extra = new SceneSuite();
        first.sceneMonsters = List.of(monster(11), monster(12));
        extra.sceneMonsters = List.of(monster(13));
        first.sceneGadgets = List.of(gadget(21), gadget(22));
        extra.sceneGadgets = List.of(gadget(23));
        group.suites = List.of(first, extra);
        var instance = new SceneGroupInstance(group, owner);
        instance.setActiveSuiteId(1);
        instance.getCachedVariables().put("progress", 8);
        instance.getDeadEntities().addAll(Set.of(11, 21));
        owner.getPersistentDungeons().suiteAdded(3, group.id, 1);
        owner.getPersistentDungeons().suiteAdded(3, group.id, 2);
        var manager = new CountingScripts(scene);
        try {
            assertEquals(1, manager.restoreGroup(instance, 0));
            assertEquals(List.of(12, 13), manager.monsters);
            assertEquals(List.of(22, 23), manager.gadgets);
            assertEquals(8, instance.getCachedVariables().get("progress"));
        } finally { restore(GameData.getDungeonDataMap(), 900001, previous); }
    }

    private static SceneMonster monster(int id) { var value = new SceneMonster(); value.config_id = id; return value; }
    private static SceneGadget gadget(int id) { var value = new SceneGadget(); value.config_id = id; return value; }
}
