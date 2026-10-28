package emu.grasscutter.game.quest.reconstruction;

import emu.grasscutter.GameConstants;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.entity.gadget.GadgetWorktop;
import emu.grasscutter.game.icecreature.*;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.ElementType;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.enums.*;
import emu.grasscutter.game.world.*;
import emu.grasscutter.net.proto.EntityMoveInfoOuterClass.EntityMoveInfo;
import emu.grasscutter.net.proto.MotionInfoOuterClass.MotionInfo;
import emu.grasscutter.net.proto.MotionStateOuterClass.MotionState;
import emu.grasscutter.net.proto.VisionTypeOuterClass.VisionType;
import emu.grasscutter.server.event.entity.EntityDamageEvent;
import emu.grasscutter.server.packet.send.*;
import java.util.*;

/** Explicit custom completion of the six 7011 goals whose supplied groups contain spawn data only. */
public final class SnezhnayaIceQuestReconstruction {
    private static final int OPTION = 7;
    private static final int CUSTOM_GROUP = 901700011;
    private static final Set<StoryReconstructionState.StageProgress> REPLAYED =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private record Goal(int quest, String key, Position position, String instructions) {}
    private static final List<Goal> GOALS = List.of(
            new Goal(701104,"Q70110401",new Position(7191.207f,209.571f,8575.136f),"操作派蒙附近的解救装置。"),
            new Goal(701114,"Q70111401",new Position(7510.101f,207.910f,8305.221f),"使用臻冰拆除技能移除路中的立方阻碍物。"),
            new Goal(701116,"Q70111601",new Position(7530.195f,204.210f,8326.852f),"在门前4米内制造立方，再操作门前装置。"),
            new Goal(701118,"Q70111801",new Position(7566.945f,216.624f,8358.829f),"在蓝图下方4米内制造立方，再登上造物靠近蓝图并拾取。"),
            new Goal(701122,"Q700113",new Position(7607.093f,235.085f,8268.915f),"连续击中目标晶矿三次。"),
            new Goal(701136,"Q70113601",new Position(7511.360f,202.638f,8357.278f),"在目标炉具旁调查；原剧情演出仍需正常完成。"));
    private SnezhnayaIceQuestReconstruction() {}

    private static boolean ownScene(Player player) {
        var scene = player == null ? null : player.getScene();
        return scene != null && scene.getId() == 3 && scene.getWorld().getHost() == player
                && player.getWorld() == scene.getWorld()
                && player.getSceneLoadState() == Player.SceneLoadState.LOADED;
    }

    private static boolean active(Player player, Goal goal) {
        var quest = player.getQuestManager().getQuestById(goal.quest);
        return quest != null && quest.getState() == QuestState.QUEST_STATE_UNFINISHED
                && quest.getQuestData() != null && quest.getQuestData().getFinishCond().stream()
                .anyMatch(c -> c.getType() == QuestContent.QUEST_CONTENT_LUA_NOTIFY
                        && goal.key.equals(c.getParamStr()) && c.getCount() == 1);
    }

    private static Goal goal(int quest) { return GOALS.stream().filter(g -> g.quest == quest).findFirst().orElse(null); }

    public static boolean ownsLuaCondition(GameQuest quest,QuestData.QuestContentCondition condition) {
        var goal = goal(quest.getSubQuestId());
        return goal != null && condition.getType() == QuestContent.QUEST_CONTENT_LUA_NOTIFY
                && goal.key.equals(condition.getParamStr());
    }

    public static boolean matchesLuaNotification(GameQuest quest,QuestData.QuestContentCondition condition) {
        var saved = quest.getOwner().getStoryReconstruction().get(quest.getSubQuestId());
        if (!ownsLuaCondition(quest,condition) || saved == null || !saved.isComplete()) return false;
        // This native step accepts either notification or its plot. The custom investigation
        // must not silently replace the original plot, so its own receipt waits for that plot.
        if (quest.getSubQuestId() == 701136) {
            var conditions = quest.getQuestData().getFinishCond();
            for (int i=0;i<conditions.size();i++)
                if (conditions.get(i).getType() == QuestContent.QUEST_CONTENT_FINISH_PLOT
                        && quest.getFinishProgressList()[i] == 1) return true;
            return false;
        }
        return true;
    }

    public static void onQuestStart(GameQuest quest) {
        var goal = goal(quest.getSubQuestId());
        if (goal == null) return;
        var player = quest.getOwner();
        player.getStoryReconstruction().reset(goal.quest).setAnchor(goal.position.clone());
        player.getPlayerProgress().resetCurrentProgress(goal.key);
        player.save();
    }

    public static void onTick(Player player) {
        if (!ownScene(player)) return;
        var scene = player.getScene();
        boolean relevant = false;
        for (var goal : GOALS) {
            var prior = player.getStoryReconstruction().get(goal.quest);
            if (prior != null) relevant = true;
            if (prior != null && prior.isComplete()) applyComplete(player,goal);
            if (!active(player,goal)) continue;
            relevant = true;
            var saved = player.getStoryReconstruction().ensure(goal.quest);
            if (saved.isComplete()) {
                // Re-evaluate an actual saved action after disconnecting before queued dispatch.
                if (REPLAYED.add(saved)) {
                    if (player.getPlayerProgress().getCurrentProgress(goal.key) < 1)
                        player.getPlayerProgress().addToCurrentProgress(goal.key,1);
                    player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_LUA_NOTIFY,goal.key,0);
                }
                continue;
            }
            if (player.getPosition().computeDistance(goal.position) > 80) continue;
            if (!saved.isStarted()) {
                saved.setStarted(true);
                player.sendPacket(new PacketPrivateChatNotify(GameConstants.SERVER_CONSOLE_UID,
                        player.getUid(),"【重建剧情】" + goal.instructions));
                player.save();
            }
            if (goal.quest == 701114) {
                IceCreatureSystem.ensureQuestObstacle(player,goal.quest,73094010,goal.position);
            } else if (goal.quest != 701122) {
                var original = original(scene,goal.quest);
                if (goal.quest == 701104 && original instanceof EntityGadget gadget
                        && gadget.getContent() instanceof GadgetWorktop worktop) {
                    if (worktop.getWorktopOptions().add(OPTION)) scene.broadcastPacket(new PacketWorktopOptionNotify(gadget));
                } else {
                    var operator = scene.getEntityByConfigId(goal.quest,CUSTOM_GROUP);
                    if (operator == null) {
                        var position = goal.position.clone();
                        if (goal.quest == 701118) position.addZ(-0.8f);
                        var gadget = new EntityGadget(scene,70360001,position,new Position());
                        gadget.setConfigId(goal.quest);
                        gadget.setGroupId(CUSTOM_GROUP);
                        gadget.replaceContent(new GadgetWorktop(gadget));
                        ((GadgetWorktop)gadget.getContent()).addWorktopOptions(new int[]{OPTION});
                        scene.addEntity(gadget);
                    } else if (operator instanceof EntityGadget gadget
                            && gadget.getContent() instanceof GadgetWorktop worktop
                            && worktop.getWorktopOptions().add(OPTION)) {
                        scene.broadcastPacket(new PacketWorktopOptionNotify(gadget));
                    }
                }
            }
        }
        if (!relevant) return;
        for (var entity : List.copyOf(scene.getEntities().values())) {
            if (entity.getGroupId() != CUSTOM_GROUP) continue;
            var goal = goal(entity.getConfigId());
            if (goal == null || !active(player,goal)
                    || player.getStoryReconstruction().ensure(goal.quest).isComplete()) scene.removeEntity(entity);
        }
    }

    private static GameEntity original(Scene scene,int quest) {
        return switch(quest) {
            case 701104 -> scene.getEntityByConfigId(6016,133700006);
            case 701118 -> scene.getEntityByConfigId(21008,133700021);
            case 701122 -> scene.getEntityByConfigId(14001,133700014);
            case 701136 -> scene.getEntityByConfigId(34001,133700034);
            default -> null;
        };
    }

    /** Invoked after a successful real IceCreatureBuildReq has spawned and retained its record. */
    public static void onBuilt(Player player, IceCreatureRecord record) {
        if (!ownScene(player) || record.getSourceQuestId() != 0 || !liveCube(player,record)) return;
        for (int id : new int[]{701116,701118}) {
            var goal = goal(id);
            if (!active(player,goal) || record.getPos().computeDistance(goal.position) > 4
                    || player.getPosition().computeDistance(record.getPos()) > 8) continue;
            player.getStoryReconstruction().ensure(id).getSwitches().add(0);
            player.save();
        }
    }

    private static boolean liveCube(Player player,IceCreatureRecord record) {
        var entity = player.getScene().getEntityById(record.getEntityId());
        return record.getSceneId() == 3 && record.getType() == 2 && !entityDead(entity)
                && entity instanceof EntityGadget gadget && gadget.getGadgetId() == record.getGadgetId()
                && gadget.getPosition().computeDistance(record.getPos()) < 0.01;
    }

    private static boolean entityDead(GameEntity entity) { return entity == null || entity.isDead(); }

    /** Only the successful removal path names this quest-owned, standing obstacle. */
    public static void onRemoved(Player player,IceCreatureRecord record,GameEntity standing) {
        var goal = goal(701114);
        if (ownScene(player) && active(player,goal) && record.getSourceQuestId() == goal.quest
                && record.getSceneId() == 3 && record.getType() == 2
                && standing instanceof EntityGadget gadget && gadget.getGadgetId() == record.getGadgetId()
                && standing.getPosition().computeDistance(goal.position) < 0.01
                && player.getPosition().computeDistance(standing.getPosition()) <= 6) complete(player,goal);
    }

    public static boolean tryInteract(Player player,GameEntity entity,int option) {
        Goal selected = null;
        if (entity.getGroupId() == CUSTOM_GROUP) selected = goal(entity.getConfigId());
        else if (entity.getGroupId() == 133700006 && entity.getConfigId() == 6016) selected = goal(701104);
        if (selected == null) return false;
        var goal = selected;
        if (!ownScene(player) || !active(player,goal) || option != OPTION
                || !(entity instanceof EntityGadget gadget) || entity != player.getScene().getEntityById(entity.getId())
                || !(gadget.getContent() instanceof GadgetWorktop worktop) || !worktop.getWorktopOptions().contains(option)
                || player.getPosition().computeDistance(entity.getPosition()) > 3
                || player.getStoryReconstruction().ensure(goal.quest).isComplete()) return true;
        if (goal.quest == 701116 || goal.quest == 701118) {
            if (goal.quest == 701118 && player.getPosition().getY() < goal.position.getY()-1.5f) return true;
            if (!player.getStoryReconstruction().ensure(goal.quest).getSwitches().contains(0)
                    || player.getIceCreatures().stream().noneMatch(record -> record.getSourceQuestId() == 0
                            && liveCube(player,record) && record.getPos().computeDistance(goal.position) <= 4)) return true;
        }
        worktop.removeWorktopOption(option);
        sceneNotify(gadget);
        if (goal.quest == 701118) {
            var drawing = original(player.getScene(),goal.quest);
            if (drawing != null) player.getScene().removeEntity(drawing);
        }
        complete(player,goal);
        return true;
    }

    private static void sceneNotify(EntityGadget gadget) { gadget.getScene().broadcastPacket(new PacketWorktopOptionNotify(gadget)); }

    /** The real model is HP-invincible and normally breaks a durability-60 modifier in three -20 hits.
     * Its supplied group lacks KresnikMine_Broken, so reconstruct that exact target's three-hit gate.
     * This leaves the shared config and every other mine's invulnerability and abilities intact.
     */
    public static boolean onMiningHit(EntityGadget gadget,float amount,int attackerId,ElementType element) {
        if (gadget.getGroupId() != 133700014 || gadget.getConfigId() != 14001 || gadget.getGadgetId() != 73094084)
            return false;
        var player = gadget.getScene().getWorld().getHost();
        var goal = goal(701122);
        if (!ownScene(player) || !active(player,goal)) return false;
        var attacker = gadget.getScene().getEntityById(attackerId);
        boolean owned = attacker instanceof EntityAvatar avatar && avatar.getPlayer() == player
                || attacker instanceof EntityClientGadget effect && effect.getOwner() == player
                || attacker instanceof EntityGadget effect && effect.getOwner() instanceof EntityAvatar avatar
                        && avatar.getPlayer() == player;
        if (!owned || !Float.isFinite(amount) || amount <= 0 || gadget.isDead()
                || gadget != player.getScene().getEntityById(gadget.getId())
                || player.getPosition().computeDistance(gadget.getPosition()) > 15) return true;
        var event = new EntityDamageEvent(gadget,amount,element,attacker);
        event.call();
        if (event.isCanceled() || !Float.isFinite(event.getDamage()) || event.getDamage() <= 0) return true;
        synchronized(player) {
            var saved = player.getStoryReconstruction().ensure(goal.quest);
            if (saved.isComplete()) return true;
            saved.setArrivalActions(saved.getArrivalActions()+1);
            if (saved.getArrivalActions() >= 3) {
                gadget.updateState(201);
                player.getScene().removeEntity(gadget,VisionType.VisionType_VISION_DIE);
                complete(player,goal);
            } else player.save();
        }
        return true;
    }

    /** A real server-side death of the exact authored ore, not a destroy-content claim. */
    public static void onGadgetDeath(EntityGadget gadget) {
        if (gadget.getGroupId() != 133700014 || gadget.getConfigId() != 14001
                || gadget.getGadgetId() != 73094084 || !gadget.isDead()) return;
        var player = gadget.getScene().getWorld().getHost();
        var goal = goal(701122);
        if (ownScene(player) && active(player,goal)
                && player.getPosition().computeDistance(gadget.getPosition()) <= 15) complete(player,goal);
    }

    private static void complete(Player player,Goal goal) {
        var saved = player.getStoryReconstruction().ensure(goal.quest);
        if (saved.isComplete()) return;
        saved.setComplete(true);
        REPLAYED.add(saved);
        applyComplete(player,goal);
        if (player.getPlayerProgress().getCurrentProgress(goal.key) < 1)
            player.getPlayerProgress().addToCurrentProgress(goal.key,1);
        player.save();
        player.getQuestManager().queueEvent(QuestCond.QUEST_COND_LUA_NOTIFY,goal.key,0);
        player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_LUA_NOTIFY,goal.key,0);
    }

    private static void applyComplete(Player player,Goal goal) {
        if (goal.quest == 701122 && active(player,goal)) {
            var ore = original(player.getScene(),goal.quest);
            if (ore != null) player.getScene().removeEntity(ore);
        } else if (goal.quest == 701118) {
            var drawing = original(player.getScene(),goal.quest);
            if (drawing != null) player.getScene().removeEntity(drawing);
        } else if (goal.quest == 701116) {
            // The supplied file identifies both real vertical-door platform halves but no route.
            // This reconstruction raises each half three metres using authoritative entity motion.
            for (int config : new int[]{11002,11011}) {
                var entity = player.getScene().getEntityByConfigId(config,133700011);
                if (!(entity instanceof EntityGadget door) || door.getGadgetId() != 73094183) continue;
                var target = door.getBornPos().clone().addY(3);
                if (door.getPosition().computeDistance(target) < 0.01) continue;
                door.getPosition().set(target);
                door.setLastMoveReliableSeq(door.getLastMoveReliableSeq()+1);
                player.getScene().broadcastPacket(new PacketSceneEntityMoveNotify(EntityMoveInfo.newBuilder()
                        .setEntityId(door.getId()).setSceneTime(player.getScene().getSceneTime())
                        .setReliableSeq(door.getLastMoveReliableSeq())
                        .setMotionInfo(MotionInfo.newBuilder().setPos(target.toProto()).setRot(door.getRotation().toProto())
                                .setState(MotionState.MotionState_MOTION_NONE)).build()));
            }
        }
    }
}
