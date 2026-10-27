package emu.grasscutter.game.quest.exec;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.QuestValueExec;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;
import java.util.Arrays;

@QuestValueExec(QuestExec.QUEST_EXEC_BATCH_SET_OPEN_STATE)
public final class ExecBatchSetOpenState extends QuestExecHandler {
    static int[][] parse(String... params) {
        if (params.length != 2) return null;
        int[] ids = Arrays.stream(params[0].split(",", -1))
                .map(String::trim).mapToInt(Integer::parseInt).toArray();
        int[] values = Arrays.stream(params[1].split(",", -1))
                .map(String::trim).mapToInt(Integer::parseInt).toArray();
        if (ids.length != values.length || ids.length == 0
                || Arrays.stream(ids).anyMatch(id -> id <= 0)) return null;
        return new int[][] {ids, values};
    }

    @Override
    public boolean execute(GameQuest quest, QuestData.QuestExecParam condition, String... params) {
        int[][] batch = parse(params);
        if (batch == null) return false;
        for (int i = 0; i < batch[0].length; i++)
            quest.getOwner().getProgressManager().forceSetOpenState(batch[0][i], batch[1][i]);
        return true;
    }
}
