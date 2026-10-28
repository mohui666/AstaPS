package emu.grasscutter.game.quest.exec;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.QuestValueExec;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;
import emu.grasscutter.game.quest.world.QuestTemporaryResourceRuntime;

@QuestValueExec(QuestExec.QUEST_EXEC_DEL_SCENE_TEMP_RES)
public final class ExecDelSceneTempRes extends QuestExecHandler {
    @Override
    public boolean execute(GameQuest quest, QuestData.QuestExecParam condition, String... params) {
        if (params.length < 1 || params.length > 3) return false;
        return QuestTemporaryResourceRuntime.delete(quest.getOwner(), Integer.parseInt(params[0]),
                params.length > 1 ? Integer.parseInt(params[1]) : 0,
                params.length > 2 ? Integer.parseInt(params[2]) : 0);
    }
}
