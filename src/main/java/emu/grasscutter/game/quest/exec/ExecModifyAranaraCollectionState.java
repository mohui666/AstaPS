package emu.grasscutter.game.quest.exec;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.QuestValueExec;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;
import emu.grasscutter.game.quest.aranara.AranaraCollectionRuntime;

@QuestValueExec(QuestExec.QUEST_EXEC_MODIFY_ARANARA_COLLECTION_STATE)
public final class ExecModifyAranaraCollectionState extends QuestExecHandler {
    @Override
    public boolean execute(GameQuest quest, QuestData.QuestExecParam condition, String... params) {
        return params.length == 3 && AranaraCollectionRuntime.modify(quest,
                Integer.parseInt(params[0].trim()), Integer.parseInt(params[1].trim()), Integer.parseInt(params[2].trim()));
    }
}
