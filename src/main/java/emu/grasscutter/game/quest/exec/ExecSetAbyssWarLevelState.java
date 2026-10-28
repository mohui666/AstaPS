package emu.grasscutter.game.quest.exec;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.QuestValueExec;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;
import emu.grasscutter.game.quest.war.QuestWarCatalog;
import emu.grasscutter.game.quest.war.QuestWarRuntime;

@QuestValueExec(QuestExec.QUEST_EXEC_SET_ABYSS_WAR_LEVEL_STATE)
public final class ExecSetAbyssWarLevelState extends QuestExecHandler {
    @Override
    public boolean execute(GameQuest quest, QuestData.QuestExecParam condition, String... params) {
        return params.length == 2 && QuestWarRuntime.setLevels(quest.getOwner(),
                QuestWarCatalog.parseIds(params[0]), Integer.parseInt(params[1].trim()));
    }
}
