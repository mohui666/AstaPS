package emu.grasscutter.game.entity;

import emu.grasscutter.game.props.FightProperty;
import java.util.Map;

/** Per-instance operator overrides; never edits the shared MonsterData. */
public final class MonsterSpawnProperties {
    private MonsterSpawnProperties() {}

    public static void apply(EntityMonster monster, Map<FightProperty, Float> properties) {
        properties.forEach(monster::setFightProperty);
        FightProperty.forEachCompoundProperty(compound -> {
            if (!properties.containsKey(compound.getResult())
                    && (properties.containsKey(compound.getBase()) || properties.containsKey(compound.getFlat())
                    || properties.containsKey(compound.getPercent()))) {
                monster.setFightProperty(compound.getResult(), monster.getFightProperty(compound.getFlat())
                        + monster.getFightProperty(compound.getBase()) * (1f + monster.getFightProperty(compound.getPercent())));
            }
        });
        float maximum = monster.getFightProperty(FightProperty.FIGHT_PROP_MAX_HP);
        if (!Float.isFinite(maximum) || maximum <= 0)
            throw new IllegalArgumentException("计算后的生命值上限必须是有限正数。");
        for (float value : monster.getFightProperties().values())
            if (!Float.isFinite(value)) throw new IllegalArgumentException("属性计算溢出，请减小填写的数值。");
    }
}
