package emu.grasscutter.game.quest.exec;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.QuestValueExec;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;
import emu.grasscutter.game.quest.world.QuestSceneOverrideRuntime;

@QuestValueExec(QuestExec.QUEST_EXEC_MODIFY_DUNGEON_EXIT_POINT)
public final class ExecModifyDungeonExitPoint extends QuestExecHandler {
    @Override
    public boolean execute(GameQuest quest, QuestData.QuestExecParam condition, String... params) {
        return params.length == 3 && QuestSceneOverrideRuntime.setDungeonExit(quest.getOwner(),
                Integer.parseInt(params[0]), Integer.parseInt(params[1]), Integer.parseInt(params[2]));
    }
}
