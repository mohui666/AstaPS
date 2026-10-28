package emu.grasscutter.game.quest.check;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.*;
import emu.grasscutter.net.proto.RetcodeOuterClass.Retcode;
import emu.grasscutter.utils.Utils;
import java.util.*;
import java.util.function.IntUnaryOperator;

/** Server-owned D20 reconstruction; the native request supplies IDs, never its own roll/result. */
public final class QuestCheckRuntime {
    public record Response(int parent, int check, int retcode, QuestCheckState.Result result) {}
    private QuestCheckRuntime() {}

    public static Response roll(Player player, int parentId, int checkId) {
        return roll(player, parentId, checkId, faces -> Utils.random.nextInt(1, faces + 1));
    }

    static Response roll(Player player, int parentId, int checkId, IntUnaryOperator dice) {
        var check = QuestCheckCatalog.installed().check(checkId);
        var main = player.getQuestManager().getMainQuestById(parentId);
        var source = check == null || main == null ? null : main.getChildQuestById(check.dialogue());
        if (check == null || check.parent() != parentId || main.isFinished() || source == null
                || source.getStartTime() <= 0 || player.getWorld() == null
                || player.getWorld().getHost() != player || player.getWorld().getPlayers().size() != 1
                || player.getSceneLoadState() != Player.SceneLoadState.LOADED
                || player.getSceneId() != check.sceneId() || player.getScene() == null
                || player.getScene().getId() != check.sceneId()
                || player.getScene().getWorld() != player.getWorld()
                || !Double.isFinite(player.getPosition().computeDistance(check.position()))
                || player.getPosition().computeDistance(check.position()) > check.radius())
            return rejected(parentId, checkId);
        var state = player.getQuestCheckState();
        synchronized (state) {
            var existing = state.get(checkId);
            if (valid(existing, check, source)
                    && (source.getState() == QuestState.QUEST_STATE_UNFINISHED
                            || source.getState() == QuestState.QUEST_STATE_FINISHED))
                return new Response(parentId, checkId, 0, existing);
            if (source.getState() != QuestState.QUEST_STATE_UNFINISHED) return rejected(parentId, checkId);
            int rolled = dice.applyAsInt(check.faces());
            if (rolled <= 0 || rolled > check.faces()) throw new IllegalStateException("Invalid server dice roll");
            var bonuses = check.bonuses().stream().filter(bonus -> bonus.applies(player)).toList();
            var result = new QuestCheckState.Result(check, source.getStartTime(), rolled,
                    rolled + bonuses.stream().mapToInt(QuestCheckCatalog.Bonus::amount).sum(),
                    bonuses.stream().map(QuestCheckCatalog.Bonus::id).toList());
            state.put(result);
            player.save();
            player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_QUEST_CHECK_EQUAL, checkId);
            return new Response(parentId, checkId, 0, result);
        }
    }

    private static Response rejected(int parent, int check) {
        return new Response(parent, check, Retcode.RET_FAIL_VALUE, null);
    }

    private static boolean valid(QuestCheckState.Result result, QuestCheckCatalog.Check check, GameQuest source) {
        return result != null && result.getCheckId() == check.id() && result.getParentQuestId() == check.parent()
                && result.getDialogueQuestId() == check.dialogue() && result.getDialogueStartTime() == source.getStartTime()
                && result.getDieRoll() > 0 && result.getDieRoll() <= check.faces();
    }

    public static boolean matchesCurrent(GameQuest quest, QuestData.QuestContentCondition condition) {
        int[] params = condition.getParam();
        if (params == null || params.length < 2 || params[1] < 0 || params[1] > 1) return false;
        var check = QuestCheckCatalog.installed().check(params[0]);
        if (check == null || quest.getSubQuestId() != check.outcome()
                || quest.getMainQuestId() != check.parent()) return false;
        var source = quest.getMainQuest().getChildQuestById(check.dialogue());
        if (source == null) return false;
        var result = quest.getOwner().getQuestCheckState().get(check.id());
        return valid(result, check, source) && result.isSuccess() == (params[1] == 1);
    }

    public static void onQuestStart(GameQuest quest) {
        if (QuestCheckCatalog.installed().checks().stream().noneMatch(check -> check.dialogue() == quest.getSubQuestId()))
            return;
        if (quest.getOwner().getQuestCheckState().clearForDialogue(quest.getSubQuestId())) quest.getOwner().save();
    }

    public static boolean preserveOnLogin(GameMainQuest main) {
        if (main.getParentQuestId() != 19182 || main.isFinished()) return false;
        for (var check : QuestCheckCatalog.installed().checks()) {
            var source = main.getChildQuestById(check.dialogue());
            if (source != null && valid(main.getOwner().getQuestCheckState().get(check.id()), check, source)
                    && (source.getState() == QuestState.QUEST_STATE_UNFINISHED
                            || source.getState() == QuestState.QUEST_STATE_FINISHED)) return true;
        }
        return false;
    }
}
