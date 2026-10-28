package emu.grasscutter.game.chat;

import emu.grasscutter.data.GameData;
import emu.grasscutter.data.common.ItemParamData;
import emu.grasscutter.data.excels.ItemData;
import emu.grasscutter.data.excels.ProudSkillData;
import emu.grasscutter.data.excels.avatar.AvatarPromoteData;
import emu.grasscutter.game.avatar.Avatar;
import emu.grasscutter.game.inventory.MaterialType;
import emu.grasscutter.game.props.ItemUseAction.ItemUseAddExp;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/** Native remaining costs for level 90, ascension 6 and ordinary base talent level 10. */
public final class AssistantAvatarMaterials {
    private static final int MORA = 202;
    private static final int TARGET_LEVEL = 90;
    private static final int TARGET_PROMOTE = 6;
    private static final int TARGET_TALENT = 10;

    public record Plan(Map<Integer, Integer> items, String summary) {}

    private record ExpBook(int id, int exp) {}

    private AssistantAvatarMaterials() {}

    public static Plan plan(int avatarId, Avatar owned) {
        var avatar = GameData.getAvatarDataMap().get(avatarId);
        if (avatar == null) throw missing("角色 " + avatarId);
        if (owned != null && owned.getAvatarId() != avatarId)
            throw new IllegalArgumentException("角色与培养材料目标不一致。");
        int level = owned == null ? 1 : owned.getLevel();
        int promote = owned == null ? 0 : owned.getPromoteLevel();
        int exp = owned == null ? 0 : owned.getExp();
        if (level < 1 || promote < 0 || promote > TARGET_PROMOTE || exp < 0)
            throw new IllegalArgumentException("角色当前等级、突破或经验数据无效。");

        Map<Integer, Integer> items = new TreeMap<>();
        ExpBook book = null;
        for (int stage = promote; stage <= TARGET_PROMOTE; stage++) {
            if (level < TARGET_LEVEL) {
                var row = promote(avatar.getAvatarPromoteId(), stage);
                int cap = Math.min(TARGET_LEVEL, row.getUnlockMaxLevel());
                if (cap <= 0 || (stage == TARGET_PROMOTE && cap < TARGET_LEVEL))
                    throw missing("突破 " + stage + " 的90级上限配置");
                if (level < cap) {
                    long neededExp = -exp;
                    for (int from = level; from < cap; from++) {
                        var curve = GameData.getAvatarLevelDataMap().get(from);
                        if (curve == null || curve.getExp() <= 0) throw missing("角色 " + from + " 级经验");
                        neededExp += curve.getExp();
                    }
                    if (neededExp <= 0) throw new IllegalArgumentException("角色当前经验数据无效。");
                    if (book == null) book = expBook();
                    long count = (neededExp + book.exp() - 1) / book.exp();
                    add(items, book.id(), count);
                    // InventorySystem charges the EXP of the books consumed, including cap overflow.
                    add(items, MORA, count * book.exp() / 5);
                    level = cap;
                    exp = 0;
                }
            }
            if (stage < TARGET_PROMOTE) {
                var next = promote(avatar.getAvatarPromoteId(), stage + 1);
                if (next.getCostItems() == null) throw missing("突破 " + (stage + 1) + " 的材料");
                costs(items, Arrays.asList(next.getCostItems()));
                add(items, MORA, next.getCoinCost());
            }
        }

        int depotId = owned == null ? avatar.getSkillDepotId() : owned.getSkillDepotId();
        var depot = GameData.getAvatarSkillDepotDataMap().get(depotId);
        if (depot == null || depot.getSkills() == null) throw missing("技能组 " + depotId);
        Map<Integer, Integer> levels = owned == null ? Map.of() : owned.getSkillLevelMap();
        int talents = 0;
        for (int skillId : depot.getSkillsAndEnergySkill().distinct().toArray()) {
            var skill = GameData.getAvatarSkillDataMap().get(skillId);
            if (skill == null) throw missing("技能 " + skillId);
            int group = skill.getProudSkillGroupId();
            if (group == 0) continue;
            // Row IDs are not always group * 100 + level (Faruzan group 7639 uses 7633xx).
            Map<Integer, ProudSkillData> rows = new HashMap<>();
            for (var row : GameData.getProudSkillDataMap().values()) {
                if (row.getProudSkillGroupId() == group) rows.put(row.getLevel(), row);
            }
            if (!rows.containsKey(1))
                throw missing("天赋组 " + group + " 的基础等级");
            // Sprint/aim skills may name a proud group that only has its initial, unlevelled row.
            boolean upgradeable = rows.values().stream()
                    .anyMatch(row -> row.getProudSkillGroupId() == group && row.getLevel() > 1);
            if (!upgradeable) continue;
            talents++;
            int current = Math.max(1, levels.getOrDefault(skillId, 1));
            for (int target = current + 1; target <= TARGET_TALENT; target++) {
                var row = rows.get(target);
                if (row == null || row.getCostItems() == null)
                    throw missing("天赋组 " + group + " 的 " + target + " 级材料");
                costs(items, row.getTotalCostItems());
            }
        }
        String summary = "90级、突破6阶、当前技能组的" + talents + "项普通天赋10级（不含命座加成）。\n"
                + (items.isEmpty() ? "已达到目标，无需补充材料。" : "按现有培养进度补齐；升级书分突破阶段取整，摩拉包含用书消耗。")
                + "\n不含90→95→100的特殊升级材料。";
        return new Plan(Map.copyOf(items), summary);
    }

    private static AvatarPromoteData promote(int id, int stage) {
        var row = GameData.getAvatarPromoteData(id, stage);
        if (row == null) throw missing("角色突破组 " + id + " 的 " + stage + " 阶数据");
        return row;
    }

    private static ExpBook expBook() {
        return GameData.getItemDataMap().values().stream()
                .filter(item -> item.getMaterialType() == MaterialType.MATERIAL_EXP_FRUIT)
                .map(item -> new ExpBook(item.getId(), bookExp(item)))
                .filter(book -> book.exp() > 0)
                .max(Comparator.comparingInt(ExpBook::exp).thenComparingInt(ExpBook::id))
                .orElseThrow(() -> missing("角色经验书的实际经验值"));
    }

    private static int bookExp(ItemData item) {
        if (item.getItemUseActions() == null) return 0;
        return item.getItemUseActions().stream().filter(action -> action instanceof ItemUseAddExp)
                .mapToInt(action -> ((ItemUseAddExp) action).getExp()).sum();
    }

    private static void costs(Map<Integer, Integer> items, Iterable<ItemParamData> costs) {
        for (var cost : costs) add(items, cost.getId(), cost.getCount());
    }

    private static void add(Map<Integer, Integer> items, int id, long count) {
        if (count == 0) return; // Native cost arrays include empty entries.
        if (count < 0 || id <= 0) throw missing("有效的培养材料数量");
        if (!GameData.getItemDataMap().containsKey(id)) throw missing("材料 " + id);
        items.merge(id, Math.toIntExact(count), Math::addExact);
    }

    private static IllegalArgumentException missing(String detail) {
        return new IllegalArgumentException("培养资源缺失：" + detail + "。");
    }
}
