package emu.grasscutter.game.ability.actions;

import com.google.protobuf.ByteString;
import emu.grasscutter.data.binout.AbilityModifier.AbilityModifierAction;
import emu.grasscutter.game.ability.Ability;
import emu.grasscutter.game.ability.PredicateEvaluator;
import emu.grasscutter.game.entity.EntityAvatar;
import emu.grasscutter.game.entity.GameEntity;
import emu.grasscutter.game.quest.QuestClimateRuntime;
import java.util.List;
import java.util.Map;

/** Runs the configured delta; the invoke payload supplies no climate or amount. */
@AbilityAction(AbilityModifierAction.Type.AddClimateMeter)
public final class ActionAddClimateMeter extends AbilityActionHandler {
    @Override public boolean execute(Ability ability, AbilityModifierAction action,
            ByteString abilityData, GameEntity target) {
        target = resolveTarget(ability, target, action.target);
        if (!(target instanceof EntityAvatar avatar) || avatar.getPlayer() != ability.getPlayerOwner())
            return false;
        if (action.predicates != null && !action.predicates.isEmpty()) {
            @SuppressWarnings("unchecked")
            var predicates = (List<Map<String, Object>>) (List<?>) action.predicates;
            if (!PredicateEvaluator.all(predicates, ability, ability.getOwner(), target, action)) return true;
        }
        return QuestClimateRuntime.addMeter(avatar.getPlayer(), action.climateType,
                action.writtenValue().get(propertiesFor(ability), 0f));
    }
}
