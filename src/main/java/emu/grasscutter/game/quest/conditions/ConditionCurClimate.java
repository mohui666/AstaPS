package emu.grasscutter.game.quest.conditions;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.PlayerProperty;
import emu.grasscutter.game.quest.QuestValueCond;
import emu.grasscutter.game.quest.enums.QuestCond;

/** Native condition 54 reads the current area, not the residual meter's climate. */
@QuestValueCond(QuestCond.QUEST_COND_CUR_CLIMATE)
public final class ConditionCurClimate extends BaseCondition {
    @Override public boolean execute(Player owner, QuestData questData,
            QuestData.QuestAcceptCondition condition, String paramStr, int... params) {
        var target = condition.getParam();
        return target != null && target.length >= 1 && target.length <= 2
                && target[0] >= 0 && target[0] < 14
                && owner.getProperty(PlayerProperty.PROP_CUR_CLIMATE_AREA_CLIMATE_TYPE) == target[0]
                && (target.length == 1 || target[1] == 0
                    || owner.getProperty(PlayerProperty.PROP_CUR_CLIMATE_AREA_ID) == target[1]);
    }
}
