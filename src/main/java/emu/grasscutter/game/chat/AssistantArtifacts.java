package emu.grasscutter.game.chat;

import static emu.grasscutter.game.props.FightProperty.*;

import emu.grasscutter.GameConstants;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.ItemCatalog;
import emu.grasscutter.data.excels.ItemData;
import emu.grasscutter.data.excels.reliquary.ReliquaryAffixData;
import emu.grasscutter.game.inventory.EquipType;
import emu.grasscutter.game.inventory.GameItem;
import emu.grasscutter.game.inventory.ItemType;
import emu.grasscutter.game.props.FightProperty;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/** A compact chat spelling for the same native main/substat depots used by the game. */
final class AssistantArtifacts {
    static final String HELP = "圣遗物：发套装名可搜索随机词条的部件。\n"
            + "DIY：打造 套装 花/羽/沙/杯/头 主词条 [副词条:次数[:档位] ...] [等级:数字]\n"
            + "例：打造 绝缘 头 暴击率 暴伤:6 充能:3\n"
            + "默认最高星级、满级并锁定；副词条精确按输入生成，最多四种，不随机补齐。\n"
            + "主词条：生命值 / 生命% / 攻击力 / 攻击% / 防御% / 精通 / 充能 / 暴击率 / 暴伤 / 治疗 / 火伤等\n"
            + "次数含初始一次，1–200；省略档位取最高档。";

    private AssistantArtifacts() {}

    static FightProperty property(String name) {
        return switch (name) {
            case "生命值", "小生命" -> FIGHT_PROP_HP;
            case "生命", "生命%", "生命值%", "大生命" -> FIGHT_PROP_HP_PERCENT;
            case "攻击力", "小攻击" -> FIGHT_PROP_ATTACK;
            case "攻击", "攻击%", "攻击力%", "大攻击" -> FIGHT_PROP_ATTACK_PERCENT;
            case "防御力", "小防御" -> FIGHT_PROP_DEFENSE;
            case "防御", "防御%", "防御力%", "大防御" -> FIGHT_PROP_DEFENSE_PERCENT;
            case "暴击", "暴击率" -> FIGHT_PROP_CRITICAL;
            case "暴伤", "爆伤", "暴击伤害" -> FIGHT_PROP_CRITICAL_HURT;
            case "充能", "元素充能", "元素充能效率" -> FIGHT_PROP_CHARGE_EFFICIENCY;
            case "精通", "元素精通" -> FIGHT_PROP_ELEMENT_MASTERY;
            case "治疗", "治疗加成" -> FIGHT_PROP_HEAL_ADD;
            case "物伤", "物理伤害" -> FIGHT_PROP_PHYSICAL_ADD_HURT;
            case "火伤" -> FIGHT_PROP_FIRE_ADD_HURT;
            case "雷伤" -> FIGHT_PROP_ELEC_ADD_HURT;
            case "水伤" -> FIGHT_PROP_WATER_ADD_HURT;
            case "草伤" -> FIGHT_PROP_GRASS_ADD_HURT;
            case "风伤" -> FIGHT_PROP_WIND_ADD_HURT;
            case "岩伤" -> FIGHT_PROP_ROCK_ADD_HURT;
            case "冰伤" -> FIGHT_PROP_ICE_ADD_HURT;
            default -> FightProperty.getPropByShortName(name);
        };
    }

    static EquipType slot(String name) {
        return switch (name) {
            case "花", "生之花" -> EquipType.EQUIP_BRACER;
            case "羽", "死之羽" -> EquipType.EQUIP_NECKLACE;
            case "沙", "时之沙" -> EquipType.EQUIP_SHOES;
            case "杯", "空之杯" -> EquipType.EQUIP_RING;
            case "头", "冠", "理之冠" -> EquipType.EQUIP_DRESS;
            default -> throw new IllegalArgumentException("部位请填写：花 / 羽 / 沙 / 杯 / 头。");
        };
    }

    static GameItem create(String text) {
        var args = text.strip().split("\\s+");
        if (args.length < 3) throw new IllegalArgumentException(HELP);
        EquipType slot = slot(args[1]);
        String query = AssistantBot.normalize(args[0]);
        var candidates = GameData.getItemDataMap().values().stream()
                .filter(item -> item.getItemType() == ItemType.ITEM_RELIQUARY && item.getSetId() > 0
                        && item.getEquipType() == slot && item.getRankLevel() >= 1 && item.getRankLevel() <= 5
                        && item.getMaxLevel() > 0 && !GameConstants.ILLEGAL_RELICS.contains(item.getId())
                        && item.getAppendPropDepotId() == item.getRankLevel() * 100 + 1)
                .filter(item -> {
                    var names = ItemCatalog.artifactSetNames(item.getSetId());
                    return AssistantBot.normalize(names[0]).contains(query) || AssistantBot.normalize(names[1]).contains(query)
                            || String.valueOf(item.getSetId()).equals(query);
                }).toList();
        var sets = candidates.stream().map(ItemData::getSetId).distinct().toList();
        if (sets.size() != 1) throw new IllegalArgumentException(sets.isEmpty()
                ? "没有找到这个套装和部位。发送套装名搜索。"
                : "匹配到多个套装，请输入更完整的套装名。");
        var definition = candidates.stream().sorted(Comparator.comparingInt(ItemData::getRankLevel).reversed()
                .thenComparingInt(ItemData::getId)).findFirst().orElseThrow();
        FightProperty main = property(args[2]);
        var mainData = GameData.getReliquaryMainPropDataMap().values().stream()
                .filter(p -> p.getPropDepotId() == definition.getMainPropDepotId() && p.getWeight() > 0 && p.getFightProp() == main).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("这个部位不能使用该主词条。"));
        var seen = new HashSet<FightProperty>();
        seen.add(main);
        List<Integer> subs = new ArrayList<>();
        int level = definition.getMaxLevel() - 1;
        for (int i = 3; i < args.length; i++) {
            String[] parts = args[i].split("[:：]", -1);
            if (parts.length == 2 && parts[0].equals("等级")) {
                level = AssistantBot.integer(parts[1], 0, definition.getMaxLevel() - 1, "圣遗物等级");
                continue;
            }
            if (parts.length < 2 || parts.length > 3) throw new IllegalArgumentException("副词条格式：暴伤:6 或 暴伤:6:4。");
            FightProperty prop = property(parts[0]);
            if (prop == FIGHT_PROP_NONE || !seen.add(prop) || seen.size() > 5)
                throw new IllegalArgumentException("主副词条不能重复，副词条最多四种。");
            var tiers = GameData.getReliquaryAffixDataMap().values().stream()
                    .filter(p -> p.getDepotId() == definition.getAppendPropDepotId() && p.getWeight() > 0 && p.getFightProp() == prop)
                    .sorted(Comparator.comparingDouble(ReliquaryAffixData::getPropValue).thenComparingInt(ReliquaryAffixData::getId)).toList();
            if (tiers.isEmpty()) throw new IllegalArgumentException("不支持的副词条：" + parts[0]);
            int rolls = AssistantBot.integer(parts[1], 1, 200, "副词条次数");
            int tier = parts.length == 3 ? AssistantBot.integer(parts[2], 1, tiers.size(), "副词条档位") : tiers.size();
            for (int j = 0; j < rolls; j++) subs.add(tiers.get(tier - 1).getId());
        }
        var item = new GameItem(definition);
        item.setLevel(level + 1);
        item.setMainPropId(mainData.getId());
        item.getAppendPropIdList().clear();
        item.getAppendPropIdList().addAll(subs);
        item.setLocked(true);
        int exp = 0;
        for (int i = 1; i <= level; i++) exp += GameData.getRelicExpRequired(definition.getRankLevel(), i);
        item.setTotalExp(exp);
        return item;
    }
}
