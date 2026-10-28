package emu.grasscutter.game.quest.content;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.QuestValueContent;
import emu.grasscutter.game.quest.enums.QuestContent;
import emu.grasscutter.game.quest.evidence.QuestEvidenceCatalog;

@QuestValueContent(QuestContent.QUEST_CONTENT_EVENTS_ITEM_STATUS)
public final class ContentEventsItemStatus extends BaseContent {
    public static boolean matchesCurrent(GameQuest quest, QuestData.QuestContentCondition condition) {
        var parameters = condition.getParam();
        if (parameters == null || parameters.length < 2 || condition.getParamStr().isBlank()) return false;
        var event = QuestEvidenceCatalog.installed().event(parameters[0]);
        if (event == null || !event.mainQuestIds().contains(quest.getMainQuestId())) return false;
        return QuestEvidenceCatalog.parseItemIds(condition.getParamStr()).stream()
                .allMatch(itemId -> quest.getOwner().getQuestEvidence().getStatus(parameters[0], itemId) == parameters[1]);
    }

    @Override
    public boolean execute(GameQuest quest, QuestData.QuestContentCondition condition, String paramStr, int... params) {
        return params.length > 0 && condition.getParam() != null
                && condition.getParam().length > 0 && condition.getParam()[0] == params[0]
                && matchesCurrent(quest, condition);
    }
}
