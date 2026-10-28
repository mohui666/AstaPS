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
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

final class SceneFlowSuitesTest {
    private boolean scripts;
    private final Map<Integer,GadgetData> previous = new HashMap<>();

    @BeforeEach
    void prepare() throws Exception {
        ScriptLoader.init();
        scripts = Grasscutter.getConfig().server.game.enableScriptInBigWorld;
        Grasscutter.getConfig().server.game.enableScriptInBigWorld = false;
    }

    @AfterEach
    void restore() {
        previous.forEach((id,data) -> {
            if (data == null) GameData.getGadgetDataMap().remove(id);
            else GameData.getGadgetDataMap().put(id,data);
        });
        Grasscutter.getConfig().server.game.enableScriptInBigWorld = scripts;
    }

    @Test
    void nativeForestDoorAndNurseryFlowStagesKeepTheirEntityProperties() {
        var door = SceneGroup.of(133304040).load(3);
        assertEquals(3,door.suites.size());
        assertEquals(List.of(40001),door.getSuiteByIndex(1).gadgets);
        assertEquals(901,door.getSuiteByIndex(1).flow.gadgetState(door.gadgets.get(40001)));
        assertEquals(Set.of(40004,40011),new HashSet<>(door.getSuiteByIndex(3).gadgets));
        assertEquals(201,door.getSuiteByIndex(3).flow.gadgetState(door.gadgets.get(40004)));
        assertEquals(List.of(40006,40012),door.getSuiteByIndex(3).regions);
        var nursery = SceneGroup.of(133301623).load(3);
        assertEquals(3,nursery.suites.size());
        assertTrue(nursery.getSuiteByIndex(1).gadgets.isEmpty());
        assertEquals(201,nursery.getSuiteByIndex(2).flow.gadgetState(nursery.gadgets.get(623001)));
        assertTrue(nursery.getSuiteByIndex(3).gadgets.isEmpty());
    }

    @Test
    void flowTransitionKeepsTheDoorEntityAndCreatesAndRemovesOnlyTheStageDifference() {
        var owner = new TestWorld.TestPlayer();
        var world = TestWorld.create(owner);
        var scene = new TestWorld.TestScene(world,3,SceneType.SCENE_WORLD) {
            @Override public void broadcastPacket(BasePacket packet) {}
        };
        var group = SceneGroup.of(133304040).load(3);
        var variable = new SceneVar();
        variable.name="phase";
        variable.value=0;
        group.variables=List.of(variable);
        var phase = new SceneFlowSuite.Variable();
        phase.name="phase";
        phase.value=7;
        group.getSuiteByIndex(1).flow.variables=List.of(phase);
        var commonTrigger = group.triggers.get("QUEST_START_40005");
        var names = new ArrayList<>(group.getSuiteByIndex(2).triggers);
        names.add(commonTrigger.getName());
        group.getSuiteByIndex(2).triggers=names;
        group.getSuiteByIndex(2).flow.triggers=names;
        var commonTriggers = new ArrayList<>(group.getSuiteByIndex(2).sceneTriggers);
        commonTriggers.add(commonTrigger);
        group.getSuiteByIndex(2).sceneTriggers=commonTriggers;
        var instance = new SceneGroupInstance(group,owner);
        for (var gadget : group.gadgets.values()) if (!previous.containsKey(gadget.gadget_id)) {
            int id = gadget.gadget_id;
            previous.put(id,GameData.getGadgetDataMap().put(id,
                    JsonUtils.decode("{id:"+id+",jsonName:'Test'}",GadgetData.class)));
        }
        var manager = new SceneScriptManager(scene) {
            @Override public SceneGroupInstance getGroupInstanceById(int id) { return id==group.id?instance:null; }
            @Override public Map<String,Integer> getVariables(int id) { return instance.getCachedVariables(); }
            @Override public EntityGadget createGadget(int groupId,int blockId,SceneGadget gadget,int state) {
                var entity = new EntityGadget(scene,gadget.gadget_id,gadget.pos,gadget.rot);
                entity.setConfigId(gadget.config_id);
                entity.setGroupId(groupId);
                entity.setState(state);
                entity.setMetaGadget(gadget);
                return entity;
            }
            @Override public void addEntities(List<? extends GameEntity> entities) {
                entities.forEach(entity -> scene.getEntities().put(entity.getId(),entity));
            }
            @Override public Future<?> callEvent(ScriptArgs args) { return CompletableFuture.completedFuture(null); }
        };
        assertEquals(0,SceneFlowSuites.goTo(manager,group.id,1));
        var door = (EntityGadget)scene.getEntityByConfigId(40001,group.id);
        assertEquals(901,door.getState());
        manager.restoreTriggerInvocation(commonTrigger,5);
        assertEquals(0,SceneFlowSuites.goTo(manager,group.id,2));
        assertEquals(5,manager.getTriggerInvocationCount(commonTrigger.getName()));
        assertSame(door,scene.getEntityByConfigId(40001,group.id));
        var marker = scene.getEntityByConfigId(40004,group.id);
        assertNotNull(marker);
        assertEquals(0,SceneFlowSuites.goTo(manager,group.id,3));
        assertNull(scene.getEntityByConfigId(40001,group.id));
        assertSame(marker,scene.getEntityByConfigId(40004,group.id));
        assertEquals(201,((EntityGadget)marker).getState());
        assertEquals(2,scene.getEntities().size());
        assertEquals(3,instance.getActiveSuiteId());
        assertEquals(3,scene.saves);
        assertEquals(-1,SceneFlowSuites.goTo(manager,group.id,3));
        assertEquals(-1,SceneFlowSuites.goTo(manager,group.id,9));
        assertEquals(3,scene.saves);
        var retainedRegion = manager.getRegionByConfigId(group.id,40006);
        retainedRegion.getEntities().add(123456);
        assertEquals(0,SceneFlowSuites.goTo(manager,group.id,2));
        assertSame(retainedRegion,manager.getRegionByConfigId(group.id,40006));
        assertTrue(retainedRegion.getEntities().contains(123456));
        assertEquals(1,manager.refreshGroup(instance,1,false));
        assertEquals(7,instance.getCachedVariables().get("phase"));
    }
}
