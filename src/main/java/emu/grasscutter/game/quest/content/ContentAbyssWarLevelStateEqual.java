package emu.grasscutter.game.quest.content;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.QuestValueContent;
import emu.grasscutter.game.quest.enums.QuestContent;
import emu.grasscutter.game.quest.war.QuestWarRuntime;

@QuestValueContent(QuestContent.QUEST_CONTENT_ABYSS_WAR_LEVEL_STATE_EQUAL)
public final class ContentAbyssWarLevelStateEqual extends BaseContent {
    @Override
    public boolean execute(GameQuest quest, QuestData.QuestContentCondition condition,
            String paramStr, int... params) {
        return QuestWarRuntime.matchesCurrent(quest, condition);
    }
}
