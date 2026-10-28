package emu.grasscutter.game.quest.exec;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.player.QuestPartyLock;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;

/** Shared native roster contract for the legacy, V2 and mirror quest execution commands. */
public final class QuestPartyExec extends QuestExecHandler {
    public static final EnumSet<QuestExec> TYPES = EnumSet.of(
            QuestExec.QUEST_EXEC_LOCK_AVATAR_TEAM, QuestExec.QUEST_EXEC_LOCK_AVATAR_TEAM_V2,
            QuestExec.QUEST_EXEC_UNLOCK_AVATAR_TEAM, QuestExec.QUEST_EXEC_UNLOCK_AVATAR_TEAM_V2,
            QuestExec.QUEST_EXEC_GRANT_TRIAL_AVATAR_AND_LOCK_TEAM,
            QuestExec.QUEST_EXEC_GRANT_TRIAL_AVATAR_BATCH_AND_LOCK_TEAM,
            QuestExec.QUEST_EXEC_GRANT_TRIAL_AVATAR_AND_LOCK_TEAM_V2,
            QuestExec.QUEST_EXEC_GRANT_TRIAL_AVATAR_BATCH_AND_LOCK_TEAM_V2,
            QuestExec.QUEST_EXEC_LOCK_MIRROR_AVATAR_TEAM,
            QuestExec.QUEST_EXEC_UNLOCK_MIRROR_AVATAR_TEAM,
            QuestExec.QUEST_EXEC_UNLOCK_DUNGEON_MIRROR_AVATAR_TEAM);

    @Override
    public boolean execute(GameQuest quest, QuestData.QuestExecParam condition, String... params) {
        var player = quest.getOwner();
        var team = player.getTeamManager();
        var type = condition.getType();
        try {
            if (type == QuestExec.QUEST_EXEC_UNLOCK_DUNGEON_MIRROR_AVATAR_TEAM) {
                if (params.length == 0) return false;
                int dungeon = Integer.parseInt(params[0]);
                return dungeon > 0 && team.unlockQuestParty(quest.getMainQuestId(), dungeon);
            }
            if (type == QuestExec.QUEST_EXEC_UNLOCK_AVATAR_TEAM
                    || type == QuestExec.QUEST_EXEC_UNLOCK_AVATAR_TEAM_V2
                    || type == QuestExec.QUEST_EXEC_UNLOCK_MIRROR_AVATAR_TEAM)
                return team.unlockQuestParty(quest.getMainQuestId(), 0);
            var kind = switch (type) {
                case QUEST_EXEC_LOCK_MIRROR_AVATAR_TEAM -> params.length > 0 && "0".equals(params[0])
                        ? QuestPartyLock.Kind.MIRROR_OWNED : QuestPartyLock.Kind.MIRROR_TRIAL;
                case QUEST_EXEC_LOCK_AVATAR_TEAM, QUEST_EXEC_LOCK_AVATAR_TEAM_V2 -> QuestPartyLock.Kind.OWNED;
                default -> QuestPartyLock.Kind.TRIAL;
            };
            var roster = roster(player, type, params);
            var scene = player.getScene();
            int dungeon = scene != null && scene.getDungeonManager() != null
                    ? scene.getDungeonManager().getDungeonData().getId() : 0;
            return team.lockQuestParty(new QuestPartyLock(quest.getMainQuestId(), kind, roster,
                    team.getCurrentCharacterIndex(), dungeon));
        } catch (IllegalArgumentException error) {
            Grasscutter.getLogger().error("Invalid native quest party {} for quest {}: {}",
                    type, quest.getSubQuestId(), error.getMessage());
            return false;
        }
    }

    static List<Integer> roster(Player player, QuestExec type, String... params) {
        if (params == null || params.length == 0) throw new IllegalArgumentException("Missing roster");
        boolean v2 = type == QuestExec.QUEST_EXEC_LOCK_AVATAR_TEAM_V2
                || type == QuestExec.QUEST_EXEC_GRANT_TRIAL_AVATAR_AND_LOCK_TEAM_V2
                || type == QuestExec.QUEST_EXEC_GRANT_TRIAL_AVATAR_BATCH_AND_LOCK_TEAM_V2;
        int offset = v2 ? 1 : 0;
        if (params.length <= offset) throw new IllegalArgumentException("Missing V2 roster");
        if (v2) {
            int mode = Integer.parseInt(params[0]);
            if (mode != 1 && mode != 2) throw new IllegalArgumentException("Unsupported V2 lock source " + mode);
        }
        boolean owned = type == QuestExec.QUEST_EXEC_LOCK_AVATAR_TEAM
                || type == QuestExec.QUEST_EXEC_LOCK_AVATAR_TEAM_V2
                || "0".equals(params[offset]);
        if ("0".equals(params[offset]))
            return new ArrayList<>(player.getTeamManager().getCurrentTeamInfo().getAvatars());
        String list = params[offset];
        if (type == QuestExec.QUEST_EXEC_LOCK_AVATAR_TEAM_V2)
            list = String.join(",", java.util.Arrays.copyOfRange(params, offset, params.length));
        int traveler = player.getMainCharacterId();
        var roster = QuestPartyLock.selectRoster(list, traveler, id -> {
            if (owned) return id;
            var trial = GameData.getTrialAvatarDataMap().get(id);
            return trial == null || trial.getTrialAvatarParamList().isEmpty() ? 0
                    : trial.getTrialAvatarParamList().getFirst();
        });
        if (!owned) return roster;
        var ids = new LinkedHashSet<Integer>();
        for (int id : roster) ids.add(QuestPartyLock.ownedAvatarId(id, traveler));
        return new ArrayList<>(ids);
    }
}
