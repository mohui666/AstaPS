package emu.grasscutter.game.shop;

import emu.grasscutter.GameConstants;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.ItemCatalog;
import emu.grasscutter.data.common.ItemParamData;
import emu.grasscutter.data.excels.ItemData;
import emu.grasscutter.game.chat.AssistantAvatarMaterials;
import emu.grasscutter.game.avatar.Avatar;
import emu.grasscutter.game.inventory.*;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.ActionReason;
import emu.grasscutter.game.props.ItemUseAction.ItemUseGainAvatar;
import emu.grasscutter.server.packet.send.PacketAvatarPropNotify;
import emu.grasscutter.server.packet.send.PacketAvatarFightPropUpdateNotify;
import java.util.*;

/** Free, unlimited native-mall goods for players who can already use the give assistant. */
public final class AssistantShop {
    public static final int SHOP_TYPE = 902;
    private static final int GOODS_BASE = 300_000_000;
    private static final Set<Integer> VIRTUAL_ITEMS = Set.of(101, 102, 105, 106, 107, 121, 201, 202, 203, 204);

    public record Purchase(ShopInfo goods, boolean accepted, boolean bagFull, String message) {}

    private AssistantShop() {}

    public static boolean permitted(Player player) {
        return player != null && player.isOnline() && player.getAccount() != null
                && Grasscutter.getPermissionHandler().checkPermission(player, player, "player.give", "player.give.others");
    }

    public static boolean isGoodsId(int goodsId) { return goodsId > GOODS_BASE; }

    /** null retains the existing shop for accounts without assistant permission. */
    public static List<ShopInfo> goods(Player player, int shopType) {
        if (shopType != SHOP_TYPE || !permitted(player)) return null;
        return GameData.getItemDataMap().values().stream().filter(AssistantShop::supported)
                .sorted(Comparator.comparingInt(AssistantShop::order)
                        .thenComparing(Comparator.comparingInt(ItemData::getRankLevel).reversed())
                        .thenComparingInt(ItemData::getId))
                .map(AssistantShop::goods).toList();
    }

    static boolean supported(ItemData item) {
        if (item == null || item.getId() <= 0 || item.getId() > Integer.MAX_VALUE - GOODS_BASE
                || !ItemCatalog.supported(item.getItemType())
                || GameConstants.ILLEGAL_ITEMS.contains(item.getId())
                || (item.getItemType() == ItemType.ITEM_WEAPON && GameConstants.ILLEGAL_WEAPONS.contains(item.getId()))
                || (item.getItemType() == ItemType.ITEM_RELIQUARY && GameConstants.ILLEGAL_RELICS.contains(item.getId()))) return false;
        if (item.getItemType() == ItemType.ITEM_VIRTUAL) return VIRTUAL_ITEMS.contains(item.getId());
        if (item.getMaterialType() == MaterialType.MATERIAL_AVATAR) {
            int avatarId = avatarId(item);
            return avatarId != 0 && ItemCatalog.avatars().stream().anyMatch(e -> e.id() == avatarId);
        }
        if (item.isUseOnGain()) {
            // Inventory admission supports these native permanent unlock cards.
            return item.getMaterialType() == MaterialType.MATERIAL_COSTUME
                    || item.getMaterialType() == MaterialType.MATERIAL_NAMECARD
                    || item.getMaterialType() == MaterialType.MATERIAL_FLYCLOAK;
        }
        return item.getItemType() == ItemType.ITEM_WEAPON || item.getItemType() == ItemType.ITEM_RELIQUARY
                || item.getStackLimit() > 0;
    }

    private static int order(ItemData item) {
        if (item.getMaterialType() == MaterialType.MATERIAL_AVATAR) return 0;
        return switch (item.getItemType()) {
            case ITEM_VIRTUAL -> 1;
            case ITEM_WEAPON -> 2;
            case ITEM_RELIQUARY -> 3;
            case ITEM_FURNITURE -> 5;
            default -> 4;
        };
    }

    static ShopInfo goods(ItemData item) {
        var goods = new ShopInfo();
        goods.setGoodsId(Math.addExact(GOODS_BASE, item.getId()));
        goods.setGoodsItem(new ItemParamData(item.getId(), unitCount(item.getId())));
        goods.setCostItemList(List.of());
        goods.setBuyLimit(0);
        goods.setMinLevel(1);
        goods.setMaxLevel(99);
        goods.setEndTime(Integer.MAX_VALUE);
        return goods;
    }

    private static int unitCount(int itemId) {
        return switch (itemId) {
            case 201 -> 10_000;
            case 202 -> 1_000_000;
            case 223, 224 -> 100;
            case 104003, 104013 -> 1_000;
            default -> 1;
        };
    }

    static int avatarId(ItemData item) {
        if (item.getItemUseActions() == null) return 0;
        return item.getItemUseActions().stream().filter(action -> action instanceof ItemUseGainAvatar)
                .mapToInt(action -> ((ItemUseGainAvatar) action).getI()).filter(id -> id > 0).findFirst().orElse(0);
    }

    public static Purchase buy(Player player, int shopType, int goodsId, int buyCount) {
        if (!permitted(player)) throw new IllegalArgumentException("当前账号没有物品领取权限。");
        if (shopType != SHOP_TYPE || !isGoodsId(goodsId) || buyCount <= 0)
            throw new IllegalArgumentException("商城商品或购买数量无效。");
        var definition = GameData.getItemDataMap().get(goodsId - GOODS_BASE);
        if (!supported(definition)) throw new IllegalArgumentException("这个商品无法领取。");
        var info = goods(definition);
        String name = ItemCatalog.entry(definition).name();
        var rewards = new ArrayList<GameItem>();
        String detail;
        int avatarId = avatarId(definition);
        if (definition.getMaterialType() == MaterialType.MATERIAL_AVATAR && avatarId != 0) {
            var owned = player.getAvatars().getAvatarById(avatarId);
            var plan = AssistantAvatarMaterials.plan(avatarId, owned);
            // Characters are unique. Repeat purchases give cultivation packs without changing constellations.
            if (owned == null) rewards.add(new GameItem(definition, 1));
            for (var material : plan.items().entrySet()) rewards.add(new GameItem(
                    GameData.getItemDataMap().get(material.getKey()), Math.multiplyExact(material.getValue(), buyCount)));
            detail = (owned == null ? "已领取100级角色与培养材料：" : "角色已设为100级，已领取培养材料：") + name + "。\n" + plan.summary();
            if (rewards.isEmpty()) detail = name + "已设为100级，基础天赋已达到材料包目标，无需补充材料。";
        } else {
            int count = Math.multiplyExact(buyCount, info.getGoodsItem().getCount());
            boolean weapon = definition.getItemType() == ItemType.ITEM_WEAPON;
            rewards.addAll(InventoryGrantBuilder.create(definition, count, weapon ? 100 : 1));
            if (weapon) {
                int totalExp = 0;
                for (int level = 1; level < 100; level++) totalExp += GameData.getWeaponExpRequired(definition.getRankLevel(), level);
                for (var item : rewards) {
                    item.setRefinement(4);
                    item.setTotalExp(totalExp);
                }
            }
            detail = "已免费领取：" + name + " ×" + count + "。";
        }
        var result = rewards.isEmpty() ? null : player.getInventory().addItems(rewards, ActionReason.Shop, InventoryAddPolicy.ALL_OR_NOTHING);
        if (result == null || result.allAccepted()) {
            if (definition.getMaterialType() == MaterialType.MATERIAL_AVATAR && avatarId != 0) {
                var purchased = player.getAvatars().getAvatarById(avatarId);
                if (purchased == null) throw new IllegalStateException("角色未实际加入账号：" + avatarId);
                purchased.setLevel(100);
                purchased.setExp(0);
                purchased.setPromoteLevel(Avatar.getMinPromoteLevel(100));
                purchased.recalcStats(true);
                purchased.save();
                player.sendPacket(new PacketAvatarPropNotify(purchased));
                player.sendPacket(new PacketAvatarFightPropUpdateNotify(purchased, purchased.getFightProperties()));
            }
            return new Purchase(info, true, false, detail);
        }
        boolean full = result.entries().stream().anyMatch(e -> e.status() == InventoryAddResult.Status.CAPACITY_FULL);
        String reason = result.entries().stream().filter(e -> !e.accepted()).map(e -> switch (e.status()) {
            case CAPACITY_FULL -> "背包空位不足";
            case STACK_LIMIT -> "超过物品持有上限";
            case ALREADY_OWNED -> "已经拥有该解锁内容";
            default -> e.status().name();
        }).distinct().reduce((a, b) -> a + "、" + b).orElse("请检查实际到账情况");
        return new Purchase(info, false, full, "未完整领取：" + name + "。" + reason + "。");
    }
}
