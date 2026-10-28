package emu.grasscutter.scripts;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.config.Configuration;
import emu.grasscutter.data.excels.monster.MonsterData;
import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.player.TeamManager;
import emu.grasscutter.game.props.SceneType;
import emu.grasscutter.game.world.*;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.PlayerEyePointStateNotifyOuterClass.PlayerEyePointStateNotify;
import emu.grasscutter.net.proto.BeginCameraSceneLookNotifyOuterClass.BeginCameraSceneLookNotify;
import emu.grasscutter.net.proto.CutSceneBeginNotifyOuterClass.CutSceneBeginNotify;
import emu.grasscutter.net.proto.VisionTypeOuterClass.VisionType;
import emu.grasscutter.scripts.constants.ScriptRegionShape;
import emu.grasscutter.scripts.data.*;
import emu.grasscutter.scripts.service.ScriptMonsterTideService;
import emu.grasscutter.utils.JsonUtils;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import org.junit.jupiter.api.*;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.JsePlatform;

final class ForestScriptActionsTest {
    private boolean scripts;
    private boolean disabledCutscenes;
    private TestScene scene;
    private EyePlayer owner, guest;
    private ScriptLib lib;
    private Globals lua;

    private static final class EyePlayer extends TestWorld.TestPlayer {
        final List<BasePacket> packets = new ArrayList<>();
        final TeamManager team = new TeamManager(this);
        @Override public TeamManager getTeamManager() { return team; }
        @Override public void sendPacket(BasePacket packet) { packets.add(packet); }
    }

    private static final class TestMonster extends EntityMonster {
        TestMonster(Scene scene, int group, int config) {
            super(scene, JsonUtils.decode("{id:1}", MonsterData.class), new Position(), new Position(), 1);
            setGroupId(group);
            setConfigId(config);
        }
        @Override public void recalcStats() {}
        @Override public void initAbilities() {}
        @Override public int getMonsterWeaponId() { return 0; }
    }

    private static final class TestScene extends TestWorld.TestScene {
        TestScripts scripts;
        final List<Integer> removed = new ArrayList<>();
        VisionType removalType;
        TestScene(TestWorld world) { super(world, 3, SceneType.SCENE_ROOM); }
        @Override public SceneScriptManager getScriptManager() {
            if (scripts == null) scripts = new TestScripts(this);
            return scripts;
        }
        @Override public synchronized void addEntity(GameEntity entity) { getEntities().put(entity.getId(), entity); }
        @Override public void removeEntities(List<GameEntity> entities, VisionType type) {
            removalType = type;
            for (var entity : entities) {
                removed.add(entity.getId());
                getEntities().remove(entity.getId());
            }
        }
        @Override public void broadcastPacket(BasePacket packet) {
            for (var player : getPlayers()) player.sendPacket(packet);
        }
    }

    private static final class TestScripts extends SceneScriptManager {
        final List<ScriptArgs> events = new ArrayList<>();
        TestScripts(Scene scene) { super(scene); }
        @Override public EntityMonster createMonster(int group, int block, SceneMonster config) {
            var monster = new TestMonster(getScene(), group, config.config_id);
            getScriptMonsterSpawnService().onMonsterCreatedListener.forEach(listener -> listener.onNotify(monster));
            return monster;
        }
        @Override public Future<?> callEvent(ScriptArgs args) {
            events.add(args);
            return CompletableFuture.completedFuture(null);
        }
    }

    @BeforeEach
    void prepare() throws Exception {
        ScriptLoader.init();
        scripts = Grasscutter.getConfig().server.game.enableScriptInBigWorld;
        Grasscutter.getConfig().server.game.enableScriptInBigWorld = false;
        disabledCutscenes = Configuration.GAME_OPTIONS.disableCutscenes;
        Configuration.GAME_OPTIONS.disableCutscenes = false;
        owner = new EyePlayer(); owner.setUid(101);
        guest = new EyePlayer(); guest.setUid(102);
        var world = TestWorld.create(owner);
        scene = new TestScene(world);
        for (var player : List.of(owner, guest)) {
            player.setWorld(world); player.setScene(scene); player.setSceneId(3);
            player.setSceneLoadState(Player.SceneLoadState.LOADED);
            player.getTeamManager().setEntity(new EntityTeam(player));
            scene.getPlayers().add(player);
        }
        lib = ScriptLoader.getScriptLib();
        lib.setSceneScriptManager(scene.getScriptManager());
        lib.setCurrentCallParams(new ScriptArgs(133304042, 0).setUid(102));
        lua = JsePlatform.standardGlobals();
        lua.set("ScriptLib", ScriptLoader.getScriptLibLua());
        lua.set("context", ScriptLoader.getScriptLibLua());
    }

    @AfterEach
    void restore() {
        lib.removeSceneScriptManager(); lib.removeCurrentGroup(); lib.removeCurrentEntity();
        Grasscutter.getConfig().server.game.enableScriptInBigWorld = scripts;
        Configuration.GAME_OPTIONS.disableCutscenes = disabledCutscenes;
    }

    private static SceneGroup group(int id, int config) {
        var group = SceneGroup.of(id);
        var monster = new SceneMonster(); monster.config_id = config;
        group.monsters = Map.of(config, monster);
        return group;
    }

    private ScriptMonsterTideService tide(SceneGroup group, int index, int count) {
        scene.getScriptManager().startMonsterTideInGroup(Integer.toString(index), group,
                group.monsters.keySet().toArray(Integer[]::new), count, 1);
        return scene.getScriptManager().getMonsterTides(group.id).getLast();
    }

    private SceneGroup eyeGroup() {
        var group = SceneGroup.of(133304042).load(3);
        assertNotNull(group.regions.get(42008));
        scene.getScriptManager().registerRegion(new EntityRegion(scene, group.regions.get(42008)));
        lib.setCurrentGroup(group);
        guest.getPosition().set(group.regions.get(42008).pos).addX(1);
        return group;
    }

    @Test
    void nativeKillCallSelectsTheExactTideDespiteCurrentAndOverlappingConfigs() {
        var group = group(133303031, 31001);
        var first = tide(group, 98, 3);
        var second = tide(group, 99, 3);
        var foreign = tide(group(133303032, 31001), 98, 3);
        int selected = first.getLiveMonsters().getFirst().getId();
        lib.setCurrentGroup(group);
        assertEquals(0, lua.load("return ScriptLib.KillMonsterTide(context,133303031,98)").call().toint());
        assertEquals(List.of(selected), scene.removed);
        assertEquals(VisionType.VisionType_VISION_MISS, scene.removalType);
        assertEquals(0, scene.kills);
        assertTrue(scene.scripts.events.isEmpty());
        assertEquals(List.of(second), scene.scripts.getMonsterTides(group.id));
        assertEquals(foreign, scene.scripts.getScriptMonsterTideService());
        assertEquals(2, scene.getEntities().size());
        assertFalse(first.isPending());
        assertEquals(-1, lib.KillMonsterTide(group.id, 98));
    }

    @Test
    void anUnrelatedDeathCannotCountOrRespawnAnotherGroupsTide() {
        var first = tide(group(133303031, 31001), 98, 2);
        var second = tide(group(133303032, 31001), 98, 2);
        var dead = second.getLiveMonsters().getFirst();
        scene.getEntities().remove(dead.getId());
        scene.scripts.getScriptMonsterSpawnService().onMonsterDead(dead);
        assertEquals(0, first.snapshot().killCount);
        assertEquals(1, second.snapshot().killCount);
        assertEquals(1, scene.scripts.events.size());
        assertEquals(2, scene.getEntities().size());
        scene.scripts.getScriptMonsterSpawnService().onMonsterDead(dead);
        assertEquals(1, second.snapshot().killCount, "replayed death cannot advance the wave twice");
    }

    private static ScriptMonsterTideService.State saved(String source) {
        var state = new ScriptMonsterTideService.State();
        state.source = source; state.tideCount = 1; state.alive = 1; state.sceneLimit = 1;
        state.configIds = List.of(31001); state.orders = List.of(31001);
        return state;
    }

    @Test
    void oldSnapshotsResolveOnlyUniqueRealGroupMembersAndRefuseAmbiguousOwnership() {
        var group = group(133303031, 31001);
        var monster = new TestMonster(scene, group.id, 31001);
        scene.addEntity(monster);
        scene.scripts.restoreMonsterTide(group, saved("98"));
        lib.setCurrentGroup(group);
        assertEquals(0, lib.KillMonsterTide(0, 98));
        assertEquals(List.of(monster.getId()), scene.removed);
        var ambiguous = new TestMonster(scene, group.id, 31001);
        scene.addEntity(ambiguous);
        scene.scripts.restoreMonsterTide(group, saved("98"));
        scene.scripts.restoreMonsterTide(group, saved("99"));
        assertEquals(-1, lib.KillMonsterTide(group.id, 98));
        assertTrue(scene.getEntities().containsKey(ambiguous.getId()));
        assertEquals(2, scene.scripts.getMonsterTides(group.id).size());
    }

    @Test
    void forestEyeCallSendsTheVerifiedShapeToOnlyItsContextPlayer() throws Exception {
        var group = eyeGroup();
        var actual = guest.getPosition().clone();
        assertEquals(0, lua.load("return ScriptLib.SetPlayerEyePoint(context,42008,42008)").call().toint());
        assertTrue(owner.packets.isEmpty());
        assertEquals(1, guest.packets.size());
        var packet = PlayerEyePointStateNotify.parseFrom(guest.packets.getFirst().getData());
        assertTrue(packet.getIsUseEyePoint());
        assertTrue(packet.getIsFilterStreamPos());
        assertEquals(-1, packet.getFixLodLevel());
        assertEquals(42008, packet.getRegionConfigId());
        assertEquals(1, packet.getRegionShape());
        assertEquals(70f, packet.getSphereRadius());
        assertEquals(955, PlayerEyePointStateNotify.SPHERE_RADIUS_FIELD_NUMBER);
        assertTrue(group.regions.get(42008).pos.equal3d(scene.getScriptEyePoints().viewPosition(guest)));
        assertTrue(actual.equal3d(guest.getPosition()), "a streaming eye never teleports the player");
        assertSame(owner.getPosition(), scene.getScriptEyePoints().viewPosition(owner));
        assertEquals(0, lib.SetPlayerEyePoint(42008,42008));
        assertEquals(1, guest.packets.size());
    }

    @Test
    void clearAndLeavingTheRelatedRegionRestoreRealViewAndDoNotAffectTheOwner() throws Exception {
        var group = eyeGroup();
        assertEquals(0, lib.SetPlayerEyePoint(42008,42008));
        assertEquals(-1, lib.ClearPlayerEyePoint(999));
        assertEquals(1, guest.packets.size());
        assertEquals(0, lib.ClearPlayerEyePoint(42008));
        assertFalse(PlayerEyePointStateNotify.parseFrom(guest.packets.getLast().getData()).getIsUseEyePoint());
        assertSame(guest.getPosition(), scene.getScriptEyePoints().viewPosition(guest));
        assertEquals(0, lib.SetPlayerEyePoint(42008,42008));
        guest.getPosition().set(group.regions.get(42008).pos).addX(71);
        assertSame(guest.getPosition(), scene.getScriptEyePoints().viewPosition(guest));
        assertFalse(PlayerEyePointStateNotify.parseFrom(guest.packets.getLast().getData()).getIsUseEyePoint());
        assertTrue(owner.packets.isEmpty());
    }

    @Test
    void missingRegionAndDeregisteredOrDepartedPlayersCannotLeaveAStaleEye() {
        var group = eyeGroup();
        assertEquals(-1, lib.SetPlayerEyePoint(999,42008));
        assertEquals(-1, lib.SetPlayerEyePoint(42008,999));
        assertTrue(guest.packets.isEmpty());
        assertEquals(0, lib.SetPlayerEyePoint(42008,42008));
        scene.scripts.deregisterRegion(group.regions.get(42008));
        assertSame(guest.getPosition(), scene.getScriptEyePoints().viewPosition(guest));
        scene.scripts.registerRegion(new EntityRegion(scene, group.regions.get(42008)));
        assertEquals(0, lib.SetPlayerEyePoint(42008,42008));
        scene.removePlayer(guest);
        guest.setScene(scene);
        assertSame(guest.getPosition(), scene.getScriptEyePoints().viewPosition(guest));
    }

    @Test
    void verifiedCubicShapeStreamAndLodUseRealFieldsAndUnknownShapesFail() throws Exception {
        var group = eyeGroup();
        var region = group.regions.get(42008);
        region.shape = ScriptRegionShape.CUBIC;
        region.size = new Position(20,30,40);
        assertEquals(0, lib.SetPlayerEyePointStream(42008,42008,false));
        var packet = PlayerEyePointStateNotify.parseFrom(guest.packets.getLast().getData());
        assertFalse(packet.getIsFilterStreamPos());
        assertEquals(20, packet.getCubicSize().getX());
        assertEquals(328, PlayerEyePointStateNotify.CUBIC_SIZE_FIELD_NUMBER);
        assertEquals(0, lib.ClearPlayerEyePoint(42008));
        assertEquals(0, lib.SetPlayerEyePointLOD(42008,42008,3));
        assertEquals(3, PlayerEyePointStateNotify.parseFrom(guest.packets.getLast().getData()).getFixLodLevel());
        assertEquals(0, lib.ClearPlayerEyePoint(42008));
        region.shape = ScriptRegionShape.CYLINDER;
        assertEquals(-1, lib.SetPlayerEyePoint(42008,42008));
    }

    @Test
    void newTideSnapshotsKeepConfigMembershipAcrossRoundTripWithoutPersistingEntityIds() {
        var group = group(133303031, 31001);
        var tide = tide(group, 98, 2);
        int entity = tide.getLiveMonsters().getFirst().getId();
        var saved = JsonUtils.decode(JsonUtils.encode(tide.snapshot()), ScriptMonsterTideService.State.class);
        assertEquals(List.of(31001), saved.aliveConfigIds);
        assertTrue(scene.scripts.unloadMonsterTide(group.id, "98"));
        scene.scripts.restoreMonsterTide(group, saved);
        assertEquals(0, emu.grasscutter.scripts.service.ScriptMonsterTideActions.kill(scene.scripts, group.id, 98));
        assertEquals(List.of(entity), scene.removed);
        assertTrue(scene.scripts.getMonsterTides(group.id).isEmpty());
    }

    @Test
    void nativeCameraFlagsVectorsAndExtendedFieldsReachOnlyTheContextPlayer() throws Exception {
        lib.setCurrentGroup(SceneGroup.of(133301038));
        assertEquals(0, lua.load("""
                return ScriptLib.BeginCameraSceneLook(context, {
                  look_pos={x=1,y=2,z=3},follow_pos={x=4,y=5,z=6},duration=1.5,
                  is_force=true,is_broadcast=false,is_allow_input=true,is_recover_keep_current=false,
                  is_set_follow_pos=true,is_force_walk=true,is_change_play_mode=true,is_set_screen_XY=true,
                  screen_x=0.25,screen_y=0.75,other_params={'first','second'},keep_rot_type=1,
                  custom_radius=4,disable_protect=1,blend_type=2,blend_duration=0.5,is_abs_follow_pos=true })
                """).call().toint());
        assertTrue(owner.packets.isEmpty());
        var data = BeginCameraSceneLookNotify.parseFrom(guest.packets.getLast().getData());
        assertEquals(1.5f, data.getDuration());
        assertEquals(1, data.getLookPos().getX());
        assertEquals(6, data.getFollowPos().getZ());
        assertTrue(data.getIsForce()); assertTrue(data.getIsAllowInput());
        assertFalse(data.getIsRecoverKeepCurrent());
        assertTrue(data.getIsSetFollowPos()); assertTrue(data.getIsForceWalk());
        assertTrue(data.getIsChangePlayMode()); assertTrue(data.getIsSetScreenXy());
        assertEquals(0.25f,data.getScreenX()); assertEquals(0.75f,data.getScreenY());
        assertEquals(List.of("first","second"), data.getOtherParamsList());
        assertEquals(1,data.getKeepRotType()); assertEquals(4,data.getCustomRadius());
        assertEquals(1,data.getDisableProtect()); assertEquals(2,data.getBlendType());
        assertEquals(0.5f,data.getBlendDuration()); assertTrue(data.getIsAbsFollowPos());
        assertEquals(1095, BeginCameraSceneLookNotify.IS_ABS_FOLLOW_POS_FIELD_NUMBER);
        assertEquals(1947, BeginCameraSceneLookNotify.ENTITY_ID_FIELD_NUMBER);
    }

    @Test
    void theActualForestCameraActionWaitsItsNativeDelayAndRejectsAnotherDelayedLook() throws Exception {
        var group = SceneGroup.of(133301053).load(3);
        lib.setCurrentGroup(group);
        var action = (LuaValue) group.getBindings().get("action_EVENT_GADGET_STATE_CHANGE_53008");
        assertEquals(0, action.call(ScriptLoader.getScriptLibLua(), new LuaTable()).toint());
        assertTrue(guest.packets.isEmpty());
        var values = lua.load("return {look_pos={x=1,y=2,z=3},duration=1,is_force=true,is_broadcast=false,delay=1}").call().checktable();
        assertEquals(-1, lib.BeginCameraSceneLook(values));
        scene.getScheduler().getTask(0).run();
        var data = BeginCameraSceneLookNotify.parseFrom(guest.packets.getLast().getData());
        assertEquals(-315.838f,data.getLookPos().getX());
        assertEquals(2,data.getDuration());
        assertTrue(owner.packets.isEmpty());
    }

    @Test
    void cameraBroadcastAndDestructionKeepTheirNativeScopeAndMalformedPointsFail() {
        lib.setCurrentGroup(SceneGroup.of(133301038));
        var broadcast = lua.load("return {look_pos={x=1,y=2,z=3},duration=1,is_force=true,is_broadcast=true}").call().checktable();
        assertEquals(0,lib.BeginCameraSceneLook(broadcast));
        assertEquals(1,owner.packets.size()); assertEquals(1,guest.packets.size());
        for (String bad : List.of("{}", "{look_pos={x=1,y=2},duration=1,is_force=true,is_broadcast=false}",
                "{look_configid=999,duration=1,is_force=true,is_broadcast=false}"))
            assertEquals(-1,lib.BeginCameraSceneLook(lua.load("return "+bad).call().checktable()));
        assertEquals(1,guest.packets.size());
        broadcast.set("delay",1);
        assertEquals(0,lib.BeginCameraSceneLook(broadcast));
        var stale = scene.getScheduler().getTask(0);
        scene.scripts.onDestroy();
        assertNull(scene.getScheduler().getTask(0));
        stale.run();
        assertEquals(1,guest.packets.size());
        assertEquals(-1,lib.BeginCameraSceneLook(broadcast));
    }

    @Test
    void parameterizedCutscenePreservesDoubleRowsAndZeroWaitDoesNotCreateWaitingState() throws Exception {
        lib.setCurrentGroup(SceneGroup.of(133301038));
        assertEquals(0,lua.load("return ScriptLib.PlayCutSceneWithParam(context,9001,0,{{1,2,3,4,5,6,7,8},{0.25,0.5}})").call().toint());
        assertEquals(1,owner.packets.size()); assertEquals(1,guest.packets.size());
        var data = CutSceneBeginNotify.parseFrom(guest.packets.getLast().getData());
        assertEquals(9001,data.getCutsceneId()); assertFalse(data.getIsWaitOthers());
        assertEquals(List.of(1d,2d,3d,4d,5d,6d,7d,8d),data.getExtraParamList(0).getDetailParamListList());
        assertEquals(List.of(0.25d,0.5d),data.getExtraParamList(1).getDetailParamListList());
        assertFalse(scene.scripts.hasPendingCutscene());
        assertTrue(scene.scripts.events.isEmpty());
        assertEquals(-1,lib.PlayCutSceneWithParam(9002,0,new LuaTable()));
        assertEquals(1,guest.packets.size());
    }

    @Test
    void waitingCutsceneCompletesByActualAckOrItsNativeTimeoutAndOldTimerCannotFinishANewRun() {
        lib.setCurrentGroup(SceneGroup.of(133301038));
        var parameters = lua.load("return {{1,2,3,4,5,6,7,8}}").call().checktable();
        assertEquals(0,lib.PlayCutSceneWithParam(9001,3,parameters));
        assertTrue(scene.scripts.hasPendingCutscene(9001));
        assertEquals(-1,lib.PlayCutSceneWithParam(9002,3,parameters));
        var oldTimer = scene.getScheduler().getTask(0);
        scene.scripts.finishCutscene(9001);
        assertNull(scene.getScheduler().getTask(0));
        assertEquals(1,scene.scripts.events.size());
        assertEquals(0,lib.PlayCutSceneWithParam(9001,3,parameters));
        oldTimer.run();
        assertTrue(scene.scripts.hasPendingCutscene(9001));
        assertEquals(1,scene.scripts.events.size());
        scene.getScheduler().getTask(1).run();
        assertFalse(scene.scripts.hasPendingCutscene(9001));
        assertEquals(2,scene.scripts.events.size());
    }

    @Test
    void maranaZeroWaitCutsceneOnlyNotifiesAndDoesNotInventAnEndEvenWhenDisabled() throws Exception {
        lib.setCurrentGroup(SceneGroup.of(133301038).load(3));
        // Boss_Marana.lua uses BattleCfg.BossAppearCutScene.id=89 with wait_seconds=0.
        assertEquals(0,lua.load("return ScriptLib.PlayCutScene(context,89,0)").call().toint());
        var data = CutSceneBeginNotify.parseFrom(guest.packets.getLast().getData());
        assertEquals(89,data.getCutsceneId());
        assertFalse(data.getIsWaitOthers());
        assertEquals(0,data.getExtraParamListCount());
        assertFalse(scene.scripts.hasPendingCutscene());
        scene.scripts.finishCutscene(89);
        assertTrue(scene.scripts.events.isEmpty());
        Configuration.GAME_OPTIONS.disableCutscenes = true;
        assertEquals(0,lib.PlayCutScene(90,0));
        assertEquals(1,guest.packets.size());
        assertTrue(scene.scripts.events.isEmpty());
    }

    @Test
    void theActualWaitingCutsceneActionUsesItsSixtySecondTimeoutAndConfiguredSkip() throws Exception {
        var group = SceneGroup.of(220018001).load(20018);
        lib.setCurrentGroup(group);
        var action = (LuaValue) group.getBindings().get("action_EVENT_ENTER_REGION_2");
        assertEquals(0,action.call(ScriptLoader.getScriptLibLua(),new LuaTable()).toint());
        var data = CutSceneBeginNotify.parseFrom(guest.packets.getLast().getData());
        assertEquals(200201,data.getCutsceneId());
        assertTrue(data.getIsWaitOthers());
        assertTrue(scene.scripts.hasPendingCutscene(200201));
        var timeout = scene.getScheduler().getTask(0);
        int ticks = (int) Math.ceil(60000d / Math.max(1,Configuration.GAME_INFO.tickRateMs));
        for (int i = 1; i < ticks; i++) assertFalse(timeout.shouldRun());
        assertTrue(scene.scripts.events.isEmpty());
        assertTrue(timeout.shouldRun());
        timeout.run();
        assertFalse(scene.scripts.hasPendingCutscene(200201));
        assertEquals(1,scene.scripts.events.size());
        int packets = guest.packets.size();
        Configuration.GAME_OPTIONS.disableCutscenes = true;
        assertEquals(0,action.call(ScriptLoader.getScriptLibLua(),new LuaTable()).toint());
        assertFalse(scene.scripts.hasPendingCutscene());
        assertEquals(2,scene.scripts.events.size());
        assertEquals(packets,guest.packets.size());
    }
}
