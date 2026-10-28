package emu.grasscutter.game.chat;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.ItemData;
import emu.grasscutter.data.excels.ProudSkillData;
import emu.grasscutter.data.excels.avatar.AvatarData;
import emu.grasscutter.data.excels.avatar.AvatarLevelData;
import emu.grasscutter.data.excels.avatar.AvatarPromoteData;
import emu.grasscutter.data.excels.avatar.AvatarSkillData;
import emu.grasscutter.data.excels.avatar.AvatarSkillDepotData;
import emu.grasscutter.game.avatar.Avatar;
import emu.grasscutter.game.inventory.MaterialType;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class AssistantAvatarMaterialsTest {
    private static final Gson GSON = new Gson();
    private static final int AVATAR = 10000002, DEPOT = 98761, PROMOTE = 9876;
    private static final int SKILL_A = 987611, SKILL_B = 987612, SKILL_C = 987613;
    private static final int GROUP_A = 98761, GROUP_B = 98762, GROUP_C = 98763;
    private static final int COMMON = 9876101, GEM = 9876102, TALENT_BOOK = 9876103, WEEKLY = 9876104;
    private static final int CROWN = 104319, EXP_BOOK = 104003;
    private final Map<Integer, ItemData> oldItems = new HashMap<>();
    private final Map<Integer, AvatarData> oldAvatars = new HashMap<>();
    private final Map<Integer, AvatarLevelData> oldLevels = new HashMap<>();
    private final Map<Integer, AvatarPromoteData> oldPromotes = new HashMap<>();
    private final Map<Integer, AvatarSkillData> oldSkills = new HashMap<>();
    private final Map<Integer, AvatarSkillDepotData> oldDepots = new HashMap<>();
    private final Map<Integer, ProudSkillData> oldProud = new HashMap<>();

    @BeforeEach
    void nativeTables() {
        put(GameData.getAvatarDataMap(), oldAvatars, AVATAR, GSON.fromJson(
                "{\"id\":" + AVATAR + ",\"avatarPromoteId\":" + PROMOTE + ",\"skillDepotId\":" + DEPOT + "}", AvatarData.class));
        for (int id : new int[] {202, COMMON, GEM, TALENT_BOOK, WEEKLY, CROWN}) item(id, 0);
        item(EXP_BOOK, 6000); // Deliberately differs from the standard 20,000 EXP book.
        for (int level = 1; level < 90; level++) {
            var row = GSON.fromJson("{\"level\":" + level + ",\"exp\":1000}", AvatarLevelData.class);
            put(GameData.getAvatarLevelDataMap(), oldLevels, level, row);
        }
        int[] caps = {20, 40, 50, 60, 70, 80, 90};
        for (int stage = 0; stage <= 6; stage++) {
            var row = GSON.fromJson("{\"avatarPromoteId\":" + PROMOTE + ",\"promoteLevel\":" + stage
                    + ",\"unlockMaxLevel\":" + caps[stage] + ",\"scoinCost\":" + stage * 100
                    + ",\"costItems\":[{\"id\":" + COMMON + ",\"count\":" + stage
                    + "},{\"id\":" + GEM + ",\"count\":" + stage + "},{}]}", AvatarPromoteData.class);
            put(GameData.getAvatarPromoteDataMap(), oldPromotes, row.getId(), row);
        }
        skill(SKILL_A, GROUP_A);
        skill(SKILL_B, GROUP_B);
        skill(SKILL_C, GROUP_C);
        skill(987614, 98764); // A sprint with only its initial proud-skill row.
        skill(987615, 0); // An aim/action skill that has no upgrade group.
        proud(98764, 1);
        for (int group : new int[] {GROUP_A, GROUP_B, GROUP_C}) {
            for (int level = 1; level <= 10; level++) proud(group, level);
        }
        depot(DEPOT, "[" + SKILL_A + "," + SKILL_B + ",0,987614,987615," + SKILL_A + "]", SKILL_C);
    }

    @AfterEach
    void restoreTables() {
        restore(GameData.getItemDataMap(), oldItems);
        restore(GameData.getAvatarDataMap(), oldAvatars);
        restore(GameData.getAvatarLevelDataMap(), oldLevels);
        restore(GameData.getAvatarPromoteDataMap(), oldPromotes);
        restore(GameData.getAvatarSkillDataMap(), oldSkills);
        restore(GameData.getAvatarSkillDepotDataMap(), oldDepots);
        restore(GameData.getProudSkillDataMap(), oldProud);
    }

    @Test
    void newAvatarRoundsBooksAtEachAscensionAndMergesAllNativeCosts() {
        var plan = AssistantAvatarMaterials.plan(AVATAR, null);
        // 19k/20k/10k/10k/10k/10k/10k EXP at 6k per book: 4+4+2+2+2+2+2 books.
        assertEquals(Map.of(EXP_BOOK, 18, 202, 39900, COMMON, 183, GEM, 21,
                TALENT_BOOK, 54, WEEKLY, 12, CROWN, 3), plan.items());
        assertTrue(plan.summary().contains("3项普通天赋10级"));
        assertTrue(plan.summary().contains("不含90→95→100"));
    }

    @Test
    void ownedAvatarOnlyNeedsRemainingLevelsAscensionsAndBaseTalents() {
        var owned = owned(45, 500, 2, Map.of(SKILL_A, 10, SKILL_B, 8, SKILL_C, 6));
        assertEquals(Map.of(EXP_BOOK, 9, 202, 17900, COMMON, 71, GEM, 18,
                TALENT_BOOK, 12, WEEKLY, 6, CROWN, 2), AssistantAvatarMaterials.plan(AVATAR, owned).items());
        assertEquals(45, owned.getLevel());
        assertEquals(500, owned.getExp());
        assertEquals(2, owned.getPromoteLevel());
        assertEquals(Map.of(SKILL_A, 10, SKILL_B, 8, SKILL_C, 6), owned.getSkillLevelMap());
    }

    @Test
    void constellationBonusesCannotReplaceTheLastThreeBaseTalentUpgrades() {
        var owned = owned(90, 0, 6, Map.of(SKILL_A, 7, SKILL_B, 10, SKILL_C, 10));
        assertEquals(Map.of(202, 2700, COMMON, 27, TALENT_BOOK, 6, WEEKLY, 3, CROWN, 1),
                AssistantAvatarMaterials.plan(AVATAR, owned).items());
    }

    @Test
    void travelerUsesTheOwnedElementDepotInsteadOfTheAvatarDefault() {
        int traveler = 10000005, elementDepot = DEPOT + 1;
        put(GameData.getAvatarDataMap(), oldAvatars, traveler, GSON.fromJson(
                "{\"id\":" + traveler + ",\"avatarPromoteId\":" + PROMOTE + ",\"skillDepotId\":" + DEPOT + "}", AvatarData.class));
        depot(elementDepot, "[" + SKILL_C + ",0,0,0]", 0);
        var owned = new OwnedAvatar(traveler, elementDepot, 90, 0, 6, Map.of(SKILL_C, 9));
        assertEquals(Map.of(202, 1000, COMMON, 10, TALENT_BOOK, 2, WEEKLY, 1, CROWN, 1),
                AssistantAvatarMaterials.plan(traveler, owned).items());
    }

    @Test
    void faruzanBurstUsesItsActualGroupAndLevelInsteadOfDerivingTheRowId() {
        int faruzan = 10000076, faruzanDepot = 7601, burst = 10765;
        put(GameData.getAvatarDataMap(), oldAvatars, faruzan, GSON.fromJson(
                "{\"id\":" + faruzan + ",\"avatarPromoteId\":" + PROMOTE + ",\"skillDepotId\":7601}", AvatarData.class));
        put(GameData.getAvatarSkillDepotDataMap(), oldDepots, faruzanDepot, GSON.fromJson(
                "{\"id\":7601,\"skills\":[" + SKILL_A + "," + SKILL_B
                        + ",0,0],\"energySkill\":10765,\"attackModeSkill\":20000}", AvatarSkillDepotData.class));
        skill(burst, 7639);
        skill(20000, 0);
        for (int level = 1; level <= 10; level++) proud(7639, level, 763300 + level);
        var owned = new OwnedAvatar(faruzan, faruzanDepot, 90, 0, 6,
                Map.of(SKILL_A, 10, SKILL_B, 10, burst, 9, 20000, 1));

        var plan = AssistantAvatarMaterials.plan(faruzan, owned);
        assertEquals(Map.of(202, 1000, COMMON, 10, TALENT_BOOK, 2, WEEKLY, 1, CROWN, 1), plan.items());
        assertTrue(plan.summary().contains("3项普通天赋10级"), "The burst still needs upgrading; the aim action does not");
    }

    @ParameterizedTest
    @ValueSource(ints = {90, 95, 100})
    void completedAvatarsNeedNoBooksOrSpecialExtraLevelMaterials(int level) {
        GameData.getItemDataMap().remove(EXP_BOOK);
        var plan = AssistantAvatarMaterials.plan(AVATAR, owned(level, 0, 6, Map.of(SKILL_A, 10, SKILL_B, 10, SKILL_C, 10)));
        assertTrue(plan.items().isEmpty());
        assertTrue(plan.summary().contains("已达到目标"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"avatar", "depot", "level", "promote", "talent", "book", "item"})
    void missingNecessaryResourcesCannotProduceAPartialPackage(String missing) {
        switch (missing) {
            case "avatar" -> GameData.getAvatarDataMap().remove(AVATAR);
            case "depot" -> GameData.getAvatarSkillDepotDataMap().remove(DEPOT);
            case "level" -> GameData.getAvatarLevelDataMap().remove(45);
            case "promote" -> GameData.getAvatarPromoteDataMap().remove((PROMOTE << 8) + 3);
            case "talent" -> GameData.getProudSkillDataMap().remove(GROUP_A * 100 + 5);
            case "item" -> GameData.getItemDataMap().remove(WEEKLY);
            case "book" -> {
                var books = GameData.getItemDataMap().values().stream()
                        .filter(item -> item.getMaterialType() == MaterialType.MATERIAL_EXP_FRUIT).toList();
                for (var book : books) {
                    if (!oldItems.containsKey(book.getId())) oldItems.put(book.getId(), book);
                    GameData.getItemDataMap().remove(book.getId());
                }
            }
        }
        var error = assertThrows(IllegalArgumentException.class, () -> AssistantAvatarMaterials.plan(AVATAR, null));
        assertTrue(error.getMessage().contains("培养资源缺失"));
    }

    private OwnedAvatar owned(int level, int exp, int promote, Map<Integer, Integer> skills) {
        return new OwnedAvatar(AVATAR, DEPOT, level, exp, promote, skills);
    }

    private void item(int id, int exp) {
        var row = GSON.fromJson("{\"id\":" + id + ",\"itemType\":\"ITEM_MATERIAL\",\"materialType\":\""
                + (exp > 0 ? "MATERIAL_EXP_FRUIT" : "MATERIAL_NONE") + "\",\"itemUse\":"
                + (exp > 0 ? "[{\"useOp\":\"ITEM_USE_ADD_EXP\",\"useParam\":[\"" + exp + "\"]}]" : "[]") + "}", ItemData.class);
        row.onLoad();
        put(GameData.getItemDataMap(), oldItems, id, row);
    }

    private void skill(int id, int group) {
        put(GameData.getAvatarSkillDataMap(), oldSkills, id,
                GSON.fromJson("{\"id\":" + id + ",\"proudSkillGroupId\":" + group + "}", AvatarSkillData.class));
    }

    private void depot(int id, String skills, int energy) {
        put(GameData.getAvatarSkillDepotDataMap(), oldDepots, id,
                GSON.fromJson("{\"id\":" + id + ",\"skills\":" + skills + ",\"energySkill\":" + energy + "}", AvatarSkillDepotData.class));
    }

    private void proud(int group, int level) {
        proud(group, level, group * 100 + level);
    }

    private void proud(int group, int level, int rowId) {
        String costs = level == 1 ? "[]" : "[{\"id\":" + COMMON + ",\"count\":" + level
                + "},{\"id\":" + TALENT_BOOK + ",\"count\":2}"
                + (level >= 7 ? ",{\"id\":" + WEEKLY + ",\"count\":1}" : "")
                + (level == 10 ? ",{\"id\":" + CROWN + ",\"count\":1}" : "") + ",{}]";
        var row = GSON.fromJson("{\"proudSkillId\":" + rowId
                + ",\"proudSkillGroupId\":" + group + ",\"level\":" + level
                + ",\"coinCost\":" + (level == 1 ? 0 : level * 100) + ",\"costItems\":" + costs + "}", ProudSkillData.class);
        put(GameData.getProudSkillDataMap(), oldProud, row.getId(), row);
    }

    private static <T> void put(Map<Integer, T> rows, Map<Integer, T> old, int id, T row) {
        if (!old.containsKey(id)) old.put(id, rows.get(id));
        rows.put(id, row);
    }

    private static <T> void restore(Map<Integer, T> rows, Map<Integer, T> old) {
        old.forEach((id, row) -> {
            if (row == null) rows.remove(id);
            else rows.put(id, row);
        });
    }

    private static final class OwnedAvatar extends Avatar {
        private final int nativeId;
        private final int depot;
        private final Map<Integer, Integer> levels;

        private OwnedAvatar(int id, int depot, int level, int exp, int promote, Map<Integer, Integer> levels) {
            this.nativeId = id;
            this.depot = depot;
            this.levels = levels;
            setLevel(level);
            setExp(exp);
            setPromoteLevel(promote);
        }

        @Override public int getAvatarId() { return nativeId; }
        @Override public int getSkillDepotId() { return depot; }
        @Override public Map<Integer, Integer> getSkillLevelMap() { return levels; }
        @Override public Map<Integer, Integer> getProudSkillBonusMap() {
            throw new AssertionError("Materials must use the base talent levels, not constellation bonuses");
        }
    }
}
