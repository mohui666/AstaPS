package emu.grasscutter.game.quest.content;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.MainQuestData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.SceneType;
import emu.grasscutter.game.quest.GameMainQuest;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.enums.QuestContent;
import emu.grasscutter.game.world.TestWorld;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.scripts.ScriptLoader;
import emu.grasscutter.utils.JsonUtils;
import org.junit.jupiter.api.*;

final class ContentServerStateTest {
    private static boolean bigWorldScripts;

    @BeforeAll
    static void prepareScenes() throws Exception {
        ScriptLoader.init();
        bigWorldScripts = Grasscutter.getConfig().server.game.enableScriptInBigWorld;
        Grasscutter.getConfig().server.game.enableScriptInBigWorld = false;
    }

    @AfterAll
    static void restoreScripts() {
        Grasscutter.getConfig().server.game.enableScriptInBigWorld = bigWorldScripts;
    }

    private static GameQuest quest(TestWorld.TestPlayer player) {
        int mainId = 990011;
        var previous = GameData.getMainQuestDataMap().put(mainId,
                JsonUtils.decode("{id:990011,subQuests:[]}", MainQuestData.class));
        try {
            var data = JsonUtils.decode("{mainId:990011,subId:99001101,finishCond:[],failCond:[]}", QuestData.class);
            return new GameQuest(new GameMainQuest(player, mainId), data);
        } finally {
            if (previous == null) GameData.getMainQuestDataMap().remove(mainId);
            else GameData.getMainQuestDataMap().put(mainId, previous);
        }
    }

    @Test
    void presentSceneObjectiveUsesLoadedServerSceneAndRejectsClientClaims() {
        var player = new TestWorld.TestPlayer();
        var world = TestWorld.create(player);
        player.setScene(new TestWorld.TestScene(world, 1075, SceneType.SCENE_ROOM));
        player.setSceneId(1075);
        player.setSceneLoadState(Player.SceneLoadState.LOADED);
        var quest = quest(player);
        var condition = JsonUtils.decode(
                "{type:'QUEST_CONTENT_PRESENT_AT_SPECIFIC_SCENE',param:[1075,0]}",
                QuestData.QuestContentCondition.class);
        var handler = new ContentServerState();
        assertTrue(handler.execute(quest, condition, ""));
        player.setSceneId(3);
        assertFalse(handler.execute(quest, condition, "", 1075));
        player.setSceneId(1075);
        player.setSceneLoadState(Player.SceneLoadState.LOADING);
        assertFalse(handler.execute(quest, condition, ""));
    }

    @Test
    void globalVariableEventMustMatchTheVariableAndItsSavedValue() {
        var player = new TestWorld.TestPlayer();
        player.getQuestGlobalVariables().put(90700002, 1);
        var quest = quest(player);
        var condition = JsonUtils.decode(
                "{type:'QUEST_CONTENT_QUEST_GLOBAL_VAR_EQUAL',param:[90700002,1]}",
                QuestData.QuestContentCondition.class);
        var handler = new ContentServerState();
        assertTrue(handler.execute(quest, condition, "", 90700002, 999));
        assertFalse(handler.execute(quest, condition, "", 90700003, 1));
        player.getQuestGlobalVariables().put(90700002, 0);
        assertFalse(handler.execute(quest, condition, "", 90700002, 1));
        assertTrue(ContentServerState.compare(QuestContent.QUEST_CONTENT_QUEST_GLOBAL_VAR_NOT_EQUAL, 0, 1));
    }

    @Test
    void visitingAnotherPlayersWorldCannotCountAsReturningToYourOwn() {
        var player = new TestWorld.TestPlayer();
        TestWorld.create(player);
        player.setSceneId(3);
        var quest = quest(player);
        var condition = JsonUtils.decode("{param:[3,0]}", QuestData.QuestContentCondition.class);
        assertTrue(new ContentEnterMyWorld().execute(quest, condition, "", 3));
        assertTrue(new ContentEnterMyWorldScene().execute(quest, condition, "", 3));
        player.setWorld(TestWorld.create(new TestWorld.TestPlayer()));
        assertFalse(new ContentEnterMyWorld().execute(quest, condition, "", 3));
        assertFalse(new ContentEnterMyWorldScene().execute(quest, condition, "", 3));
    }
}
