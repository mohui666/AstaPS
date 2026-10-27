package emu.grasscutter.game.entity;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.ByteString;
import emu.grasscutter.data.binout.AbilityData;
import emu.grasscutter.data.binout.AbilityModifier;
import emu.grasscutter.data.binout.AbilityModifier.AbilityModifierAction;
import emu.grasscutter.game.ability.Ability;
import emu.grasscutter.game.ability.AbilityModifierController;
import emu.grasscutter.game.ability.actions.ActionRemoveModifier;
import emu.grasscutter.game.ability.actions.ActionRemoveUniqueModifier;
import emu.grasscutter.game.world.Position;
import emu.grasscutter.net.proto.SceneEntityInfoOuterClass.SceneEntityInfo;
import it.unimi.dsi.fastutil.ints.Int2FloatMap;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

class UniqueModifierLimboTest {
    private static final String ABILITY = "Monster_Effigy_Electric_HpDetect";

    @Test
    void uniqueAndOrdinaryRemovalBothReleaseTheirLastTrackedLimbo() throws Exception {
        for (boolean unique : new boolean[] {false, true}) {
            var entity = new StubEntity();
            var ability = ability();
            var action = tracked(entity, ability, "UNIQUE_Effigy_Limbo", 0.3f);

            boolean removed = unique
                    ? new ActionRemoveUniqueModifier().execute(ability, action, ByteString.EMPTY, entity)
                    : new ActionRemoveModifier().execute(ability, action, ByteString.EMPTY, entity);

            assertTrue(removed);
            assertFalse(ability.getModifiers().containsKey(action.modifierName));
            assertFalse(entity.isLimbo());
        }
    }

    @Test
    void removingOneUniqueModifierKeepsTheOtherLimboAndItsThreshold() throws Exception {
        var entity = new StubEntity();
        var ability = ability();
        var high = tracked(entity, ability, "HighPhase", 0.8f);
        tracked(entity, ability, "OtherPhase", 0.3f);

        assertTrue(new ActionRemoveUniqueModifier().execute(ability, high, ByteString.EMPTY, entity));

        assertTrue(entity.isLimbo());
        assertEquals(0.3f, entity.getLimboHpThreshold(), 0.000001f);
        assertTrue(ability.getModifiers().containsKey("OtherPhase"));
        assertFalse(ability.getModifiers().containsKey("HighPhase"));
    }

    @Test
    void unrelatedOrMissingUniqueRemovalDoesNotReleaseAnUntrackedLimbo() throws Exception {
        var entity = new StubEntity();
        var ability = ability();
        var limbo = new AbilityModifier();
        limbo.state = AbilityModifier.State.Limbo;
        entity.onAddAbilityModifier(limbo);
        var unrelated = new AbilityModifierAction();
        unrelated.modifierName = "VisualOnly";
        ability.getModifiers().put("VisualOnly", new AbilityModifierController(ability, ability.getData(), new AbilityModifier()));

        assertTrue(new ActionRemoveUniqueModifier().execute(ability, unrelated, ByteString.EMPTY, entity));
        assertTrue(entity.isLimbo());
        assertFalse(new ActionRemoveUniqueModifier().execute(ability, unrelated, ByteString.EMPTY, entity));
        assertTrue(entity.isLimbo());
    }

    private static AbilityModifierAction tracked(StubEntity entity, AbilityStub ability, String name, float threshold) {
        var modifier = new AbilityModifier();
        modifier.state = AbilityModifier.State.Limbo;
        ability.getModifiers().put(name, new AbilityModifierController(ability, ability.getData(), modifier));
        entity.trackLimboModifier(ABILITY, name, threshold);
        var action = new AbilityModifierAction();
        action.modifierName = name;
        return action;
    }

    // These remove handlers need no world or server; avoid constructing their unrelated services.
    private static AbilityStub ability() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var ability = (AbilityStub) ((Unsafe) field.get(null)).allocateInstance(AbilityStub.class);
        ability.data = new AbilityData();
        ability.data.abilityName = ABILITY;
        ability.modifiers = new HashMap<>();
        return ability;
    }

    private static final class AbilityStub extends Ability {
        AbilityData data;
        Map<String, AbilityModifierController> modifiers;
        private AbilityStub() { super(null, null, null); }
        @Override public AbilityData getData() { return data; }
        @Override public Map<String, AbilityModifierController> getModifiers() { return modifiers; }
    }

    private static final class StubEntity extends GameEntity {
        private final Int2FloatMap properties = new Int2FloatOpenHashMap();
        StubEntity() { super(null); }
        @Override public void initAbilities() { }
        @Override public int getEntityTypeId() { return 0; }
        @Override public Int2FloatMap getFightProperties() { return properties; }
        @Override public Position getPosition() { return new Position(); }
        @Override public Position getRotation() { return new Position(); }
        @Override public SceneEntityInfo toProto() { return SceneEntityInfo.getDefaultInstance(); }
    }
}
