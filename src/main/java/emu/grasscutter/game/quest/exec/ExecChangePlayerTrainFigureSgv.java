package emu.grasscutter.game.quest.exec;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.QuestValueExec;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;
import emu.grasscutter.game.train.SnezhnayaTrainSystem;

/** See QuestTrainFigures.json: this explicitly marked mapping is a reconstruction. */
@QuestValueExec(QuestExec.QUEST_EXEC_CHANGE_PLAYER_TRAIN_FIGURE_SGV)
public final class ExecChangePlayerTrainFigureSgv extends QuestExecHandler {
    @Override
    public boolean execute(GameQuest quest, QuestData.QuestExecParam condition, String... params) {
        if (params.length != 3) return false;
        return SnezhnayaTrainSystem.setFigure(quest.getOwner(), Integer.parseInt(params[0]),
                Integer.parseInt(params[1]), Integer.parseInt(params[2]));
    }
}
