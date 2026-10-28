package emu.grasscutter.scripts;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.routes.*;
import emu.grasscutter.data.excels.GadgetData;
import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.entity.gadget.platform.ConfigRoute;
import emu.grasscutter.game.props.SceneType;
import emu.grasscutter.game.world.*;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.scripts.data.*;
import emu.grasscutter.utils.*;
import java.util.*;
import org.junit.jupiter.api.*;

final class SceneFlowEntitiesTest {
    private boolean scripts;
    private final Map<Integer, GadgetData> previous = new HashMap<>();

    @BeforeEach
    void prepare() throws Exception {
        ScriptLoader.init();
        scripts = Grasscutter.getConfig().server.game.enableScriptInBigWorld;
        Grasscutter.getConfig().server.game.enableScriptInBigWorld = false;
    }

    @AfterEach
    void restore() {
        previous.forEach((id, data) -> {
            if (data == null) GameData.getGadgetDataMap().remove(id);
            else GameData.getGadgetDataMap().put(id, data);
        });
        Grasscutter.getConfig().server.game.enableScriptInBigWorld = scripts;
    }

    @Test
    void nativeFlowPropertiesAndNpcReferencesAreReallyDeserialized() {
        var group = SceneGroup.of(144004005).load(4);
        var first = group.getSuiteByIndex(2).flow.gadgets.stream()
                .filter(gadget -> gadget.platform_info != null).findFirst().orElseThrow();
        assertEquals(400400001, first.platform_info.route_id);
        assertEquals(6, first.platform_info.route_index);
        assertFalse(first.platform_info.is_started);
        var second = group.getSuiteByIndex(3).flow.gadgets.stream()
                .filter(gadget -> gadget.platform_info != null).findFirst().orElseThrow();
        assertEquals(400400015, second.platform_info.route_id);
        assertEquals(4, second.platform_info.route_index);
        var npcGroup = SceneGroup.of(133210071).load(3);
        assertEquals(List.of(71002), npcGroup.getSuiteByIndex(1).npcs);
        assertEquals(List.of(npcGroup.npcs.get(71002)), npcGroup.getSuiteByIndex(1).sceneNPCs);
        assertTrue(npcGroup.getSuiteByIndex(3).npcs.isEmpty());
    }

    @Test
    void newNativePlatformUsesTheFlowRouteIndexAndKeepsItsTemplateUnchanged() throws Exception {
        var owner = new TestWorld.TestPlayer();
        var scene = new FlowScene(TestWorld.create(owner), 4);
        var routes = JsonUtils.loadToClass(FileUtils.getResourcePath(
                "BinOutput/LevelDesign/Routes/14215719194489764739.json"), SceneRoutes.class);
        for (var route : routes.getRoutes()) scene.routes.put(route.getLocalId(), route);
        var group = SceneGroup.of(144004005).load(4);
        var suite = group.getSuiteByIndex(2);
        var flow = suite.flow.gadgets.stream().filter(gadget -> gadget.platform_info != null).findFirst().orElseThrow();
        var template = group.gadgets.get(flow.config_id);
        var originalPosition = template.pos.clone();
        int originalRoute = template.route_id;
        var instance = new SceneGroupInstance(group, owner);
        assertTrue(SceneFlowEntities.validateTarget(scene.getScriptManager(), instance, suite));
        var entity = gadget(scene, template);
        SceneFlowEntities.configureNewGadget(entity, suite);
        var route = (ConfigRoute) entity.getRouteConfig();
        assertEquals(400400001, route.getRouteId());
        assertEquals(6, route.getStartIndex());
        assertFalse(route.isStarted());
        assertEquals(0, entity.getPosition().computeDistance(scene.routes.get(400400001).getPoints()[6].getPos()), 0.00001);
        assertEquals(originalRoute, template.route_id);
        assertEquals(0, template.pos.computeDistance(originalPosition), 0.00001);
        SceneFlowEntities.onEntityCreated(entity);
        assertTrue(route.getScheduledIndexes().isEmpty());

        // The next native stage uses a different authored route; do not confuse a
        // partially populated test scene with missing installed route resources.
        var laterSuite = group.getSuiteByIndex(3);
        assertTrue(SceneFlowEntities.validateTarget(scene.getScriptManager(), instance, laterSuite));
        var laterFlow = laterSuite.flow.gadgets.stream()
                .filter(gadget -> gadget.platform_info != null).findFirst().orElseThrow();
        var later = gadget(scene, group.gadgets.get(laterFlow.config_id));
        SceneFlowEntities.configureNewGadget(later, laterSuite);
        assertEquals(400400015, ((ConfigRoute) later.getRouteConfig()).getRouteId());
        assertEquals(0, later.getPosition().computeDistance(
                scene.routes.get(400400015).getPoints()[4].getPos()), 0.00001);
    }

    @Test
    void forestFlowUsesTheInstalledSecondWaterDrainRoute() throws Exception {
        var owner = new TestWorld.TestPlayer();
        var scene = new FlowScene(TestWorld.create(owner), 3);
        var routes = JsonUtils.loadToClass(FileUtils.getResourcePath(
                "BinOutput/LevelDesign/Routes/16971644736907031750.json"), SceneRoutes.class);
        assertEquals(3, routes.getSceneId());
        for (var route : routes.getRoutes()) scene.routes.put(route.getLocalId(), route);
        var group = SceneGroup.of(133210074).load(3);
        var suite = group.getSuiteByIndex(2);
        assertTrue(SceneFlowEntities.validateTarget(scene.getScriptManager(),
                new SceneGroupInstance(group, owner), suite));
        var entity = gadget(scene, group.gadgets.get(74003));
        SceneFlowEntities.configureNewGadget(entity, suite);
        var route = (ConfigRoute) entity.getRouteConfig();
        assertEquals(321000071, route.getRouteId());
        var points = scene.routes.get(321000071).getPoints();
        assertEquals(2, points.length);
        assertTrue(points[1].isHasReachEvent());
        assertTrue(points[1].isReachStop());
        assertEquals(3, points[1].getTargetVelocity());
        assertEquals(0, entity.getPosition().computeDistance(points[0].getPos()), 0.00001);
        scene.getEntities().put(entity.getId(), entity);
        assertTrue(entity.startPlatform());
        scene.now = 1300;
        scene.getScheduler().runTasks();
        scene.now = 8000;
        scene.getScheduler().runTasks();
        assertEquals(0, entity.getPosition().computeDistance(points[1].getPos()), 0.00001);
        assertFalse(route.isStarted());
    }

    @Test
    void startedFlowPlatformArmsTheExistingServerMovementExactlyOnce() {
        var owner = new TestWorld.TestPlayer();
        var scene = new FlowScene(TestWorld.create(owner), 3);
        scene.routes.put(9001, JsonUtils.decode("{localId:9001,points:["
                + "{pos:{x:0,y:0,z:0},targetVelocity:1},"
                + "{pos:{x:10,y:0,z:0},targetVelocity:1},"
                + "{pos:{x:20,y:0,z:0},targetVelocity:1}]}", Route.class));
        var group = SceneGroup.of(133990001);
        var template = new SceneGadget();
        template.group = group;
        template.config_id = 1;
        template.gadget_id = 70350292;
        template.pos = new Position(100, 100, 100);
        template.rot = new Position();
        var flow = JsonUtils.decode("{gadgets:[{config_id:1,state:0,platform_info:"
                + "{move_type:1,route_id:9001,route_index:1,is_started:true}}]}", SceneFlowSuite.class);
        var suite = flow.toSuite();
        var entity = gadget(scene, template);
        SceneFlowEntities.configureNewGadget(entity, suite);
        scene.getEntities().put(entity.getId(), entity);
        var route = (ConfigRoute) entity.getRouteConfig();
        assertFalse(route.isStarted());
        SceneFlowEntities.onEntityCreated(entity);
        assertTrue(route.isStarted());
        var tasks = List.copyOf(route.getScheduledIndexes());
        assertFalse(tasks.isEmpty());
        SceneFlowEntities.onEntityCreated(entity);
        assertEquals(tasks, route.getScheduledIndexes());
        scene.now = 1300;
        scene.getScheduler().runTasks();
        scene.now = 11300;
        scene.getScheduler().runTasks();
        assertEquals(20, entity.getPosition().getX(), 0.0001);
        assertFalse(route.isStarted());
    }

    @Test
    void flowNpcCreationIsOwnedDeduplicatedAndRestoresRealEarlierStageIds() {
        var owner = new TestWorld.TestPlayer();
        owner.setUid(42);
        var scene = new FlowScene(TestWorld.create(owner), 3);
        var group = SceneGroup.of(133210071).load(3);
        group.block_id = 3210;
        var instance = new SceneGroupInstance(group, owner);
        scene.instance = instance;
        var first = SceneFlowEntities.newNpcs(scene.getScriptManager(), instance, group.getSuiteByIndex(1));
        assertEquals(1, first.size());
        var npc = first.getFirst();
        assertEquals(71002, npc.getConfigId());
        assertEquals(group.id, npc.getGroupId());
        assertEquals(3210, npc.getBlockId());
        scene.getEntities().put(npc.getId(), npc);
        assertTrue(SceneFlowEntities.newNpcs(scene.getScriptManager(), instance, group.getSuiteByIndex(3)).isEmpty());
        assertEquals(Set.of(71002), instance.getFlowNpcConfigs());

        var cold = JsonUtils.decode(JsonUtils.encode(instance), SceneGroupInstance.class);
        cold.setLuaGroup(group);
        var nextScene = new FlowScene(scene.getWorld(), 3);
        nextScene.instance = cold;
        var restored = SceneFlowEntities.newNpcs(nextScene.getScriptManager(), cold, group.getSuiteByIndex(3));
        assertEquals(1, restored.size());
        nextScene.getEntities().put(restored.getFirst().getId(), restored.getFirst());
        var lib = new ScriptLib();
        lib.setSceneScriptManager(nextScene.getScriptManager());
        assertEquals(0, lib.RemoveEntityByConfigId(group.id, restored.getFirst().getEntityType().getValue(), 71002));
        assertTrue(cold.getFlowNpcConfigs().isEmpty());
        assertEquals(1, nextScene.saves);
        assertTrue(SceneFlowEntities.newNpcs(nextScene.getScriptManager(), cold, group.getSuiteByIndex(3)).isEmpty());
    }

    private EntityGadget gadget(FlowScene scene, SceneGadget template) {
        if (!previous.containsKey(template.gadget_id)) previous.put(template.gadget_id,
                GameData.getGadgetDataMap().put(template.gadget_id,
                        JsonUtils.decode("{id:" + template.gadget_id + ",jsonName:'Test'}", GadgetData.class)));
        var entity = new EntityGadget(scene, template.gadget_id, template.pos.clone(), template.rot.clone());
        entity.setConfigId(template.config_id);
        entity.setGroupId(template.group.id);
        entity.setMetaGadget(template);
        return entity;
    }

    private static final class FlowScene extends TestWorld.TestScene {
        final Map<Integer, Route> routes = new HashMap<>();
        int now = 1000;
        SceneGroupInstance instance;
        SceneScriptManager manager;
        FlowScene(World world, int id) { super(world, id, SceneType.SCENE_WORLD); }
        @Override public int getSceneTime() { return now; }
        @Override public Route getSceneRouteById(int id) { return routes.get(id); }
        @Override public void broadcastPacket(BasePacket packet) {}
        @Override public SceneScriptManager getScriptManager() {
            if (manager == null) manager = new SceneScriptManager(this) {
                @Override public SceneGroupInstance getGroupInstanceById(int id) {
                    return instance != null && instance.getGroupId() == id ? instance : null;
                }
            };
            return manager;
        }
    }
}
