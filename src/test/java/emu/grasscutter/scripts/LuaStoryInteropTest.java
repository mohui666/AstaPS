package emu.grasscutter.scripts;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.GadgetData;
import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.props.SceneType;
import emu.grasscutter.game.world.*;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.scripts.data.*;
import emu.grasscutter.utils.JsonUtils;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.script.SimpleBindings;
import org.junit.jupiter.api.*;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.jse.JsePlatform;

final class LuaStoryInteropTest {
    private boolean scripts;
    private TestWorld.TestPlayer owner;
    private TestWorld.TestPlayer guest;
    private TestWorld.TestScene scene;
    private ScriptLib lib;
    private Globals lua;

    @BeforeEach
    void setUp() throws Exception {
        ScriptLoader.init();
        scripts = Grasscutter.getConfig().server.game.enableScriptInBigWorld;
        Grasscutter.getConfig().server.game.enableScriptInBigWorld = false;
        owner = new LuaPlayer();
        owner.setUid(101);
        guest = new LuaPlayer();
        guest.setUid(102);
        var world = TestWorld.create(owner);
        scene = new TestWorld.TestScene(world, 3, SceneType.SCENE_WORLD) {
            @Override public void broadcastPacket(BasePacket packet) {}
            @Override public synchronized void addEntity(GameEntity entity) {
                getEntities().put(entity.getId(), entity);
            }
        };
        for (var player : new TestWorld.TestPlayer[] {owner, guest}) {
            player.setWorld(world);
            player.setScene(scene);
            player.setSceneId(3);
            player.setSceneLoadState(emu.grasscutter.game.player.Player.SceneLoadState.LOADED);
            scene.getPlayers().add(player);
            player.getTeamManager().setEntity(new EntityTeam(player));
        }
        lib = ScriptLoader.getScriptLib();
        lib.setSceneScriptManager(scene.getScriptManager());
        lib.setCurrentGroup(SceneGroup.of(1));
        lib.setCurrentCallParams(new ScriptArgs(1, 0).setUid(guest.getUid())
                .setSourceEntityId(123).setTargetEntityId(456));
        lua = JsePlatform.standardGlobals();
        lua.set("ScriptLib", ScriptLoader.getScriptLibLua());
        lua.set("context", ScriptLoader.getScriptLibLua());
    }

    private static final class LuaPlayer extends TestWorld.TestPlayer {
        private final emu.grasscutter.game.player.TeamManager team =
                new emu.grasscutter.game.player.TeamManager(this);
        @Override public emu.grasscutter.game.player.TeamManager getTeamManager() { return team; }
    }

    @AfterEach
    void tearDown() {
        lib.removeSceneScriptManager();
        lib.removeCurrentGroup();
        lib.removeCurrentEntity();
        Grasscutter.getConfig().server.game.enableScriptInBigWorld = scripts;
    }

    @Test
    void nativeContextFieldsAndJavaMethodReceiversWorkTogetherAndStayThreadLocal() {
        assertEquals(102, lua.load("return context.uid").call().toint());
        assertEquals(101, lua.load("return context.owner_uid").call().toint());
        assertEquals(123, lua.load("return context.source_entity_id").call().toint());
        assertEquals(456, lua.load("return context.target_entity_id").call().toint());
        assertEquals(101, lua.load("return ScriptLib.GetSceneOwnerUid(context)").call().toint());
        assertEquals(0, CompletableFuture.supplyAsync(() ->
                ScriptLoader.getScriptLibLua().get("uid").toint()).join());
        assertEquals(102, ScriptLoader.getScriptLibLua().get("uid").toint());
    }

    @Test
    void forestLuaCanIteratePlayersAndWriteTheSameTeamValuesTheServerReads() {
        assertEquals(203, lua.load("local sum=0; local u=ScriptLib.GetSceneUidList(context); "
                + "assert(#u==2); for _,uid in ipairs(u) do sum=sum+uid end; return sum").call().toint());
        assertEquals(0, lua.load("return ScriptLib.SetTeamEntityGlobalFloatValue(context, "
                + "ScriptLib.GetSceneUidList(context), 'SGV_Forest', 3.5)").call().toint());
        assertEquals(0, lib.AddTeamServerGlobalValue(102, "SGV_Forest", 1.25f));
        assertEquals(3.5f, lib.GetTeamAbilityFloatValue(101, "SGV_Forest"));
        assertEquals(4.75f, lib.GetTeamServerGlobalValue(102, "SGV_Forest"));
        assertEquals(-1, lib.SetTeamServerGlobalValue(999, "SGV_Forest", 8));
        assertEquals(0, lib.SetEntityServerGlobalValueByEntityId(
                owner.getTeamManager().getEntity().getId(), "SGV_Forest", 2.25f));
        assertEquals(2.25f, lib.GetTeamServerGlobalValue(101, "SGV_Forest"));
    }

    @Test
    void nestedCrossGroupCallsKeepTheirOwnCountersAndRestoreTheCaller() {
        var second = SceneGroup.of(2);
        second.setBindings(new SimpleBindings());
        second.getBindings().put("Outer", lua.load("return function(ctx,args) "
                + "assert(ctx.uid==102); ScriptLib.SetGroupTempValue(ctx,'visited',args[1],{}); "
                + "return ScriptLib.ExecuteActiveGroupLua(ctx,3,'Inner',{args[1]+1}) end").call());
        var third = SceneGroup.of(3);
        third.setBindings(new SimpleBindings());
        third.getBindings().put("Inner", lua.load("return function(ctx,args) "
                + "assert(ctx.uid==102); return ScriptLib.SetGroupTempValue(ctx,'visited',args[1],{}) end").call());
        var groups = Map.of(2, new SceneGroupInstance(second, owner),
                3, new SceneGroupInstance(third, owner));
        var manager = new SceneScriptManager(scene) {
            @Override public SceneGroupInstance getGroupInstanceById(int id) { return groups.get(id); }
        };
        lib.setSceneScriptManager(manager);
        manager.setGroupTempValue(1, "visited", 100);
        var args = new LuaTable();
        args.set(1, LuaValue.valueOf(7));
        assertEquals(0, lib.ExecuteActiveGroupLua(2, "Outer", args));
        assertEquals(7, manager.getGroupTempValue(2, "visited"));
        assertEquals(8, manager.getGroupTempValue(3, "visited"));
        assertEquals(100, lib.GetGroupTempValue("visited", new LuaTable()));
        assertEquals(123, ScriptLoader.getScriptLibLua().get("source_entity_id").toint());
        assertEquals(-1, lib.ExecuteActiveGroupLua(4, "Missing", args));
        assertEquals(-1, lib.ExecuteActiveGroupLua(2, "Missing", args));
    }

    @Test
    void maranaCreatesItsAranaraAtTheRequestedPointWithoutMovingTheSharedTemplate() {
        int gadgetId = 99000701;
        var previous = GameData.getGadgetDataMap().put(gadgetId,
                JsonUtils.decode("{id:99000701,jsonName:'Test'}", GadgetData.class));
        try {
            var group = SceneGroup.of(1);
            var config = new SceneGadget();
            config.config_id = 17001;
            config.gadget_id = gadgetId;
            config.group = group;
            config.pos = new Position(1, 2, 3);
            config.rot = new Position(0, 10, 0);
            config.logic_state = java.util.List.of("SGV_DeathZoneState");
            group.gadgets = Map.of(config.config_id, config);
            var logic = new SceneVar();
            logic.name = "SGV_DeathZoneState";
            logic.variable_type = 1;
            group.variables = java.util.List.of(logic);
            var values = new java.util.concurrent.ConcurrentHashMap<>(Map.of("SGV_DeathZoneState", 1));
            lib.setSceneScriptManager(new SceneScriptManager(scene) {
                @Override public Map<String,Integer> getVariables(int id) { return values; }
            });
            lib.setCurrentGroup(group);
            assertEquals(0, lua.load("return ScriptLib.CreateGadgetByConfigIdByPos(context,17001,"
                    + "{x=20,y=30,z=40},{x=0,y=90,z=0})").call().toint());
            var gadget = (EntityGadget) scene.getEntityByConfigId(17001, 1);
            assertNotNull(gadget);
            assertEquals(20f, gadget.getPosition().getX());
            assertEquals(90f, gadget.getBornRot().getY());
            assertEquals(1f, config.pos.getX());
            assertEquals(10f, config.rot.getY());
            assertEquals(1f, gadget.getGlobalAbilityValues().get("SGV_DeathZoneState"));
            var spawnValues = gadget.toProto().getEntityAuthorityInfo().getAbilityInfo().getSgvDynamicValueMapList();
            assertEquals(1f, spawnValues.stream().filter(value -> value.getKey().getStr().equals("SGV_DeathZoneState"))
                    .findFirst().orElseThrow().getFloatValue());
            assertEquals(0, lib.SetGroupLogicStateValue("SGV_DeathZoneState",0));
            assertEquals(0f, gadget.getGlobalAbilityValues().get("SGV_DeathZoneState"));
            assertEquals(17001, lib.GetGadgetConfigId((LuaTable) lua.load(
                    "return {gadget_eid=" + gadget.getId() + "}").call()));
        } finally {
            if (previous == null) GameData.getGadgetDataMap().remove(gadgetId);
            else GameData.getGadgetDataMap().put(gadgetId, previous);
        }
    }
}
