package emu.grasscutter.game.quest;

import static emu.grasscutter.config.Configuration.*;
import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.enums.ParentQuestState;
import emu.grasscutter.game.quest.enums.QuestState;
import emu.grasscutter.net.proto.FinishedParentQuestUpdateNotifyOuterClass.FinishedParentQuestUpdateNotify;
import emu.grasscutter.net.proto.QuestListNotifyOuterClass.QuestListNotify;
import emu.grasscutter.net.proto.QuestListUpdateNotifyOuterClass.QuestListUpdateNotify;
import emu.grasscutter.server.packet.send.PacketFinishedParentQuestUpdateNotify;
import emu.grasscutter.server.packet.send.PacketQuestListNotify;
import emu.grasscutter.server.packet.send.PacketQuestListUpdateNotify;
import java.lang.reflect.Field;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(ServerResourceFixture.class)
final class ClientQuestMarkersTest {
    private Player player;
    private boolean oldQuesting, oldScripts;

    @BeforeEach
    void setup() {
        oldQuesting = GAME_OPTIONS.questing.enabled;
        oldScripts = SERVER.game.enableScriptInBigWorld;
        GAME_OPTIONS.questing.enabled = true;
        SERVER.game.enableScriptInBigWorld = true;
        player = new Player();
        player.setUid(1);
    }

    @AfterEach
    void restoreConfig() {
        GAME_OPTIONS.questing.enabled = oldQuesting;
        SERVER.game.enableScriptInBigWorld = oldScripts;
    }

    @Test
    void statuesRemainVisibleUntilTheirOwnPointHasBeenUnlocked() throws Exception {
        var first = quest(30302);
        var second = quest(30303);
        var other = quest(30304);
        assertTrue(ClientQuestMarkers.shouldSend(first));
        assertTrue(ClientQuestMarkers.shouldSend(second));
        assertFalse(player.getUnlockedScenePoints().containsKey(3), "Reading visibility must not create map progress");
        player.getUnlockedScenePoints(3).add(3);
        assertFalse(ClientQuestMarkers.shouldSend(first));
        assertTrue(ClientQuestMarkers.shouldSend(second));
        player.getUnlockedScenePoints(3).add(4);
        assertFalse(ClientQuestMarkers.shouldSend(second));
        assertTrue(ClientQuestMarkers.shouldSend(other));
        assertTrue(ClientQuestMarkers.shouldSend(quest(30362)));
        assertTrue(ClientQuestMarkers.shouldSend(quest(701150)));
        assertFalse(ClientQuestMarkers.shouldSend(quest(37001)));
    }

    @Test
    void allOtherStatesAndSourceQuestProgressArePreserved() throws Exception {
        player.getUnlockedScenePoints(3).addAll(List.of(3, 4));
        for (int id : new int[] {30302, 30303, 37001}) {
            var quest = quest(id);
            for (var state : List.of(QuestState.QUEST_STATE_NONE, QuestState.QUEST_STATE_UNSTARTED,
                    QuestState.QUEST_STATE_FINISHED, QuestState.QUEST_STATE_FAILED)) {
                quest.setState(state);
                var before = quest.toProto();
                assertTrue(ClientQuestMarkers.shouldSend(quest));
                assertEquals(before, quest.toProto());
            }
            quest.setState(QuestState.QUEST_STATE_UNFINISHED);
            var before = quest.toProto();
            assertFalse(ClientQuestMarkers.shouldSend(quest));
            assertEquals(before, quest.toProto());
        }
        assertEquals(Set.of(3, 4), player.getUnlockedScenePoints(3));
    }

    @Test
    void loginAndIncrementalPacketsApplyTheSameNarrowRule() throws Exception {
        player.getUnlockedScenePoints(3).add(3);
        var entries = List.of(quest(30302), quest(30303), quest(30304), quest(37001), quest(37002), quest(701150));
        var login = QuestListNotify.parseFrom(new PacketQuestListNotify(player).getData());
        assertEquals(Set.of(30303, 30304, 37002, 701150), new HashSet<>(login.getQuestListList().stream().map(q -> q.getQuestId()).toList()));
        for (var quest : entries) {
            var update = QuestListUpdateNotify.parseFrom(new PacketQuestListUpdateNotify(quest).getData());
            assertEquals(ClientQuestMarkers.shouldSend(quest) ? List.of(quest.toProto()) : List.of(), update.getQuestListList());
        }
        entries.get(0).setState(QuestState.QUEST_STATE_FINISHED);
        entries.get(3).setState(QuestState.QUEST_STATE_FINISHED);
        var finished = QuestListNotify.parseFrom(new PacketQuestListNotify(player).getData());
        assertTrue(finished.getQuestListList().contains(entries.get(0).toProto()));
        assertTrue(finished.getQuestListList().contains(entries.get(3).toProto()));
    }

    @Test
    void parentUpdatesKeepMetadataAndCannotReintroduceSuppressedChildren() throws Exception {
        player.getUnlockedScenePoints(3).add(3);
        var omitted = quest(30302);
        var kept = quest(30303);
        var other = quest(30304);
        other.setState(QuestState.QUEST_STATE_FINISHED);
        var parent = omitted.getMainQuest();
        var original = parent.toProto(true);
        var expected = original.toBuilder().clearChildQuestList()
                .addAllChildQuestList(original.getChildQuestListList().stream().filter(q -> q.getQuestId() != 30302).toList()).build();
        assertEquals(expected, ClientQuestMarkers.parentWithChildren(parent));
        for (var packet : List.of(new PacketFinishedParentQuestUpdateNotify(parent), new PacketFinishedParentQuestUpdateNotify(List.of(parent)))) {
            assertEquals(List.of(expected), FinishedParentQuestUpdateNotify.parseFrom(packet.getData()).getParentQuestListList());
        }
        assertEquals(original, parent.toProto(true));
        assertSame(kept, parent.getChildQuests().get(30303));
        var story = quest(37001).getMainQuest();
        assertEquals(0, ClientQuestMarkers.parentWithChildren(story).getChildQuestListCount());
        var forged = FinishedParentQuestUpdateNotify.parseFrom(new PacketFinishedParentQuestUpdateNotify(370).getData());
        assertTrue(forged.getParentQuestList(0).getIsFinished());
        assertEquals(0, forged.getParentQuestList(0).getChildQuestListCount());
    }

    @Test
    void disabledQuestingKeepsExistingVisibilityRules() throws Exception {
        var statue = quest(30303);
        quest(37002);
        GAME_OPTIONS.questing.enabled = false;
        var login = QuestListNotify.parseFrom(new PacketQuestListNotify(player).getData());
        assertEquals(List.of(statue.toProto()), login.getQuestListList());
        assertEquals(1, QuestListUpdateNotify.parseFrom(new PacketQuestListUpdateNotify(statue).getData()).getQuestListCount());
    }

    private GameQuest quest(int id) throws Exception {
        int parentId = id / 100;
        var manager = player.getQuestManager();
        var parent = manager.getMainQuests().get(parentId);
        if (parent == null) {
            parent = new GameMainQuest();
            set(parent, "ownerUid", player.getUid());
            set(parent, "parentQuestId", parentId);
            set(parent, "state", ParentQuestState.PARENT_QUEST_STATE_NONE);
            set(parent, "questVars", new int[] {9, 2, 7, 4, 0});
            set(parent, "childQuests", new LinkedHashMap<Integer, GameQuest>());
            parent.setOwner(player);
            manager.getMainQuests().put(parentId, parent);
        }
        var quest = new GameQuest();
        set(quest, "subQuestId", id);
        set(quest, "mainQuestId", parentId);
        quest.setMainQuest(parent);
        quest.setState(QuestState.QUEST_STATE_UNFINISHED);
        quest.setStartTime(12345);
        quest.setAcceptTime(12344);
        set(quest, "finishProgressList", new int[] {0, 1});
        parent.getChildQuests().put(id, quest);
        return quest;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
