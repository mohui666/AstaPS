package emu.grasscutter.game.quest.exec;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.QuestValueExec;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;
import java.util.Arrays;

/** Resource form [groupId,...] registers groups in the quest owner's current scene. */
@QuestValueExec(QuestExec.QUEST_EXEC_REGISTER_DYNAMIC_GROUP_ONLY)
public final class ExecRegisterDynamicGroupOnly extends QuestExecHandler {
    @Override
    public boolean execute(GameQuest quest, QuestData.QuestExecParam condition, String... params) {
        if (params.length != 1 || params[0].isBlank()) return false;
        var scene = quest.getOwner().getScene();
        if (scene == null) return false;
        int[] groups = Arrays.stream(params[0].split(","))
                .map(String::trim).mapToInt(Integer::parseInt).toArray();
        if (Arrays.stream(groups).anyMatch(id -> id <= 0)) return false;
        var register = new ExecRegisterDynamicGroup();
        for (int group : groups) {
            if (!register.execute(quest, condition, String.valueOf(scene.getId()), String.valueOf(group)))
                return false;
        }
        return true;
    }
}
