package emu.grasscutter.game.quest.reconstruction;

import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.config.ConfigEntityMonster;
import emu.grasscutter.data.excels.monster.MonsterData;
import emu.grasscutter.game.entity.EntityMonster;
import emu.grasscutter.game.entity.EntityAvatar;
import emu.grasscutter.game.entity.EntityClientGadget;
import emu.grasscutter.game.entity.EntityGadget;
import emu.grasscutter.game.props.ElementType;
import emu.grasscutter.game.world.Position;
import emu.grasscutter.game.world.Scene;
import emu.grasscutter.net.proto.SceneEntityInfoOuterClass.SceneEntityInfo;
import emu.grasscutter.net.proto.PropChangeReasonOuterClass.PropChangeReason;
import emu.grasscutter.net.proto.ChangHpReasonOuterClass.ChangHpReason;

/** Two explicitly rebuilt single-phase fights using ordinary combat models.
 * Source monster IDs and real HP/death events remain native; missing boss stage scripts do not run.
 * The client is told the replacement model ID, rather than receiving an unplayable native boss AI.
 */
public final class ReconstructedStoryMonster extends EntityMonster {
    private ReconstructedStoryMonster(Scene scene,MonsterData source,Position position,Position rotation,int level) {
        super(scene,source,position,rotation,level);
    }

    public static EntityMonster create(Scene scene,MonsterData source,Position position,Position rotation,int level) {
        return modelId(source.getId()) == 0 ? new EntityMonster(scene,source,position,rotation,level)
                : new ReconstructedStoryMonster(scene,source,position,rotation,level);
    }

    private static int modelId(int source) {
        return switch(source) {
            case 29090102 -> 25010301; // 402415: native whale encounter -> custom single-phase fighter.
            case 26260101 -> 25010201; // 7500509: Wayob field encounter -> custom single-phase fighter.
            default -> 0;
        };
    }

    public int getCombatModelId() { return modelId(getMonsterData().getId()); }

    @Override public ConfigEntityMonster getConfigEntityMonster() {
        var mapping=GameData.getMonsterMappingMap().get(getCombatModelId());
        var config=mapping==null?null:GameData.getMonsterConfigData().get(mapping.getMonsterJson());
        if(config==null)throw new IllegalStateException("Missing explicit reconstructed combat-model config: "+getCombatModelId());
        return config;
    }

    @Override public void initAbilities() {
        // Called by EntityMonster's constructor after monsterData has been assigned.
        // No source affix, transition controller, field or invisible-node boss ability is installed.
        var abilities=getConfigEntityMonster().getAbilities();
        if(abilities==null)return;
        for(var entry:abilities) {
            var ability=GameData.getAbilityData(entry.abilityName);
            if(ability!=null)getWorld().getHost().getAbilityManager().addAbilityToEntity(this,ability);
        }
    }

    @Override public int getEntityTypeId() { return getCombatModelId(); }

    @Override public void damage(float amount,int attackerId,ElementType element,PropChangeReason reason,ChangHpReason hpReason) {
        var owner=getWorld().getHost();
        var attacker=getScene().getEntityById(attackerId);
        boolean owned=attacker instanceof EntityAvatar avatar && avatar.getPlayer()==owner
                || attacker instanceof EntityClientGadget effect && effect.getOwner()==owner
                || attacker instanceof EntityGadget effect && effect.getOwner() instanceof EntityAvatar avatar
                        && avatar.getPlayer()==owner;
        if(owner==null || owner.getScene()!=getScene() || !owned)return;
        super.damage(amount,attackerId,element,reason,hpReason);
    }

    @Override public SceneEntityInfo toProto() {
        var result=super.toProto().toBuilder();
        var model=GameData.getMonsterDataMap().get(getCombatModelId());
        if(model==null)throw new IllegalStateException("Missing explicit reconstructed combat model: "+getCombatModelId());
        result.getMonsterBuilder().setMonsterId(getCombatModelId()).setPoseId(0)
                .clearAiConfigId().clearSummonTagMap().clearWeaponList().clearAffixList()
                .addAllAffixList(model.getAffix());
        return result.build();
    }
}
