package emu.grasscutter.game.quest.content;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.entity.EntityGadget;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.QuestSceneConditions;
import emu.grasscutter.game.quest.QuestValueContent;
import emu.grasscutter.game.quest.enums.QuestContent;
import emu.grasscutter.game.train.SnezhnayaTrainSystem;
import emu.grasscutter.game.world.Position;

/** Native content 196: a successful player-train call at the quest's scene dummy point. */
@QuestValueContent(QuestContent.QUEST_CONTENT_CALL_PLAYER_TRAIN_IN_RANGE)
public final class ContentCallPlayerTrainInRange extends BaseContent {
    public static boolean matches(GameQuest quest, QuestData.QuestContentCondition condition,
            EntityGadget train) {
        return matches(quest, condition, train, train == null ? null : train.getPosition());
    }

    public static boolean matches(GameQuest quest, QuestData.QuestContentCondition condition,
            EntityGadget train, Position callPosition) {
        int[] target = condition.getParam();
        var scene = quest.getOwner().getScene();
        if (target == null || target.length < 2 || target[0] <= 0 || target[1] <= 0
                || scene == null || scene.getId() != target[0] || train == null
                || train.getGadgetId() != SnezhnayaTrainSystem.PLAYER_TRAIN_GADGET
                || train.getScene() != scene || scene.getEntityById(train.getId()) != train || callPosition == null)
            return false;
        var center = QuestSceneConditions.dummyPoint(target[0], condition.getParamStr());
        return center != null && callPosition.computeDistance(center) <= target[1];
    }

    @Override
    public boolean execute(GameQuest quest, QuestData.QuestContentCondition condition,
            String value, int... parameters) {
        // The last three values snapshot the server entity position at the successful call check.
        // Its next movement must not undo this event while it waits in the quest executor.
        if (parameters.length != 6 || parameters[2] != quest.getSubQuestId()
                || quest.getOwner().getScene() == null
                || parameters[0] != quest.getOwner().getScene().getId()) return false;
        var entity = quest.getOwner().getScene().getEntityById(parameters[1]);
        var position = new Position(Float.intBitsToFloat(parameters[3]),
                Float.intBitsToFloat(parameters[4]), Float.intBitsToFloat(parameters[5]));
        return entity instanceof EntityGadget train && matches(quest, condition, train, position);
    }
}
