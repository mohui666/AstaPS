package emu.grasscutter.scripts;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.*;
import emu.grasscutter.data.binout.config.*;
import emu.grasscutter.data.excels.GadgetData;
import emu.grasscutter.data.excels.dungeon.*;
import emu.grasscutter.data.excels.monster.*;
import emu.grasscutter.game.activity.ActivityManager;
import emu.grasscutter.game.activity.leylinechallenge.LeyLineChallengeRun;
import emu.grasscutter.game.battlepass.BattlePassManager;
import emu.grasscutter.game.dungeons.*;
import emu.grasscutter.game.dungeons.dungeon_results.BaseDungeonResult.DungeonEndReason;
import emu.grasscutter.game.dungeons.enums.DungeonPassConditionType;
import emu.grasscutter.game.dungeons.handlers.DungeonBaseHandler;
import emu.grasscutter.game.dungeons.pass_condition.ConditionFinishChallenge;
import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.entity.gadget.GadgetWorktop;
import emu.grasscutter.game.player.*;
import emu.grasscutter.game.props.*;
import emu.grasscutter.game.tower.TowerManager;
import emu.grasscutter.game.world.*;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.VisionTypeOuterClass.VisionType;
import emu.grasscutter.scripts.constants.EventType;
import emu.grasscutter.scripts.data.*;
import emu.grasscutter.server.game.GameServer;
import emu.grasscutter.utils.*;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import sun.misc.Unsafe;

/** Actual authored Lua, spawning, numeric monster stats, damage, death callbacks and dungeon pass conditions.
 * Client AI and client-driven ability phases are outside this offline fixture. */
@ExtendWith(ServerResourceFixture.class)
final class TowerAuthoredLuaRuntimeTest {
    private static final List<Runnable> RESTORE = new ArrayList<>();
    private static ConfigGlobalCombat originalCombat;
    private static boolean originalBigWorldScripts;
    private static boolean configured;
    private static final Set<Integer> EXPECTED_MONSTERS = new HashSet<>();
    private static final Set<Integer> DAMAGED_MONSTERS = new HashSet<>();
    private BattleScene scene;

    @BeforeAll
    static void resources() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(FileUtils.getScriptPath("Scene/mgc-schedule-124.json")),
                "Generate schedule 124 with tools/tower/generate_current_abyss.py first");
        ScriptLoader.init();
        originalBigWorldScripts = Grasscutter.getConfig().server.game.enableScriptInBigWorld;
        Grasscutter.getConfig().server.game.enableScriptInBigWorld = false;
        originalCombat = GameData.getConfigGlobalCombat();
        configured = true;
        var manifest = JsonParser.parseString(Files.readString(FileUtils.getScriptPath("Scene/mgc-schedule-124.json"))).getAsJsonArray();
        for (var room : manifest) for (var half : room.getAsJsonObject().getAsJsonArray("halves"))
            for (var wave : half.getAsJsonObject().getAsJsonArray("waves")) for (var monster : wave.getAsJsonArray())
                EXPECTED_MONSTERS.add(monster.getAsJsonObject().get("monsterId").getAsInt());
        for (int difficulty = 1; difficulty <= 6; difficulty++) for (int battle = 1; battle <= 3; battle++)
            EXPECTED_MONSTERS.add(LeyLineChallengeRun.plan(difficulty, battle).monsterId());
        loadTable(GameData.getGadgetDataMap(), GadgetData.class, "GadgetExcelConfigData.json", null);
        loadTable(GameData.getMonsterCurveDataMap(), MonsterCurveData.class, "MonsterCurveExcelConfigData.json", null);
        loadTable(GameData.getMonsterTitleDataMap(), MonsterTitleData.class, "MonsterTitleExcelConfigData.json", null);
        loadTable(GameData.getMonsterSpecialNameDataMap(), MonsterSpecialNameData.class, "MonsterSpecialNameExcelConfigData.json", null);
        loadTable(GameData.getMonsterDescribeDataMap(), MonsterDescribeData.class, "MonsterDescribeExcelConfigData.json", null);
        loadTable(GameData.getMonsterDataMap(), MonsterData.class, "MonsterExcelConfigData.json", EXPECTED_MONSTERS);
        loadTable(GameData.getDungeonDataMap(), DungeonData.class, "DungeonExcelConfigData.json", null);
        loadTable(GameData.getDungeonPassConfigDataMap(), DungeonPassConfigData.class, "DungeonPassExcelConfigData.json", Set.of(50, 173));
        loadTable(GameData.getDungeonChallengeConfigDataMap(), DungeonChallengeConfigData.class,
                "DungeonChallengeConfigData.json", Set.of(188, 197, 198));
        String name = "SceneObj_Gear_Operator_Mamolu_Entity";
        var previous = GameData.getGadgetConfigData().get(name);
        var raw = JsonParser.parseString(Files.readString(FileUtils.getResourcePath(
                "BinOutput/Gadget/ConfigGadget_Scene_Gear_Operator.json"))).getAsJsonObject().get(name);
        GameData.getGadgetConfigData().put(name, JsonUtils.decode(raw, ConfigEntityGadget.class));
        RESTORE.add(() -> { if (previous == null) GameData.getGadgetConfigData().remove(name);
                           else GameData.getGadgetConfigData().put(name, previous); });
    }

    private static <T extends GameResource> void loadTable(Map<Integer, T> target, Class<T> type,
            String filename, Set<Integer> selected) throws Exception {
        var before = new HashMap<>(target);
        RESTORE.add(() -> { target.clear(); target.putAll(before); });
        for (var row : JsonUtils.loadToList(FileUtils.getExcelPath(filename), type)) {
            if (selected != null && !selected.contains(row.getId())) continue;
            target.put(row.getId(), row);
            row.onLoad();
        }
    }

    @AfterAll
    static void restore() {
        for (int i = RESTORE.size() - 1; i >= 0; i--) RESTORE.get(i).run();
        if (configured) {
            GameData.setConfigGlobalCombat(originalCombat);
            Grasscutter.getConfig().server.game.enableScriptInBigWorld = originalBigWorldScripts;
        }
        assertEquals(EXPECTED_MONSTERS, DAMAGED_MONSTERS, "every authored enemy kind must take real damage");
    }

    @AfterEach
    void finishEvents() throws Exception {
        if (scene != null) scene.scripts.awaitEvents();
    }

    @ParameterizedTest
    @ValueSource(ints = {33881,33882,33883,33884,33885,33886,33899,33900,33901,33902,33903,33904})
    void bothHalvesSpawnTakeDamageAdvanceWavesAndSettleOnlyAfterTheLowerHalf(int authoredScene) throws Exception {
        var owner = new BattlePlayer();
        owner.setUid(901);
        var world = TestWorld.create(owner);
        var dungeonSystem = allocate(DungeonSystem.class);
        var handlers = new Int2ObjectOpenHashMap<DungeonBaseHandler>();
        handlers.put(DungeonPassConditionType.DUNGEON_COND_FINISH_CHALLENGE.ordinal(), new ConditionFinishChallenge());
        set(dungeonSystem, DungeonSystem.class, "passCondHandlers", handlers);
        var server = allocate(GameServer.class);
        set(server, GameServer.class, "dungeonSystem", dungeonSystem);
        set(world, World.class, "server", server);
        scene = new BattleScene(world, authoredScene);
        owner.setScene(scene);
        owner.setSceneId(authoredScene);
        owner.getPlayerProgress().setPlayer(owner);
        owner.team.setEntity(new EntityTeam(owner));
        scene.getPlayers().add(owner);
        owner.tower.floor = authoredScene <= 33883 ? 9 : authoredScene <= 33886 ? 10 : authoredScene <= 33901 ? 11 : 12;
        var dungeon = GameData.getDungeonDataMap().values().stream()
                .filter(row -> row.getSceneId() == authoredScene && row.getType() == emu.grasscutter.game.dungeons.enums.DungeonType.DUNGEON_TOWER)
                .findFirst().orElseThrow();
        var manager = new DungeonManager(scene, dungeon);
        manager.setTowerDungeon(true);
        scene.setDungeonManager(manager);
        scene.addDungeonSettleObserver((ignored, reason) -> scene.settlements.add(reason));
        var groupFiles = Files.list(FileUtils.getScriptPath("Scene/" + authoredScene));
        try (groupFiles) {
            for (var path : groupFiles.filter(p -> p.getFileName().toString().contains("_group")).sorted().toList()) {
                var file = path.getFileName().toString();
                int id = Integer.parseInt(file.substring(file.indexOf("_group") + 6, file.length() - 4));
                scene.scripts.install(SceneGroup.of(id).load(authoredScene), owner);
            }
        }
        owner.tower.level = scene.scripts.groups.values().stream().flatMap(group -> group.monsters.values().stream())
                .findFirst().orElseThrow().level;
        for (var instance : scene.scripts.instances.values())
            scene.scripts.addGroupSuite(instance, instance.getLuaGroup().getSuiteByIndex(1));
        scene.scripts.awaitEvents();
        assertTrue(monsters().isEmpty(), "no enemies before using the start worktop");
        var worktop = scene.getEntities().values().stream().filter(e -> e instanceof EntityGadget gadget
                && gadget.getContent() instanceof GadgetWorktop).map(e -> (EntityGadget)e).findFirst().orElseThrow();
        assertEquals(Set.of(175), ((GadgetWorktop)worktop.getContent()).getWorktopOptions());
        select(worktop, 175);
        if (authoredScene == 33902) {
            assertEquals(25133802, monsters().getFirst().getEntityTypeId(), "use the native dungeon variant");
            assertEquals(5_535_346f, monsters().getFirst().getFightProperty(FightProperty.FIGHT_PROP_MAX_HP));
            var info = monsters().getFirst().toProto().getMonster();
            assertTrue(info.getIsElite(), "the solo boss carries the client display flag");
            assertEquals(9032, info.getTitleId());
            assertEquals(2904701, info.getSpecialNameId());
        }
        assertNotNull(scene.getChallenge());
        assertTrue(scene.getChallenge().inProgress());
        var guard = scene.getEntities().values().stream().filter(e -> e instanceof EntityGadget gadget
                && gadget.getGadgetId() == 70350450).map(e -> (EntityGadget)e).findFirst().orElse(null);
        float guardHp = 0;
        if (authoredScene == 33899) {
            assertNotNull(guard);
            assertEquals(233899003, guard.getGroupId());
            assertEquals(3024, guard.getConfigId());
            guard.damage(guard.getFightProperty(FightProperty.FIGHT_PROP_MAX_HP) * .1f, 0, ElementType.None);
            guardHp = guard.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP);
            assertEquals(90, scene.getChallenge().getGuardEntityHpPercent());
        }
        int upperSpawnCount = scene.spawned;
        defeatHalf();
        scene.scripts.awaitEvents();
        assertTrue(scene.settlements.isEmpty(), "upper half must never settle the chamber");
        assertEquals(1, owner.tower.requestedTeam);
        assertTrue(monsters().isEmpty(), "upper death must not start lower enemies before option 176");
        assertTrue(((GadgetWorktop)worktop.getContent()).getWorktopOptions().contains(176));
        if (authoredScene == 33899) {
            assertTrue(scene.spawned > upperSpawnCount, "11-1 must advance to its second wave through monster deaths");
            assertSame(guard, scene.getEntityByConfigId(3024, 233899003));
            assertEquals(guardHp, guard.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP));
        }
        select(worktop, 176);
        assertNotNull(scene.getChallenge());
        assertTrue(scene.getChallenge().inProgress());
        if (authoredScene == 33902) {
            assertEquals(1, monsters().size(), "12-1 lower starts with the Baptist alone");
            assertEquals(22100301, monsters().get(0).getEntityTypeId());
        }
        if (guard != null) {
            assertSame(guard, scene.getChallenge().getGuardEntity());
            assertEquals(90, scene.getChallenge().getGuardEntityHpPercent());
        }
        defeatHalf();
        scene.scripts.awaitEvents();
        assertEquals(List.of(DungeonEndReason.COMPLETED), scene.settlements);
        assertTrue(scene.getDungeonManager().isFinishedSuccessfully());
        assertTrue(monsters().isEmpty());
        int expectedCount = scene.scripts.groups.values().stream().mapToInt(group -> group.monsters.size()).sum();
        assertEquals(expectedCount, scene.spawned, "every authored enemy must actually spawn");
        assertEquals(expectedCount, scene.getKilledMonsterCount(), "every enemy must follow the real death path");
        for (var group : scene.scripts.groups.values()) {
            var vars = scene.scripts.getVariables(group.id);
            if (vars.containsKey("stage")) assertEquals(2, vars.get("stage"));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6})
    void independentStygianBattlesSpawnAtTheSelectedLevelAndSettleOnlyAfterActualDeath(int difficulty) throws Exception {
        int[] expectedLevels = {40, 70, 90, 100, 105, 110};
        for (int battle = 1; battle <= 3; battle++) {
            var owner = new BattlePlayer();
            owner.setUid(901);
            var world = TestWorld.create(owner);
            var system = allocate(DungeonSystem.class);
            set(system, DungeonSystem.class, "passCondHandlers", new Int2ObjectOpenHashMap<DungeonBaseHandler>());
            var server = allocate(GameServer.class);
            set(server, GameServer.class, "dungeonSystem", system);
            set(world, World.class, "server", server);
            var plan = LeyLineChallengeRun.plan(difficulty, battle);
            var dungeon = GameData.getDungeonDataMap().get(plan.dungeonId());
            scene = new BattleScene(world, dungeon.getSceneId());
            owner.setScene(scene);
            owner.setSceneId(scene.getId());
            owner.setSceneLoadState(Player.SceneLoadState.LOADING);
            owner.getPlayerProgress().setPlayer(owner);
            owner.team.setEntity(new EntityTeam(owner));
            scene.getPlayers().add(owner);
            scene.setDungeonManager(new DungeonManager(scene, dungeon));
            scene.addDungeonSettleObserver((ignored, reason) -> scene.settlements.add(reason));
            var run = new LeyLineChallengeRun(scene, plan);
            run.tick();
            assertTrue(monsters().isEmpty(), "wait for the actual scene handshake before starting");
            owner.setSceneLoadState(Player.SceneLoadState.LOADED);
            run.tick();
            run.tick();
            assertEquals(1, monsters().size(), "one boss per independent battlefield, with no duplicate tick spawn");
            var monster = monsters().get(0);
            assertEquals(expectedLevels[difficulty - 1], monster.getLevel());
            assertEquals(difficulty <= 3 ? 600 : 120, scene.getChallenge().getTimeLimit());
            assertTrue(scene.settlements.isEmpty());
            float hp = monster.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP);
            assertTrue(Float.isFinite(hp) && hp > 0);
            monster.damage(hp * .1f, 0, ElementType.None);
            assertTrue(monster.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP) < hp);
            DAMAGED_MONSTERS.add(monster.getEntityTypeId());
            assertTrue(scene.getChallenge().inProgress());
            monster.damage(hp * 2, 0, ElementType.None);
            scene.scripts.awaitEvents();
            run.tick();
            run.tick();
            assertEquals(List.of(DungeonEndReason.COMPLETED), scene.settlements);
            assertTrue(monsters().isEmpty());
        }
    }

    @Test
    void operatorMonsterPropertiesStayLocalAndSurviveRecalculation() throws Exception {
        var owner = new BattlePlayer();
        var world = TestWorld.create(owner);
        scene = new BattleScene(world, 33902);
        var data = GameData.getMonsterDataMap().get(25133802);
        var monster = new EntityMonster(scene, data, new Position(), new Position(), 95);
        var other = new EntityMonster(scene, data, new Position(), new Position(), 95);
        float originalHp = other.getFightProperty(FightProperty.FIGHT_PROP_MAX_HP);
        monster.setSpawnFightProperties(Map.of(FightProperty.FIGHT_PROP_MAX_HP, 1000f,
                FightProperty.FIGHT_PROP_BASE_ATTACK, 100f, FightProperty.FIGHT_PROP_ATTACK_PERCENT, .5f,
                FightProperty.FIGHT_PROP_FIRE_SUB_HURT, -.2f, FightProperty.FIGHT_PROP_CRITICAL, .5f));
        assertEquals(1000, monster.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP));
        assertEquals(150, monster.getFightProperty(FightProperty.FIGHT_PROP_CUR_ATTACK));
        monster.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP, 800f);
        monster.recalcStats();
        assertEquals(800, monster.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP));
        assertEquals(-.2f, monster.getFightProperty(FightProperty.FIGHT_PROP_FIRE_SUB_HURT));
        assertEquals(.5f, monster.getFightProperty(FightProperty.FIGHT_PROP_CRITICAL));
        other.recalcStats();
        assertEquals(originalHp, other.getFightProperty(FightProperty.FIGHT_PROP_MAX_HP));
        assertNotEquals(1000, other.getFightProperty(FightProperty.FIGHT_PROP_MAX_HP));
        assertThrows(IllegalArgumentException.class, () -> monster.setSpawnFightProperties(Map.of(
                FightProperty.FIGHT_PROP_MAX_HP, 1000f, FightProperty.FIGHT_PROP_CUR_HP, 1001f)));
    }

    @Test
    void archerBlinkProtectionEndsAtTheNativeCountdownWithoutRemovingOtherProtection() throws Exception {
        var owner = new BattlePlayer();
        var world = TestWorld.create(owner);
        scene = new BattleScene(world, 33902);
        var monster = new EntityMonster(scene, GameData.getMonsterDataMap().get(25133802), new Position(), new Position(), 95);
        var path = FileUtils.getResourcePath("BinOutput/Ability/Temp/MonsterAbilities/ConfigAbility_Monster_Volkodlak_Greyhound.json");
        var rows = JsonParser.parseString(Files.readString(path)).getAsJsonArray();
        emu.grasscutter.data.binout.AbilityData blink = null;
        for (var row : rows) {
            var ability = row.getAsJsonObject().getAsJsonObject("Default");
            if (ability != null && "Monster_Volkodlak_Greyhound_Furiosa_Blink".equals(ability.get("abilityName").getAsString()))
                blink = JsonUtils.decode(ability, emu.grasscutter.data.binout.AbilityData.class);
        }
        assertNotNull(blink);
        assertEquals(2f, blink.modifiers.get("Invisible_CountDown").duration.get());
        var invincible = blink.modifiers.get("Blink_Invincible");
        var controller = new emu.grasscutter.game.ability.AbilityModifierController(null, blink, invincible);
        monster.getInstancedModifiers().put(100, controller);
        monster.onAddAbilityModifier(invincible);
        float hp = monster.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP);
        monster.damage(1000f, 0, ElementType.None);
        assertEquals(hp, monster.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP));
        var phase = new VolkodlakBlinkState();
        phase.tick(monster, 10); phase.tick(monster, 11);
        assertTrue(monster.isModifierInvincible());
        phase.tick(monster, 12);
        assertFalse(monster.isModifierInvincible());
        monster.damage(1000f, 0, ElementType.None);
        assertEquals(hp - 1000f, monster.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP));
        monster.getInstancedModifiers().put(101, controller);
        monster.onAddAbilityModifier(invincible);
        var other = new emu.grasscutter.data.binout.AbilityModifier();
        other.state = emu.grasscutter.data.binout.AbilityModifier.State.Invincible;
        monster.getInstancedModifiers().put(102, new emu.grasscutter.game.ability.AbilityModifierController(null,
                new emu.grasscutter.data.binout.AbilityData(), other));
        phase.tick(monster, 20); phase.tick(monster, 22);
        assertTrue(monster.isModifierInvincible(), "independent protection must remain");
        assertTrue(monster.getInstancedModifiers().containsKey(102));
        assertFalse(monster.getInstancedModifiers().containsKey(101));
    }

    private void select(EntityGadget worktop, int option) throws Exception {
        scene.scripts.callEvent(new ScriptArgs(worktop.getGroupId(), EventType.EVENT_SELECT_OPTION,
                worktop.getConfigId(), option).setUid(901)).get(10, TimeUnit.SECONDS);
        scene.scripts.awaitEvents();
    }

    private List<EntityMonster> monsters() {
        return scene.getEntities().values().stream().filter(e -> e instanceof EntityMonster)
                .map(e -> (EntityMonster)e).toList();
    }

    private void defeatHalf() throws Exception {
        int killedAtStart = scene.getKilledMonsterCount();
        if (scene.getId() == 33899) assertEquals(3, monsters().size());
        for (int round = 0; round < 20 && !monsters().isEmpty(); round++) {
            for (var monster : monsters()) {
                float hp = monster.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP);
                assertTrue(Float.isFinite(hp) && hp > 0, "unusable HP for monster " + monster.getEntityTypeId());
                monster.damage(hp * .1f, 0, ElementType.None);
                assertTrue(monster.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP) < hp,
                        "spawned monster refused actual damage: " + monster.getEntityTypeId());
                DAMAGED_MONSTERS.add(monster.getEntityTypeId());
                monster.damage(hp * 2f, 0, ElementType.None);
                assertFalse(scene.getEntities().containsKey(monster.getId()), "real death did not remove the monster");
                scene.scripts.awaitEvents();
                if (scene.getId() == 33899) {
                    int remaining = 24 - (scene.getKilledMonsterCount() - killedAtStart);
                    assertEquals(Math.min(3, remaining), monsters().size(),
                            "11-1 replenishes each vacancy immediately, rather than waiting for three deaths");
                }
            }
        }
        assertTrue(monsters().isEmpty(), "wave progression did not terminate");
    }

    private static final class BattlePlayer extends TestWorld.TestPlayer {
        final TeamManager team = new TeamManager(this);
        final TestTower tower = new TestTower(this);
        final BattlePassManager quietPass = new BattlePassManager(this) {
            @Override public void triggerMission(WatcherTriggerType type, int param, int progress) {}
        };
        final ActivityManager activities;
        BattlePlayer() throws Exception { activities = allocate(QuietActivities.class); }
        @Override public TeamManager getTeamManager() { return team; }
        @Override public TowerManager getTowerManager() { return tower; }
        @Override public BattlePassManager getBattlePassManager() { return quietPass; }
        @Override public ActivityManager getActivityManager() { return activities; }
        @Override public void save() {}
        @Override public void dropMessage(Object message) {}
    }

    private static class QuietActivities extends ActivityManager {
        QuietActivities() { super(null); }
        @Override public void triggerWatcher(WatcherTriggerType type, String... params) {}
    }

    private static final class TestTower extends TowerManager {
        int floor, level, requestedTeam = -1;
        TestTower(Player player) { super(player); }
        @Override public int getCurrentFloorNumber() { return floor; }
        @Override public int getCurrentMonsterLevel() { return level; }
        @Override public void onBegin() {}
        @Override public void mirrorTeamSetUp(int team) {
            requestedTeam = team;
            getPlayer().getScene().setChallenge(null);
        }
    }

    private static final class BattleScene extends Scene {
        int authoredScene;
        boolean initializing;
        BattleScripts scripts;
        int spawned;
        final List<DungeonEndReason> settlements = new ArrayList<>();
        BattleScene(TestWorld world, int id) {
            super(world, TestWorld.sceneData(3, SceneType.SCENE_DUNGEON));
            authoredScene = id;
            initializing = true;
            scripts = new BattleScripts(this);
            initializing = false;
        }
        @Override public int getId() { return authoredScene == 0 || initializing ? 3 : authoredScene; }
        @Override public SceneScriptManager getScriptManager() { return scripts == null ? super.getScriptManager() : scripts; }
        @Override public void broadcastPacket(BasePacket packet) {}
        @Override public void saveGroups() {}
        @Override public void addEntities(Collection<? extends GameEntity> entities, VisionType type) {
            for (var entity : entities) getEntities().put(entity.getId(), entity);
            for (var entity : entities) {
                entity.onCreate();
                if (entity instanceof EntityMonster) { spawned++; entity.onTick(getSceneTimeSeconds()); }
            }
        }
        @Override public void addEntities(Collection<? extends GameEntity> entities) {
            addEntities(entities, VisionType.VisionType_VISION_BORN);
        }
    }

    private static final class BattleScripts extends SceneScriptManager {
        final Map<Integer, SceneGroup> groups = new LinkedHashMap<>();
        final Map<Integer, SceneGroupInstance> instances = new LinkedHashMap<>();
        final List<Future<?>> events = new CopyOnWriteArrayList<>();
        BattleScripts(Scene scene) { super(scene); }
        void install(SceneGroup group, Player owner) {
            assertNotNull(group.suites, "actual authored Lua did not load: " + group.id);
            groups.put(group.id, group);
            var instance = new SceneGroupInstance(group, owner);
            for (var variable : group.variables) instance.getCachedVariables().put(variable.name, variable.value);
            instances.put(group.id, instance);
            getCachedGroupInstances().put(group.id, instance);
            getScene().getLoadedGroups().add(group);
        }
        @Override public boolean isInit() { return true; }
        @Override public SceneGroup getGroupById(int id) { return groups.get(id); }
        @Override public SceneGroupInstance getGroupInstanceById(int id) { return instances.get(id); }
        @Override public SceneGroupInstance getCachedGroupInstanceById(int id) { return instances.get(id); }
        @Override public Future<?> callEvent(ScriptArgs args) {
            var future = super.callEvent(args);
            events.add(future);
            return future;
        }
        void awaitEvents() throws Exception {
            for (int i = 0; i < events.size(); i++) events.get(i).get(10, TimeUnit.SECONDS);
        }
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        var field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((Unsafe)field.get(null)).allocateInstance(type));
    }

    private static void set(Object object, Class<?> owner, String name, Object value) throws Exception {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }
}
