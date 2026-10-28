package emu.grasscutter.scripts;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.binout.routes.RotType;
import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.entity.gadget.platform.ConfigRoute;
import emu.grasscutter.game.world.Scene;
import emu.grasscutter.game.world.SceneGroupInstance;
import emu.grasscutter.scripts.data.*;
import java.util.*;

/** Native flow-stage entity properties and the NPCs retained by createNPCs(is_clear=false). */
public final class SceneFlowEntities {
    private SceneFlowEntities() {}

    /** Called before a transition mutates the current stage. */
    public static boolean validateTarget(SceneScriptManager manager, SceneGroupInstance instance, SceneSuite suite) {
        if (suite.flow == null) return true;
        var group = instance.getLuaGroup();
        for (var gadget : suite.flow.gadgets) {
            if (!group.gadgets.containsKey(gadget.config_id)) return false;
            if (manager.getScene().getEntityByConfigId(gadget.config_id, group.id) == null
                    && !validPlatform(manager.getScene(), gadget.platform_info)) return false;
        }
        var npcs = new HashSet<>(suite.npcs);
        synchronized (instance) { npcs.addAll(instance.getFlowNpcConfigs()); }
        for (int id : npcs) if (!group.npcs.containsKey(id)) return false;
        return true;
    }

    /** Only newly created gadgets receive platform_info; retained native platforms keep their run. */
    public static EntityGadget configureNewGadget(EntityGadget entity, SceneSuite suite) {
        if (entity == null || suite.flow == null) return entity;
        var config = suite.flow.gadgets.stream().filter(value -> value.config_id == entity.getConfigId())
                .findFirst().orElseThrow(() -> new IllegalStateException("Missing flow gadget " + entity.getConfigId()));
        var platform = config.platform_info;
        if (platform == null) return entity;
        if (!validPlatform(entity.getScene(), platform))
            throw new IllegalStateException("Unsupported flow platform group=" + entity.getGroupId()
                    + " config=" + entity.getConfigId() + " route=" + platform.route_id);
        if (platform.route_id != 0) {
            var authoredRoute = entity.getScene().getSceneRouteById(platform.route_id);
            var point = authoredRoute.getPoints()[platform.route_index];
            // Group::createFlowGagdets sets is_use_last_index; PlatformComp::initRouteState
            // restores the indexed transform before the first client appearance.
            entity.getPosition().set(point.getPos());
            if (authoredRoute.getRotType() == RotType.ROT_ROUND && point.getRotRoundLeaveDir() != null)
                entity.getRotation().set(point.getRotRoundLeaveDir());
        }
        entity.setRouteConfig(new FlowConfigRoute(entity, platform));
        return entity;
    }

    /** Scene inserts the entity before this call, and invokes its Lua onCreate afterwards. */
    public static void onEntityCreated(GameEntity entity) {
        if (!(entity instanceof EntityGadget gadget) || !(gadget.getRouteConfig() instanceof FlowConfigRoute route)) return;
        synchronized (gadget) {
            if (!route.startOnCreate) return;
            route.startOnCreate = false;
            if (!gadget.startPlatform()) throw new IllegalStateException("Cannot start native flow platform group="
                    + gadget.getGroupId() + " config=" + gadget.getConfigId() + " route=" + route.getRouteId());
        }
    }

    /** The saved set contains real past creations, not stages inferred from a suite index. */
    public static List<EntityNPC> newNpcs(SceneScriptManager manager, SceneGroupInstance instance, SceneSuite suite) {
        if (suite.flow == null) return List.of();
        var group = instance.getLuaGroup();
        synchronized (instance) {
            var ids = new TreeSet<>(instance.getFlowNpcConfigs());
            ids.addAll(suite.npcs);
            for (int id : ids) if (!group.npcs.containsKey(id))
                throw new IllegalStateException("Missing native flow NPC group=" + group.id + " config=" + id);
            var created = new ArrayList<EntityNPC>();
            for (int id : ids) {
                var existing = manager.getScene().getEntityByConfigId(id, group.id);
                if (existing == null) {
                    var npc = manager.createNPC(group.npcs.get(id), group.block_id, group.suites.indexOf(suite) + 1);
                    if (npc == null) throw new IllegalStateException("Cannot create native flow NPC group="
                            + group.id + " config=" + id);
                    created.add(npc);
                } else if (!(existing instanceof EntityNPC)) {
                    throw new IllegalStateException("Native flow NPC config is occupied group=" + group.id + " config=" + id);
                }
                instance.getFlowNpcConfigs().add(id);
            }
            return created;
        }
    }

    private static boolean validPlatform(Scene scene, SceneFlowSuite.Platform platform) {
        if (platform == null) return true;
        // All installed native flow entries use this mode. Other modes need their own native
        // consumer before they can be implemented; do not silently use the template's route.
        if (platform.move_type != 1 || platform.point_id != 0 || platform.route_id < 0 || platform.route_index < 0)
            return false;
        if (platform.route_id == 0) return platform.route_index == 0;
        var route = scene.getSceneRouteById(platform.route_id);
        boolean valid = route != null && route.getPoints() != null && platform.route_index < route.getPoints().length
                && route.getPoints()[platform.route_index] != null && route.getPoints()[platform.route_index].getPos() != null;
        if (!valid) Grasscutter.getLogger().warn("Missing native flow route scene={} route={} index={}",
                scene.getId(), platform.route_id, platform.route_index);
        return valid;
    }

    private static final class FlowConfigRoute extends ConfigRoute {
        boolean startOnCreate;
        FlowConfigRoute(EntityGadget entity, SceneFlowSuite.Platform platform) {
            super(entity.getRotation().clone(), platform.route_id == 0 && platform.is_started,
                    platform.route_id == 0 && platform.is_started, platform.route_id);
            setStartIndex(platform.route_index);
            startOnCreate = platform.route_id != 0 && platform.is_started;
        }
    }
}
