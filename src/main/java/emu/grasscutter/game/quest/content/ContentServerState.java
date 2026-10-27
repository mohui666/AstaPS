package emu.grasscutter.game.quest.content;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.QuestSceneConditions;
import emu.grasscutter.game.quest.enums.QuestContent;

/** Registered for predicates whose evidence is a server scene or saved quest state. */
public final class ContentServerState extends BaseContent {
    @Override
    public boolean execute(GameQuest quest, QuestData.QuestContentCondition condition,
            String paramStr, int... params) {
        var type = condition.getType();
        if (type == QuestContent.QUEST_CONTENT_QUEST_GLOBAL_VAR_EQUAL
                || type == QuestContent.QUEST_CONTENT_QUEST_GLOBAL_VAR_NOT_EQUAL
                || type == QuestContent.QUEST_CONTENT_QUEST_GLOBAL_VAR_GREATER
                || type == QuestContent.QUEST_CONTENT_QUEST_GLOBAL_VAR_LESS) {
            if (condition.getParam() == null || condition.getParam().length == 0
                    || params.length == 0 || params[0] != condition.getParam()[0]) return false;
        }
        return matchesCurrent(quest, condition);
    }

    public static boolean matchesCurrent(GameQuest quest, QuestData.QuestContentCondition condition) {
        var owner = quest.getOwner();
        int[] target = condition.getParam();
        if (target == null || target.length == 0) return false;
        return switch (condition.getType()) {
            case QUEST_CONTENT_PRESENT_AT_SPECIFIC_SCENE -> sceneReady(owner)
                    && target[0] > 0 && owner.getSceneId() == target[0];
            case QUEST_CONTENT_LEAVE_SCENE_RANGE, QUEST_CONTENT_LEAVE_SCENE_RANGE_AND_ROOM ->
                    sceneReady(owner) && QuestSceneConditions.outsideRange(owner.getSceneId(),
                            owner.getPosition(), target, condition.getParamStr(),
                            condition.getType() == QuestContent.QUEST_CONTENT_LEAVE_SCENE_RANGE_AND_ROOM);
            case QUEST_CONTENT_QUEST_GLOBAL_VAR_EQUAL, QUEST_CONTENT_QUEST_GLOBAL_VAR_NOT_EQUAL,
                    QUEST_CONTENT_QUEST_GLOBAL_VAR_GREATER, QUEST_CONTENT_QUEST_GLOBAL_VAR_LESS ->
                    target.length >= 2 && target[0] > 0
                            && compare(condition.getType(),
                                    owner.getQuestManager().getQuestGlobalVarValue(target[0]), target[1]);
            case QUEST_CONTENT_PARENT_QUEST_STATE_EQUAL -> {
                var parent = owner.getQuestManager().getMainQuestById(target[0]);
                yield target.length >= 2 && parent != null && parent.getState().getValue() == target[1];
            }
            default -> false;
        };
    }

    private static boolean sceneReady(Player owner) {
        return owner.getScene() != null && owner.getScene().getId() == owner.getSceneId()
                && owner.getSceneLoadState() == Player.SceneLoadState.LOADED;
    }

    static boolean compare(QuestContent type, int actual, int expected) {
        return switch (type) {
            case QUEST_CONTENT_QUEST_GLOBAL_VAR_EQUAL -> actual == expected;
            case QUEST_CONTENT_QUEST_GLOBAL_VAR_NOT_EQUAL -> actual != expected;
            case QUEST_CONTENT_QUEST_GLOBAL_VAR_GREATER -> actual > expected;
            case QUEST_CONTENT_QUEST_GLOBAL_VAR_LESS -> actual < expected;
            default -> false;
        };
    }
}
