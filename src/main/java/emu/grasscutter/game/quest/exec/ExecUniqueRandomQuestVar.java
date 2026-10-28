package emu.grasscutter.game.quest.exec;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@QuestValueExec(QuestExec.QUEST_EXEC_UNIQUE_RANDOM_QUEST_VAR)
public final class ExecUniqueRandomQuestVar extends QuestExecHandler {
    @Override public boolean execute(GameQuest quest, QuestData.QuestExecParam condition, String... values) {
        // The 3.4 server has no executor104. Reconstruct only the actual 7.1 task's
        // three different interview prompts, sampled without replacement from its six options.
        if (quest.getSubQuestId()!=2503601 || values.length!=3
                || !"1,2,3".equals(values[0]) || !"1".equals(values[1]) || !"6".equals(values[2])) return false;
        var main=quest.getMainQuest(); var vars=main.getQuestVars();
        if(vars.length<=3) return false;
        var options=new ArrayList<>(List.of(1,2,3,4,5,6)); Collections.shuffle(options,ThreadLocalRandom.current());
        for(int i=1;i<=3;i++) vars[i]=options.get(i-1);
        main.save();
        for(int i=1;i<=3;i++) main.triggerQuestVarAction(i,vars[i]);
        return true;
    }
}
