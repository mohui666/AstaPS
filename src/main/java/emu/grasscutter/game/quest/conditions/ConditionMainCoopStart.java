package emu.grasscutter.game.quest.conditions;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.QuestValueCond;
import emu.grasscutter.game.quest.enums.QuestCond;

@QuestValueCond(QuestCond.QUEST_COND_MAIN_COOP_START)
public class ConditionMainCoopStart extends BaseCondition {

    @Override
    public boolean execute(
            Player owner,
            QuestData questData,
            QuestData.QuestAcceptCondition condition,
            String paramStr,
            int... params) {
        return condition.getParam() != null && condition.getParam().length > 0
                && emu.grasscutter.game.quest.coop.CoopSystem.running(owner, condition.getParam()[0]);
    }
}
