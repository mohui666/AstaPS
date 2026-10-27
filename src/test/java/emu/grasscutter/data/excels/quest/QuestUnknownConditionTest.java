package emu.grasscutter.data.excels.quest;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.data.GameData;
import emu.grasscutter.game.quest.enums.*;
import emu.grasscutter.utils.JsonUtils;
import org.junit.jupiter.api.Test;

final class QuestUnknownConditionTest {
    @Test
    void unknownObjectiveIsRetainedInsteadOfWeakeningAnAndQuest() {
        var data = JsonUtils.decode("""
                {"mainId":990010,"subId":99001001,"acceptCond":[],
                 "finishCondComb":"LOGIC_AND","finishCond":[
                  {"type":"QUEST_CONTENT_COMPLETE_TALK","param":[302903,0]},
                  {"type":"QUEST_CONTENT_FUTURE_OBJECTIVE","param":[42,1]}],
                 "failCond":[],"beginExec":[],"finishExec":[],"failExec":[]}
                """, QuestData.class);
        data.onLoad();
        try {
            assertEquals(2, data.getFinishCond().size());
            assertEquals(QuestContent.QUEST_CONTENT_UNKNOWN, data.getFinishCond().get(1).getType());
            assertFalse(LogicType.calculate(data.getFinishCondComb(), new int[] {1,0}));
        } finally {
            GameData.getBeginCondQuestMap().get("QUEST_COND_NONE0").remove(data);
        }
    }

    @Test
    void unknownAcceptanceGateCannotBecomeAnUnconditionalQuest() {
        var data = JsonUtils.decode("""
                {"mainId":990010,"subId":99001002,
                 "acceptCond":[{"type":"QUEST_COND_FUTURE_GATE","param":[]}],
                 "finishCond":[],"failCond":[],"beginExec":[],"finishExec":[],"failExec":[]}
                """, QuestData.class);
        data.onLoad();
        try {
            assertEquals(1, data.getAcceptCond().size());
            assertEquals(QuestCond.QUEST_COND_UNKNOWN, data.getAcceptCond().getFirst().getType());
            assertEquals("QUEST_COND_UNKNOWN0", data.getAcceptCond().getFirst().asKey());
        } finally {
            GameData.getBeginCondQuestMap().remove("QUEST_COND_UNKNOWN0");
        }
    }
}
