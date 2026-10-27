package emu.grasscutter.game.quest.content;

import static emu.grasscutter.game.quest.enums.QuestContent.QUEST_CONTENT_ENTER_MY_WORLD_SCENE;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.*;

@QuestValueContent(QUEST_CONTENT_ENTER_MY_WORLD_SCENE)
public class ContentEnterMyWorldScene extends BaseContent {
    // params[0] scene ID
    @Override
    public boolean execute(
            GameQuest quest, QuestData.QuestContentCondition condition, String paramStr, int... params) {
        if (condition.getParam() == null || condition.getParam().length == 0 || params.length == 0)
            return false;
        var owner = quest.getOwner();
        return owner.getWorld() != null && owner.getWorld().getHost() == owner
                && condition.getParam()[0] > 0 && condition.getParam()[0] == params[0]
                && owner.getSceneId() == params[0];
    }
}
