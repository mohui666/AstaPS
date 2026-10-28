package emu.grasscutter.game.quest.war;

import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.entity.gadget.GadgetWorktop;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.*;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.GameMainQuest;
import emu.grasscutter.game.quest.enums.*;
import emu.grasscutter.game.world.*;
import emu.grasscutter.game.world.data.TeleportProperties;
import emu.grasscutter.net.proto.EnterTypeOuterClass.EnterType;
import emu.grasscutter.scripts.data.SceneGroup;
import emu.grasscutter.server.event.player.PlayerTeleportEvent.TeleportType;
import emu.grasscutter.server.packet.send.PacketWorktopOptionNotify;
import java.util.*;

/** AstaPS's authorized custom reconstruction, never an emulation of missing official scripts. */
public final class ReconstructedWarRuntime {
    private static final int OPTION = 7;
    private static final Map<Scene, Session> sessions = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<Integer> rescueQuests = Set.of(501104,501118,501123,501110,501114,501127,
            501204,501207,501212,501240,501242,501221,501224,501230,501233,501236);
    private static final class Session {
        int quest, suite, group;
    }
    private ReconstructedWarRuntime() {}

    /** A restored real war run resumes its active objective without replaying its begin exec. */
    public static boolean preserveOnLogin(GameMainQuest main) {
        var player = main.getOwner();
        var scene = player.getScene();
        if (owner(scene) != player) return false;
        var run = player.getPersistentDungeons().getRun(ReconstructedWarCatalog.SCENE);
        if (run == null || run.getProgress() == null || !run.getProgress().isStarted()
                || !scene.getDungeonManager().capturePersistentProgress().isStarted()) return false;
        return main.getActiveQuests().stream().anyMatch(quest -> {
            var stage = ReconstructedWarCatalog.installed().stage(quest.getSubQuestId());
            return stage != null && stage.mainId == main.getParentQuestId();
        });
    }

    public static boolean isServerOwnedLuaNotification(String key) {
        return key != null && key.matches("501[123][0-9]{2}")
                && ReconstructedWarCatalog.installed().serverOwnedLua(key);
    }

    /** A previously visited camp/selected level must not auto-complete a later distinct objective. */
    public static boolean hasStateProof(GameQuest quest, emu.grasscutter.data.excels.quest.QuestData.QuestContentCondition condition) {
        var stage = ReconstructedWarCatalog.installed().stage(quest.getSubQuestId());
        if (stage == null) return true;
        var state = quest.getOwner().getQuestWar().getReconstruction();
        int[] params = condition.getParam();
        if (params == null || params.length < 2) return false;
        if (stage.kind.equals("SELECT") && condition.getType() == QuestContent.QUEST_CONTENT_ABYSS_WAR_LEVEL_STATE_EQUAL)
            return params[1] == 3 && stage.selectionGadgets.containsValue(params[0]) && state.selected(stage.questId,params[0]);
        if (stage.kind.equals("ARRIVE") && condition.getType() == QuestContent.QUEST_CONTENT_ABYSS_WAR_LIMIT_REIGON_STATE_EQUAL)
            return params[0] == stage.regionId && params[1] == 3 && state.arrived(stage.questId);
        return true;
    }

    public static boolean ownsLuaCondition(GameQuest quest, emu.grasscutter.data.excels.quest.QuestData.QuestContentCondition condition) {
        var stage = ReconstructedWarCatalog.installed().stage(quest.getSubQuestId());
        return stage != null && (stage.kind.equals("BATTLE") || stage.kind.equals("TRAVEL"))
                && stage.luaKey.equals(condition.getParamStr());
    }

    /** Native Lua keys are reused by distinct objectives: only this objective's actual result counts. */
    public static boolean matchesLuaNotification(GameQuest quest, emu.grasscutter.data.excels.quest.QuestData.QuestContentCondition condition) {
        if (!ownsLuaCondition(quest,condition) || owner(quest.getOwner().getScene()) != quest.getOwner()) return false;
        var stage = ReconstructedWarCatalog.installed().stage(quest.getSubQuestId());
        var state = quest.getOwner().getQuestWar().getReconstruction();
        return condition.getCount() <= 1 && (stage.kind.equals("BATTLE") ? complete(state,stage) : state.journeyed(stage.questId));
    }

    private static Player owner(Scene scene) {
        if (scene == null || scene.getId() != ReconstructedWarCatalog.SCENE
                || scene.getSceneType() != SceneType.SCENE_DUNGEON || scene.getDungeonManager() == null
                || scene.getDungeonManager().getDungeonData().getId() != ReconstructedWarCatalog.DUNGEON) return null;
        var owner = scene.getWorld().getHost();
        return owner != null && owner.getWorld() == scene.getWorld() && owner.getScene() == scene
                && owner.getSceneId() == scene.getId() ? owner : null;
    }

    private static GameQuest quest(Player player, int id) { return player.getQuestManager().getQuestById(id); }
    private static boolean active(Player player, int id) {
        var quest = quest(player,id);
        return quest != null && quest.getState() == QuestState.QUEST_STATE_UNFINISHED;
    }
    private static boolean finished(Player player, int id) {
        var quest = quest(player,id);
        return quest != null && quest.getState() == QuestState.QUEST_STATE_FINISHED;
    }
    static ReconstructedWarCatalog.Stage current(Player player) {
        var catalog = ReconstructedWarCatalog.installed();
        var current = catalog.stages().stream().filter(stage -> active(player,stage.questId)).findFirst().orElse(null);
        if (current != null) return current;
        if (!finished(player,501311)) return null;
        var state = player.getQuestWar().getReconstruction();
        return catalog.stages().stream().filter(stage -> stage.kind.equals("BATTLE"))
                .filter(stage -> state.findEncounter(stage.questId) != null)
                .allMatch(stage -> complete(state,stage)) ? catalog.exit() : null;
    }

    /** Called before appearance: only the actual saved surviving health is restored. */
    public static void onEntityCreated(GameEntity entity) {
        if (!(entity instanceof EntityMonster monster)) return;
        var owner = owner(entity.getScene());
        if (owner == null) return;
        var binding = ReconstructedWarCatalog.installed().enemy(entity.getGroupId(),entity.getConfigId());
        if (binding == null) return;
        var progress = owner.getQuestWar().getReconstruction().findEncounter(binding.stage().questId);
        Float ratio = progress == null ? null : progress.healthRatio(entity.getConfigId());
        if (ratio != null) monster.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP,
                monster.getFightProperty(FightProperty.FIGHT_PROP_MAX_HP) * ratio);
    }

    /** The native suite cache may be refreshed; that cannot resurrect custom recorded deaths. */
    public static boolean shouldSpawn(Scene scene, int groupId, int configId) {
        if (scene == null || scene.getId() != ReconstructedWarCatalog.SCENE
                || !ReconstructedWarCatalog.installed().groups().contains(groupId)) return true;
        var owner = owner(scene);
        var binding = ReconstructedWarCatalog.installed().enemy(groupId,configId);
        if (binding == null) {
            var stage = ReconstructedWarCatalog.installed().gadget(groupId,configId);
            if (owner == null || stage == null || current(owner) != stage) return false;
            return !stage.gates || configId != stage.portalConfig
                    || !owner.getQuestWar().getReconstruction().encounter(stage.questId).sealed();
        }
        if (owner == null || !active(owner,binding.stage().questId)) return false;
        var progress = owner.getQuestWar().getReconstruction().encounter(binding.stage().questId);
        return progress.wave() < binding.stage().waves.size()
                && binding.stage().waves.get(progress.wave()).stream().anyMatch(enemy -> enemy.configId == configId)
                && !progress.deaths().contains(configId);
    }

    /** Called by the dungeon's normal capture path before entities are unloaded on disconnect. */
    public static void capture(Player player) {
        var scene = player.getScene();
        if (owner(scene) != player) return;
        boolean changed = false;
        var state = player.getQuestWar().getReconstruction();
        for (var entity : scene.getEntities().values()) {
            if (!(entity instanceof EntityMonster monster)) continue;
            var binding = ReconstructedWarCatalog.installed().enemy(entity.getGroupId(),entity.getConfigId());
            if (binding == null || !active(player,binding.stage().questId)) continue;
            float hp = monster.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP);
            float max = monster.getFightProperty(FightProperty.FIGHT_PROP_MAX_HP);
            if (hp > 0 && max > 0) changed |= state.recordHealth(binding.stage().questId,entity.getConfigId(),Math.min(1,hp/max));
        }
        if (changed) player.save();
    }

    public static void onTick(Player player) {
        var scene = player.getScene();
        if (scene == null || player.getSceneLoadState() != Player.SceneLoadState.LOADED
                || player.getWorld() == null || player.getWorld().getHost() != player) return;
        // Leaving the inn and walking out to the war's real entry point enters the real manager.
        if (scene.getId() == 3 && active(player,501101) && finished(player,501128)
                && player.getPersistentDungeons().isActive(ReconstructedWarCatalog.SCENE)
                && player.getPosition().computeDistance(new Position(-1873.736f,60.377f,8996.402f)) < 100) {
            player.getServer().getDungeonSystem().enterDungeon(player,1358,ReconstructedWarCatalog.DUNGEON,true);
            return;
        }
        if (owner(scene) != player || !scene.getScriptManager().isInit()) return;
        var session = sessions.computeIfAbsent(scene, ignored -> new Session());
        synchronized (session) {
            capture(player);
            observeRescues(player);
            var stage = current(player);
            if (stage == null) return;
            var state = player.getQuestWar().getReconstruction();
            if (stage.kind.equals("TALK") && state.observeTalk(stage.questId)) player.save();
            int suite = stage.suite;
            if (stage.kind.equals("BATTLE")) {
                var progress = state.encounter(stage.questId);
                while (progress.wave() < stage.waves.size()
                        && progress.deaths().containsAll(stage.waves.get(progress.wave()).stream().map(e -> e.configId).toList()))
                    if (state.advanceWave(stage.questId,progress.wave()+1)) player.save();
                suite += Math.min(progress.wave(),stage.waves.size()-1);
            }
            if (session.quest != stage.questId || session.suite != suite
                    || stage.groupId != 0 && scene.getScriptManager().getGroupInstanceById(stage.groupId) == null) {
                activate(scene,stage,suite);
                session.quest=stage.questId; session.suite=suite; session.group=stage.groupId;
            }
            if (stage.groupId != 0) {
                var instance = scene.getScriptManager().getGroupInstanceById(stage.groupId);
                if (instance != null && instance.getActiveSuiteId() != suite)
                    scene.getScriptManager().refreshGroup(instance,suite,false);
                if (instance != null) for (var trigger : instance.getLuaGroup().getSuiteByIndex(suite).sceneTriggers)
                    if (trigger.getTrigger_count() == 0 && !scene.getScriptManager().getTriggersByEvent(trigger.getEvent()).contains(trigger))
                        scene.getScriptManager().registerTrigger(trigger);
                ensureNpcs(scene,stage,suite);
                setOptions(scene,stage);
            }
            // A quest may start while the avatar is already inside its registered region.
            // Re-check actual presence; an optional shuttle lands outside this arrival radius.
            if (stage.kind.equals("ARRIVE") && player.getPosition().computeDistance(stage.position) <= 10
                    || stage.kind.equals("TRAVEL") && player.getPosition().computeDistance(stage.destination) <= 12)
                entered(scene,player.getUid(),stage.groupId,stage.regionConfig);
            if (stage.kind.equals("BATTLE") && complete(state,stage)) emit(player,stage);
            if (stage.kind.equals("TRAVEL") && state.journeyed(stage.questId)) emit(player,stage);
            if (stage.kind.equals("ARRIVE") && state.arrived(stage.questId))
                QuestWarRuntime.setRegions(player,new int[]{stage.regionId},3);
        }
    }

    private static void activate(Scene scene, ReconstructedWarCatalog.Stage stage, int suite) {
        var ids = ReconstructedWarCatalog.installed().groups();
        for (var group : List.copyOf(scene.getLoadedGroups()))
            if (ids.contains(group.id) && group.id != stage.groupId)
                scene.unloadGroup(scene.getScriptManager().getBlocks().get(group.block_id),group.id);
        if (stage.groupId == 0) return;
        var manager = scene.getScriptManager();
        var group = manager.getGroupById(stage.groupId);
        if (group == null) throw new IllegalStateException("Missing custom war group "+stage.groupId);
        var instance = manager.getGroupInstanceById(stage.groupId);
        if (instance == null || manager.refreshGroup(instance,suite,false) != suite)
            throw new IllegalStateException("Cannot activate custom war suite "+stage.groupId+":"+suite);
    }

    private static void ensureNpcs(Scene scene, ReconstructedWarCatalog.Stage stage, int suite) {
        var group = scene.getScriptManager().getGroupById(stage.groupId);
        var currentConfigs = stage.npcs.stream().map(npc -> npc.configId).collect(java.util.stream.Collectors.toSet());
        // Ordinary suite refresh removes monsters and gadgets; these explicitly authored NPCs
        // need the same stage lifetime or repeated actor IDs would offer obsolete conversations.
        scene.getEntities().values().stream().filter(entity -> entity instanceof EntityNPC
                        && entity.getGroupId() == stage.groupId && !currentConfigs.contains(entity.getConfigId()))
                .toList().forEach(scene::removeEntity);
        for (var npc : stage.npcs) {
            if (scene.getEntityByConfigId(npc.configId,stage.groupId) != null) continue;
            var definition = group.npcs.get(npc.configId);
            if (definition == null) throw new IllegalStateException("Missing real war NPC "+npc.id);
            scene.addEntity(scene.getScriptManager().createNPC(definition,20238,suite));
        }
    }

    private static void setOptions(Scene scene, ReconstructedWarCatalog.Stage stage) {
        var state = owner(scene).getQuestWar().getReconstruction();
        var configs = new ArrayList<>(stage.selectionGadgets.keySet());
        if (stage.kind.equals("TRAVEL") || stage.kind.equals("EXIT") || stage.gates) configs.add(stage.operationConfig);
        for (int config : configs) {
            var entity = scene.getEntityByConfigId(config,stage.groupId);
            if (!(entity instanceof EntityGadget gadget) || !(gadget.getContent() instanceof GadgetWorktop worktop)) continue;
            boolean enabled = stage.kind.equals("EXIT") || stage.kind.equals("TRAVEL") && !state.journeyed(stage.questId)
                    || stage.kind.equals("SELECT") && !state.selected(stage.questId,stage.selectionGadgets.get(config))
                    || stage.gates && state.encounter(stage.questId).wave() == stage.waves.size()
                            && !state.encounter(stage.questId).sealed();
            if (enabled == worktop.getWorktopOptions().contains(OPTION)) continue;
            worktop.getWorktopOptions().clear();
            if (enabled) worktop.addWorktopOptions(new int[]{OPTION});
            scene.broadcastPacket(new PacketWorktopOptionNotify(gadget));
        }
    }

    private static boolean complete(ReconstructedWarState state, ReconstructedWarCatalog.Stage stage) {
        var value = state.findEncounter(stage.questId);
        return value != null && value.wave() == stage.waves.size() && (!stage.gates || value.sealed());
    }
    private static void emit(Player player, ReconstructedWarCatalog.Stage stage) {
        if (stage.luaKey == null || stage.luaKey.isEmpty()) return;
        player.getQuestManager().queueEvent(QuestCond.QUEST_COND_LUA_NOTIFY,stage.luaKey,0);
        player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_LUA_NOTIFY,stage.luaKey,0);
    }

    /** Only called by the actual ANY_MONSTER_DIE Lua bridge, not a network completion report. */
    public static boolean canRecordDeath(Scene scene, int group, int config) {
        var owner = owner(scene);
        if (owner == null) return false;
        var stage = current(owner);
        var binding = ReconstructedWarCatalog.installed().enemy(group,config);
        if (stage == null || binding == null || binding.stage() != stage || stage.groupId != group
                || scene.getEntityByConfigId(config,group) != null) return false;
        var state = owner.getQuestWar().getReconstruction();
        var progress = state.encounter(stage.questId);
        return progress.wave() < stage.waves.size() && !progress.deaths().contains(config)
                && stage.waves.get(progress.wave()).stream().anyMatch(enemy -> enemy.configId == config);
    }

    public static int monsterDied(Scene scene, int group, int config) {
        if (!canRecordDeath(scene,group,config)) return -1;
        var owner = owner(scene);
        var stage = current(owner);
        var state = owner.getQuestWar().getReconstruction();
        if (state.recordDeath(stage.questId,config)) owner.save();
        return 0;
    }

    /** Rejected player actions fail the Lua condition, so they cannot consume its reusable trigger. */
    public static boolean canSelect(Scene scene, int uid, int group, int config, int option) {
        var owner = owner(scene);
        if (owner == null || uid <= 0 || uid != owner.getUid() || option != OPTION
                || owner.getSceneLoadState() != Player.SceneLoadState.LOADED) return false;
        var stage = current(owner);
        var entity = scene.getEntityByConfigId(config,group);
        if (stage == null || stage.groupId != group || !(entity instanceof EntityGadget gadget)
                || !(gadget.getContent() instanceof GadgetWorktop worktop)
                || !worktop.getWorktopOptions().contains(option)
                || owner.getPosition().computeDistance(entity.getPosition()) > 10) return false;
        var state = owner.getQuestWar().getReconstruction();
        if (stage.kind.equals("SELECT")) {
            Integer level = stage.selectionGadgets.get(config);
            return level != null && QuestWarCatalog.installed().hasLevel(level) && !state.selected(stage.questId,level);
        }
        return config == stage.operationConfig && (stage.kind.equals("EXIT")
                || stage.kind.equals("TRAVEL") && !state.journeyed(stage.questId)
                || stage.gates && state.encounter(stage.questId).wave() == stage.waves.size()
                        && !state.encounter(stage.questId).sealed());
    }

    public static int select(Scene scene, int uid, int group, int config, int option) {
        if (!canSelect(scene,uid,group,config,option)) return -1;
        var owner = owner(scene);
        var stage = current(owner);
        var gadget = (EntityGadget) scene.getEntityByConfigId(config,group);
        var worktop = (GadgetWorktop) gadget.getContent();
        var state = owner.getQuestWar().getReconstruction();
        if (stage.kind.equals("SELECT")) {
            Integer level = stage.selectionGadgets.get(config);
            if (level == null || !QuestWarCatalog.installed().hasLevel(level) || !state.select(stage.questId,level)) return -1;
            owner.save();
            if (!QuestWarRuntime.setLevels(owner,new int[]{level},3)) return -1;
        } else if (stage.kind.equals("EXIT") && config == stage.operationConfig) {
            // The standard exit queues its actual world transfer after 200 ms.
            owner.getServer().getDungeonSystem().exitDungeon(owner);
        } else if (stage.kind.equals("TRAVEL") && config == stage.operationConfig && !state.journeyed(stage.questId)) {
            boolean moved = owner.getWorld().transferPlayerToScene(owner,TeleportProperties.builder()
                    .sceneId(scene.getId()).teleportType(TeleportType.SCRIPT).enterReason(EnterReason.Lua)
                    .enterType(EnterType.EnterType_ENTER_GOTO).teleportTo(stage.landing).build());
            if (!moved) return -1;
            state.journey(stage.questId); owner.save();
        } else if (stage.gates && config == stage.operationConfig
                && state.encounter(stage.questId).wave() == stage.waves.size()) {
            if (!state.seal(stage.questId)) return -1;
            var portal = scene.getEntityByConfigId(stage.portalConfig,stage.groupId);
            if (portal != null) scene.removeEntity(portal);
            owner.save();
        } else return -1;
        worktop.getWorktopOptions().clear();
        scene.broadcastPacket(new PacketWorktopOptionNotify(gadget));
        return 0;
    }

    public static boolean canEnter(Scene scene, int uid, int group, int config) {
        var owner = owner(scene);
        if (owner == null || uid <= 0 || owner.getUid() != uid
                || owner.getSceneLoadState() != Player.SceneLoadState.LOADED) return false;
        var stage = current(owner);
        return stage != null && stage.groupId == group && stage.regionConfig == config
                && (stage.kind.equals("ARRIVE") && owner.getPosition().computeDistance(stage.position) <= 10
                || stage.kind.equals("TRAVEL") && owner.getPosition().computeDistance(stage.destination) <= 12);
    }

    public static int entered(Scene scene, int uid, int group, int config) {
        if (!canEnter(scene,uid,group,config)) return -1;
        var owner = owner(scene);
        var stage = current(owner);
        var state = owner.getQuestWar().getReconstruction();
        if (stage.kind.equals("ARRIVE")) {
            if (owner.getPosition().computeDistance(stage.position) > 10) return -1;
            if (state.arrive(stage.questId)) owner.save();
            return QuestWarRuntime.setRegions(owner,new int[]{stage.regionId},3) ? 0 : -1;
        }
        if (stage.kind.equals("TRAVEL") && owner.getPosition().computeDistance(stage.destination) <= 12) {
            if (state.journey(stage.questId)) owner.save();
            return 0;
        }
        return -1;
    }

    private static void observeRescues(Player owner) {
        boolean changed = false;
        var state = owner.getQuestWar().getReconstruction();
        for (int id : rescueQuests) if (state.observedTalk(id) && finished(owner,id))
            for (var npc : ReconstructedWarCatalog.installed().stage(id).npcs) changed |= state.rescue(npc.id);
        if (changed) owner.save();
    }

    /** Custom accounting for 501313: unique rescued actors, actual kills, and cleared gates. */
    public static boolean settleAccount(GameQuest quest, int account) {
        var owner = quest.getOwner();
        if (quest.getSubQuestId() != 501313 || owner(owner.getScene()) != owner) return false;
        var state = owner.getQuestWar().getReconstruction();
        var catalog = ReconstructedWarCatalog.installed();
        if (!complete(state,catalog.stage(501303)) || !complete(state,catalog.stage(501305))
                || !complete(state,catalog.stage(501307))) return false;
        observeRescues(owner);
        int value = switch (account) {
            case 1002 -> state.rescueCount();
            case 1003 -> state.killCount();
            case 1004 -> (int) catalog.stages().stream().filter(stage -> stage.gates && complete(state,stage)).count();
            default -> -1;
        };
        if (value < 0) return false;
        if (owner.getQuestWar().setReconstructedAccount(account,value)) owner.save();
        return true;
    }
}
