package emu.grasscutter.game.quest.content;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.QuestValueContent;
import emu.grasscutter.game.quest.enums.QuestContent;

@QuestValueContent(QuestContent.QUEST_CONTENT_AVATAR_RENAME_COMPLETE)
public final class ContentAvatarRenameComplete extends BaseContent {
    public static boolean matchesCurrent(GameQuest quest, QuestData.QuestContentCondition condition) {
        return condition.getParam() != null && condition.getParam().length > 0
                && quest.getOwner().getQuestAvatarRenames().hasName(condition.getParam()[0]);
    }

    @Override
    public boolean execute(GameQuest quest, QuestData.QuestContentCondition condition, String value, int... parameters) {
        return parameters.length > 0 && condition.getParam() != null && condition.getParam().length > 0
                && parameters[0] == condition.getParam()[0] && matchesCurrent(quest, condition);
    }
}
