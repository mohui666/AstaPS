package emu.grasscutter.game.quest.exec;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.QuestValueExec;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.evidence.QuestEvidenceCatalog;
import emu.grasscutter.game.quest.evidence.QuestEvidenceService;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;

@QuestValueExec(QuestExec.QUEST_EXEC_UNLOCK_EVENTS_ITEM)
public final class ExecUnlockEventsItem extends QuestExecHandler {
    @Override
    public boolean execute(GameQuest quest, QuestData.QuestExecParam condition, String... parameters) {
        if (parameters == null || parameters.length < 2) return false;
        return QuestEvidenceService.unlock(quest, Integer.parseInt(parameters[0]),
                QuestEvidenceCatalog.parseItemIds(parameters[1]));
    }
}
