package emu.grasscutter.game.quest.coop;

import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.inventory.*;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.ActionReason;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.*;
import emu.grasscutter.net.proto.CoopChapterOuterClass.CoopChapter;
import emu.grasscutter.net.proto.CoopCgOuterClass.CoopCg;
import emu.grasscutter.net.proto.CoopPointOuterClass.CoopPoint;
import emu.grasscutter.net.proto.CoopRewardOuterClass.CoopReward;
import emu.grasscutter.net.proto.CoopDataNotifyOuterClass.CoopDataNotify;
import emu.grasscutter.net.proto.MainCoopOuterClass.MainCoop;
import emu.grasscutter.net.proto.MainCoopUpdateNotifyOuterClass.MainCoopUpdateNotify;
import emu.grasscutter.net.proto.CoopProgressUpdateNotifyOuterClass.CoopProgressUpdateNotify;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.packet.PacketOpcodes;
import emu.grasscutter.server.packet.send.*;
import java.util.*;

/** Native hangout start/save/replay contract. Only an admitted, owned run can emit save events. */
public final class CoopSystem {
    public record Result(int retcode, MainCoop snapshot, boolean firstPoint) {}
    public static final int INVALID = -1;
    public static final int CLOSED = 9601;
    private CoopSystem() {}

    public static boolean running(Player player, int chapter) {
        var state = player.getHangouts();
        var run = state == null ? null : state.getRun();
        return run != null && run.getChapterId() == chapter && run.getCurrent() != null
                && run.getCurrent().getStatus() == 1;
    }

    public static boolean hasSavePoint(Player player, int chapter, int savePoint) {
        var state = player.getHangouts();
        var run = state == null ? null : state.getRun();
        return run != null && run.getChapterId() == chapter && run.getCurrent() != null
                && run.getCurrent().getSavePoints().contains(savePoint);
    }

    public static boolean matchesCurrent(GameQuest quest, QuestData.QuestContentCondition condition) {
        var target = condition.getParam();
        if (target == null || target.length == 0 || target[0] <= 0) return false;
        var state = quest.getOwner().getHangouts();
        var run = state == null ? null : state.getRun();
        if (run == null || run.getChapterId() != target[0] || run.getCurrent() == null) return false;
        if (condition.getType() == QuestContent.QUEST_CONTENT_MAIN_COOP_ENTER_SAVE_POINT)
            return target.length >= 2 && hasSavePoint(quest.getOwner(), target[0], target[1]);
        // ANY is a fresh save in this subquest's round, never a prior stage's saved key.
        var start = run.getQuestStarts().get(quest.getSubQuestId());
        return start != null && run.getCurrent().getSavePoints().size() > start.getSavePoints().size();
    }

    public static int unlock(Player player, int chapterId) {
        var chapter = GameData.getCoopChapterDataMap().get(chapterId);
        if (chapter == null || player.isInMultiplayer() || CoopCatalog.chapter(chapterId) == null) return INVALID;
        var state = player.getHangouts();
        synchronized (state) {
            if (state.getAcceptedChapters().contains(chapterId)) return INVALID;
            if (!chapterConditions(player, chapterId)) return CLOSED;
            if (chapter.getOpenMaterialId() != 107 || chapter.getOpenMaterialNum() < 0
                    || player.getLegendaryKey() < chapter.getOpenMaterialNum()) return INVALID;
            player.useLegendaryKey(chapter.getOpenMaterialNum());
            state.getAcceptedChapters().add(chapterId);
            for (var point : GameData.getCoopPointDataMap().values()) {
                if (point.getChapterId() != chapterId || !"POINT_START".equals(point.getType())) continue;
                var progress = state.point(point.getId());
                var config = CoopCatalog.chapter(chapterId);
                progress.setStart(new CoopState.Snapshot(chapterId, config.initialConfidence(),
                        config.normalVars(), config.tempVars()));
                progress.setState(1);
            }
            player.save();
        }
        notifyState(player);
        return 0;
    }

    public static Result start(Player player, int pointId) {
        var point = GameData.getCoopPointDataMap().get(pointId);
        var route = CoopProgressionCatalog.installed().point(pointId);
        if (point == null || player.isInMultiplayer() || point.getAcceptQuest() <= 0
                || !GameData.getQuestDataMap().containsKey(point.getAcceptQuest())
                || route == null || route.chapter() != point.getChapterId()
                || route.entryQuest() != point.getAcceptQuest())
            return new Result(INVALID, null, false);
        var state = player.getHangouts();
        synchronized (state) {
            if (CoopProgression.isPendingPoint(player, pointId) && CoopProgression.advanceToPoint(player, pointId))
                return new Result(0, state.getRun().getCurrent().toProto(state.endings(point.getChapterId())), false);
            var stored = state.getPoints().get(pointId);
            if (!state.getAcceptedChapters().contains(point.getChapterId()) || stored == null
                    || stored.getState() == 0 || stored.getStart() == null)
                return new Result(CLOSED, null, false);
            if (state.getRun() != null && state.getRun().getPointId() == pointId
                    && state.getRun().getCurrent().getStatus() == 1)
                return new Result(INVALID, null, false);
            if (state.getRun() != null) clearSeries(player, state.getRun().getChapterId());
            clearSeries(player, point.getChapterId());
            var run = new CoopState.Run(point.getChapterId(), pointId, stored.getStart());
            state.setRun(run);
            if (!CoopProgression.onPointStarted(player, pointId)) return new Result(INVALID, null, false);
            player.save();
            // Replaying begins at exactly the point's configured quest. It does not finish
            // prerequisite dialogues, other branches, or unrelated quests.
            player.getQuestManager().addQuest(point.getAcceptQuest());
            if ("POINT_START".equals(point.getType())) player.getQuestManager().queueEvent(
                    QuestCond.QUEST_COND_MAIN_COOP_START, point.getChapterId());
            notifyState(player);
            return new Result(0, run.getCurrent().toProto(state.endings(point.getChapterId())),
                    "POINT_START".equals(point.getType()));
        }
    }

    public static int save(Player player, int chapter, int savePoint, int confidence,
            Map<Integer, Integer> normal, Map<Integer, Integer> temporary) {
        var config = CoopCatalog.chapter(chapter);
        if (config == null || player.isInMultiplayer() || confidence < 0 || confidence > config.maxConfidence()
                || !config.savePoints().containsKey(savePoint) || config.isEnd(savePoint)) return INVALID;
        var state = player.getHangouts();
        synchronized (state) {
            if (!running(player, chapter)) return INVALID;
            var snapshot = state.getRun().getCurrent();
            if (snapshot.getSavePoints().contains(savePoint) || !canReport(player, config, savePoint)) return INVALID;
            snapshot.getSavePoints().add(savePoint);
            snapshot.setConfidence(confidence);
            snapshot.getNormalVars().clear();
            snapshot.getNormalVars().putAll(normal);
            snapshot.getTempVars().clear();
            snapshot.getTempVars().putAll(temporary);
            player.save();
        }
        emitSave(player, chapter, savePoint);
        notifyState(player);
        return 0;
    }

    private static boolean canReport(Player player, CoopCatalog.Chapter config, int savePoint) {
        if (!CoopProgression.mayReportSave(player, savePoint)) return false;
        for (var entry : config.interactionSaves().entrySet()) {
            if (!entry.getValue().contains(savePoint)) continue;
            var source = player.getQuestManager().getQuestById(entry.getKey());
            if (source != null && (source.getState() == QuestState.QUEST_STATE_UNFINISHED
                    || source.getState() == QuestState.QUEST_STATE_FINISHED)) return true;
        }
        return player.getQuestManager().getMainQuests().values().stream()
                .filter(main -> CoopCatalog.chapterForQuest(main.getParentQuestId()) == config.id())
                .flatMap(main -> main.getActiveQuests().stream()).anyMatch(quest ->
                        quest.getQuestData().getFinishCond().stream().anyMatch(condition -> {
                            int[] p = condition.getParam();
                            return p != null && p.length >= 2 && p[0] == config.id()
                                    && condition.getType() == QuestContent.QUEST_CONTENT_MAIN_COOP_ENTER_SAVE_POINT
                                    && p[1] == savePoint;
                        }));
    }

    public static int finish(Player player, int chapter, int ending) {
        var config = CoopCatalog.chapter(chapter);
        if (config == null || !config.isEnd(ending) || player.isInMultiplayer()) return INVALID;
        var state = player.getHangouts();
        synchronized (state) {
            if (!running(player, chapter) || !canReport(player, config, ending)) return INVALID;
            var run = state.getRun();
            var snapshot = run.getCurrent();
            if (snapshot.getSavePoints().contains(ending)) return INVALID;
            snapshot.getSavePoints().add(ending);
            snapshot.setStatus(2);
            state.endings(chapter).merge(ending, 1, Integer::sum);
            int endPoint = CoopProgressionCatalog.installed().endingPoint(chapter, run.getPointId(), ending);
            if (snapshot.getConfidence() > 0 && endPoint > 0) {
                state.point(run.getPointId()).setState(2);
                state.point(endPoint).setState(2);
            }
            player.save();
        }
        emitSave(player, chapter, ending);
        notifyState(player);
        return 0;
    }

    private static void emitSave(Player player, int chapter, int savePoint) {
        player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_MAIN_COOP_ENTER_SAVE_POINT, chapter, savePoint);
        player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_MAIN_COOP_ENTER_ANY_SAVE_POINT, chapter, savePoint);
        player.getQuestManager().queueEvent(QuestCond.QUEST_COND_MAIN_COOP_ENTER_SAVE_POINT, chapter, savePoint);
    }

    public static int cancel(Player player, int chapter) {
        var state = player.getHangouts();
        synchronized (state) {
            if (state.getRun() == null || state.getRun().getChapterId() != chapter) return INVALID;
            state.setRun(null);
            clearSeries(player, chapter);
            player.save();
        }
        notifyState(player);
        return 0;
    }

    public static void onQuestStarted(GameQuest quest) {
        int chapter = CoopCatalog.chapterForQuest(quest.getMainQuestId());
        if (chapter == 0 || !running(quest.getOwner(), chapter)) return;
        var player = quest.getOwner();
        var state = player.getHangouts();
        synchronized (state) {
            var run = state.getRun();
            var current = GameData.getCoopPointDataMap().get(run.getPointId());
            var next = GameData.getCoopPointDataMap().values().stream()
                    .filter(point -> point.getChapterId() == chapter && point.getAcceptQuest() == quest.getSubQuestId())
                    .findFirst().orElse(null);
            if (next != null && current != null && next.getId() != current.getId()
                    && current.getPostPointList() != null
                    && Arrays.stream(current.getPostPointList()).anyMatch(id -> id == next.getId())) {
                state.point(current.getId()).setState(2);
                var progress = state.point(next.getId());
                if (progress.getState() == 0) progress.setState(1);
                progress.setStart(run.getCurrent().copy());
                run.setPointId(next.getId());
            }
            var previous = run.getQuestStarts().get(quest.getSubQuestId());
            if (previous != null) {
                var restored = previous.copy();
                for (int key : CoopCatalog.chapter(chapter).retainedTempVars())
                    if (run.getCurrent().getTempVars().containsKey(key))
                        restored.getTempVars().put(key, run.getCurrent().getTempVars().get(key));
                run.setCurrent(restored);
            }
            else run.getQuestStarts().put(quest.getSubQuestId(), run.getCurrent().copy());
            player.save();
        }
        CoopDungeonRuntime.onQuestStarted(quest);
        CoopProgression.onQuestStarted(quest);
        notifyState(player);
    }

    public static boolean mayAccept(Player player, QuestData data) {
        return CoopProgression.mayAccept(player, data);
    }

    public static boolean preserveOnLogin(GameMainQuest main) {
        int chapter = CoopCatalog.chapterForQuest(main.getParentQuestId());
        if (chapter == 103201 && (main.getParentQuestId() == 19044 || main.getParentQuestId() == 19045))
            return CoopDungeonRuntime.recoverOnLogin(main);
        var state = main.getOwner().getHangouts();
        var run = state == null ? null : state.getRun();
        return chapter > 0 && run != null && run.getChapterId() == chapter
                && (!main.getActiveQuests().isEmpty() || !run.getPendingStageIds().isEmpty()
                        || !run.getPendingPointIds().isEmpty());
    }

    private static void clearSeries(Player player, int chapter) {
        CoopDungeonRuntime.clear(player, chapter);
        var manager = player.getQuestManager();
        for (var main : List.copyOf(manager.getMainQuests().values())) {
            if (CoopCatalog.chapterForQuest(main.getParentQuestId()) != chapter) continue;
            for (var quest : main.getChildQuests().values()) {
                manager.getAcceptProgressLists().remove(quest.getSubQuestId());
                if (quest.getState() != QuestState.QUEST_STATE_UNSTARTED)
                    player.sendPacket(new PacketDelQuestNotify(quest.getSubQuestId()));
            }
            manager.getMainQuests().remove(main.getParentQuestId());
            main.delete();
        }
    }

    public static boolean matchesRule(Player player, CoopCatalog.Rule rule) {
        int[] p = rule.args();
        return switch (rule.type()) {
            case "COOP_COND_NONE" -> true;
            case "COOP_COND_FINISH_QUEST" -> p.length >= 1 && player.getQuestManager().getQuestById(p[0]) != null
                    && player.getQuestManager().getQuestById(p[0]).getState() == QuestState.QUEST_STATE_FINISHED;
            case "COOP_COND_PLAYER_LEVEL" -> p.length >= 1 && player.getLevel() >= p[0];
            case "COOP_COND_COOP_POINT_FINISH" -> p.length >= 1 && player.getHangouts().getPoints().containsKey(p[0])
                    && player.getHangouts().getPoints().get(p[0]).getState() == 2;
            case "COOP_COND_CHAPTER_END_FINISH_COUNT" -> p.length >= 2 && finishedEndCount(player, p[0]) >= p[1];
            case "COOP_COND_CHAPTER_END_ALL_FINISH" -> p.length >= 1 && totalEndCount(p[0]) > 0
                    && finishedEndCount(player, p[0]) == totalEndCount(p[0]);
            default -> false;
        };
    }

    private static boolean chapterConditions(Player player, int id) {
        var chapter = GameData.getCoopChapterDataMap().get(id);
        return chapter != null && (chapter.getUnlockCond() == null || chapter.getUnlockCond().stream()
                .allMatch(rule -> matchesRule(player, new CoopCatalog.Rule(rule.getType(), rule.getArgs()))));
    }

    private static int totalEndCount(int chapter) {
        return (int) GameData.getCoopPointDataMap().values().stream()
                .filter(point -> point.getChapterId() == chapter && "POINT_END".equals(point.getType())).count();
    }

    private static int finishedEndCount(Player player, int chapter) {
        return (int) GameData.getCoopPointDataMap().values().stream()
                .filter(point -> point.getChapterId() == chapter && "POINT_END".equals(point.getType()))
                .filter(point -> player.getHangouts().getPoints().containsKey(point.getId())
                        && player.getHangouts().getPoints().get(point.getId()).getState() == 2).count();
    }

    public static int takeReward(Player player, int rewardId) {
        var config = CoopCatalog.rewards().stream().filter(row -> row.id() == rewardId).findFirst().orElse(null);
        if (config == null || !player.getHangouts().getAcceptedChapters().contains(config.chapterId())
                || !config.conditions().stream().allMatch(rule -> matchesRule(player, rule))) return INVALID;
        var reward = GameData.getRewardDataMap().get(config.rewardId());
        if (reward == null) return INVALID;
        var state = player.getHangouts();
        var result = player.getInventory().addItems(reward.getAllRewardItems().stream()
                        .map(item -> new GameItem(item.getId(), item.getCount())).toList(), ActionReason.QuestReward,
                InventoryAddPolicy.ALL_OR_NOTHING, () -> !state.getTakenRewards().contains(rewardId), () -> {
                    state.getTakenRewards().add(rewardId);
                    player.save();
                });
        if (!result.allAccepted()) return INVALID;
        notifyState(player);
        return 0;
    }

    public static int viewed(Player player, int chapter) {
        if (!player.getHangouts().getAcceptedChapters().contains(chapter)) return CLOSED;
        if (!player.getHangouts().getViewedChapters().add(chapter)) return INVALID;
        player.save();
        return 0;
    }

    public static int interaction(Player player, int chapter, int interaction) {
        var config = CoopCatalog.chapter(chapter);
        if (config == null || player.isInMultiplayer() || !running(player, chapter)
                || !config.interactionSaves().containsKey(interaction)) return INVALID;
        var source = player.getQuestManager().getQuestById(interaction);
        return source != null && (source.getState() == QuestState.QUEST_STATE_UNFINISHED
                || source.getState() == QuestState.QUEST_STATE_FINISHED) ? 0 : INVALID;
    }

    public static CoopDataNotify toProto(Player player) {
        var state = player.getHangouts();
        var result = CoopDataNotify.newBuilder().addAllViewedChapterList(state.getViewedChapters());
        var run = state.getRun();
        if (run != null && run.getCurrent().getStatus() == 1)
            result.setIsHaveProgress(true).setCurCoopPoint(run.getPointId());
        GameData.getCoopChapterDataMap().values().stream().sorted(Comparator.comparingInt(c -> c.getId()))
                .forEach(config -> {
                    int id = config.getId();
                    var chapter = CoopChapter.newBuilder().setId(id).setStateValue(
                            state.getAcceptedChapters().contains(id) ? 3 : chapterConditions(player, id) ? 2 : 1)
                            .setTotalEndCount(totalEndCount(id)).setFinishedEndCount(finishedEndCount(player, id));
                    chapter.putAllSeenEndingMap(state.endings(id));
                    GameData.getCoopPointDataMap().values().stream().filter(p -> p.getChapterId() == id)
                            .sorted(Comparator.comparingInt(p -> p.getId())).forEach(point -> {
                                var saved = state.getPoints().get(point.getId());
                                var value = CoopPoint.newBuilder().setId(point.getId());
                                if (saved != null) {
                                    value.setStateValue(saved.getState());
                                    if (saved.getStart() != null) value.setSelfConfidence(saved.getStart().getConfidence());
                                }
                                if (CoopProgression.isPendingPoint(player, point.getId())) value.setStateValue(1);
                                chapter.addCoopPointList(value);
                            });
                    for (var reward : CoopCatalog.rewards()) if (reward.chapterId() == id)
                        chapter.addCoopRewardList(CoopReward.newBuilder().setId(reward.id()).setStateValue(
                                state.getTakenRewards().contains(reward.id()) ? 2
                                        : reward.conditions().stream().allMatch(rule -> matchesRule(player, rule)) ? 0 : 1));
                    for (var cg : CoopCatalog.cgs()) if (cg.chapterId() == id)
                        chapter.addCoopCgList(CoopCg.newBuilder().setId(cg.id()).setIsUnlock(
                                state.getPoints().containsKey(cg.pointId())
                                        && state.getPoints().get(cg.pointId()).getState() == 2
                                        && cg.conditions().stream().allMatch(rule -> matchesRule(player, rule))));
                    result.addChapterList(chapter);
                });
        return result.build();
    }

    public static void notifyState(Player player) {
        player.sendPacket(new PacketCoopDataNotify(player));
        var run = player.getHangouts().getRun();
        if (run != null) {
            var mainPacket = new BasePacket(PacketOpcodes.MainCoopUpdateNotify);
            mainPacket.setData(MainCoopUpdateNotify.newBuilder().addMainCoopList(
                    run.getCurrent().toProto(player.getHangouts().endings(run.getChapterId()))));
            player.sendPacket(mainPacket);
        }
        var progress = CoopProgressUpdateNotify.newBuilder();
        if (run != null && run.getCurrent().getStatus() == 1)
            progress.setCurCoopPoint(run.getPointId()).setIsHaveProgress(true);
        var progressPacket = new BasePacket(PacketOpcodes.CoopProgressUpdateNotify);
        progressPacket.setData(progress);
        player.sendPacket(progressPacket);
    }
}
