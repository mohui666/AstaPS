package emu.grasscutter.game.quest;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.MainQuestData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.enums.QuestContent;
import emu.grasscutter.game.quest.enums.QuestState;
import emu.grasscutter.game.world.TestWorld;
import emu.grasscutter.utils.JsonUtils;
import org.junit.jupiter.api.Test;

final class QuestPartialProgressPersistenceTest {
    private static final int MAIN = 990013, SUB = 99001301;

    private static final class SavedMainQuest extends GameMainQuest {
        int saves;
        int[] savedProgress;

        SavedMainQuest(TestWorld.TestPlayer player) { super(player, MAIN); }

        @Override public void save() {
            saves++;
            savedProgress = getChildQuestById(SUB).getFinishProgressList().clone();
        }
    }

    @Test
    void incompleteAndObjectiveIsSavedOnChangeButNotOnRepeatedEvents() {
        var mainData = JsonUtils.decode(
                "{id:990013,subQuests:[{subId:99001301}]}", MainQuestData.class);
        var data = JsonUtils.decode("""
                {mainId:990013,subId:99001301,finishCondComb:'LOGIC_AND',finishCond:[
                  {type:'QUEST_CONTENT_QUEST_GLOBAL_VAR_EQUAL',param:[90700002,1]},
                  {type:'QUEST_CONTENT_QUEST_GLOBAL_VAR_EQUAL',param:[90700003,1]}],failCond:[]}
                """, QuestData.class);
        var previousMain = GameData.getMainQuestDataMap().put(MAIN,mainData);
        var previousSub = GameData.getQuestDataMap().put(SUB,data);
        try {
            var player = new TestWorld.TestPlayer();
            var main = new SavedMainQuest(player);
            main.getChildQuestById(SUB).setState(QuestState.QUEST_STATE_UNFINISHED);
            player.getQuestGlobalVariables().put(90700002,1);
            main.tryFinishSubQuests(QuestContent.QUEST_CONTENT_QUEST_GLOBAL_VAR_EQUAL,"",90700002,1);
            assertEquals(1,main.saves);
            assertArrayEquals(new int[] {1,0},main.savedProgress);
            assertEquals(QuestState.QUEST_STATE_UNFINISHED,main.getChildQuestById(SUB).getState());
            main.tryFinishSubQuests(QuestContent.QUEST_CONTENT_QUEST_GLOBAL_VAR_EQUAL,"",90700002,1);
            assertEquals(1,main.saves);
            player.getQuestGlobalVariables().put(90700002,0);
            main.tryFinishSubQuests(QuestContent.QUEST_CONTENT_QUEST_GLOBAL_VAR_EQUAL,"",90700002,0);
            assertEquals(2,main.saves);
            assertArrayEquals(new int[] {0,0},main.savedProgress);
        } finally {
            if (previousMain == null) GameData.getMainQuestDataMap().remove(MAIN);
            else GameData.getMainQuestDataMap().put(MAIN,previousMain);
            if (previousSub == null) GameData.getQuestDataMap().remove(SUB);
            else GameData.getQuestDataMap().put(SUB,previousSub);
        }
    }
}
