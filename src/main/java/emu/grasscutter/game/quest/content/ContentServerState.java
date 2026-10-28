package emu.grasscutter.game.quest.content;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.data.GameData;
import emu.grasscutter.game.home.HomeWorld;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.QuestSceneConditions;
import emu.grasscutter.game.quest.enums.QuestContent;
import java.util.function.IntPredicate;

/** Registered for predicates whose evidence is a server scene or saved quest state. */
public final class ContentServerState extends BaseContent {
    @Override
    public boolean execute(GameQuest quest, QuestData.QuestContentCondition condition,
            String paramStr, int... params) {
        var type = condition.getType();
        if (type == QuestContent.QUEST_CONTENT_QUEST_GLOBAL_VAR_EQUAL
                || type == QuestContent.QUEST_CONTENT_QUEST_GLOBAL_VAR_NOT_EQUAL
                || type == QuestContent.QUEST_CONTENT_QUEST_GLOBAL_VAR_GREATER
                || type == QuestContent.QUEST_CONTENT_QUEST_GLOBAL_VAR_LESS) {
            if (condition.getParam() == null || condition.getParam().length == 0
                    || params.length == 0 || params[0] != condition.getParam()[0]) return false;
        }
        return matchesCurrent(quest, condition);
    }

    public static boolean matchesCurrent(GameQuest quest, QuestData.QuestContentCondition condition) {
        var owner = quest.getOwner();
        int[] target = condition.getParam();
        if (target == null || target.length == 0) return false;
        return switch (condition.getType()) {
            case QUEST_CONTENT_PRESENT_AT_SPECIFIC_SCENE -> sceneReady(owner)
                    && target[0] > 0 && owner.getSceneId() == target[0];
            case QUEST_CONTENT_LEAVE_SCENE_RANGE, QUEST_CONTENT_LEAVE_SCENE_RANGE_AND_ROOM ->
                    sceneReady(owner) && QuestSceneConditions.outsideRange(owner.getSceneId(),
                            owner.getPosition(), target, condition.getParamStr(),
                            condition.getType() == QuestContent.QUEST_CONTENT_LEAVE_SCENE_RANGE_AND_ROOM);
            case QUEST_CONTENT_QUEST_GLOBAL_VAR_EQUAL, QUEST_CONTENT_QUEST_GLOBAL_VAR_NOT_EQUAL,
                    QUEST_CONTENT_QUEST_GLOBAL_VAR_GREATER, QUEST_CONTENT_QUEST_GLOBAL_VAR_LESS ->
                    target.length >= 2 && target[0] > 0
                            && compare(condition.getType(),
                                    owner.getQuestManager().getQuestGlobalVarValue(target[0]), target[1]);
            case QUEST_CONTENT_PARENT_QUEST_STATE_EQUAL -> {
                var parent = owner.getQuestManager().getMainQuestById(target[0]);
                yield target.length >= 2 && parent != null && parent.getState().getValue() == target[1];
            }
            case QUEST_CONTENT_EVENTS_ITEM_STATUS -> ContentEventsItemStatus.matchesCurrent(quest, condition);
            case QUEST_CONTENT_SCENE_LEVEL_TAG_EQ ->
                    emu.grasscutter.game.quest.QuestSceneTags.matchesCurrent(quest, target[0]);
            case QUEST_CONTENT_SPECIFIC_WIDGET_ATTACHED -> target[0] > 0 && owner.getWidgetId() == target[0];
            case QUEST_CONTENT_AVATAR_RENAME_COMPLETE -> ContentAvatarRenameComplete.matchesCurrent(quest, condition);
            case QUEST_CONTENT_MISC_RENAME_COMPLETE -> emu.grasscutter.game.quest.rename.QuestMiscRenameService.matchesCurrent(quest,condition);
            case QUEST_CONTENT_ACTIVITY_TRIGGER_UPDATE -> emu.grasscutter.game.quest.reconstruction.QuestActivityRuntime.matchesCurrent(quest,condition);
            case QUEST_CONTENT_ACTIVITY_END -> emu.grasscutter.game.quest.reconstruction.QuestActivityRuntime.activityEnded(owner,condition);
            case QUEST_CONTENT_FISHING_SUCC, QUEST_CONTENT_USE_WIDGET,
                    QUEST_CONTENT_CAPTURE_USE_MATERIAL_LIST, QUEST_CONTENT_AVATAR_CAPTURE_ANIMAL ->
                    emu.grasscutter.game.quest.field.QuestFieldRuntime.matchesCurrent(quest,condition);
            case QUEST_CONTENT_IRODORI_FINISH_FLOWER_COMBINATION, QUEST_CONTENT_IRODORI_POETRY_REACH_MIN_PROGRESS,
                    QUEST_CONTENT_IRODORI_POETRY_FINISH_FILL_POETRY -> emu.grasscutter.game.quest.reconstruction.QuestActivityRuntime.matchesCurrent(quest,condition);
            case QUEST_CONTENT_EXHIBITION_ACCUMULATE_GT_EQ -> emu.grasscutter.game.quest.comedy.ComedyVoucherRuntime.matchesCurrent(quest,condition);
            case QUEST_CONTENT_SCENERY_STATE_EQUAL -> emu.grasscutter.game.quest.scenery.QuestSceneryRuntime.matchesCurrent(quest,condition);
            case QUEST_CONTENT_ENTER_ROGUE_DUNGEON -> emu.grasscutter.game.quest.reconstruction.QuestRogueStory.matchesCurrent(quest,condition);
            case QUEST_CONTENT_SHOP_SELL_OUT -> emu.grasscutter.game.quest.world.QuestShopProgress.matchesCurrent(quest,condition);
            case QUEST_CONTENT_ACHIEVEMENT_ISACHIEVED, QUEST_CONTENT_ACHIEVEMENT_STATE_EQUAL -> emu.grasscutter.game.quest.world.QuestAchievementProgress.matchesCurrent(quest,condition);
            case QUEST_CONTENT_ACTIVITY_TRIGGER_FAILED -> emu.grasscutter.game.quest.delivery.QuestDeliveryRuntime.matchesCurrent(quest,condition);
            case QUEST_CONTENT_ITEM_NUM_EQUAL, QUEST_CONTENT_ITEM_NUM_GREATER,
                    QUEST_CONTENT_ITEM_NUM_LESS -> target.length >= 2 && target[0] > 0 && target[1] >= 0
                    && GameData.getItemDataMap().containsKey(target[0])
                    && compare(condition.getType(), owner.getInventory().getItemCountById(target[0]), target[1]);
            case QUEST_CONTENT_OBTAIN_MATERIAL_WITH_SUBTYPE -> target[0] > 0
                    && owner.getInventory().getItems().values().stream()
                            .filter(item -> item.getItemData() != null
                                    && item.getItemData().getItemType()
                                            == emu.grasscutter.game.inventory.ItemType.ITEM_MATERIAL
                                    && item.getItemData().getMaterialType() != null
                                    && item.getItemData().getMaterialType().getValue() == target[0])
                            .mapToLong(item -> Math.max(0, item.getCount())).sum()
                                    >= Math.max(1, condition.getCount());
            case QUEST_CONTENT_OBTAIN_VARIOUS_ITEM ->
                    emu.grasscutter.game.quest.QuestInventoryProgress.matchesCurrent(quest, condition);
            case QUEST_CONTENT_MAIN_COOP_ENTER_SAVE_POINT, QUEST_CONTENT_MAIN_COOP_ENTER_ANY_SAVE_POINT ->
                    emu.grasscutter.game.quest.coop.CoopSystem.matchesCurrent(quest, condition);
            case QUEST_CONTENT_QUEST_CHECK_EQUAL ->
                    emu.grasscutter.game.quest.check.QuestCheckRuntime.matchesCurrent(quest, condition);
            case QUEST_CONTENT_GCG_LEVEL_WIN, QUEST_CONTENT_GCG_GUIDE_PROGRESS ->
                    emu.grasscutter.game.gcg.story.StoryCardRuntime.matchesCurrent(quest, condition);
            case QUEST_CONTENT_TIME_VAR_GT_EQ, QUEST_CONTENT_TIME_VAR_PASS_DAY,
                    QUEST_CONTENT_TIME_VAR_PASS_REFRESH_POLICY ->
                    emu.grasscutter.game.quest.QuestTimeConditions.matchesCurrent(quest, condition);
            case QUEST_CONTENT_CITY_LEVEL_UP -> target.length >= 2 && target[0] > 0 && target[1] > 0
                    && owner.getCityInfoData() != null && owner.getCityInfoData().containsKey(target[0])
                    && owner.getCityInfoData().get(target[0]).getLevel() >= target[1];
            case QUEST_CONTENT_UNLOCKED_RECIPE, QUEST_CONTENT_NOT_UNLOCKED_RECIPE ->
                    allConfiguredRecipes(condition.getParamStr(), owner,
                            condition.getType() == QuestContent.QUEST_CONTENT_UNLOCKED_RECIPE);
            case QUEST_CONTENT_QUEST_VAR_NOT_EQUAL -> {
                var parent = quest.getMainQuest();
                var vars = parent == null ? null : parent.getQuestVars();
                yield target.length >= 2 && vars != null && target[0] >= 0 && target[0] < vars.length
                        && vars[target[0]] != target[1];
            }
            case QUEST_CONTENT_UNLOCK_ANY_TRANS_POINT -> {
                var points = owner.getUnlockedScenePoints().get(target[0]);
                yield target[0] > 0 && points != null
                        && anyConfiguredId(condition.getParamStr(), points::contains);
            }
            case QUEST_CONTENT_ENTER_MY_HOME_WORLD -> sceneReady(owner)
                    && owner.getWorld() instanceof HomeWorld home && home.getHost() == owner
                    && owner.getScene().getWorld() == home
                    && anyConfiguredId(condition.getParamStr(), id -> id == owner.getSceneId());
            case QUEST_CONTENT_ABYSS_WAR_LEVEL_STATE_EQUAL,
                    QUEST_CONTENT_ABYSS_WAR_LIMIT_REIGON_STATE_EQUAL ->
                    emu.grasscutter.game.quest.war.QuestWarRuntime.matchesCurrent(quest, condition);
            default -> false;
        };
    }

    private static boolean sceneReady(Player owner) {
        return owner.getScene() != null && owner.getScene().getId() == owner.getSceneId()
                && owner.getSceneLoadState() == Player.SceneLoadState.LOADED;
    }

    /** Native param_str is an explicit comma-separated allow-list, not an ordinal or a count. */
    private static boolean anyConfiguredId(String configured, IntPredicate matches) {
        if (configured == null || configured.isBlank()) return false;
        boolean matched = false;
        try {
            for (var value : configured.split(",", -1)) {
                int id = Integer.parseInt(value.trim());
                if (id <= 0) return false;
                matched |= matches.test(id);
            }
        } catch (NumberFormatException error) {
            return false;
        }
        return matched;
    }

    /** Called after accepted inventory mutations, independently of optional watcher callbacks. */
    public static void onInventoryItemChanged(Player player, int itemId) {
        player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_ITEM_NUM_EQUAL, itemId);
        player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_ITEM_NUM_GREATER, itemId);
        player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_ITEM_NUM_LESS, itemId);
        player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_OBTAIN_MATERIAL_WITH_SUBTYPE, itemId);
    }

    public static void onInventoryItemChanged(Player player, int itemId, int quantityDelta) {
        emu.grasscutter.game.quest.QuestInventoryProgress.onItemQuantityChanged(player, itemId, quantityDelta);
        onInventoryItemChanged(player, itemId);
    }

    private static boolean allConfiguredRecipes(String configured, Player player, boolean unlocked) {
        if (configured == null || configured.isBlank()) return false;
        try {
            for (var value : configured.split(",", -1)) {
                int id = Integer.parseInt(value.trim());
                var data = GameData.getCookRecipeDataMap().get(id);
                if (id <= 0 || data == null) return false;
                boolean known = data.isDefaultUnlocked() || player.getUnlockedRecipies().containsKey(id);
                if (known != unlocked) return false;
            }
        } catch (NumberFormatException error) { return false; }
        return true;
    }

    static boolean compare(QuestContent type, int actual, int expected) {
        return switch (type) {
            case QUEST_CONTENT_QUEST_GLOBAL_VAR_EQUAL, QUEST_CONTENT_ITEM_NUM_EQUAL -> actual == expected;
            case QUEST_CONTENT_QUEST_GLOBAL_VAR_NOT_EQUAL -> actual != expected;
            case QUEST_CONTENT_QUEST_GLOBAL_VAR_GREATER, QUEST_CONTENT_ITEM_NUM_GREATER -> actual > expected;
            case QUEST_CONTENT_QUEST_GLOBAL_VAR_LESS, QUEST_CONTENT_ITEM_NUM_LESS -> actual < expected;
            default -> false;
        };
    }
}
