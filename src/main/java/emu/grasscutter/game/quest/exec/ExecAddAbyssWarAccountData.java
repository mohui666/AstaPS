package emu.grasscutter.game.quest.exec;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.QuestValueExec;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;
import emu.grasscutter.game.quest.war.QuestWarRuntime;

@QuestValueExec(QuestExec.QUEST_EXEC_ADD_ABYSS_WAR_ACCOUNT_DATA)
public final class ExecAddAbyssWarAccountData extends QuestExecHandler {
    @Override
    public boolean execute(GameQuest quest, QuestData.QuestExecParam condition, String... params) {
        if (params.length == 4 && quest.getSubQuestId() == 501313 && "1".equals(params[1])) {
            int account = Integer.parseInt(params[0]);
            // Authorized custom accounting uses observed rescues/kills/gates, not guessed native branches.
            String expected = switch (account) {
                case 1002 -> "150,150|-300,-200";
                case 1004 -> "1,1|0,0";
                case 1003 -> "2880,2880|-2880,-2880";
                default -> "";
            };
            return expected.equals(params[2]+"|"+params[3])
                    && emu.grasscutter.game.quest.war.ReconstructedWarRuntime.settleAccount(quest,account);
        }
        if (params.length != 2) {
            Grasscutter.getLogger().warn("Quest {} needs the native conditional AbyssWar account action: {}",
                    quest.getSubQuestId(), java.util.Arrays.toString(params));
            return false;
        }
        return QuestWarRuntime.addAccount(quest, Integer.parseInt(params[0].trim()), params[1]);
    }
}
