package emu.grasscutter.game.quest.aranara;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.MainQuestData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.props.SceneType;
import emu.grasscutter.game.quest.GameMainQuest;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.world.SceneGroupInstance;
import emu.grasscutter.game.world.TestWorld;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.AddAranaraCollectionNotifyOuterClass.AddAranaraCollectionNotify;
import emu.grasscutter.net.proto.AranaraCollectionDataNotifyOuterClass.AranaraCollectionDataNotify;
import emu.grasscutter.scripts.SceneScriptManager;
import emu.grasscutter.scripts.ScriptLoader;
import emu.grasscutter.scripts.data.SceneGroup;
import emu.grasscutter.server.packet.send.PacketAddAranaraCollectionNotify;
import emu.grasscutter.server.packet.send.PacketAranaraCollectionDataNotify;
import emu.grasscutter.utils.JsonUtils;
import java.util.*;
import org.junit.jupiter.api.*;

final class AranaraCollectionTest {
    private static boolean scripts;

    private static final class SavedPlayer extends TestWorld.TestPlayer {
        int saves;
        final List<BasePacket> packets = new ArrayList<>();
        @Override public void save() { saves++; }
        @Override public void sendPacket(BasePacket packet) { if (packets != null) packets.add(packet); }
    }

    @BeforeAll
    static void prepareScripts() throws Exception {
        ScriptLoader.init();
        scripts = Grasscutter.getConfig().server.game.enableScriptInBigWorld;
        Grasscutter.getConfig().server.game.enableScriptInBigWorld = false;
    }

    @AfterAll
    static void restoreScripts() { Grasscutter.getConfig().server.game.enableScriptInBigWorld = scripts; }

    private static SavedPlayer player() {
        var player = new SavedPlayer();
        player.setUid(42);
        var world = TestWorld.create(player);
        player.setScene(new TestWorld.TestScene(world, 3, SceneType.SCENE_ROOM));
        player.setSceneId(3);
        return player;
    }

    private static GameQuest quest(SavedPlayer player) {
        var previous = GameData.getMainQuestDataMap().put(990441,
                JsonUtils.decode("{id:990441,subQuests:[]}", MainQuestData.class));
        try {
            return new GameQuest(new GameMainQuest(player, 990441), JsonUtils.decode(
                    "{mainId:990441,subId:99044101,finishCond:[],failCond:[]}", QuestData.class));
        } finally {
            if (previous == null) GameData.getMainQuestDataMap().remove(990441);
            else GameData.getMainQuestDataMap().put(990441, previous);
        }
    }

    @SuppressWarnings("unchecked")
    private static void loadMailbox(SavedPlayer player) throws Exception {
        var field = SceneScriptManager.class.getDeclaredField("sceneGroupsInstances");
        field.setAccessible(true);
        ((Map<Integer, SceneGroupInstance>) field.get(player.getScene().getScriptManager())).put(
                AranaraCollectionCatalog.MAILBOX_GROUP,
                new SceneGroupInstance(SceneGroup.of(AranaraCollectionCatalog.MAILBOX_GROUP), player));
    }

    @Test
    void catalogAndStatePreserveAllFourNativeStatesWithoutInventingCollectedPages() {
        var catalog = AranaraCollectionCatalog.installed();
        assertEquals(15, catalog.pageIds().size());
        assertTrue(catalog.hasPage(1, 15));
        assertFalse(catalog.hasPage(1, 16));
        assertFalse(catalog.hasPage(0, 1));
        var state = new AranaraCollectionState();
        state.modify(1, 1);
        state.modify(2, 3);
        var restored = JsonUtils.decode(JsonUtils.encode(state), AranaraCollectionState.class);
        assertEquals(List.of(new AranaraCollectionState.Change(1, 1, 2)), restored.receiveAll(catalog.pageIds()));
        assertEquals(0, restored.getState(3));
        assertEquals(3, restored.getState(2));
        assertTrue(restored.receiveAll(catalog.pageIds()).isEmpty());
        assertEquals(0, new AranaraCollectionState().getState(1));
        restored.modify(2, 0);
        assertFalse(restored.getPages().containsKey(2));
    }

    @Test
    void theActualMailboxCollectsOnlyAvailablePagesAndReplaysDoNotCountTwice() throws Exception {
        var player = player();
        loadMailbox(player);
        player.getAranaraCollection().modify(1, 1);
        player.getAranaraCollection().modify(2, 1);
        player.getAranaraCollection().modify(3, 3);
        assertEquals(2, AranaraCollectionRuntime.getCount(player.getScene(), 1, 1));
        assertEquals(12, AranaraCollectionRuntime.getCount(player.getScene(), 1, 0));
        assertEquals(0, AranaraCollectionRuntime.receiveAll(player.getScene(), 133301087, 1));
        assertEquals(2, player.packets.size());
        assertEquals(1, player.saves);
        assertEquals(2, player.getAranaraCollection().getState(1));
        assertEquals(3, player.getAranaraCollection().getState(3));
        assertEquals(0, player.getAranaraCollection().getState(4));
        assertEquals(0, player.itemsAdded);
        assertEquals(0, AranaraCollectionRuntime.receiveAll(player.getScene(), 133301087, 1));
        assertEquals(2, player.packets.size());
        assertEquals(1, player.saves);
    }

    @Test
    void invalidTypePageStateAndAnUnloadedMailboxHaveNoEffects() {
        var player = player();
        var quest = quest(player);
        assertFalse(AranaraCollectionRuntime.modify(quest, 1, 16, 1));
        assertFalse(AranaraCollectionRuntime.modify(quest, 0, 1, 1));
        assertFalse(AranaraCollectionRuntime.modify(quest, 1, 1, 4));
        player.getAranaraCollection().modify(1, 1);
        assertEquals(-1, AranaraCollectionRuntime.receiveAll(player.getScene(), 133301087, 1));
        assertEquals(-1, AranaraCollectionRuntime.getCount(player.getScene(), 9, 1));
        assertEquals(1, player.getAranaraCollection().getState(1));
        assertEquals(0, player.saves);
        assertTrue(player.packets.isEmpty());
    }

    @Test
    void nativeQuestChangesNotifyRealDeltasAndCannotMutateAnotherWorld() throws Exception {
        var player = player();
        var quest = quest(player);
        assertTrue(AranaraCollectionRuntime.modify(quest, 1, 7, 1));
        assertTrue(AranaraCollectionRuntime.modify(quest, 1, 7, 1));
        assertEquals(1, player.saves);
        var packet = AddAranaraCollectionNotify.parseFrom(player.packets.getFirst().getData());
        assertEquals(1, packet.getCollectionType());
        assertEquals(7, packet.getCollectionId());
        assertEquals(0, packet.getFromStateValue());
        assertEquals(1, packet.getTargetStateValue());
        player.setWorld(TestWorld.create(new SavedPlayer()));
        assertFalse(AranaraCollectionRuntime.modify(quest, 1, 7, 3));
        assertEquals(1, player.getAranaraCollection().getState(7));
    }

    @Test
    void packetsMatchIndependentNativeSevenOneParserFixtures() throws Exception {
        // JJJGHNOLJHI parser: tags 0x48->previous, 0x60->target, 0x68->page, 0x70->type.
        var change = new PacketAddAranaraCollectionNotify(1, new AranaraCollectionState.Change(7, 1, 2));
        assertArrayEquals(HexFormat.of().parseHex("4801600268077001"), change.getData());
        assertEquals(25348, change.getOpcode());
        var player = player();
        player.getAranaraCollection().modify(7, 2);
        // NINALKKEMHN tag0x1a; JDANHNGAMJM map tag0x22 (key1/value2), type tag0x38.
        var full = new PacketAranaraCollectionDataNotify(player);
        assertArrayEquals(HexFormat.of().parseHex("1a082204080710023801"), full.getData());
        assertEquals(4234, full.getOpcode());
        var decoded = AranaraCollectionDataNotify.parseFrom(full.getData());
        assertEquals(2, decoded.getCollectionSuiteList(0).getCollectionIdStateMapValueOrThrow(7));
    }

    @Test
    void loginSendsThePlayersSavedPageMapIncludingFinishedPages() throws Exception {
        var player = player();
        player.getAranaraCollection().modify(7, 3);
        AranaraCollectionRuntime.onLogin(player);
        var full = AranaraCollectionDataNotify.parseFrom(player.packets.getFirst().getData());
        assertEquals(3, full.getCollectionSuiteList(0).getCollectionIdStateMapValueOrThrow(7));
        assertEquals(1, full.getCollectionSuiteList(0).getCollectionIdStateMapCount());
    }
}
