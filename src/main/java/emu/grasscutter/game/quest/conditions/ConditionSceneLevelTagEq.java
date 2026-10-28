package emu.grasscutter.game.quest.conditions;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.QuestCond;

@QuestValueCond(QuestCond.QUEST_COND_SCENE_LEVEL_TAG_EQ)
public final class ConditionSceneLevelTagEq extends BaseCondition {
    @Override
    public boolean execute(Player owner, QuestData data, QuestData.QuestAcceptCondition condition,
            String paramStr, int... params) {
        return condition.getParam() != null && condition.getParam().length > 0
                && QuestSceneTags.active(owner, condition.getParam()[0]);
    }
}
