package emu.grasscutter.game.quest.exec;

import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.QuestValueExec;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;

/** The parameter is a Traveler candidate-depot index, not an element or full depot ID. */
@QuestValueExec(QuestExec.QUEST_EXEC_CHANGE_SKILL_DEPOT)
public final class ExecChangeSkillDepot extends QuestExecHandler {
    @Override
    public boolean execute(GameQuest quest, QuestData.QuestExecParam condition, String... params) {
        if (params.length != 1) return false;
        int index = Integer.parseInt(params[0].trim());
        var player = quest.getOwner();
        var avatar = player.getAvatars().getAvatarById(player.getMainCharacterId());
        if (avatar == null) return false;
        var candidates = avatar.getAvatarData().getCandSkillDepotIds();
        if (candidates == null || index < 0 || index >= candidates.size()) return false;
        var depot = GameData.getAvatarSkillDepotDataMap().get((int) candidates.get(index));
        if (depot == null) return false;
        if (depot.getId() != avatar.getSkillDepotId()) avatar.setSkillDepotData(depot, true);
        if (depot.getElementType() != null) player.setMainCharacterElement(depot.getElementType());
        avatar.recalcStats(true);
        avatar.save();
        return true;
    }
}
