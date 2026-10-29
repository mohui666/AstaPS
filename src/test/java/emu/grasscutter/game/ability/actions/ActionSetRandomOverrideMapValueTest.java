package emu.grasscutter.game.ability.actions;

import static org.junit.jupiter.api.Assertions.*;
import com.google.protobuf.ByteString;
import emu.grasscutter.data.binout.AbilityModifier.AbilityModifierAction;
import emu.grasscutter.data.common.DynamicFloat;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class ActionSetRandomOverrideMapValueTest {
    @Test void readsTheRealDionaClientFieldInsteadOfDefaultingToZero() throws Exception {
        var bytes = HexFormat.of().parseHex("6d2c9c7d3f");
        float value = ActionSetRandomOverrideMapValue.readClientRoll(ByteString.copyFrom(bytes));
        assertEquals(Float.intBitsToFloat(0x3f7d9c2c), value);
        assertTrue(value >= .8f && value <= 1f);
        assertNull(ActionSetRandomOverrideMapValue.readClientRoll(ByteString.EMPTY));
        assertNull(ActionSetRandomOverrideMapValue.readClientRoll(ByteString.copyFrom(HexFormat.of().parseHex("252c9c7d3f"))));
    }

    @Test void nonFiniteValuesAreRejectedBeforeReadingDynamicBounds() {
        var action = new AbilityModifierAction();
        action.valueRangeMin = new DynamicFloat(.8f);
        action.valueRangeMax = new DynamicFloat(1f);
        assertFalse(ActionSetRandomOverrideMapValue.isWithinRange(null, action, Float.NaN));
        assertFalse(ActionSetRandomOverrideMapValue.isWithinRange(null, action, Float.POSITIVE_INFINITY));
    }
}
