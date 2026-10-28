package emu.grasscutter.game.quest;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.MainQuestData;
import emu.grasscutter.data.excels.ItemData;
import emu.grasscutter.data.excels.CookRecipeData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.home.HomeWorld;
import emu.grasscutter.game.inventory.GameItem;
import emu.grasscutter.game.inventory.ItemType;
import emu.grasscutter.game.city.CityInfoData;
import emu.grasscutter.game.managers.cooking.CookingManager;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.content.ContentServerState;
import emu.grasscutter.game.quest.enums.QuestContent;
import emu.grasscutter.game.quest.enums.QuestState;
import emu.grasscutter.game.world.Scene;
import emu.grasscutter.game.world.TestWorld;
import emu.grasscutter.game.world.World;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.AddQuestContentProgressReqOuterClass.AddQuestContentProgressReq;
import emu.grasscutter.net.proto.AddQuestContentProgressBatchReq._AddQuestContentProgressBatchReq;
import emu.grasscutter.net.proto.QuestProgressInfo._QuestProgressInfo;
import emu.grasscutter.net.proto.QuestProgressUpdateNotifyOuterClass.QuestProgressUpdateNotify;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.game.GameServer;
import emu.grasscutter.game.systems.InventorySystem;
import emu.grasscutter.server.packet.recv.HandlerAddQuestContentProgressReq;
import emu.grasscutter.server.packet.recv.HandlerAddQuestContentProgressBatchReq;
import emu.grasscutter.server.packet.send.PacketQuestProgressUpdateNotify;
import emu.grasscutter.utils.JsonUtils;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import sun.misc.Unsafe;

@ExtendWith(ServerResourceFixture.class)
@ResourceLock("GameData")
final class QuestWorldPredicatesTest {
    private static final int MAIN = 990019;
    private static final int ITEM = 120596;
    private MainQuestData oldMain;
    private ItemData oldItem;
    private final Map<Integer, CookRecipeData> oldRecipes = new HashMap<>();

    @BeforeEach
    void resources() {
        oldMain = GameData.getMainQuestDataMap().put(MAIN,
                JsonUtils.decode("{id:990019,subQuests:[]}", MainQuestData.class));
        oldItem = GameData.getItemDataMap().put(ITEM, JsonUtils.decode(
                "{id:120596,itemType:'ITEM_MATERIAL',materialType:'MATERIAL_NONE',stackLimit:99}", ItemData.class));
        for (int id : new int[] {3107,2105,2102}) oldRecipes.put(id,
                GameData.getCookRecipeDataMap().put(id, JsonUtils.decode("{id:" + id + "}", CookRecipeData.class)));
    }

    @AfterEach
    void restore() throws Exception {
        drain();
        if (oldMain == null) GameData.getMainQuestDataMap().remove(MAIN);
        else GameData.getMainQuestDataMap().put(MAIN, oldMain);
        if (oldItem == null) GameData.getItemDataMap().remove(ITEM);
        else GameData.getItemDataMap().put(ITEM, oldItem);
        oldRecipes.forEach((id,recipe) -> {
            if (recipe == null) GameData.getCookRecipeDataMap().remove(id);
            else GameData.getCookRecipeDataMap().put(id, recipe);
        });
    }

    @Test
    void nativeIdsRegisterAsLiveServerPredicates() {
        // 7.1 IPONFOFHKIJ enum + native questbin content identifiers.
        int[] ids = {132, 167, 168, 169, 170, 171};
        var types = new QuestContent[] {QuestContent.QUEST_CONTENT_ENTER_MY_HOME_WORLD,
                QuestContent.QUEST_CONTENT_UNLOCK_ANY_TRANS_POINT, QuestContent.QUEST_CONTENT_ITEM_NUM_EQUAL,
                QuestContent.QUEST_CONTENT_ITEM_NUM_GREATER, QuestContent.QUEST_CONTENT_ITEM_NUM_LESS,
                QuestContent.QUEST_CONTENT_QUEST_VAR_NOT_EQUAL};
        for (int i = 0; i < ids.length; i++) {
            assertEquals(ids[i], types[i].getValue());
            assertTrue(types[i].isServerStatePredicate());
        }
        var player = new StatePlayer();
        own(player, 3);
        var quest = quest(player, "[{type:'QUEST_CONTENT_ITEM_NUM_EQUAL',param:[120596,3]}]");
        assertTrue(new QuestSystem(null).triggerContent(quest, quest.getQuestData().getFinishCond().getFirst(), "", 1, 99));
    }

    @Test
    void itemComparisonsUseCurrentInventoryWithStrictBoundariesAndNoResourceGuessing() {
        var player = new StatePlayer();
        var stored = own(player, 3);
        var quest = quest(player, "[]");
        assertTrue(matches(quest, "ITEM_NUM_EQUAL", 3, ""));
        assertFalse(matches(quest, "ITEM_NUM_GREATER", 3, ""));
        assertFalse(matches(quest, "ITEM_NUM_LESS", 3, ""));
        assertTrue(matches(quest, "ITEM_NUM_GREATER", 2, ""));
        assertTrue(matches(quest, "ITEM_NUM_LESS", 4, ""));
        stored.setCount(0);
        assertTrue(matches(quest, "ITEM_NUM_EQUAL", 0, ""));
        var invalid = condition("ITEM_NUM_EQUAL", "[999999999,0]", "");
        assertFalse(ContentServerState.matchesCurrent(quest, invalid));
    }

    @Test
    void acceptedAddRemoveAndQuestVarEventsRecheckAnAndGateInsteadOfLatchingOldCounts() throws Exception {
        var player = new StatePlayer();
        var stored = own(player, 1);
        var quest = quest(player, "[{type:'QUEST_CONTENT_ITEM_NUM_EQUAL',param:[120596,3]},"
                + "{type:'QUEST_CONTENT_QUEST_VAR_NOT_EQUAL',param:[0,0]}]");
        assertTrue(player.getInventory().addItem(new GameItem(GameData.getItemDataMap().get(ITEM), 2)));
        drain();
        assertArrayEquals(new int[] {1, 0}, quest.getFinishProgressList());
        assertTrue(player.getInventory().removeItem(stored, 1));
        drain();
        assertArrayEquals(new int[] {0, 0}, quest.getFinishProgressList());
        quest.getMainQuest().setQuestVar(0, 1);
        drain();
        assertArrayEquals(new int[] {0, 1}, quest.getFinishProgressList());
        assertEquals(0, quest.finishes);
        assertTrue(player.getInventory().addItem(new GameItem(GameData.getItemDataMap().get(ITEM), 1)));
        drain();
        assertArrayEquals(new int[] {1, 1}, quest.getFinishProgressList());
        assertEquals(1, quest.finishes);
    }

    @Test
    void anyTeleportRequiresOneConfiguredPointInTheConfiguredScene() {
        var player = new StatePlayer();
        var quest = quest(player, "[]");
        var target = condition("UNLOCK_ANY_TRANS_POINT", "[3,0]", "814,815,922,923,1121");
        player.getUnlockedScenePoints(4).add(814);
        assertFalse(ContentServerState.matchesCurrent(quest, target));
        player.getUnlockedScenePoints(3).add(900);
        assertFalse(ContentServerState.matchesCurrent(quest, target));
        player.getUnlockedScenePoints(3).add(922);
        assertTrue(ContentServerState.matchesCurrent(quest, target));
        assertFalse(ContentServerState.matchesCurrent(quest,
                condition("UNLOCK_ANY_TRANS_POINT", "[3,0]", "922,bad")));
    }

    @Test
    void ownHomeRequiresRealHomeOwnershipLoadedSceneAndConfiguredRealm() throws Exception {
        var player = new StatePlayer();
        var quest = quest(player, "[]");
        var target = condition("ENTER_MY_HOME_WORLD", "[0,0]", "2001,2002,2003");
        var home = home(player);
        enter(player, home, 2001);
        assertTrue(ContentServerState.matchesCurrent(quest, target));
        player.setSceneLoadState(Player.SceneLoadState.LOADING);
        assertFalse(ContentServerState.matchesCurrent(quest, target));
        enter(player, home, 2004);
        assertFalse(ContentServerState.matchesCurrent(quest, target));
        enter(player, home(new StatePlayer()), 2001);
        assertFalse(ContentServerState.matchesCurrent(quest, target));
        enter(player, TestWorld.create(player), 2001);
        assertFalse(ContentServerState.matchesCurrent(quest, target));
    }

    @Test
    void clientProgressReportsCannotTriggerAnyOfTheSixServerConditions() throws Exception {
        var player = new StatePlayer();
        own(player, 3);
        enter(player, home(player), 2001);
        player.getUnlockedScenePoints(3).add(814);
        var session = new RecordingSession(player);
        var handler = new HandlerAddQuestContentProgressReq();
        for (String json : List.of(
                "{type:'QUEST_CONTENT_ITEM_NUM_EQUAL',param:[120596,3]}",
                "{type:'QUEST_CONTENT_ITEM_NUM_GREATER',param:[120596,2]}",
                "{type:'QUEST_CONTENT_ITEM_NUM_LESS',param:[120596,4]}",
                "{type:'QUEST_CONTENT_QUEST_VAR_NOT_EQUAL',param:[0,1]}",
                "{type:'QUEST_CONTENT_UNLOCK_ANY_TRANS_POINT',param:[3,0],param_str:'814'}",
                "{type:'QUEST_CONTENT_ENTER_MY_HOME_WORLD',param:[0,0],param_str:'2001'}")) {
            var quest = quest(player, "[" + json + "]");
            var condition = quest.getQuestData().getFinishCond().getFirst();
            assertTrue(ContentServerState.matchesCurrent(quest, condition));
            var request = AddQuestContentProgressReq.newBuilder()
                    .setContentType(condition.getType().getValue()).setParam(999999).build();
            handler.handle(session, new byte[0], request.toByteArray());
            drain();
            assertEquals(0, quest.finishes);
            assertArrayEquals(new int[] {0}, quest.getFinishProgressList());
        }
        assertEquals(6, session.packets.size());
    }

    @Test
    void materialSubtypeUsesRealPackQuantitiesAndLossesUndoAnUnfinishedAndGate() throws Exception {
        GameData.getItemDataMap().put(ITEM, JsonUtils.decode(
                "{id:120596,itemType:'ITEM_MATERIAL',materialType:'MATERIAL_CRICKET',stackLimit:99}", ItemData.class));
        var player = new StatePlayer();
        var stored = own(player, 2);
        var quest = quest(player, "[{type:'QUEST_CONTENT_OBTAIN_MATERIAL_WITH_SUBTYPE',param:[9,0],count:3},"
                + "{type:'QUEST_CONTENT_QUEST_VAR_NOT_EQUAL',param:[0,0]}]");
        assertFalse(ContentServerState.matchesCurrent(quest, quest.getQuestData().getFinishCond().getFirst()));
        assertTrue(player.getInventory().addItem(new GameItem(GameData.getItemDataMap().get(ITEM), 1)));
        drain();
        assertArrayEquals(new int[] {1,0}, quest.getFinishProgressList());
        assertTrue(player.getInventory().removeItem(stored, 1));
        drain();
        assertArrayEquals(new int[] {0,0}, quest.getFinishProgressList());
        quest.getMainQuest().setQuestVar(0, 1);
        drain();
        assertArrayEquals(new int[] {0,1}, quest.getFinishProgressList());
        assertEquals(0, quest.finishes);
        assertTrue(player.getInventory().addItem(new GameItem(GameData.getItemDataMap().get(ITEM), 1)));
        drain();
        assertEquals(1, quest.finishes);
        assertFalse(ContentServerState.matchesCurrent(quest,
                condition("OBTAIN_MATERIAL_WITH_SUBTYPE", "[120596,0]", "")));
    }

    @Test
    void variousItemsCountsAcceptedRoundQuantitiesRatherThanOldInventoryOrNumberOfDifferentKinds() throws Exception {
        var player = new StatePlayer();
        var stored = own(player, 20); // Native 122 has no refreshProgress inventory seeding.
        var quest = quest(player, "[{type:'QUEST_CONTENT_OBTAIN_VARIOUS_ITEM',param:[0,0],"
                + "param_str:'120596',count:3},{type:'QUEST_CONTENT_QUEST_VAR_NOT_EQUAL',param:[0,0]}]");
        var target = quest.getQuestData().getFinishCond().getFirst();
        assertEquals(0, QuestInventoryProgress.currentCount(quest, target));
        assertTrue(player.getInventory().addItem(new GameItem(GameData.getItemDataMap().get(ITEM), 2)));
        drain();
        assertEquals(2, QuestInventoryProgress.currentCount(quest, target));
        assertArrayEquals(new int[] {0,0}, quest.getFinishProgressList());
        assertArrayEquals(new int[] {2,0}, quest.getFinishProgressForNotify());
        var wire = QuestProgressUpdateNotify.parseFrom(new PacketQuestProgressUpdateNotify(quest).getData());
        assertEquals(2, wire.getFinishProgressList(0));
        assertEquals(2, quest.toProto().getFinishProgressList(0));
        assertTrue(player.getInventory().removeItem(stored, 1));
        drain();
        assertEquals(1, QuestInventoryProgress.currentCount(quest, target));
        assertFalse(player.getInventory().addItem(new GameItem(GameData.getItemDataMap().get(ITEM), 0)));
        assertEquals(1, QuestInventoryProgress.currentCount(quest, target));
        quest.getMainQuest().setQuestVar(0, 1);
        drain();
        assertEquals(0, quest.finishes);
        assertTrue(player.getInventory().addItem(new GameItem(GameData.getItemDataMap().get(ITEM), 2)));
        drain();
        assertEquals(3, QuestInventoryProgress.currentCount(quest, target));
        assertEquals(1, quest.finishes);
    }

    @Test
    void variousItemCountsARealAcceptedImmediateEffectAndRejectsAnUnsupportedEffect() throws Exception {
        var player = new StatePlayer();
        var server = allocate(GameServer.class);
        var inventorySystem = GameServer.class.getDeclaredField("inventorySystem");
        inventorySystem.setAccessible(true);
        inventorySystem.set(server, new InventorySystem(server));
        player.setSession(new GameSession(server));
        var quest = quest(player, "[{type:'QUEST_CONTENT_OBTAIN_VARIOUS_ITEM',param:[0,0],"
                + "param_str:'120596',count:2}]");
        var target = quest.getQuestData().getFinishCond().getFirst();
        var card = JsonUtils.decode("{id:120596,itemType:'ITEM_MATERIAL',materialType:'MATERIAL_NAMECARD',"
                + "stackLimit:1,useOnGain:true,itemUse:[{useOp:'ITEM_USE_GAIN_NAME_CARD',useParam:['120596']}]}", ItemData.class);
        card.onLoad();
        GameData.getItemDataMap().put(ITEM, card);
        assertTrue(player.getInventory().addItem(new GameItem(card, 1)));
        drain();
        assertTrue(player.getNameCardList().contains(ITEM));
        assertEquals(0, player.getInventory().getItemCountById(ITEM));
        assertEquals(1, QuestInventoryProgress.currentCount(quest, target));
        var unsupported = JsonUtils.decode("{id:120596,itemType:'ITEM_MATERIAL',materialType:'MATERIAL_CHANNELLER_SLAB_BUFF',"
                + "stackLimit:1,useOnGain:true,itemUse:[{useOp:'ITEM_USE_ADD_CHANNELLER_SLAB_BUFF',useParam:['1']}]}", ItemData.class);
        unsupported.onLoad();
        GameData.getItemDataMap().put(ITEM, unsupported);
        assertFalse(player.getInventory().addItem(new GameItem(unsupported, 1)));
        drain();
        assertEquals(1, QuestInventoryProgress.currentCount(quest, target));
        assertEquals(0, quest.finishes);
    }

    @Test
    void variousItemSavedCounterResumesAndExplicitRewindResetsOnlyItsOwnRound() throws Exception {
        var player = new StatePlayer();
        own(player, 20);
        var quest = quest(player, "[{type:'QUEST_CONTENT_OBTAIN_VARIOUS_ITEM',param:[0,0],"
                + "param_str:'120596',count:3}]");
        var savedQuestReader = new com.google.gson.GsonBuilder()
                .setExclusionStrategies(new com.google.gson.ExclusionStrategy() {
                    @Override public boolean shouldSkipField(com.google.gson.FieldAttributes field) {
                        return field.getAnnotation(dev.morphia.annotations.Transient.class) != null;
                    }
                    @Override public boolean shouldSkipClass(Class<?> type) { return false; }
                }).create();
        var restored = savedQuestReader.fromJson("{subQuestId:99001901,mainQuestId:990019,"
                + "state:'QUEST_STATE_UNFINISHED',finishProgressList:[0],failProgressList:[],"
                + "obtainedVariousItems:{'120596':2}}", TrackingQuest.class);
        restored.setMainQuest(quest.getMainQuest());
        restored.setConfig(quest.getQuestData());
        quest.getMainQuest().getChildQuests().put(restored.getSubQuestId(), restored);
        assertNull(quest.getMainQuest().resumeOnLogin());
        assertEquals(QuestState.QUEST_STATE_UNFINISHED, restored.getState());
        assertEquals(2, QuestInventoryProgress.currentCount(restored, restored.getQuestData().getFinishCond().getFirst()));
        assertArrayEquals(new int[] {2}, restored.getFinishProgressForNotify());
        player.getPlayerProgress().addToCurrentProgress("unrelated_story", 7);
        assertTrue(restored.clearProgress(false));
        assertFalse(QuestInventoryProgress.preserveOnLogin(quest.getMainQuest()));
        assertEquals(0, QuestInventoryProgress.currentCount(restored, restored.getQuestData().getFinishCond().getFirst()));
        assertEquals(7, player.getPlayerProgress().getCurrentProgress("unrelated_story"));
    }

    @Test
    void cityLevelUsesSavedStatueLevelAndAcceptsAnAlreadyReachedThreshold() throws Exception {
        var player = new StatePlayer();
        var quest = quest(player, "[{type:'QUEST_CONTENT_CITY_LEVEL_UP',param:[1,2]}]");
        var city = new CityInfoData(1);
        player.setCityInfoData(new HashMap<>(Map.of(1,city)));
        var target = quest.getQuestData().getFinishCond().getFirst();
        assertFalse(new QuestSystem(null).triggerContent(quest, target, "", 1, 1, 99));
        city.setLevel(3);
        assertTrue(ContentServerState.matchesCurrent(quest, target));
        assertFalse(ContentServerState.matchesCurrent(quest, condition("CITY_LEVEL_UP", "[2,2]", "")));
        player.getQuestManager().triggerEvent(QuestContent.QUEST_CONTENT_CITY_LEVEL_UP, "", 1, 1);
        assertEquals(1, quest.finishes);
    }

    @Test
    void recipePredicatesRequireEveryListedRecipeAndRealUnlockEventsCompleteTheQuest() throws Exception {
        var player = new StatePlayer();
        var quest = quest(player, "[{type:'QUEST_CONTENT_UNLOCKED_RECIPE',param:[0,0],param_str:'3107,2105,2102'}]");
        var all = quest.getQuestData().getFinishCond().getFirst();
        var locked = condition("NOT_UNLOCKED_RECIPE", "[0,0]", "3107,2105,2102");
        assertFalse(ContentServerState.matchesCurrent(quest, all));
        assertTrue(ContentServerState.matchesCurrent(quest, locked));
        var cooking = new CookingManager(player);
        assertTrue(cooking.unlockRecipe(3107));
        drain();
        assertEquals(0, quest.finishes);
        assertFalse(ContentServerState.matchesCurrent(quest, locked));
        assertTrue(ContentServerState.matchesCurrent(quest, condition("NOT_UNLOCKED_RECIPE", "[0,0]", "2105")));
        assertTrue(cooking.unlockRecipe(2105));
        drain();
        assertEquals(0, quest.finishes);
        assertTrue(cooking.unlockRecipe(2102));
        drain();
        assertEquals(1, quest.finishes);
        assertFalse(cooking.unlockRecipe(2102));
        assertFalse(ContentServerState.matchesCurrent(quest, condition("UNLOCKED_RECIPE", "[0,0]", "3107,bad")));
        assertFalse(ContentServerState.matchesCurrent(quest, condition("NOT_UNLOCKED_RECIPE", "[0,0]", "9999999")));
    }

    @Test
    void singleAndBatchClientReportsCannotDriveInventoryRecipeOrCityState() throws Exception {
        var player = new StatePlayer();
        own(player, 20);
        var session = new RecordingSession(player);
        for (String json : List.of(
                "{type:'QUEST_CONTENT_OBTAIN_MATERIAL_WITH_SUBTYPE',param:[9,0]}",
                "{type:'QUEST_CONTENT_OBTAIN_VARIOUS_ITEM',param:[0,0],param_str:'120596',count:3}",
                "{type:'QUEST_CONTENT_CITY_LEVEL_UP',param:[1,2]}",
                "{type:'QUEST_CONTENT_UNLOCKED_RECIPE',param:[0,0],param_str:'3107'}",
                "{type:'QUEST_CONTENT_NOT_UNLOCKED_RECIPE',param:[0,0],param_str:'2105'}")) {
            var quest = quest(player, "[" + json + "]");
            var type = quest.getQuestData().getFinishCond().getFirst().getType();
            assertTrue(type.isServerStatePredicate());
            var request = AddQuestContentProgressReq.newBuilder().setContentType(type.getValue()).setParam(9999999).build();
            new HandlerAddQuestContentProgressReq().handle(session, new byte[0], request.toByteArray());
            var batch = _AddQuestContentProgressBatchReq.newBuilder().addProgressInfoList(
                    _QuestProgressInfo.newBuilder().setContentType(type.getValue()).setParam(9999999)).build();
            new HandlerAddQuestContentProgressBatchReq().handle(session, new byte[0], batch.toByteArray());
            drain();
            assertEquals(0, quest.finishes);
            assertArrayEquals(new int[] {0}, quest.getFinishProgressList());
        }
    }

    private static boolean matches(GameQuest quest, String type, int count, String text) {
        return ContentServerState.matchesCurrent(quest, condition(type, "[120596," + count + "]", text));
    }

    private static QuestData.QuestContentCondition condition(String type, String params, String text) {
        return JsonUtils.decode("{type:'QUEST_CONTENT_" + type + "',param:" + params
                + ",param_str:'" + text + "'}", QuestData.QuestContentCondition.class);
    }

    private static TrackingQuest quest(StatePlayer player, String finishConditions) {
        var main = new GameMainQuest(player, MAIN);
        var data = JsonUtils.decode("{mainId:990019,subId:99001901,finishCondComb:'LOGIC_AND',"
                + "finishCond:" + finishConditions + ",failCond:[]}", QuestData.class);
        var quest = new TrackingQuest(main, data);
        quest.setState(QuestState.QUEST_STATE_UNFINISHED);
        main.getChildQuests().put(data.getId(), quest);
        player.getQuestManager().getMainQuests().put(MAIN, main);
        return quest;
    }

    private static GameItem own(StatePlayer player, int count) {
        var item = new GameItem(GameData.getItemDataMap().get(ITEM), count) {
            @Override public void save() {}
        };
        item.setOwner(player);
        player.getInventory().getItems().put(item.getGuid(), item);
        player.getInventory().getInventoryTab(ItemType.ITEM_MATERIAL).onAddItem(item);
        return item;
    }

    private static void drain() throws Exception {
        QuestManager.eventExecutor.submit(() -> {}).get(10, TimeUnit.SECONDS);
    }

    private static final class StatePlayer extends TestWorld.TestPlayer {
        @Override public void save() {}
    }

    private static final class TrackingQuest extends GameQuest {
        int finishes;
        TrackingQuest(GameMainQuest main, QuestData data) { super(main, data); }
        @Override public void finish() { finishes++; }
        @Override public void save() {}
    }

    private static final class RecordingSession extends GameSession {
        final Player player;
        final List<BasePacket> packets = new ArrayList<>();
        RecordingSession(Player player) { super(null); this.player = player; }
        @Override public Player getPlayer() { return player; }
        @Override public void send(BasePacket packet) { packets.add(packet); }
    }

    private static TestHome home(Player host) throws Exception {
        var home = allocate(TestHome.class);
        home.host = host;
        return home;
    }

    private static void enter(Player player, World world, int id) throws Exception {
        var scene = allocate(StateScene.class);
        scene.world = world;
        scene.id = id;
        player.setWorld(world);
        player.setScene(scene);
        player.setSceneId(id);
        player.setSceneLoadState(Player.SceneLoadState.LOADED);
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
    }

    private static final class TestHome extends HomeWorld {
        Player host;
        private TestHome() { super(null, null); }
        @Override public Player getHost() { return host; }
    }

    private static final class StateScene extends Scene {
        World world;
        int id;
        private StateScene() { super(null, null); }
        @Override public World getWorld() { return world; }
        @Override public int getId() { return id; }
    }
}
