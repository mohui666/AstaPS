package emu.grasscutter.game.quest.exec;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;
import emu.grasscutter.game.quest.reconstruction.QuestActivityRuntime;
@QuestValueExec(QuestExec.QUEST_EXEC_ADD_ALCHEMY_SIM_CROP)
public final class ExecAddAlchemySimCrop extends QuestExecHandler {
    @Override public boolean execute(GameQuest quest, QuestData.QuestExecParam condition, String... params) { return QuestActivityRuntime.grantCrops(quest,params); }
}
