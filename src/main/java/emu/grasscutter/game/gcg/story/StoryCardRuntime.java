package emu.grasscutter.game.gcg.story;

import emu.grasscutter.GameConstants;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.entity.gadget.GadgetWorktop;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.*;
import emu.grasscutter.game.world.*;
import emu.grasscutter.net.proto.VisionTypeOuterClass.VisionType;
import emu.grasscutter.server.packet.send.*;
import emu.grasscutter.utils.Utils;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Explicit story-only replacement: real persisted card actions, not a native GCG settlement report. */
public final class StoryCardRuntime {
    public static final int OPTION = 7;
    private static final int OPERATOR_GADGET = 70350081;
    private static final Map<Integer, Board> BOARDS = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> PREFERRED_LEVEL = new ConcurrentHashMap<>();
    private static final Map<Integer, Player> RESTORED_PLAYERS = new ConcurrentHashMap<>();
    private record Goal(StoryCardCatalog.Stage stage, GameQuest quest, int level, Position anchor) {}
    private static final class Board {
        final Player player;
        final Scene scene;
        final Goal goal;
        final Map<Integer, EntityGadget> actors = new ConcurrentHashMap<>();
        Board(Player player, Goal goal) { this.player = player; this.scene = player.getScene(); this.goal = goal; }
    }
    private StoryCardRuntime() {}

    private static boolean ready(Player player) {
        var scene = player.getScene();
        return player.getWorld() != null && player.getWorld().getHost() == player
                && player.getWorld().getPlayers().size() == 1 && scene != null
                && scene.getWorld() == player.getWorld() && scene.getId() == player.getSceneId()
                && player.getSceneLoadState() == Player.SceneLoadState.LOADED;
    }

    private static GameQuest active(Player player, StoryCardCatalog.Stage stage) {
        var quest = player.getQuestManager().getQuestById(stage.quest());
        if (quest == null || quest.getState() != QuestState.QUEST_STATE_UNFINISHED || quest.getStartTime() <= 0
                || quest.getMainQuest().isFinished() || quest.getQuestData() == null) return null;
        if (stage.gate() != 0) {
            var gate = player.getQuestManager().getQuestById(stage.gate());
            if (gate == null || gate.getState() != QuestState.QUEST_STATE_UNFINISHED) return null;
        }
        // The replacement may only settle original GCG goals, never a neighbouring dialogue.
        var conditions = quest.getQuestData().getFinishCond();
        if (conditions == null || conditions.stream().noneMatch(condition -> stage.tutorial()
                ? condition.getType() == QuestContent.QUEST_CONTENT_GCG_GUIDE_PROGRESS
                        && condition.getParam() != null && condition.getParam().length >= 1
                        && condition.getParam()[0] == 1 && condition.getCount() == 4
                : condition.getType() == QuestContent.QUEST_CONTENT_GCG_LEVEL_WIN
                        && condition.getParam() != null && condition.getParam().length >= 2
                        && stage.levels().contains(condition.getParam()[0])
                        && (condition.getParam()[1] == 1 || condition.getParam()[1] == 2))) return null;
        return quest;
    }

    private static boolean hasProof(Player player, GameQuest quest) {
        return quest.getQuestData().getFinishCond().stream().filter(condition ->
                condition.getType() == QuestContent.QUEST_CONTENT_GCG_LEVEL_WIN
                        || condition.getType() == QuestContent.QUEST_CONTENT_GCG_GUIDE_PROGRESS)
                .anyMatch(condition -> matchesCurrent(quest, condition));
    }

    private static Goal goal(Player player, StoryCardCatalog catalog, Integer requested, boolean requireNearby) {
        var candidates = new ArrayList<Goal>();
        var saved = player.getStoryCards().getDuel();
        for (var stage : catalog.stages()) {
            if (stage.scene() != player.getSceneId()) continue;
            var quest = active(player, stage);
            if (quest == null || !stage.tutorial() && hasProof(player, quest)) continue;
            int level;
            if (stage.tutorial()) {
                int progress = player.getStoryCards().getTutorialProgress();
                if (progress < 0 || progress >= catalog.tutorialLevels().size()) continue;
                level = catalog.tutorialLevels().get(progress);
                if (requested != null && requested != level) continue;
            } else {
                if (requested != null && !stage.levels().contains(requested)) continue;
                level = requested == null ? stage.levels().getFirst() : requested;
            }
            var anchor = stage.anchors().stream().min(Comparator.comparingDouble(point ->
                    player.getPosition().computeDistance(point))).orElseThrow();
            double distance = player.getPosition().computeDistance(anchor);
            if (!Double.isFinite(distance) || requireNearby && distance > 40) continue;
            if (saved != null && saved.getOutcome() == 0 && saved.getQuestId() == stage.quest()
                    && saved.getQuestStart() == quest.getStartTime() && stage.anchors().stream().anyMatch(point ->
                            saved.getAnchor() != null && point.computeDistance(saved.getAnchor()) < .01))
                level = saved.getLevelId();
            candidates.add(new Goal(stage, quest, level, anchor));
        }
        return candidates.stream().min(Comparator
                .comparingInt((Goal value) -> saved != null && saved.getOutcome() == 0
                        && saved.getQuestId() == value.stage.quest() && saved.getQuestStart() == value.quest.getStartTime() ? -1 : value.stage.priority())
                .thenComparingInt(value -> value.stage.quest())).orElse(null);
    }

    public static void onTick(Player player) {
        var existing = BOARDS.get(player.getUid());
        if (!ready(player)) { retire(player.getUid(), existing); return; }
        if (StoryCardCatalog.PARENTS.stream().noneMatch(id -> player.getQuestManager().getMainQuestById(id) != null)) return;
        synchronized (player.getStoryCards()) {
            var catalog = StoryCardCatalog.installed();
            // Once per login, including a finished duel whose pending quest event left no playable table.
            if (RESTORED_PLAYERS.put(player.getUid(), player) != player) replaySavedEvents(player);
            var goal = goal(player, catalog, PREFERRED_LEVEL.get(player.getUid()), true);
            if (goal == null) goal = goal(player, catalog, null, true);
            if (goal == null) { retire(player.getUid(), existing); return; }
            if (existing != null && existing.player == player && existing.scene == player.getScene()
                    && existing.goal.quest == goal.quest && existing.goal.quest.getStartTime() == goal.quest.getStartTime()
                    && existing.goal.level == goal.level && existing.goal.anchor.computeDistance(goal.anchor) < .01) return;
            retire(player.getUid(), existing);
            var board = new Board(player, goal);
            BOARDS.put(player.getUid(), board);
            rebuild(board);
            tell(player, "【AstaPS重建卡牌试炼】" + catalog.notice());
            show(board);
        }
    }

    /** Native launch requests select an existing story goal; starting and every action require its actual worktop. */
    public static boolean offerLevel(Player player, int level) {
        if (!ready(player) || StoryCardCatalog.installed().level(level) == null) return false;
        synchronized (player.getStoryCards()) {
            var goal = goal(player, StoryCardCatalog.installed(), level, false);
            if (goal == null) return false;
            PREFERRED_LEVEL.put(player.getUid(), level);
            onTick(player);
            tell(player, "原生七圣对局界面尚未恢复。请使用剧情目标旁的重建卡桌；位置 "
                    + String.format(Locale.ROOT, "(%.1f, %.1f, %.1f)", goal.anchor.getX(), goal.anchor.getY(), goal.anchor.getZ())
                    + "，场景 " + goal.stage.scene() + "。中央装置开局/查看，北侧五台按左至右出牌，东侧翻页，南侧结束回合，西侧认输。");
            return true;
        }
    }

    /** Owned entities are consumed even when access is denied, including attempts from a visiting player. */
    public static boolean tryInteract(Player player, GameEntity entity, int option) {
        var board = BOARDS.get(player.getUid());
        if (board != null && board.actors.containsValue(entity)) {
            synchronized (player.getStoryCards()) { interact(board, player, entity, option); }
            return true;
        }
        var host = player.getWorld() == null ? null : player.getWorld().getHost();
        var hostBoard = host == null ? null : BOARDS.get(host.getUid());
        return hostBoard != null && hostBoard.actors.containsValue(entity);
    }

    private static void interact(Board board, Player player, GameEntity entity, int option) {
        if (board.player != player || !ready(player) || board.scene != player.getScene() || option != OPTION
                || BOARDS.get(player.getUid()) != board || active(player, board.goal.stage) != board.goal.quest
                || board.scene.getEntityById(entity.getId()) != entity || !(entity instanceof EntityGadget gadget)
                || !(gadget.getContent() instanceof GadgetWorktop worktop) || !worktop.getWorktopOptions().contains(option)
                || !Double.isFinite(player.getPosition().computeDistance(entity.getPosition()))
                || player.getPosition().computeDistance(entity.getPosition()) > 4) return;
        int slot = board.actors.entrySet().stream().filter(entry -> entry.getValue() == entity)
                .mapToInt(Map.Entry::getKey).findFirst().orElse(-1);
        var state = player.getStoryCards(); var game = current(board);
        if (slot == 0) {
            if (game == null || game.getOutcome() != 0) {
                var expected = goal(player, StoryCardCatalog.installed(), board.goal.level, true);
                if (expected == null || expected.quest != board.goal.quest) return;
                game = StoryCardDuel.create(StoryCardCatalog.installed(), board.goal.stage,
                        board.goal.quest.getStartTime(), board.goal.level, board.goal.anchor, Utils.random);
                state.setDuel(game); player.save(); rebuild(board);
            }
            show(board); return;
        }
        if (game == null || game.getOutcome() != 0) return;
        boolean changed;
        if (slot >= 1 && slot <= 5) changed = StoryCardDuel.play(StoryCardCatalog.installed(), game, game.getPage() * 5 + slot - 1);
        else if (slot == 6) {
            game.setPage((game.getPage() + 1) % Math.max(1, (game.getHand().size() + 4) / 5)); changed = true;
        } else if (slot == 7) changed = StoryCardDuel.endRound(StoryCardCatalog.installed(), game);
        else if (slot == 8) { game.setPlayerHp(0); game.setOutcome(2); changed = true; }
        else return;
        if (!changed) { tell(player, "此牌暂时不能使用：请检查行动点；满生命时不能使用治疗牌。"); return; }
        if (game.getOutcome() != 0) settle(board, game);
        else player.save();
        // New entity IDs invalidate replayed input against the previous hand/turn.
        rebuild(board); show(board);
    }

    private static StoryCardState.Duel current(Board board) {
        var game = board.player.getStoryCards().getDuel();
        return game != null && game.getQuestId() == board.goal.stage.quest()
                && game.getQuestStart() == board.goal.quest.getStartTime() && game.getLevelId() == board.goal.level ? game : null;
    }

    private static void settle(Board board, StoryCardState.Duel game) {
        if (game.isEmitted()) return;
        var player = board.player; var state = player.getStoryCards(); var catalog = StoryCardCatalog.installed();
        if (game.getOutcome() != 1 && game.getOutcome() != 2) throw new IllegalStateException("Invalid reconstructed card outcome");
        for (var stage : catalog.stages()) {
            var quest = active(player, stage);
            if (quest != null && stage.levels().contains(game.getLevelId()))
                state.record(quest.getSubQuestId(), quest.getStartTime(), game.getLevelId(), game.getOutcome());
        }
        boolean guideAdvanced = game.isTutorial() && game.getOutcome() == 1
                && state.getTutorialProgress() < catalog.tutorialLevels().size()
                && catalog.tutorialLevels().get(state.getTutorialProgress()) == game.getLevelId();
        if (guideAdvanced) state.advanceTutorial();
        game.setEmitted(true);
        player.save();
        player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_GCG_LEVEL_WIN, game.getLevelId(), game.getOutcome());
        if (guideAdvanced) player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_GCG_GUIDE_PROGRESS, 1, 0);
    }

    private static void replaySavedEvents(Player player) {
        var state = player.getStoryCards();
        if (state.getTutorialProgress() > 0) player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_GCG_GUIDE_PROGRESS, 1, 0);
        for (var stage : StoryCardCatalog.installed().stages()) {
            var quest = active(player, stage); if (quest == null) continue;
            for (int level : stage.levels()) for (int outcome = 1; outcome <= 2; outcome++)
                if (state.hasOutcome(quest.getSubQuestId(), quest.getStartTime(), level, outcome))
                    player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_GCG_LEVEL_WIN, level, outcome);
        }
    }

    public static boolean matchesCurrent(GameQuest quest, QuestData.QuestContentCondition condition) {
        if (!StoryCardCatalog.PARENTS.contains(quest.getMainQuestId())) return false;
        var stage = StoryCardCatalog.installed().stage(quest.getSubQuestId());
        int[] params = condition.getParam();
        if (stage == null || params == null || params.length < 2 || quest.getStartTime() <= 0) return false;
        var state = quest.getOwner().getStoryCards();
        return switch (condition.getType()) {
            case QUEST_CONTENT_GCG_LEVEL_WIN -> stage.levels().contains(params[0])
                    && (params[1] == 1 || params[1] == 2) && condition.getCount() >= 0 && condition.getCount() <= 1
                    && state.hasOutcome(quest.getSubQuestId(), quest.getStartTime(), params[0], params[1]);
            case QUEST_CONTENT_GCG_GUIDE_PROGRESS -> params[0] == 1 && params[1] == 0
                    && (stage.tutorial() || quest.getSubQuestId() == 7066519)
                    && condition.getCount() >= 1 && condition.getCount() <= 4
                    && state.getTutorialProgress() >= condition.getCount();
            default -> false;
        };
    }

    public static void onQuestStart(GameQuest quest) {
        if (!StoryCardCatalog.PARENTS.contains(quest.getMainQuestId())
                || StoryCardCatalog.installed().stage(quest.getSubQuestId()) == null) return;
        var player = quest.getOwner();
        synchronized (player.getStoryCards()) {
            if (player.getStoryCards().resetQuest(quest.getSubQuestId())) player.save();
            var board = BOARDS.get(player.getUid());
            if (board != null && board.goal.stage.quest() == quest.getSubQuestId()) retire(player.getUid(), board);
        }
    }

    public static boolean preserveOnLogin(GameMainQuest main) {
        if (!StoryCardCatalog.PARENTS.contains(main.getParentQuestId()) || main.isFinished()) return false;
        var state = main.getOwner().getStoryCards(); var game = state.getDuel();
        return main.getActiveQuests().stream().anyMatch(quest ->
                StoryCardCatalog.installed().stage(quest.getSubQuestId()) != null
                        && (game != null && game.getQuestId() == quest.getSubQuestId()
                                && game.getQuestStart() == quest.getStartTime() || hasProof(main.getOwner(), quest)
                                || quest.getSubQuestId() == 7066505 && state.getTutorialProgress() > 0));
    }

    public static void onLogout(Player player) {
        var board = BOARDS.get(player.getUid());
        if (board != null && board.player == player) retire(player.getUid(), board);
        PREFERRED_LEVEL.remove(player.getUid());
        RESTORED_PLAYERS.remove(player.getUid(), player);
    }

    private static void retire(int uid, Board board) {
        if (board == null) return;
        BOARDS.remove(uid, board); removeActors(board);
    }
    private static void removeActors(Board board) {
        for (var entity : board.actors.values()) if (board.scene.getEntityById(entity.getId()) == entity)
            board.scene.removeEntity(entity, VisionType.VisionType_VISION_REMOVE);
        board.actors.clear();
    }

    @SuppressWarnings("removal")
    private static void rebuild(Board board) {
        removeActors(board);
        var game = current(board);
        int last = game != null && game.getOutcome() == 0 ? 8 : 0;
        for (int slot = 0; slot <= last; slot++) {
            if (slot >= 1 && slot <= 5 && game.getPage() * 5 + slot - 1 >= game.getHand().size()) continue;
            var position = board.goal.anchor.clone();
            if (slot >= 1 && slot <= 5) position.addX((slot - 3) * 1.5f).addZ(2);
            else if (slot == 6) position.addX(4.5f);
            else if (slot == 7) position.addZ(-2);
            else if (slot == 8) position.addX(-4.5f);
            if (!GameData.getGadgetDataMap().containsKey(OPERATOR_GADGET))
                throw new IllegalStateException("Missing reconstructed card table operator gadget");
            var actor = new EntityGadget(board.scene, OPERATOR_GADGET, position, new Position());
            actor.setConfigId(900000 + slot);
            actor.setContent(new GadgetWorktop(actor));
            ((GadgetWorktop) actor.getContent()).addWorktopOptions(new int[] {OPTION});
            board.actors.put(slot, actor); board.scene.addEntity(actor);
            board.scene.broadcastPacket(new PacketWorktopOptionNotify(actor));
        }
    }

    private static void show(Board board) {
        var game = current(board); var player = board.player;
        if (game == null) { tell(player, "剧情卡牌关卡 " + board.goal.level + "：操作中央装置开局。北侧五台按 X 坐标从小到大为第1～5张手牌，东侧翻页，南侧结束回合，西侧认输。每回合8行动点；对手通常攻击3，每第3回合攻击5；第15回合后判负。"); return; }
        var text = new StringBuilder("【重建牌局 ").append(game.getLevelId()).append("】回合 ").append(game.getRound())
                .append("，行动点 ").append(game.getActionPoints()).append("，生命 ").append(game.getPlayerHp()).append('/').append(game.getPlayerMaxHp())
                .append("，护盾 ").append(game.getShield()).append("，对手生命 ").append(game.getOpponentHp()).append('/').append(game.getOpponentMaxHp());
        if (game.getOutcome() != 0) text.append(game.getOutcome() == 1 ? "。胜利。" : "。失败，可在中央重新开局。")
                .append("教程进度 ").append(player.getStoryCards().getTutorialProgress()).append("/4；胜利仅由服务端生命归零结算。");
        else {
            text.append("\n北侧手牌第 ").append(game.getPage() + 1).append(" 页（从小 X 到大 X）：");
            for (int index = game.getPage() * 5; index < Math.min(game.getHand().size(), game.getPage() * 5 + 5); index++) {
                var card = StoryCardCatalog.installed().card(game.getHand().get(index));
                text.append('\n').append(index % 5 + 1).append(' ').append(card.name()).append("：消耗").append(card.cost());
                if (card.damage() > 0) text.append("，伤害").append(card.damage());
                if (card.shield() > 0) text.append("，护盾").append(card.shield());
                if (card.heal() > 0) text.append("，治疗").append(card.heal());
            }
            text.append("\n东侧翻页，南侧结束回合，西侧认输；中央查看状态。");
        }
        tell(player, text.toString());
    }
    private static void tell(Player player, String message) {
        player.sendPacket(new PacketPrivateChatNotify(GameConstants.SERVER_CONSOLE_UID, player.getUid(), message));
    }
}
