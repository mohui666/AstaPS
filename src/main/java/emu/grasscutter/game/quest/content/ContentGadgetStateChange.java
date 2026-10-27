package emu.grasscutter.game.quest.content;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.QuestValueContent;
import emu.grasscutter.game.quest.enums.QuestContent;

@QuestValueContent(QuestContent.QUEST_CONTENT_GADGET_STATE_CHANGE)
public final class ContentGadgetStateChange extends BaseContent {
    static boolean matches(int[] target, String state, int... event) {
        if (target == null || target.length < 2 || state == null || event.length < 3 || target[0] <= 0
                || target[1] <= 0 || target[0] != event[0] || target[1] != event[1]) return false;
        try {
            return Integer.parseInt(state.trim()) == event[2];
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    @Override
    public boolean execute(GameQuest quest, QuestData.QuestContentCondition condition,
            String paramStr, int... params) {
        return matches(condition.getParam(), condition.getParamStr(), params);
    }
}
