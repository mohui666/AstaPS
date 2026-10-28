package emu.grasscutter.game.quest.exec;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;
import java.util.concurrent.ThreadLocalRandom;

@QuestValueExec(QuestExec.QUEST_EXEC_RANDOM_CLOSED_QUEST_VAR)
public final class ExecRandomClosedQuestVar extends QuestExecHandler {
    @Override public boolean execute(GameQuest quest, QuestData.QuestExecParam condition, String... values) {
        if (values.length != 3) return false;
        try {
            int index=Integer.parseInt(values[0]), low=Integer.parseInt(values[1]), high=Integer.parseInt(values[2]);
            var main=quest.getMainQuest();
            if(index<0 || index>=main.getQuestVars().length || low<0 || high<0) return false;
            // RandomUtils::rand<unsigned>(min,max) normalizes reversed bounds, inclusive at both ends.
            int draw=(int)ThreadLocalRandom.current().nextLong(Math.min(low,high),(long)Math.max(low,high)+1);
            main.setQuestVar(index,draw); main.save(); return true;
        } catch (NumberFormatException error) { return false; }
    }
}
