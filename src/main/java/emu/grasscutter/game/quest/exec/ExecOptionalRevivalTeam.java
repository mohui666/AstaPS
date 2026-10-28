package emu.grasscutter.game.quest.exec;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.QuestValueExec;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;

/** Native BNFIAFPKOJN enum names are preserved in global 7.1 metadata/fields.csv. */
@QuestValueExec(QuestExec.QUEST_EXEC_OPTIONAL_REVIVAL_TEAM)
public final class ExecOptionalRevivalTeam extends QuestExecHandler {
    @Override
    public boolean execute(GameQuest quest, QuestData.QuestExecParam condition, String... params) {
        return params.length == 1 && quest.getOwner().getTeamManager()
                .optionalRevivalTeam(Integer.parseInt(params[0]));
    }
}
