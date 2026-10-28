package emu.grasscutter.game.quest.conditions;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.QuestValueCond;
import emu.grasscutter.game.quest.coop.CoopSystem;
import emu.grasscutter.game.quest.enums.QuestCond;

@QuestValueCond(QuestCond.QUEST_COND_MAIN_COOP_ENTER_SAVE_POINT)
public final class ConditionMainCoopEnterSavePoint extends BaseCondition {
    @Override
    public boolean execute(Player player, QuestData data, QuestData.QuestAcceptCondition condition,
            String paramStr, int... params) {
        int[] p = condition.getParam();
        return p != null && p.length >= 2 && CoopSystem.hasSavePoint(player, p[0], p[1]);
    }
}
