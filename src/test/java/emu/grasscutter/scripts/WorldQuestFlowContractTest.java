package emu.grasscutter.scripts;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.GadgetData;
import emu.grasscutter.data.excels.monster.MonsterData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.SceneType;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.QuestState;
import emu.grasscutter.game.quest.exec.*;
import emu.grasscutter.game.world.*;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.packet.PacketOpcodes;
import emu.grasscutter.net.proto.SceneEntityDisappearNotifyOuterClass.SceneEntityDisappearNotify;
import emu.grasscutter.net.proto.VisionTypeOuterClass.VisionType;
import emu.grasscutter.scripts.constants.EventType;
import emu.grasscutter.scripts.data.*;
import emu.grasscutter.utils.JsonUtils;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import javax.script.SimpleBindings;
import org.junit.jupiter.api.*;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.jse.JsePlatform;

final class WorldQuestFlowContractTest {
    private boolean scripts;

    @BeforeEach
    void prepare() throws Exception {
        ScriptLoader.init();
        scripts = Grasscutter.getConfig().server.game.enableScriptInBigWorld;
        Grasscutter.getConfig().server.game.enableScriptInBigWorld = false;
    }

    @AfterEach
    void restore() {
        var lib = ScriptLoader.getScriptLib();
        lib.removeSceneScriptManager();
        lib.removeCurrentGroup();
        lib.removeCurrentEntity();
        Grasscutter.getConfig().server.game.enableScriptInBigWorld = scripts;
    }

    private static final class FlowScene extends TestWorld.TestScene {
        FlowScripts scripts;
        final List<BasePacket> packets = new ArrayList<>();
        FlowScene(World world, int id) { super(world,id,SceneType.SCENE_WORLD); }
        @Override public SceneScriptManager getScriptManager() {
            if (scripts == null) scripts = new FlowScripts(this);
            return scripts;
        }
        @Override public void broadcastPacket(BasePacket packet) { packets.add(packet); }
    }

    private static final class FlowScripts extends SceneScriptManager {
        SceneGroupInstance instance;
        final List<ScriptArgs> queuedEvents = new ArrayList<>();
        FlowScripts(Scene scene) { super(scene); }
        @Override public boolean isInit() { return true; }
        @Override public SceneGroupInstance getGroupInstanceById(int id) {
            return instance != null && instance.getGroupId() == id ? instance : null;
        }
        @Override public SceneGroup getGroupById(int id) {
            var found = getGroupInstanceById(id);
            return found == null ? null : found.getLuaGroup();
        }
        @Override public Map<String,Integer> getVariables(int id) {
            return getGroupInstanceById(id).getCachedVariables();
        }
        @Override public Future<?> callEvent(ScriptArgs args) {
            queuedEvents.add(args);
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class FlowQuest extends GameQuest {
        private final Player owner;
        private final List<QuestGroupSuite> saved = new ArrayList<>();
        private final GameMainQuest main = new GameMainQuest() {
            @Override public List<QuestGroupSuite> getQuestGroupSuites() { return saved; }
        };
        int saves;
        FlowQuest(Player owner) {
            this.owner = owner;
            setState(QuestState.QUEST_STATE_UNFINISHED);
        }
        @Override public Player getOwner() { return owner; }
        @Override public GameMainQuest getMainQuest() { return main; }
        @Override public void save() { saves++; }
    }

    private static SceneVar variable(String name, int value, boolean noRefresh) {
        var variable = new SceneVar();
        variable.name = name;
        variable.value = value;
        variable.no_refresh = noRefresh;
        return variable;
    }

    private static SceneGroup flowGroup(int id, LuaTable records) {
        var group = SceneGroup.of(id);
        group.init_config = new SceneInitConfig();
        group.init_config.io_type = 1;
        group.init_config.sub_flow_type = 1;
        group.init_config.suite = 1;
        group.gadgets = Map.of();
        group.monsters = Map.of();
        group.npcs = Map.of();
        group.regions = Map.of();
        group.variables = List.of(variable("phase",3,false),variable("sticky",5,true));
        var lua = JsePlatform.standardGlobals();
        lua.set("ScriptLib",ScriptLoader.getScriptLibLua());
        lua.set("records",records);
        var action = lua.load("""
                return function(ctx,evt)
                  table.insert(records,{evt.type,evt.param1,evt.param2,
                    ScriptLib.GetGroupVariableValue(ctx,'phase'),
                    ScriptLib.GetGroupVariableValue(ctx,'sticky')})
                  return 0
                end
                """).call();
        group.setBindings(new SimpleBindings(Map.of("record",action)));
        var triggers = new LinkedHashMap<String,SceneTrigger>();
        for (int event : List.of(EventType.EVENT_GROUP_WILL_REFRESH,EventType.EVENT_GROUP_REFRESH)) {
            var trigger = new SceneTrigger();
            trigger.setName("flow_"+event);
            trigger.setEvent(event);
            trigger.setSource("");
            trigger.setCondition("");
            trigger.setAction("record");
            trigger.setTrigger_count(0);
            trigger.currentGroup = group;
            triggers.put(trigger.getName(),trigger);
        }
        group.triggers = triggers;
        var suites = new ArrayList<SceneSuite>();
        for (int value : List.of(1,7)) {
            var disk = new SceneFlowSuite();
            var phase = new SceneFlowSuite.Variable();
            phase.name = "phase";
            phase.value = value;
            disk.variables = List.of(phase);
            disk.triggers = List.copyOf(triggers.keySet());
            var suite = disk.toSuite();
            suite.init(group);
            suites.add(suite);
        }
        group.suites = suites;
        return group;
    }

    private static SceneGroupInstance install(FlowScene scene, SceneGroup group) {
        var scripts = (FlowScripts)scene.getScriptManager();
        var instance = new SceneGroupInstance(group,scene.getWorld().getHost());
        scripts.instance = instance;
        instance.setActiveSuiteId(1);
        instance.getCachedVariables().putAll(Map.of("phase",9,"sticky",99,"orphan",123));
        scripts.registerTrigger(group.getSuiteByIndex(1).sceneTriggers);
        return instance;
    }

    private static List<Integer> row(LuaTable records, int index) {
        var row = records.get(index);
        return List.of(row.get(1).toint(),row.get(2).toint(),row.get(3).toint(),
                row.get(4).toint(),row.get(5).toint());
    }

    private static TestWorld world(Player owner) throws Exception {
        var world = TestWorld.create(owner);
        var field = World.class.getDeclaredField("scenes");
        field.setAccessible(true);
        field.set(world,new Int2ObjectOpenHashMap<Scene>());
        return world;
    }

    private static QuestData.QuestExecParam action71() {
        return JsonUtils.decode("{type:'QUEST_EXEC_REFRESH_WORLD_QUEST_FLOW_GROUP_SUITE'}",
                QuestData.QuestExecParam.class);
    }

    @Test
    void actualForestWorldQuestFlowTypeAndSceneComeFromTheAuthoredResources() {
        var worldQuest = SceneGroup.of(133303011).load(3);
        assertEquals(1,worldQuest.init_config.io_type);
        assertEquals(1,worldQuest.init_config.sub_flow_type);
        assertNotNull(worldQuest.getSuiteByIndex(4).flow);
        var ordinaryFlow = SceneGroup.of(133304040).load(3);
        assertEquals(1,ordinaryFlow.init_config.io_type);
        assertEquals(0,ordinaryFlow.init_config.sub_flow_type);
        assertEquals(3,SceneGroup.findSceneId(133303011));
        assertEquals(4,SceneGroup.findSceneId(144004005));
    }

    @Test
    void aMissingScriptLoadCannotCreateANativeGroupSceneIndexEntry() {
        SceneGroup.of(197654321).load(9);
        assertEquals(0,SceneGroup.findSceneId(197654321));
    }

    @Test
    void willRefreshRunsWithOldAndTargetStagesBeforeRefreshSeesTheNewValues() {
        var owner = new TestWorld.TestPlayer();
        var scene = new FlowScene(TestWorld.create(owner),3);
        var records = new LuaTable();
        var group = flowGroup(133303011,records);
        var instance = install(scene,group);
        assertEquals(0,SceneFlowSuites.refreshWorldQuest(scene.getScriptManager(),group.id,2));
        assertEquals(List.of(EventType.EVENT_GROUP_WILL_REFRESH,1,2,9,99),row(records,1));
        assertEquals(List.of(EventType.EVENT_GROUP_REFRESH,2,0,7,99),row(records,2));
        assertEquals(2,instance.getActiveSuiteId());
        assertFalse(instance.getCachedVariables().containsKey("orphan"));
        assertTrue(scene.scripts.queuedEvents.isEmpty(), "flow defaults only update values and entity logic state");
        assertEquals(1,scene.saves);
    }

    @Test
    void nativeQuestForceRefreshesTheCurrentStageWhileLuaGotoKeepsItsDifferentContract() {
        var owner = new TestWorld.TestPlayer();
        var scene = new FlowScene(TestWorld.create(owner),3);
        var records = new LuaTable();
        var group = flowGroup(133303011,records);
        var instance = install(scene,group);
        assertEquals(0,SceneFlowSuites.goTo(scene.getScriptManager(),group.id,2));
        assertEquals(0,records.length());
        assertTrue(scene.scripts.queuedEvents.isEmpty(), "native Lua GoTo emits no variable-change event for its defaults");
        instance.getCachedVariables().put("phase",42);
        assertEquals(-1,SceneFlowSuites.goTo(scene.getScriptManager(),group.id,2));
        assertEquals(42,instance.getCachedVariables().get("phase"));
        assertEquals(0,SceneFlowSuites.refreshWorldQuest(scene.getScriptManager(),group.id,2));
        assertEquals(List.of(EventType.EVENT_GROUP_WILL_REFRESH,2,2,42,99),row(records,1));
        assertEquals(List.of(EventType.EVENT_GROUP_REFRESH,2,0,7,99),row(records,2));
        assertTrue(scene.scripts.queuedEvents.isEmpty());
        assertEquals(2,scene.saves);
    }

    @Test
    void ordinaryFlowAndInvalidTargetsCannotBeMutatedAsWorldQuestFlow() {
        var owner = new TestWorld.TestPlayer();
        var scene = new FlowScene(TestWorld.create(owner),3);
        var records = new LuaTable();
        var group = flowGroup(133303011,records);
        var instance = install(scene,group);
        group.init_config.sub_flow_type = 0;
        assertEquals(-1,SceneFlowSuites.refreshWorldQuest(scene.getScriptManager(),group.id,2));
        group.init_config.sub_flow_type = 1;
        group.init_config.io_type = 0;
        assertEquals(-1,SceneFlowSuites.refreshWorldQuest(scene.getScriptManager(),group.id,2));
        group.init_config.io_type = 1;
        assertEquals(-1,SceneFlowSuites.refreshWorldQuest(scene.getScriptManager(),group.id,9));
        assertEquals(1,instance.getActiveSuiteId());
        assertEquals(Map.of("phase",9,"sticky",99,"orphan",123),instance.getCachedVariables());
        assertEquals(0,records.length());
        assertEquals(0,scene.saves);
        group.init_config.sub_flow_type = 0;
        assertEquals(0,SceneFlowSuites.goTo(scene.getScriptManager(),group.id,2));
    }

    @Test
    void nativeExecAndItsWorldFlowBranchUseTheAuthoredSceneRatherThanTheOwnersCurrentScene() throws Exception {
        var owner = new TestWorld.TestPlayer();
        var world = world(owner);
        var target = new FlowScene(world,3);
        var current = new FlowScene(world,9);
        world.getScenes().put(3,target);
        world.getScenes().put(9,current);
        owner.setScene(current);
        owner.setSceneId(9);
        var records = new LuaTable();
        var group = flowGroup(133303011,records);
        var instance = install(target,group);
        var quest = new FlowQuest(owner);
        assertTrue(new ExecRefreshWorldQuestFlowGroupSuite().execute(quest,action71(),"133303011,2"));
        assertEquals(2,instance.getActiveSuiteId());
        assertSame(current,owner.getScene());
        assertEquals(0,current.saves);
        assertEquals(1,target.saves);
        assertEquals(1,quest.saves);
        assertEquals(1,quest.saved.size());
        assertEquals(3,quest.saved.getFirst().getScene());
        assertEquals(group.id,quest.saved.getFirst().getGroup());
        assertEquals(2,quest.saved.getFirst().getSuite());
        instance.getCachedVariables().put("phase",42);
        assertTrue(new ExecRefreshGroupSuite().execute(quest,action71(),"3","133303011,2"));
        assertEquals(7,instance.getCachedVariables().get("phase"));
        assertEquals(4,records.length());
        assertEquals(2,target.saves);
        assertEquals(1,quest.saves,"the persisted suite is already current");
    }

    @Test
    void forceRefreshUsesTheNativeRefreshNoticeForItsDiscardedEntitiesAndKeepsOtherGroups() throws Exception {
        int gadgetId = 99000711;
        var previous = GameData.getGadgetDataMap().put(gadgetId,
                JsonUtils.decode("{id:99000711,jsonName:'Test'}",GadgetData.class));
        try {
            var owner = new TestWorld.TestPlayer();
            var scene = new FlowScene(TestWorld.create(owner),3);
            var group = flowGroup(133303011,new LuaTable());
            var keep = new SceneGadget();
            keep.group = group; keep.config_id = 1; keep.gadget_id = gadgetId;
            keep.pos = new Position(); keep.rot = new Position();
            var discard = new SceneGadget();
            discard.group = group; discard.config_id = 2; discard.gadget_id = gadgetId;
            discard.pos = new Position(); discard.rot = new Position();
            group.gadgets = Map.of(1,keep,2,discard);
            var target = group.getSuiteByIndex(2);
            var property = new SceneFlowSuite.Gadget();
            property.config_id = 1; property.state = 201;
            target.flow.gadgets = List.of(property);
            target.gadgets = List.of(1);
            target.init(group);
            install(scene,group);
            var kept = new EntityGadget(scene,gadgetId,new Position(),new Position());
            kept.setConfigId(1); kept.setGroupId(group.id); kept.setMetaGadget(keep);
            var obsolete = new EntityGadget(scene,gadgetId,new Position(),new Position());
            obsolete.setConfigId(2); obsolete.setGroupId(group.id); obsolete.setMetaGadget(discard);
            var npc = new EntityNPC(scene,1001,new Position(),new Position());
            npc.setConfigId(3); npc.setGroupId(group.id);
            var foreign = new EntityNPC(scene,1002,new Position(),new Position());
            foreign.setConfigId(3); foreign.setGroupId(group.id+1);
            var monster = new EntityMonster(scene,JsonUtils.decode("{id:1}",MonsterData.class),
                    new Position(),new Position(),1) {
                @Override public void recalcStats() {}
                @Override public void initAbilities() {}
                @Override public int getMonsterWeaponId() { return 0; }
            };
            monster.setConfigId(4); monster.setGroupId(group.id);
            for (var entity : List.of(kept,obsolete,npc,foreign,monster))
                scene.getEntities().put(entity.getId(),entity);
            assertEquals(0,SceneFlowSuites.refreshWorldQuest(scene.getScriptManager(),group.id,2));
            var notices = scene.packets.stream().filter(packet -> packet.getOpcode() == PacketOpcodes.SceneEntityDisappearNotify).toList();
            assertEquals(1,notices.size());
            var data = SceneEntityDisappearNotify.parseFrom(notices.getFirst().getData());
            assertEquals(VisionType.VisionType_VISION_REFRESH,data.getDisappearType());
            assertEquals(Set.of(obsolete.getId(),npc.getId(),monster.getId()),new HashSet<>(data.getEntityListList()));
            assertSame(kept,scene.getEntityByConfigId(1,group.id));
            assertEquals(201,kept.getState());
            assertSame(foreign,scene.getEntityByConfigId(3,group.id+1));
            assertEquals(2,scene.getEntities().size());
            assertEquals(0,scene.kills);
        } finally {
            if (previous == null) GameData.getGadgetDataMap().remove(gadgetId);
            else GameData.getGadgetDataMap().put(gadgetId,previous);
        }
    }
}
