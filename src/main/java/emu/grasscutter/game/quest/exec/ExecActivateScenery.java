package emu.grasscutter.game.quest.exec;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;
import emu.grasscutter.game.quest.scenery.QuestSceneryRuntime;

@QuestValueExec(QuestExec.QUEST_EXEC_ACTIVATE_SCENERY)
public final class ExecActivateScenery extends QuestExecHandler {
    @Override public boolean execute(GameQuest quest, QuestData.QuestExecParam condition, String... params) {
        if(params==null||params.length!=1) return false;
        try { return QuestSceneryRuntime.activate(quest,Integer.parseInt(params[0])); }
        catch(NumberFormatException error) { return false; }
    }
}
