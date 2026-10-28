package emu.grasscutter.game.quest.content;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.QuestContent;
import emu.grasscutter.game.quest.world.QuestCookingProgress;

@QuestValueContent(QuestContent.QUEST_CONTENT_FINISH_ANY_FOOD_COOK)
public final class ContentFinishAnyFoodCook extends BaseContent {
    @Override
    public boolean execute(GameQuest quest, QuestData.QuestContentCondition condition,
            String paramStr, int... params) {
        return QuestCookingProgress.matches(quest, condition, paramStr, params);
    }
}
