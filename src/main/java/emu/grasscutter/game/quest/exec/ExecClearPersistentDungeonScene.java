package emu.grasscutter.game.quest.exec;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.QuestValueExec;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;
import emu.grasscutter.game.quest.persistent.QuestPersistentDungeonRuntime;

@QuestValueExec(QuestExec.QUEST_EXEC_CLEAR_PERSISTENT_DUNGEON_SCENE)
public final class ExecClearPersistentDungeonScene extends QuestExecHandler {
    @Override
    public boolean execute(GameQuest quest, QuestData.QuestExecParam condition, String... params) {
        return params.length == 1 && QuestPersistentDungeonRuntime.clear(quest, Integer.parseInt(params[0]));
    }
}
