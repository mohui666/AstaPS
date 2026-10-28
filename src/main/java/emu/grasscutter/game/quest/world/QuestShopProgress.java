package emu.grasscutter.game.quest.world;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.enums.QuestContent;
import emu.grasscutter.game.shop.ShopInfo;
import emu.grasscutter.utils.Utils;
import java.util.Map;

/** Story shop completion is proved by the server catalog and accepted purchase limits. */
public final class QuestShopProgress {
    private static final Map<Integer, Integer> QUEST_SHOPS = Map.of(
            7411101, 1103, 7419304, 1108, 7614502, 1143, 7614504, 1144, 7614507, 1145);

    private QuestShopProgress() {}
    public static boolean isStoryShop(int shopType) { return QUEST_SHOPS.containsValue(shopType); }

    public static boolean matchesCurrent(GameQuest quest, QuestData.QuestContentCondition condition) {
        int[] target = condition.getParam();
        if (condition.getType() != QuestContent.QUEST_CONTENT_SHOP_SELL_OUT
                || target == null || target.length < 2 || target[1] != 1
                || !Integer.valueOf(target[0]).equals(QUEST_SHOPS.get(quest.getSubQuestId()))) return false;
        Player player = quest.getOwner();
        var goods = player.getServer().getShopSystem().getShopData().get(target[0]);
        if (goods == null || goods.isEmpty()) return false;
        int now = Utils.getCurrentSeconds();
        for (var good : goods) {
            if (good == null || good.getGoodsId() <= 0 || good.getBuyLimit() <= 0) return false;
            var limit = player.getGoodsLimit(good.getGoodsId());
            if (limit == null || limit.getHasBoughtInPeriod() < good.getBuyLimit()) return false;
            if (good.getShopRefreshType() != ShopInfo.ShopRefreshType.NONE
                    && limit.getNextRefreshTime() > 0 && now >= limit.getNextRefreshTime()) return false;
        }
        return true;
    }

    /** Invoke after both output delivery and the corresponding ShopLimit update succeeded. */
    public static void onAcceptedPurchase(Player player, int shopType) {
        if (QUEST_SHOPS.containsValue(shopType))
            player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_SHOP_SELL_OUT, shopType);
    }
}
