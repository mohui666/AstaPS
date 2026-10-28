package emu.grasscutter.game.quest.world;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.entity.EntityGadget;
import emu.grasscutter.game.inventory.GameItem;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.enums.QuestContent;
import emu.grasscutter.game.quest.enums.QuestState;
import java.util.*;
import java.util.stream.Collectors;

/** Receipts for the actual food produced during the current quest attempt. */
public final class QuestCookingProgress {
    private static final int GROUP = 133601030;
    private static final int CONFIG = 30001;
    private static final int GADGET = 73076009;
    private static final Set<Integer> QUESTS = Set.of(
            7603301, 7603303, 7603305, 7603307, 7603311, 7605905, 7605906);

    private QuestCookingProgress() {}

    public record WaitingAttempt(int groupId, int configId, int subQuestId, int startSequence) {}

    /** Freeze every recipient before inserting the first output or queuing any cooking event. */
    public static List<WaitingAttempt> captureWaitingAttempts(Player player) {
        if (!atStoryCooker(player)) return List.of();
        var waiting = new ArrayList<WaitingAttempt>();
        player.getQuestManager().forEachActiveQuest(quest -> {
            if (quest.getState() != QuestState.QUEST_STATE_UNFINISHED
                    || !QUESTS.contains(quest.getSubQuestId())) return;
            var data = quest.getQuestData();
            if (java.util.stream.Stream.concat(data.getFinishCond().stream(), data.getFailCond().stream())
                    .noneMatch(QuestCookingProgress::storyCondition)) return;
            waiting.add(new WaitingAttempt(GROUP, CONFIG, quest.getSubQuestId(), quest.getStartSequence()));
        });
        return List.copyOf(waiting);
    }

    /** Called only for accepted outputs; output callbacks cannot add recipients to the frozen batch. */
    public static void onAcceptedCook(Player player, List<WaitingAttempt> waiting,
            Collection<GameItem> outputs) {
        if (waiting.isEmpty() || outputs == null) return;
        String dishes = outputs.stream().filter(Objects::nonNull)
                .filter(item -> item.getCount() > 0 && item.getItemId() > 0)
                .map(GameItem::getItemId).distinct().sorted().map(String::valueOf)
                .collect(Collectors.joining(","));
        if (dishes.isEmpty()) return;
        for (var attempt : waiting)
            player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_FINISH_ANY_FOOD_COOK,
                    dishes, attempt.groupId(), attempt.configId(), attempt.subQuestId(), attempt.startSequence());
    }

    public static boolean matches(GameQuest quest, QuestData.QuestContentCondition condition,
            String cookedDishes, int... receipt) {
        if (!QUESTS.contains(quest.getSubQuestId()) || !storyCondition(condition)
                || receipt.length != 4 || receipt[0] != GROUP || receipt[1] != CONFIG
                || receipt[2] != quest.getSubQuestId() || receipt[3] != quest.getStartSequence()) return false;
        var dishes = ids(cookedDishes);
        if (dishes.isEmpty()) return false;
        String configured = condition.getParamStr();
        if (configured == null || configured.isBlank()) return true;
        var allowed = ids(configured);
        return !allowed.isEmpty() && dishes.stream().anyMatch(allowed::contains);
    }

    private static boolean storyCondition(QuestData.QuestContentCondition condition) {
        var target = condition.getParam();
        return condition.getType() == QuestContent.QUEST_CONTENT_FINISH_ANY_FOOD_COOK
                && target != null && target.length >= 2 && target[0] == GROUP && target[1] == CONFIG;
    }

    private static Set<Integer> ids(String text) {
        if (text == null || text.isBlank()) return Set.of();
        var result = new HashSet<Integer>();
        try {
            for (String part : text.split(",", -1)) {
                int id = Integer.parseInt(part.trim());
                if (id <= 0) return Set.of();
                result.add(id);
            }
        } catch (NumberFormatException error) { return Set.of(); }
        return result;
    }

    private static boolean atStoryCooker(Player player) {
        var scene = player.getScene();
        if (scene == null || player.getSceneId() != 3 || scene.getId() != 3
                || player.getSceneLoadState() != Player.SceneLoadState.LOADED) return false;
        // Reconstructed context for these seven native quests only: the two opaque 7.1 CookReq
        // fields are not proven group/config IDs. Require the actual loaded cooker within 4 m.
        return scene.getEntities().values().stream().anyMatch(entity -> entity instanceof EntityGadget gadget
                && gadget.getGroupId() == GROUP && gadget.getConfigId() == CONFIG
                && gadget.getGadgetId() == GADGET
                && player.getPosition().computeDistance(gadget.getPosition()) <= 4.0);
    }
}
