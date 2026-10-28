package emu.grasscutter.game.quest.evidence;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.MainQuestData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.GameMainQuest;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.content.ContentEventsItemStatus;
import emu.grasscutter.game.quest.enums.QuestState;
import emu.grasscutter.utils.JsonUtils;
import java.util.List;
import org.junit.jupiter.api.Test;

final class QuestEvidenceStateTest {
    @Test
    void eventNamespacesAndSubmissionBranchesSurvivePersistence() {
        var state = new QuestEvidenceState();
        assertTrue(state.unlock(4001, List.of(10101, 10102)));
        assertFalse(state.unlock(4001, List.of(10101)));
        state.setSubmitState(400201, false);
        var restored = JsonUtils.decode(JsonUtils.encode(state), QuestEvidenceState.class);
        assertEquals(1, restored.getStatus(4001, 10101));
        assertEquals(0, restored.getStatus(4004, 10101));
        assertEquals(0, restored.getStatus(4001, 10103));
        assertEquals(2, restored.getSubmitState(400201));
        restored.setSubmitState(400201, true);
        assertEquals(1, restored.getSubmitState(400201));
    }

    @Test
    void invalidBatchDoesNotPartiallyGrantEvidence() {
        var state = new QuestEvidenceState();
        assertThrows(IllegalArgumentException.class, () -> state.unlock(4001, List.of(10101, 0)));
        assertEquals(0, state.getStatus(4001, 10101));
    }

    @Test
    void nativeEventCatalogPreservesMainQuestScopeAndMultiEvidenceAnswers() {
        var catalog = QuestEvidenceCatalog.installed();
        assertEquals(List.of(4001, 4002), catalog.event(4001).mainQuestIds());
        assertEquals(120445, catalog.event(4001).bookMaterialId());
        assertEquals(List.of(10110, 10111), catalog.giving(400201).correctItems());
        assertEquals(400225, catalog.giving(400201).failureTalkId());
    }

    @Test
    void predicateRequiresEverySavedEvidenceAndIgnoresClientClaims() {
        var player = new Player();
        var quest = quest(player, 4002, "finishExec:[]");
        var condition = JsonUtils.decode("{param:[4001,1],param_str:'10110,10111'}", QuestData.QuestContentCondition.class);
        var handler = new ContentEventsItemStatus();
        assertFalse(handler.execute(quest, condition, "", 4001, 1, 10110, 10111));
        player.getQuestEvidence().unlock(4001, List.of(10110));
        assertFalse(ContentEventsItemStatus.matchesCurrent(quest, condition));
        player.getQuestEvidence().unlock(4001, List.of(10111));
        assertTrue(ContentEventsItemStatus.matchesCurrent(quest, condition));
        assertFalse(ContentEventsItemStatus.matchesCurrent(quest(player, 4015, "finishExec:[]"), condition));
    }

    @Test
    void loginRestoresOnlyExecutedNativeQuestGrants() {
        var player = new Player() {
            @Override public void save() {}
            @Override public void sendPacket(emu.grasscutter.net.packet.BasePacket packet) {}
        };
        var quest = quest(player, 4002,
                "finishExec:[{type:'QUEST_EXEC_UNLOCK_EVENTS_ITEM',param:['4001','10110,10111']}]");
        quest.getMainQuest().getChildQuests().put(quest.getSubQuestId(), quest);
        player.getQuestManager().getMainQuests().put(4002, quest.getMainQuest());
        QuestEvidenceService.onLogin(player);
        assertEquals(0, player.getQuestEvidence().getStatus(4001, 10110));
        quest.setState(QuestState.QUEST_STATE_FINISHED);
        QuestEvidenceService.onLogin(player);
        assertEquals(1, player.getQuestEvidence().getStatus(4001, 10110));
        assertEquals(1, player.getQuestEvidence().getStatus(4001, 10111));
        assertEquals(0, player.getQuestEvidence().getStatus(4001, 10112));
    }

    private static GameQuest quest(Player player, int mainId, String actions) {
        var previous = GameData.getMainQuestDataMap().put(mainId,
                JsonUtils.decode("{id:" + mainId + ",subQuests:[]}", MainQuestData.class));
        try {
            return new GameQuest(new GameMainQuest(player, mainId), JsonUtils.decode(
                    "{mainId:" + mainId + ",subId:" + mainId + "01,finishCond:[],failCond:[]," + actions + "}",
                    QuestData.class));
        } finally {
            if (previous == null) GameData.getMainQuestDataMap().remove(mainId);
            else GameData.getMainQuestDataMap().put(mainId, previous);
        }
    }
}
