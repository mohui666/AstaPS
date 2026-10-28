package emu.grasscutter.game.quest.reconstruction;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.entity.gadget.GadgetWorktop;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.*;
import emu.grasscutter.net.proto.ActivityInfoOuterClass.ActivityInfo;
import emu.grasscutter.server.packet.send.PacketActivityInfoNotify;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** Explicit replacement for absent event engines. Real ordered actions produce the original goal. */
public final class QuestActivityRuntime {
    public static final int OPTION = 7;
    private QuestActivityRuntime() {}

    private static GameQuest active(Player player, QuestActivityCatalog.Trial trial) {
        var quest = player.getQuestManager().getQuestById(trial.quest());
        if (quest == null || quest.getQuestData() == null || quest.getState() != QuestState.QUEST_STATE_UNFINISHED
                || quest.getMainQuestId() != trial.parent() || quest.getMainQuest().isFinished()) return null;
        for (var goal : trial.goals()) if (quest.getQuestData().getFinishCond().stream().noneMatch(condition ->
                condition.getType() == goal.type()
                        && condition.getParam() != null && condition.getParam().length >= 2
                        && condition.getParam()[0] == goal.activity() && condition.getParam()[1] == goal.trigger()
                        && Math.max(1,condition.getCount()) == goal.count())) return null;
        return quest;
    }

    private static boolean ready(Player player) {
        return player.getScene() != null && player.getWorld() != null && player.getWorld().getHost() == player
                && player.getWorld().getPlayers().size() == 1 && player.getScene().getWorld() == player.getWorld()
                && player.getSceneId() == player.getScene().getId()
                && player.getSceneLoadState() == Player.SceneLoadState.LOADED;
    }

    static void retire(QuestActivityState.Trial saved) {
        var scene = saved.getActorScene();
        if (scene != null) for (var entity : saved.getActors().values())
            if (scene.getEntities().get(entity.getId()) == entity) scene.removeEntity(entity);
        saved.getActors().clear(); saved.setActorScene(null);
    }

    public static void onQuestStart(GameQuest quest) {
        QuestRogueStory.onQuestStart(quest);
        if (QuestActivityCatalog.installed().trial(quest.getSubQuestId()) == null) return;
        var state = quest.getOwner().getQuestActivityState();
        synchronized (state) {
            var old = state.getTrials().get(quest.getSubQuestId());
            if (old != null) retire(old);
            var saved = new QuestActivityState.Trial(); saved.setQuestStart(quest.getStartTime());
            state.getTrials().put(quest.getSubQuestId(),saved); quest.getOwner().save();
        }
    }

    public static boolean preserveOnLogin(GameMainQuest main) {
        if (QuestRogueStory.preserveOnLogin(main)) return true;
        var state = main.getOwner().getQuestActivityState();
        for (var quest : main.getActiveQuests()) {
            var saved = state.getTrials().get(quest.getSubQuestId());
            if (saved != null && saved.getQuestStart() == quest.getStartTime()
                    && QuestActivityCatalog.installed().trial(quest.getSubQuestId()) != null) return true;
        }
        return false;
    }

    private static void sequence(QuestActivityState.Trial saved) {
        saved.getSequence().clear(); saved.setStep(0);
        for (int step = 0; step < 4; step++) {
            int choice;
            do { choice = ThreadLocalRandom.current().nextInt(3); }
            while (step > 0 && saved.getSequence().getLast() == choice);
            saved.getSequence().add(choice);
        }
    }

    private static void explain(Player player, QuestActivityCatalog.Trial trial, QuestActivityState.Trial saved) {
        String order = saved.getSequence().stream().map(id -> Integer.toString(id + 1)).collect(java.util.stream.Collectors.joining("→"));
        player.dropMessage("【重建活动剧情】" + trial.title() + "\n原活动专用引擎缺失，本段使用机关顺序挑战。西到东为1、2、3；本轮顺序："
                + order + "。已操作" + saved.getStep() + "/4；错误只重做当前轮。"
                + (trial.kind().equals("choice") && saved.getChoice() < 0 ? "\n先操作1或2选择原任务的两条结果分支，再完成本轮机关；另一分支保持未完成。" : "")
                + (trial.kind().equals("alchemy") ? "\n完成一轮消耗三种任务专用作物各1份；不足时保留本轮进度。" : ""));
    }

    public static void onTick(Player player) {
        QuestRogueStory.onTick(player);
        var state = player.getQuestActivityState();
        synchronized (state) {
            QuestActivityCatalog.Trial selected = null;
            for (var trial : QuestActivityCatalog.installed().trials().stream().sorted(Comparator.comparingInt(QuestActivityCatalog.Trial::quest)).toList()) {
                var quest = active(player,trial); var saved = state.getTrials().get(trial.quest());
                if (saved == null) continue;
                boolean same = quest != null && saved.getQuestStart() == quest.getStartTime();
                if (!same || !ready(player) || player.getSceneId() != trial.scene()) { retire(saved); continue; }
                if (trial.observer()) { retire(saved); continue; }
                if (saved.isSettled() || trial.goals().stream().allMatch(goal -> saved.getCompleted().getOrDefault(goal.key(),0) >= goal.count())) {
                    retire(saved);
                    for (var goal : trial.goals()) if (saved.getCompleted().getOrDefault(goal.key(),0) >= goal.count())
                        player.getQuestManager().queueEvent(goal.type(),goal.activity(),goal.trigger());
                    continue;
                }
                if (saved.getAnchor() == null) {
                    saved.setAnchor((trial.anchorAtPlayer() ? player.getPosition() : trial.position()).clone());
                    sequence(saved); player.save();
                }
                double distance = player.getPosition().computeDistance(saved.getAnchor());
                if (!Double.isFinite(distance) || distance > 40 || selected != null) { retire(saved); continue; }
                selected = trial;
                if (saved.getActorScene() != player.getScene()) retire(saved);
                if (!saved.getActors().isEmpty() && saved.getActors().values().stream().anyMatch(entity ->
                        player.getScene().getEntities().get(entity.getId()) != entity)) retire(saved);
                if (saved.getActors().isEmpty()) {
                    saved.setActorScene(player.getScene());
                    for (int slot = 0; slot < 3; slot++) {
                        var point = saved.getAnchor().clone(); point.setX(point.getX() + (slot - 1) * 2.5f); point.setZ(point.getZ() + 3);
                        var entity = new EntityGadget(player.getScene(),70350081,point);
                        entity.buildContent();
                        if (!(entity.getContent() instanceof GadgetWorktop worktop)) throw new IllegalStateException("Activity trial operator is not a worktop");
                        worktop.addWorktopOptions(new int[] {OPTION});
                        saved.getActors().put(slot,entity); player.getScene().addEntity(entity);
                    }
                    explain(player,trial,saved);
                }
            }
        }
    }

    public static boolean tryInteract(Player player, GameEntity entity, int option) {
        if (QuestRogueStory.tryInteract(player,entity,option)) return true;
        var state = player.getQuestActivityState();
        synchronized (state) {
            for (var trial : QuestActivityCatalog.installed().trials()) {
                var saved = state.getTrials().get(trial.quest());
                if (saved == null) continue;
                var slot = saved.getActors().entrySet().stream().filter(entry -> entry.getValue() == entity).findFirst().orElse(null);
                if (slot == null) continue;
                var quest = active(player,trial);
                double distance = player.getPosition().computeDistance(entity.getPosition());
                if (option != OPTION || !ready(player) || quest == null || saved.getQuestStart() != quest.getStartTime()
                        || saved.getActorScene() != player.getScene() || player.getScene().getEntities().get(entity.getId()) != entity
                        || !Double.isFinite(distance) || distance > 6 || saved.getSequence().size() != 4) return true;
                if (saved.getStep() >= 4) return true;
                if (trial.kind().equals("choice") && saved.getChoice() < 0) {
                    if (slot.getKey() >= trial.goals().size()) return true;
                    saved.setChoice(slot.getKey()); saved.setGoal(slot.getKey());
                    retire(saved); player.save(); return true;
                }
                if (saved.getSequence().get(saved.getStep()) != slot.getKey().intValue()) saved.setStep(0);
                else saved.setStep(saved.getStep() + 1);
                // Retire on every accepted action. Replaying an old entity request cannot count twice.
                retire(saved);
                if (saved.getStep() == 4) {
                    if (trial.kind().equals("alchemy") && List.of(1,4,16).stream().anyMatch(id -> state.getCrops().getOrDefault(id,0) < 1)) {
                        saved.setStep(3); player.dropMessage("专用作物不足：本轮最后一步已保留，请先完成原剧情发放作物的步骤。");
                    } else {
                        if (trial.kind().equals("alchemy")) for (int id : List.of(1,4,16)) state.getCrops().merge(id,-1,Integer::sum);
                        while (saved.getGoal() < trial.goals().size() && saved.getCompleted().getOrDefault(trial.goals().get(saved.getGoal()).key(),0) >= trial.goals().get(saved.getGoal()).count()) saved.setGoal(saved.getGoal() + 1);
                        if (saved.getGoal() < trial.goals().size()) {
                            var goal = trial.goals().get(saved.getGoal()); saved.getCompleted().merge(goal.key(),1,Integer::sum);
                            if (trial.kind().equals("choice")) saved.setSettled(true);
                            recordObservers(player,trial,goal);
                            player.save();
                            player.getQuestManager().queueEvent(goal.type(),goal.activity(),goal.trigger());
                        }
                        sequence(saved);
                    }
                }
                player.save(); return true;
            }
        }
        return false;
    }

    public static boolean matchesCurrent(GameQuest quest, QuestData.QuestContentCondition condition) {
        var trial = QuestActivityCatalog.installed().trial(quest.getSubQuestId());
        var p = condition.getParam();
        if (trial == null || p == null || p.length < 2 || active(quest.getOwner(),trial) != quest) return false;
        var saved = quest.getOwner().getQuestActivityState().getTrials().get(quest.getSubQuestId());
        return saved != null && saved.getQuestStart() == quest.getStartTime()
                && saved.getCompleted().getOrDefault(condition.getType().getValue() + ":" + p[0] + ":" + p[1],0) >= Math.max(1,condition.getCount());
    }

    private static void recordObservers(Player player, QuestActivityCatalog.Trial source, QuestActivityCatalog.Goal goal) {
        for (var observer : QuestActivityCatalog.installed().trials()) {
            if (!observer.observer() || observer.parent() != source.parent()) continue;
            var quest = active(player,observer);
            var saved = player.getQuestActivityState().getTrials().get(observer.quest());
            if (quest == null || saved == null || saved.getQuestStart() != quest.getStartTime()) continue;
            boolean listens = java.util.stream.Stream.concat(quest.getQuestData().getFinishCond().stream(),quest.getQuestData().getFailCond().stream())
                    .anyMatch(c -> c.getType() == goal.type() && c.getParam() != null
                            && c.getParam().length >= 2 && c.getParam()[0] == goal.activity() && c.getParam()[1] == goal.trigger());
            if (listens) saved.getCompleted().merge(goal.key(),1,Integer::sum);
        }
    }

    public static void onLogout(Player player) { player.getQuestActivityState().getTrials().values().forEach(QuestActivityRuntime::retire); }

    public static boolean grantCrops(GameQuest quest, String... params) {
        if ((quest.getSubQuestId() != 4014402 && quest.getSubQuestId() != 4016001) || params.length < 2
                || !"1,4,16".equals(params[0]) || !"10,10,10".equals(params[1])) return false;
        var player = quest.getOwner(); var state = player.getQuestActivityState();
        synchronized (state) {
            if (state.getCropGrants().add(quest.getSubQuestId())) {
                for (int id : List.of(1,4,16)) state.getCrops().merge(id,10,Integer::sum);
                player.save();
            }
        }
        return true;
    }

    public static Boolean conditionOverride(Player player, int activity, int condition) {
        var overrides = player.getQuestActivityState().getConditions().get(activity);
        return overrides == null ? null : overrides.get(condition);
    }

    public static boolean activityEnded(Player player, QuestData.QuestContentCondition condition) {
        var p = condition.getParam();
        if (p == null || p.length < 2 || p[0] <= 0 || p[1] != 0) return false;
        var schedule = emu.grasscutter.game.activity.ActivityManager.getScheduleActivityConfigMap().get(p[0]);
        // An absent event archive is unknown, never proof that its story has ended.
        return schedule != null && player.getActivityManager().hasActivityEnded(schedule.getActivityId());
    }

    public static void applyConditionOverrides(Player player, int activity, ActivityInfo.Builder proto) {
        var overrides = player.getQuestActivityState().getConditions().get(activity);
        if (overrides == null) return;
        var met = new LinkedHashSet<>(proto.getMeetCondListList());
        overrides.forEach((id,enabled) -> { if (enabled) met.add(id); else met.remove(id); });
        proto.clearMeetCondList().addAllMeetCondList(met);
    }

    public static boolean setCondition(GameQuest quest, boolean enabled, String... params) {
        if (params.length < 2) return false;
        final int activity, condition;
        try { activity = Integer.parseInt(params[0]); condition = Integer.parseInt(params[1]); }
        catch (NumberFormatException error) { return false; }
        if (activity <= 0 || condition <= 0) return false;
        var player = quest.getOwner(); var state = player.getQuestActivityState();
        synchronized (state) {
            var overrides = state.getConditions().computeIfAbsent(activity,ignored -> new HashMap<>());
            if (!Objects.equals(overrides.put(condition,enabled),enabled)) player.save();
        }
        var info = player.getActivityManager().getInfoProtoByActivityId(activity);
        if (info != null) player.sendPacket(new PacketActivityInfoNotify(info));
        player.getQuestManager().queueEvent(QuestCond.QUEST_COND_ACTIVITY_COND,condition);
        return true;
    }
}
