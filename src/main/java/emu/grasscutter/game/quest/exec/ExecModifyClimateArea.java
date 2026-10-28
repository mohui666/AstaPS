package emu.grasscutter.game.quest.exec;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.QuestClimateAreas;
import emu.grasscutter.game.quest.QuestValueExec;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;

@QuestValueExec(QuestExec.QUEST_EXEC_MODIFY_CLIMATE_AREA)
public final class ExecModifyClimateArea extends QuestExecHandler {
    @Override
    public boolean execute(GameQuest quest, QuestData.QuestExecParam condition, String... params) {
        if (params.length != 3) return false;
        try {
            return QuestClimateAreas.change(quest.getOwner(), Integer.parseInt(params[0].trim()),
                    Integer.parseInt(params[1].trim()), Integer.parseInt(params[2].trim()));
        } catch (NumberFormatException error) {
            return false;
        }
    }
}
