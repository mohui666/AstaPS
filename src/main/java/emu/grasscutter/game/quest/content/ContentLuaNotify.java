package emu.grasscutter.game.quest.content;

import static emu.grasscutter.game.quest.enums.QuestContent.QUEST_CONTENT_LUA_NOTIFY;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.*;

@QuestValueContent(QUEST_CONTENT_LUA_NOTIFY)
public class ContentLuaNotify extends BaseContent {

    @Override
    public boolean execute(
            GameQuest quest, QuestData.QuestContentCondition condition, String paramStr, int... params) {
        if (emu.grasscutter.game.quest.coop.CoopProgression.ownsLuaCondition(quest, condition))
            return emu.grasscutter.game.quest.coop.CoopProgression.matchesLuaCondition(quest, condition);
        if (emu.grasscutter.game.quest.war.ReconstructedWarRuntime.ownsLuaCondition(quest,condition))
            return emu.grasscutter.game.quest.war.ReconstructedWarRuntime.matchesLuaNotification(quest,condition);
        if (emu.grasscutter.game.quest.reconstruction.StoryReconstructionRuntime.ownsLuaCondition(quest,condition))
            return emu.grasscutter.game.quest.reconstruction.StoryReconstructionRuntime.matchesLuaNotification(quest,condition);
        if (emu.grasscutter.game.quest.reconstruction.SnezhnayaIceQuestReconstruction.ownsLuaCondition(quest,condition))
            return emu.grasscutter.game.quest.reconstruction.SnezhnayaIceQuestReconstruction.matchesLuaNotification(quest,condition);
        var targetAmount = condition.getCount();
        if (targetAmount == 0) {
            targetAmount = 1;
        }
        return targetAmount
                <= quest.getOwner().getPlayerProgress().getCurrentProgress(condition.getParamStr());
    }
}
