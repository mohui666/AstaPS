package emu.grasscutter.server.http.console;

import com.google.gson.JsonParser;
import emu.grasscutter.GameConstants;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.GameDepot;
import emu.grasscutter.data.excels.ItemData;
import emu.grasscutter.data.excels.reliquary.ReliquaryAffixData;
import emu.grasscutter.game.inventory.GameItem;
import emu.grasscutter.game.inventory.ItemType;
import emu.grasscutter.game.props.FightProperty;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exact DIY relics using the loaded native depots, level values and inventory admission. */
final class WebConsoleArtifacts {
    record Piece(int id, int setId, String name, String en, String slot, int rank,
                 int maxLevel, int mainDepot, int subDepot) {}
    record Stat(int id, int prop, String name, String en, boolean percent, double value) {}
    record Sub(int affixId, int rolls) {}
    record Preview(Piece artifact, int level, Stat main, List<Stat> substats, int rolls, boolean locked) {}
    record Receipt(int uid, String guid, Preview preview) {}

    private WebConsoleArtifacts() {}

    static boolean supported(ItemData item) {
        return item != null && item.getItemType() == ItemType.ITEM_RELIQUARY
                && item.getSetId() > 0 && item.getRankLevel() >= 1 && item.getRankLevel() <= 5
                && item.getMaxLevel() > 0 && !GameConstants.ILLEGAL_RELICS.contains(item.getId())
                && item.getAppendPropDepotId() == item.getRankLevel() * 100 + 1
                && Set.of(1000, 2000, 3000, 4000, 5000).contains(item.getMainPropDepotId());
    }

    private static Piece piece(ItemData item) {
        var names = WebConsoleItems.artifactSetNames(item.getSetId());
        return new Piece(item.getId(), item.getSetId(), names[0], names[1], item.getEquipType().name(),
                item.getRankLevel(), item.getMaxLevel() - 1, item.getMainPropDepotId(), item.getAppendPropDepotId());
    }

    static Map<String, Object> catalog() {
        // Multiple native IDs differ only in initial random substat count. DIY replaces that list.
        var pieces = new LinkedHashMap<String, Piece>();
        GameData.getItemDataMap().values().stream().filter(WebConsoleArtifacts::supported)
                .sorted(Comparator.comparingInt(ItemData::getAppendPropNum).reversed().thenComparingInt(ItemData::getId))
                .forEach(item -> pieces.putIfAbsent(item.getSetId() + ":" + item.getRankLevel() + ":" + item.getEquipType(), piece(item)));
        var mains = new LinkedHashMap<Integer, List<Stat>>();
        var subs = new LinkedHashMap<Integer, List<Stat>>();
        for (var item : pieces.values()) {
            mains.computeIfAbsent(item.mainDepot(), depot -> GameDepot.getRelicMainPropList(depot).stream()
                    .filter(p -> p.getWeight() > 0).sorted(Comparator.comparingInt(p -> p.getFightProp().getId()))
                    .map(p -> stat(p.getId(), p.getFightProp(), 0)).toList());
            subs.computeIfAbsent(item.subDepot(), depot -> GameDepot.getRelicAffixList(depot).stream()
                    .filter(p -> p.getWeight() > 0).sorted(Comparator.comparingInt(ReliquaryAffixData::getId))
                    .map(p -> stat(p.getId(), p.getFightProp(), p.getPropValue())).toList());
        }
        return Map.of("items", List.copyOf(pieces.values()), "mainProps", mains, "subProps", subs);
    }

    private static Stat stat(int id, FightProperty prop, double value) {
        var names = switch (prop) {
            case FIGHT_PROP_HP -> new String[] {"生命值", "HP"};
            case FIGHT_PROP_HP_PERCENT -> new String[] {"生命值%", "HP%"};
            case FIGHT_PROP_ATTACK -> new String[] {"攻击力", "ATK"};
            case FIGHT_PROP_ATTACK_PERCENT -> new String[] {"攻击力%", "ATK%"};
            case FIGHT_PROP_DEFENSE -> new String[] {"防御力", "DEF"};
            case FIGHT_PROP_DEFENSE_PERCENT -> new String[] {"防御力%", "DEF%"};
            case FIGHT_PROP_CRITICAL -> new String[] {"暴击率", "CRIT Rate"};
            case FIGHT_PROP_CRITICAL_HURT -> new String[] {"暴击伤害", "CRIT DMG"};
            case FIGHT_PROP_CHARGE_EFFICIENCY -> new String[] {"元素充能效率", "Energy Recharge"};
            case FIGHT_PROP_ELEMENT_MASTERY -> new String[] {"元素精通", "Elemental Mastery"};
            case FIGHT_PROP_HEAL_ADD -> new String[] {"治疗加成", "Healing Bonus"};
            case FIGHT_PROP_PHYSICAL_ADD_HURT -> new String[] {"物理伤害加成", "Physical DMG Bonus"};
            case FIGHT_PROP_FIRE_ADD_HURT -> new String[] {"火元素伤害加成", "Pyro DMG Bonus"};
            case FIGHT_PROP_ELEC_ADD_HURT -> new String[] {"雷元素伤害加成", "Electro DMG Bonus"};
            case FIGHT_PROP_WATER_ADD_HURT -> new String[] {"水元素伤害加成", "Hydro DMG Bonus"};
            case FIGHT_PROP_GRASS_ADD_HURT -> new String[] {"草元素伤害加成", "Dendro DMG Bonus"};
            case FIGHT_PROP_WIND_ADD_HURT -> new String[] {"风元素伤害加成", "Anemo DMG Bonus"};
            case FIGHT_PROP_ROCK_ADD_HURT -> new String[] {"岩元素伤害加成", "Geo DMG Bonus"};
            case FIGHT_PROP_ICE_ADD_HURT -> new String[] {"冰元素伤害加成", "Cryo DMG Bonus"};
            default -> throw new IllegalArgumentException("不支持的圣遗物属性：" + prop);
        };
        boolean percent = prop != FightProperty.FIGHT_PROP_HP && prop != FightProperty.FIGHT_PROP_ATTACK
                && prop != FightProperty.FIGHT_PROP_DEFENSE && prop != FightProperty.FIGHT_PROP_ELEMENT_MASTERY;
        return new Stat(id, prop.getId(), names[0], names[1], percent, value);
    }

    record Request(int uid, int itemId, int level, int mainPropId, List<Sub> substats, boolean locked) {
        static Request parse(String body) {
            var json = JsonParser.parseString(body).getAsJsonObject();
            int uid = WebConsoleItems.Grant.integer(json, "uid", 0);
            int itemId = WebConsoleItems.Grant.integer(json, "itemId", null);
            int level = WebConsoleItems.Grant.integer(json, "level", 0);
            int main = WebConsoleItems.Grant.integer(json, "mainPropId", null);
            if (!json.has("substats") || !json.get("substats").isJsonArray() || json.getAsJsonArray("substats").size() > 4) {
                throw new IllegalArgumentException("请配置 0–4 条副词条。");
            }
            var subs = new ArrayList<Sub>();
            for (var element : json.getAsJsonArray("substats")) {
                var row = element.getAsJsonObject();
                int id = WebConsoleItems.Grant.integer(row, "affixId", null);
                int rolls = WebConsoleItems.Grant.integer(row, "rolls", 1);
                if (rolls < 1 || rolls > 200) throw new IllegalArgumentException("每条副词条次数须为 1–200。");
                subs.add(new Sub(id, rolls));
            }
            boolean locked = false;
            if (json.has("locked")) {
                var flag = json.get("locked");
                if (!flag.isJsonPrimitive() || !flag.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("锁定选项须为布尔值。");
                locked = flag.getAsBoolean();
            }
            return new Request(uid, itemId, level, main, List.copyOf(subs), locked);
        }

        ItemData validate() {
            var item = GameData.getItemDataMap().get(itemId);
            if (!supported(item)) throw new IllegalArgumentException("请选择列表中的圣遗物。");
            if (level < 0 || level >= item.getMaxLevel()) throw new IllegalArgumentException("等级须为 0–" + (item.getMaxLevel() - 1) + "。");
            var main = GameData.getReliquaryMainPropDataMap().get(mainPropId);
            if (main == null || main.getPropDepotId() != item.getMainPropDepotId() || main.getWeight() <= 0) {
                throw new IllegalArgumentException("此部位不能选择该主词条。");
            }
            var seen = new HashSet<FightProperty>();
            seen.add(main.getFightProp());
            if (substats.size() > 4) throw new IllegalArgumentException("最多选择四种副词条。");
            for (var sub : substats) {
                var affix = GameData.getReliquaryAffixDataMap().get(sub.affixId());
                if (affix == null || affix.getDepotId() != item.getAppendPropDepotId() || affix.getWeight() <= 0
                        || sub.rolls() < 1 || sub.rolls() > 200) throw new IllegalArgumentException("副词条档位或次数不适用于当前星级。");
                if (!seen.add(affix.getFightProp())) throw new IllegalArgumentException("主副词条不能相同，副词条类型不能重复。");
            }
            return item;
        }
    }

    static Preview preview(Request request) {
        var item = request.validate();
        var main = GameData.getReliquaryMainPropDataMap().get(request.mainPropId());
        var level = GameData.getRelicLevelData(item.getRankLevel(), request.level() + 1);
        if (level == null) throw new IllegalStateException("当前资源缺少圣遗物等级数值。");
        var stats = request.substats().stream().map(sub -> {
            var affix = GameData.getReliquaryAffixDataMap().get(sub.affixId());
            return stat(affix.getId(), affix.getFightProp(), (double) affix.getPropValue() * sub.rolls());
        }).toList();
        return new Preview(piece(item), request.level(), stat(main.getId(), main.getFightProp(), level.getPropValue(main.getFightProp())),
                stats, request.substats().stream().mapToInt(Sub::rolls).sum(), request.locked());
    }

    static GameItem make(Request request) {
        var item = new GameItem(request.validate());
        item.setLevel(request.level() + 1); // Native relic level 1 is displayed as +0.
        int exp = 0;
        for (int level = 1; level < item.getLevel(); level++) exp += GameData.getRelicExpRequired(item.getItemData().getRankLevel(), level);
        item.setTotalExp(exp);
        item.setMainPropId(request.mainPropId());
        item.setLocked(request.locked());
        item.getAppendPropIdList().clear();
        for (var sub : request.substats()) {
            for (int i = 0; i < sub.rolls(); i++) item.getAppendPropIdList().add(sub.affixId());
        }
        return item;
    }

    /** The caller runs this on the game logic thread. */
    static Receipt give(Request request) {
        var preview = preview(request);
        var server = Grasscutter.getGameServer();
        var player = server == null ? null : server.getPlayers().get(request.uid());
        if (player == null || !player.isOnline()) throw new IllegalArgumentException("该角色未在线，请进入游戏后刷新玩家列表。");
        var tab = player.getInventory().getInventoryTab(ItemType.ITEM_RELIQUARY);
        if (tab.getSize() >= tab.getMaxCapacity()) throw new IllegalArgumentException("圣遗物背包已满，请清理后再发放。");
        var item = make(request);
        var result = player.getInventory().addItems(List.of(item));
        if (!result.allAccepted()) throw new IllegalArgumentException("背包拒绝了此圣遗物：" + result.entries().getFirst().status());
        return new Receipt(player.getUid(), String.valueOf(item.getGuid()), preview);
    }
}
