package emu.grasscutter.game.quest.exec;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.QuestValueExec;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;
import emu.grasscutter.game.tps.TpsGlassesChatSystem;

@QuestValueExec(QuestExec.QUEST_EXEC_SET_TPS_GLASSES_CHAT_PROGRESS)
public final class ExecSetTpsGlassesChatProgress extends QuestExecHandler {
    @Override
    public boolean execute(GameQuest quest, QuestData.QuestExecParam condition, String... params) {
        if (params.length != 2) return false;
        return TpsGlassesChatSystem.setProgress(quest.getOwner(),
                Integer.parseInt(params[0]), Integer.parseInt(params[1]));
    }
}
