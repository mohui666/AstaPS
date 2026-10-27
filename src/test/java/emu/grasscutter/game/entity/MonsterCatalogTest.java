package emu.grasscutter.game.entity;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.grasscutter.data.excels.monster.MonsterData;
import org.junit.jupiter.api.Test;

class MonsterCatalogTest {
    private static final Gson GSON = new Gson();

    @Test
    void namesAreJoinedByExactNativeId() {
        var monster = GSON.fromJson("""
                {"id":20010101,"monsterName":"Unknown_Internal_Name","type":"MONSTER_ORDINARY"}
                """, MonsterData.class);
        var entry = MonsterCatalog.entry(monster);
        assertEquals("草史莱姆", entry.name());
        assertEquals("Dendro Slime", entry.en());
        assertTrue(entry.named());
        assertEquals("Unknown_Internal_Name", entry.internalName());
    }

    @Test
    void internalVariantsRemainSelectableWithoutInventedNames() {
        var monster = GSON.fromJson("""
                {"id":99999999,"monsterName":"Monster_Internal_TestVariant","type":"MONSTER_BOSS"}
                """, MonsterData.class);
        var entry = MonsterCatalog.entry(monster);
        assertEquals("Monster_Internal_TestVariant", entry.name());
        assertEquals("MONSTER_BOSS", entry.type());
        assertFalse(entry.named());
        assertEquals("", entry.en());
    }

    @Test
    void entityCapacityIsAppliedWithoutIntegerOverflow() {
        assertEquals(10, MonsterCatalog.allowedAmount(10, 50, 100));
        assertEquals(50, MonsterCatalog.allowedAmount(Integer.MAX_VALUE, 50, 100));
        assertEquals(0, MonsterCatalog.allowedAmount(1, 100, 100));
        assertEquals(0, MonsterCatalog.allowedAmount(1, 110, 100));
    }

    @Test
    void missingPlayerCannotGenerateAnything() {
        assertThrows(IllegalArgumentException.class, () -> MonsterCatalog.spawn(null, 20010101, 1, 90, 5));
    }
}
