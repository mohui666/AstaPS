package emu.grasscutter.game.quest;

import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.giving.GivingData;
import emu.grasscutter.data.excels.giving.GivingData.GiveMethod;
import emu.grasscutter.game.inventory.GameItem;
import emu.grasscutter.game.inventory.Inventory;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.enums.QuestCond;
import emu.grasscutter.game.quest.enums.QuestContent;
import emu.grasscutter.net.proto.ItemGivingReqOuterClass.ItemGivingReq;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Validates a native giving against its activation, resource recipe and owned item stacks. */
public final class QuestItemGivingService {
    private QuestItemGivingService() {}

    public record Result(boolean success, int givingId, int groupId) {
        public static Result failure(int givingId) { return new Result(false, givingId, 0); }
    }

    public static Result submit(Player player, ItemGivingReq request) {
        int givingId = request.getGivingId();
        var inventory = player.getInventory();
        // Inventory removals also lock this monitor. Validate every stack before taking any item.
        synchronized (inventory) {
            var record = player.getPlayerProgress().getItemGivings().get(givingId);
            var data = GameData.getGivingDataMap().get(givingId);
            if (record == null || data == null || data.getGiveType() != GivingData.GiveType.QUEST)
                return Result.failure(givingId);

            if (record.isFinished()) {
                // Report the persisted result, even when the consumed item no longer exists.
                return new Result(true, givingId, record.getLastGroupId());
            }

            var selection = resolve(inventory, request);
            if (selection == null) return Result.failure(givingId);
            int groupId = matchingGroup(data, selection.totals());
            if (groupId < 0) return Result.failure(givingId);

            if (data.isRemoveItem()) {
                for (var stack : selection.stacks().entrySet()) {
                    if (!inventory.removeItem(stack.getKey(), stack.getValue()))
                        throw new IllegalStateException("Validated giving stack could not be removed");
                }
            }

            record.setGivenItems(new HashMap<>(selection.totals()));
            record.setGroupId(groupId);
            record.setLastGroupId(groupId);
            player.getQuestManager().markCompleted(givingId);
            player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_FINISH_ITEM_GIVING, givingId, groupId);
            player.getQuestManager().queueEvent(QuestCond.QUEST_COND_ITEM_GIVING_FINISHED, givingId, groupId);
            return new Result(true, givingId, groupId);
        }
    }

    /** -1 means invalid; zero is a successful exact recipe; positive values are native group IDs. */
    static int matchingGroup(GivingData data, Map<Integer, Integer> totals) {
        if (totals.isEmpty() || totals.values().stream().anyMatch(count -> count <= 0)) return -1;
        var method = data.getGivingMethod();
        if (method == GiveMethod.GIVING_METHOD_EXACT || method == GiveMethod.GIVING_METHOD_FAST) {
            var required = new HashMap<Integer, Integer>();
            if (data.getExactItems() == null) return -1;
            for (var item : data.getExactItems()) {
                // Native fixed-size exactItems arrays contain empty padding entries.
                if (item.getItemId() == 0 && item.getCount() == 0) continue;
                if (item.getItemId() <= 0 || item.getCount() <= 0
                        || !addCount(required, item.getItemId(), item.getCount())) return -1;
            }
            return required.equals(totals) ? 0 : -1;
        }
        if (method != GiveMethod.GIVING_METHOD_GROUP && method != GiveMethod.GIVING_METHOD_VAGUE_GROUP)
            return -1;
        if (data.getGivingGroupIds() == null || data.getGivingGroupIds().isEmpty()
                || data.getGivingGroupCount() <= 0
                || totals.values().stream().mapToLong(Integer::longValue).sum() != data.getGivingGroupCount())
            return -1;

        var groups = data.getGivingGroupIds().stream()
                .map(id -> GameData.getGivingGroupDataMap().get(id.intValue()))
                .toList();
        if (groups.stream().anyMatch(group -> group == null || group.getItemIds() == null)) return -1;
        // GROUP also encodes alternative combinations as multisets, e.g. 1916105 has AAB/ABC/ABB.
        boolean combinations = method == GiveMethod.GIVING_METHOD_GROUP && groups.size() > 1
                && groups.stream().allMatch(group -> group.getItemIds().size() == data.getGivingGroupCount());
        for (var group : groups) {
            if (combinations) {
                var recipe = new HashMap<Integer, Integer>();
                for (int id : group.getItemIds()) recipe.merge(id, 1, Integer::sum);
                if (recipe.equals(totals)) return group.getId();
            } else if (group.getItemIds().containsAll(totals.keySet())) {
                return group.getId();
            }
        }
        return -1;
    }

    private record Selection(Map<Integer, Integer> totals, Map<GameItem, Integer> stacks) {}

    private static Selection resolve(Inventory inventory, ItemGivingReq request) {
        var itemTotals = new HashMap<Integer, Integer>();
        for (var item : request.getItemParamListList()) {
            if (item.getItemId() <= 0 || item.getCount() <= 0
                    || !addCount(itemTotals, item.getItemId(), item.getCount())) return null;
        }

        var guidTotals = new HashMap<Integer, Integer>();
        var stacks = new LinkedHashMap<GameItem, Integer>();
        for (var entry : request.getItemGuidCountMapMap().entrySet()) {
            var item = inventory.getItemByGuid(entry.getKey());
            int count = entry.getValue();
            if (item == null || count <= 0 || item.getCount() < count
                    || !addCount(guidTotals, item.getItemId(), count)) return null;
            stacks.put(item, count);
        }
        if (!itemTotals.isEmpty() && !guidTotals.isEmpty() && !itemTotals.equals(guidTotals)) return null;
        if (!guidTotals.isEmpty()) return new Selection(guidTotals, stacks);
        if (itemTotals.isEmpty()) return null;

        List<GameItem> owned = new ArrayList<>(inventory.getItems().values());
        owned.sort(Comparator.comparingLong(GameItem::getGuid));
        for (var entry : itemTotals.entrySet()) {
            int remaining = entry.getValue();
            for (var item : owned) {
                if (item.getItemId() != entry.getKey() || item.getCount() <= 0) continue;
                int count = Math.min(remaining, item.getCount());
                stacks.put(item, count);
                remaining -= count;
                if (remaining == 0) break;
            }
            if (remaining != 0) return null;
        }
        return new Selection(itemTotals, stacks);
    }

    private static boolean addCount(Map<Integer, Integer> totals, int itemId, int count) {
        long sum = (long) totals.getOrDefault(itemId, 0) + count;
        if (sum > Integer.MAX_VALUE) return false;
        totals.put(itemId, (int) sum);
        return true;
    }
}
