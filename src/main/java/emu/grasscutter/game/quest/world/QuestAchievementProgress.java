package emu.grasscutter.game.quest.world;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.enums.QuestContent;
import com.google.gson.JsonParser;
import emu.grasscutter.utils.FileUtils;
import java.nio.file.Files;
import java.io.IOException;
import java.util.Arrays;

/** Actual achievement state only. Content165 has an explicit reconstruction for the unreleased 804 test. */
public final class QuestAchievementProgress {
    private static final int QUEST = 80402;
    private static final int ACHIEVEMENT = 81230;
    private static final int RAW_STATE_ACHIEVEMENT = 81233;
    private record RawStateGate(int quest,int parent,int achievement,int state,int failAchievement) {}
    private static final class Reconstructed {static final RawStateGate VALUE=loadGate();}

    private QuestAchievementProgress() {}

    public static boolean matchesCurrent(GameQuest quest, QuestData.QuestContentCondition condition) {
        var target = condition.getParam();
        if(condition.getType()==QuestContent.QUEST_CONTENT_ACHIEVEMENT_STATE_EQUAL)
            return matchesReconstructedRawState(quest,condition);
        return quest.getSubQuestId() == QUEST && condition.getType() == QuestContent.QUEST_CONTENT_ACHIEVEMENT_ISACHIEVED
                && target != null && target.length >= 2 && target[0] == ACHIEVEMENT && target[1] == 1
                && quest.getOwner().getAchievements() != null
                && !quest.getOwner().getAchievements().isInvalid(ACHIEVEMENT)
                && quest.getOwner().getAchievements().isFinished(ACHIEVEMENT);
    }

    private static RawStateGate loadGate(){
        try{
            var json=JsonParser.parseString(Files.readString(FileUtils.getDataPath("QuestAchievementReconstruction.json"))).getAsJsonObject();
            var rows=json.getAsJsonArray("rules");
            if(!json.get("reconstructed").getAsBoolean()||rows.size()!=1)throw new IllegalStateException("Invalid achievement reconstruction rules");
            var row=rows.get(0).getAsJsonObject();
            var gate=new RawStateGate(row.get("quest").getAsInt(),row.get("parent").getAsInt(),row.get("achievement").getAsInt(),
                    row.get("rawStatus").getAsInt(),row.get("failAchievement").getAsInt());
            if(gate.quest()!=80402||gate.parent()!=804||gate.achievement()!=81233||gate.state()!=1||gate.failAchievement()!=81230)
                throw new IllegalStateException("Only the original 80402 raw-status goal is reconstructed");
            return gate;
        }catch(IOException error){throw new IllegalStateException("Cannot load achievement reconstruction",error);}
    }

    private static boolean matchesReconstructedRawState(GameQuest quest,QuestData.QuestContentCondition condition){
        if(quest.getSubQuestId()!=QUEST||quest.getMainQuestId()!=804)return false;
        var gate=Reconstructed.VALUE;var achievements=quest.getOwner().getAchievements();
        if(quest.getSubQuestId()!=gate.quest()||quest.getMainQuestId()!=gate.parent()
                ||!Arrays.equals(condition.getParam(),new int[]{gate.achievement(),gate.state()})
                ||achievements==null||achievements.isInvalid(gate.achievement())||quest.getQuestData()==null)return false;
        var failures=quest.getQuestData().getFailCond();
        if(failures.size()!=1)return false;
        var failure=failures.getFirst();
        if(failure.getType()!=QuestContent.QUEST_CONTENT_ACHIEVEMENT_ISACHIEVED
                ||!Arrays.equals(failure.getParam(),new int[]{gate.failAchievement(),1}))return false;
        // Admission queues finish predicates before fail predicates. Preserve the original fail
        // priority even when both saved achievement conditions are already true at that moment.
        var status=achievements.getStatus(gate.achievement());
        return !matchesCurrent(quest,failure)&&status!=null&&status.getNumber()==gate.state();
    }

    /** Called by actual saved-achievement status transitions, never by quest progress reports. */
    public static void onStatusChanged(Player player, int achievementId) {
        if(player==null)return;
        if (achievementId == ACHIEVEMENT) {
            player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_ACHIEVEMENT_ISACHIEVED, achievementId);
            player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_ACHIEVEMENT_STATE_EQUAL, Reconstructed.VALUE.achievement());
        } else if(achievementId==RAW_STATE_ACHIEVEMENT)
            player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_ACHIEVEMENT_STATE_EQUAL,achievementId);
    }

    /** Called after the ordinary login registration of unfinished achievement records. */
    public static void onLogin(Player player){
        if(player==null)return;
        player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_ACHIEVEMENT_ISACHIEVED,ACHIEVEMENT);
        player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_ACHIEVEMENT_STATE_EQUAL,Reconstructed.VALUE.achievement());
    }
}
