package emu.grasscutter.game.quest.conditions;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.QuestValueCond;
import emu.grasscutter.game.quest.enums.QuestCond;

@QuestValueCond(QuestCond.QUEST_COND_COOP_POINT_RUNNING)
public final class ConditionCoopPointRunning extends BaseCondition {
    @Override
    public boolean execute(Player player, QuestData data, QuestData.QuestAcceptCondition condition,
            String paramStr, int... params) {
        int[] p = condition.getParam();
        var state = player.getHangouts();
        var run = state == null ? null : state.getRun();
        return p != null && p.length > 0 && run != null && run.getPointId() == p[0]
                && run.getCurrent() != null && run.getCurrent().getStatus() == 1;
    }
}
