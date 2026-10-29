package emu.grasscutter.game.ability.actions;

import com.google.protobuf.*;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.binout.AbilityModifier.AbilityModifierAction;
import emu.grasscutter.game.ability.Ability;
import emu.grasscutter.game.entity.GameEntity;

@AbilityAction(AbilityModifierAction.Type.SetRandomOverrideMapValue)
public class ActionSetRandomOverrideMapValue extends AbilityActionHandler {
    @Override
    public boolean execute(
            Ability ability, AbilityModifierAction action, ByteString abilityData, GameEntity target) {
        Float received;
        try {
            received = readClientRoll(abilityData);
        } catch (java.io.IOException e) {
            return false;
        }
        // The modifier's server-side onAdded also reaches this action with no client roll.
        // Leave the value alone until the actual action invoke arrives.
        if (received == null) return false;
        float value = received;
        if (!isWithinRange(ability, action, value)) {
            Grasscutter.getLogger()
                    .warn(
                            "技能随机参数超出范围：{}，允许 [{}, {}]；技能={}，参数={}",
                            value,
                            action.valueRangeMin.get(ability),
                            action.valueRangeMax.get(ability), ability.getData().abilityName, action.overrideMapKey);
            return true;
        }

        ability.getAbilitySpecials().put(action.overrideMapKey, value);

        return true;
    }

    /** 7.1 C2S Diona rolls captured on 2026-10-29 use fixed32 field 13, not generated field 4. */
    static Float readClientRoll(ByteString payload) throws java.io.IOException {
        var input = payload.newCodedInput();
        Float value = null;
        for (int tag; (tag = input.readTag()) != 0;) {
            if (tag == (13 << 3 | 5)) value = input.readFloat();
            else if (!input.skipField(tag)) break;
        }
        return value;
    }

    /**
     * Checks the client's roll against the bounds the ability declares.
     *
     * <p>Plenty of abilities -- Animal_Fox_Random among them -- declare no bounds at all, which
     * leaves both ranges null. A missing bound means "unconstrained", so the roll is accepted. It
     * must never be read as zero: that would reject every non-zero roll and leave the entity
     * without its random value, which is the same visible bug without the stack trace.
     */
    static boolean isWithinRange(Ability ability, AbilityModifierAction action, float value) {
        if (!Float.isFinite(value)) return false;
        if (action.valueRangeMin == null || action.valueRangeMax == null) {
            return true;
        }

        float valueRangeMin = action.valueRangeMin.get(ability);
        float valueRangeMax = action.valueRangeMax.get(ability);

        return value >= valueRangeMin && value <= valueRangeMax;
    }
}
