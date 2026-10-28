package emu.grasscutter.game.quest.content;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.QuestContent;

@QuestValueContent(QuestContent.QUEST_CONTENT_LEAVE_VEHICLE)
public final class ContentLeaveVehicle extends BaseContent {
    @Override
    public boolean execute(GameQuest quest, QuestData.QuestContentCondition condition, String paramStr, int... params) {
        return params.length > 0 && condition.getParam() != null && condition.getParam().length > 0
                && (condition.getParam()[0] == 0 || condition.getParam()[0] == params[0]);
    }
}
