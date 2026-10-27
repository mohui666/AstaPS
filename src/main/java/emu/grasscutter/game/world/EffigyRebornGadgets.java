package emu.grasscutter.game.world;

import com.google.protobuf.ByteString;
import emu.grasscutter.data.binout.AbilityModifier.AbilityModifierAction;
import emu.grasscutter.game.entity.EntityClientGadget;
import emu.grasscutter.game.entity.GameEntity;

/** Native Hypostasis prism requests enter the same placement path as other monster mechanics. */
public final class EffigyRebornGadgets {
    private EffigyRebornGadgets() {}

    public static boolean createFromClientRequest(
            GameEntity owner, AbilityModifierAction action, ByteString payload) {
        var boss = MonsterSkillGadgets.rootMonster(owner);
        if (!EffigyCombatHelper.isEffigy(boss)
                || !EffigyCombatHelper.isEffigyRebornPrismGadget(action.gadgetID)) return true;
        return MonsterSkillGadgets.createFromClientRequest(owner, action, payload);
    }

    public static void onClientGadgetCreated(EntityClientGadget client) {
        if (EffigyCombatHelper.isEffigyRebornPrismGadget(client.getGadgetId())) {
            MonsterSkillGadgets.onClientGadgetCreated(client);
        }
    }
}
