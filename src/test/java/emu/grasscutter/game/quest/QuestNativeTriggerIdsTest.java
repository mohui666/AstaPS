package emu.grasscutter.game.quest;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.game.quest.enums.*;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

/** IDs decoded from native Quest binary records by Genshin-Reverse/genshinre/questbin.py. */
final class QuestNativeTriggerIdsTest {
    @Test
    void clientReportedConditionsUseNativeIdsAndDoNotCollideWithOlderConditions() {
        assertSame(QuestContent.QUEST_CONTENT_EVENTS_ITEM_STATUS, QuestContent.getContentTriggerByValue(164));
        assertSame(QuestContent.QUEST_CONTENT_PRESENT_AT_SPECIFIC_SCENE, QuestContent.getContentTriggerByValue(177));
        assertSame(QuestContent.QUEST_CONTENT_ABYSS_WAR_LEVEL_STATE_EQUAL, QuestContent.getContentTriggerByValue(183));
        assertSame(QuestContent.QUEST_CONTENT_PARENT_QUEST_STATE_EQUAL, QuestContent.getContentTriggerByValue(186));
        var used = new HashSet<Integer>();
        for (var type : QuestContent.values()) assertTrue(used.add(type.getValue()), type.name());
    }

    @Test
    void nativeSceneAndPartyActionsHaveDistinctClientCompatibleIds() {
        assertEquals(69, QuestExec.QUEST_EXEC_MODIFY_ARANARA_COLLECTION_STATE.getValue());
        assertEquals(87, QuestExec.QUEST_EXEC_UNLOCK_EVENTS_ITEM.getValue());
        assertEquals(92, QuestExec.QUEST_EXEC_LOCK_MIRROR_AVATAR_TEAM.getValue());
        assertEquals(93, QuestExec.QUEST_EXEC_UNLOCK_MIRROR_AVATAR_TEAM.getValue());
        assertEquals(105, QuestExec.QUEST_EXEC_SET_ABYSS_WAR_LEVEL_STATE.getValue());
        assertEquals(115, QuestExec.QUEST_EXEC_CREATE_VEHICLE.getValue());
        assertEquals(123, QuestExec.QUEST_EXEC_BATCH_SET_OPEN_STATE.getValue());
        var used = new HashSet<Integer>();
        for (var type : QuestExec.values()) assertTrue(used.add(type.getValue()), type.name());
    }
}
