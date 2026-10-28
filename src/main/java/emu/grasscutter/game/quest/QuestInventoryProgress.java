package emu.grasscutter.game.quest;

import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.quest.QuestData.QuestContentCondition;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.enums.QuestContent;
import emu.grasscutter.game.quest.enums.QuestState;
import emu.grasscutter.server.packet.send.PacketQuestProgressUpdateNotify;
import java.util.*;
import java.util.stream.Collectors;

/** Native OBTAIN_VARIOUS_ITEM tracks this quest round's accepted item quantities, including losses. */
public final class QuestInventoryProgress {
    private QuestInventoryProgress() {}

    /** Login resumes accepted quantities; only an explicit quest rewind starts a new round. */
    static boolean preserveOnLogin(GameMainQuest main) {
        return main.getActiveQuests().stream().anyMatch(quest -> quest.hasObtainedVariousItemRound()
                && quest.getQuestData() != null
                && java.util.stream.Stream.concat(quest.getQuestData().getFinishCond().stream(),
                        quest.getQuestData().getFailCond().stream())
                        .anyMatch(condition -> condition.getType() == QuestContent.QUEST_CONTENT_OBTAIN_VARIOUS_ITEM));
    }

    private static SortedSet<Integer> itemIds(QuestContentCondition condition) {
        if (condition.getType() != QuestContent.QUEST_CONTENT_OBTAIN_VARIOUS_ITEM
                || condition.getParamStr() == null || condition.getParamStr().isBlank()) return null;
        var ids = new TreeSet<Integer>();
        try {
            for (var value : condition.getParamStr().split(",", -1)) {
                int id = Integer.parseInt(value.trim());
                if (id <= 0 || !GameData.getItemDataMap().containsKey(id)) return null;
                ids.add(id);
            }
        } catch (NumberFormatException error) { return null; }
        return ids;
    }

    private static String key(Set<Integer> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    public static int currentCount(GameQuest quest, QuestContentCondition condition) {
        var ids = itemIds(condition);
        return ids == null ? 0 : quest.getObtainedVariousItemCount(key(ids));
    }

    public static boolean matchesCurrent(GameQuest quest, QuestContentCondition condition) {
        return currentCount(quest, condition) >= Math.max(1, condition.getCount());
    }

    /** Called only after an inventory mutation has been accepted, using that mutation's real quantity. */
    public static void onItemQuantityChanged(Player player, int itemId, int quantityDelta) {
        if (itemId <= 0 || quantityDelta == 0) return;
        boolean changedAny = false;
        for (var main : player.getQuestManager().getActiveMainQuests()) {
            for (var quest : main.getChildQuests().values()) {
                if (quest.getState() != QuestState.QUEST_STATE_UNFINISHED || quest.getQuestData() == null) continue;
                var keys = new HashSet<String>();
                java.util.stream.Stream.concat(quest.getQuestData().getFinishCond().stream(),
                                quest.getQuestData().getFailCond().stream())
                        .filter(c -> c.getType() == QuestContent.QUEST_CONTENT_OBTAIN_VARIOUS_ITEM)
                        .forEach(c -> {
                            var ids = itemIds(c);
                            if (ids != null && ids.contains(itemId)) keys.add(key(ids));
                        });
                boolean changed = false;
                synchronized (quest) {
                    if (quest.getState() != QuestState.QUEST_STATE_UNFINISHED) continue;
                    for (var key : keys) changed |= quest.changeObtainedVariousItemCount(key, quantityDelta);
                }
                if (changed) {
                    quest.save();
                    player.sendPacket(new PacketQuestProgressUpdateNotify(quest));
                    changedAny = true;
                }
            }
        }
        if (changedAny) player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_OBTAIN_VARIOUS_ITEM, itemId);
    }

    /** Wire progress is a quantity; the quest engine retains its existing boolean condition slots. */
    static int[] forNotify(GameQuest quest, List<QuestContentCondition> conditions, int[] progress) {
        if (progress == null || conditions == null) return progress;
        int[] result = progress.clone();
        for (int index = 0; index < Math.min(result.length, conditions.size()); index++) {
            if (conditions.get(index).getType() == QuestContent.QUEST_CONTENT_OBTAIN_VARIOUS_ITEM)
                result[index] = currentCount(quest, conditions.get(index));
        }
        return result;
    }
}
