package emu.grasscutter.game.entity;

import emu.grasscutter.game.ability.AbilityModifierController;
import java.util.*;

/** MGC lifecycle for the archer's two-second Invisible_CountDown phase. */
public final class VolkodlakBlinkState {
    private static final String ABILITY = "Monster_Volkodlak_Greyhound_Furiosa_Blink";
    private record Timed(AbilityModifierController controller, int endsAt) {}
    private final Map<Integer, Timed> active = new HashMap<>();

    public void tick(EntityMonster monster, int sceneTime) {
        var modifiers = monster.getInstancedModifiers();
        active.entrySet().removeIf(e -> modifiers.get(e.getKey()) != e.getValue().controller());
        boolean changed = false;
        for (var entry : modifiers.entrySet()) {
            var controller = entry.getValue();
            if (controller == null || controller.getAbilityData() == null
                    || !ABILITY.equals(controller.getAbilityData().abilityName)) continue;
            var data = controller.getAbilityData();
            if (data.modifiers == null || (controller.getModifierData() != data.modifiers.get("Blink_Invincible")
                    && controller.getModifierData() != data.modifiers.get("Blink_DenyLockOn"))) continue;
            var countdown = data.modifiers.get("Invisible_CountDown");
            if (countdown == null || countdown.duration == null) continue;
            float seconds = controller.getAbility() == null ? countdown.duration.get()
                    : countdown.duration.get(controller.getAbility());
            if (!Float.isFinite(seconds) || seconds <= 0) continue;
            var timed = active.get(entry.getKey());
            if (timed == null) {
                active.put(entry.getKey(), new Timed(controller, sceneTime + (int)Math.ceil(seconds)));
            } else if (sceneTime >= timed.endsAt()) {
                changed |= modifiers.remove(entry.getKey(), controller);
                active.remove(entry.getKey());
            }
        }
        if (changed) monster.refreshModifierInvincible();
    }
}
