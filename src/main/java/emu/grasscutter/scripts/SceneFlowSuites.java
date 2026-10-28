package emu.grasscutter.scripts;

import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.world.SceneGroupInstance;
import emu.grasscutter.net.proto.VisionTypeOuterClass.VisionType;
import emu.grasscutter.scripts.constants.EventType;
import emu.grasscutter.scripts.data.*;
import java.util.*;

public final class SceneFlowSuites {
    private SceneFlowSuites() {}

    public static int goTo(SceneScriptManager manager, int groupId, int suiteId) {
        return goTo(manager,groupId,suiteId,true);
    }

    private static int goTo(SceneScriptManager manager, int groupId, int suiteId, boolean notify) {
        var scene = manager.getScene();
        synchronized (scene) {
            var instance = manager.getGroupInstanceById(groupId);
            if (instance == null) {
                manager.getGroupById(groupId);
                instance = manager.getGroupInstanceById(groupId);
            }
            if (instance == null) return -1;
            var group = instance.getLuaGroup();
            var target = group.getSuiteByIndex(suiteId);
            if (target == null || target.flow == null) return -1;
            // Native Group::goToFlowSuite rejects the current stage instead of re-creating it.
            if (instance.getActiveSuiteId() == suiteId) return -1;
            if (!SceneFlowEntities.validateTarget(manager,instance,target)) return -1;
            var previous = group.getSuiteByIndex(instance.getActiveSuiteId());
            if (previous != null) {
                manager.deregisterTrigger(previous.sceneTriggers.stream()
                        .filter(trigger -> !target.triggers.contains(trigger.getName())).toList());
                emu.grasscutter.game.quest.persistent.QuestPersistentDungeonRuntime.suiteRemoved(
                        scene,groupId,group.suites.indexOf(previous)+1);
            }
            var unwanted = scene.getEntities().values().stream().filter(entity -> entity.getGroupId() == groupId)
                    .filter(entity -> entity instanceof EntityGadget && !target.gadgets.contains(entity.getConfigId())
                            || entity instanceof EntityMonster && !target.monsters.contains(entity.getConfigId()))
                    .toList();
            scene.removeEntities(unwanted,VisionType.VisionType_VISION_REMOVE);
            instance.getDeadEntities().removeAll(target.monsters);
            instance.getDeadEntities().removeAll(target.gadgets);
            // Target properties also apply to retained entities; do not respawn a door or reward
            // container merely because both stages contain its config ID.
            for (var template : target.sceneGadgets) {
                int state = target.flow.gadgetState(template);
                instance.getCachedGadgetStates().put(template.config_id,state);
                if (scene.getEntityByConfigId(template.config_id,groupId) instanceof EntityGadget gadget)
                    gadget.updateState(state);
            }
            for (var template : target.sceneMonsters)
                if (scene.getEntityByConfigId(template.config_id,groupId) instanceof EntityMonster monster)
                    monster.setPoseId(target.flow.monsterPose(template));
            applyVariables(manager,instance,target);
            instance.setActiveSuiteId(suiteId);
            instance.setTargetSuiteId(0);
            manager.addGroupSuite(instance,target);
            if (notify) {
                scene.broadcastPacket(new emu.grasscutter.server.packet.send.PacketGroupSuiteNotify(groupId,suiteId));
                scene.saveGroups();
            }
            return 0;
        }
    }

    /** Native quest action 71 forces a WQ flow refresh, including the current stage. */
    public static int refreshWorldQuest(SceneScriptManager manager, int groupId, int suiteId) {
        var scene = manager.getScene();
        synchronized (scene) {
            var instance = manager.getGroupInstanceById(groupId);
            if (instance == null) {
                manager.getGroupById(groupId);
                instance = manager.getGroupInstanceById(groupId);
            }
            if (instance == null) return -1;
            var group = instance.getLuaGroup();
            var target = group.getSuiteByIndex(suiteId);
            if (group.init_config == null || group.init_config.io_type != 1 || group.init_config.sub_flow_type != 1
                    || target == null || target.flow == null || !SceneFlowEntities.validateTarget(manager,instance,target)) return -1;
            manager.fireQuestFlowEvent(new ScriptArgs(groupId,EventType.EVENT_GROUP_WILL_REFRESH,
                    instance.getActiveSuiteId(),suiteId));
            manager.unloadMonsterTides(groupId);
            if (scene.getChallenge() != null && scene.getChallenge().getGroup().id == groupId) {
                scene.getChallenge().abortQuiet();
                scene.setChallenge(null);
            }
            var discarded = scene.getEntities().values().stream().filter(entity -> entity.getGroupId() == groupId)
                    .filter(entity -> entity instanceof EntityMonster || entity instanceof EntityNPC
                            || entity instanceof EntityGadget && !target.gadgets.contains(entity.getConfigId())).toList();
            scene.removeEntities(discarded,VisionType.VisionType_VISION_REFRESH);
            instance.getFlowNpcConfigs().clear();
            for (int config : manager.getRegisteredRegionConfigs(groupId)) {
                var region = manager.getRegionByConfigId(groupId,config);
                if (region != null) manager.deregisterRegion(region.getMetaRegion());
            }
            manager.deregisterTrigger(new ArrayList<>(group.triggers.values()));
            var values = manager.getVariables(groupId);
            var previousValues = new HashMap<>(values);
            values.clear();
            for (var variable : group.variables) values.put(variable.name,
                    variable.no_refresh ? previousValues.getOrDefault(variable.name,variable.value) : variable.value);
            for (int suite = 1; suite <= group.suites.size(); suite++)
                emu.grasscutter.game.quest.persistent.QuestPersistentDungeonRuntime.suiteRemoved(scene,groupId,suite);
            instance.setActiveSuiteId(0);
            int result = goTo(manager,groupId,suiteId,false);
            if (result != 0) return result;
            manager.fireQuestFlowEvent(new ScriptArgs(groupId,EventType.EVENT_GROUP_REFRESH,suiteId));
            scene.broadcastPacket(new emu.grasscutter.server.packet.send.PacketGroupSuiteNotify(groupId,suiteId));
            scene.saveGroups();
            return 0;
        }
    }

    static void applyVariables(SceneScriptManager manager, SceneGroupInstance instance, SceneSuite suite) {
        if (suite.flow == null) return;
        var values = manager.getVariables(instance.getGroupId());
        for (var variable : suite.flow.variables) {
            values.put(variable.name,variable.value);
            manager.setGroupLogicState(instance.getLuaGroup(),variable.name,variable.value);
        }
    }
}
