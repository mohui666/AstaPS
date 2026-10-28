package emu.grasscutter.game.quest.war;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.MainQuestData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.SceneType;
import emu.grasscutter.game.quest.GameMainQuest;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.QuestManager;
import emu.grasscutter.game.world.TestWorld;
import emu.grasscutter.scripts.ScriptLoader;
import emu.grasscutter.utils.JsonUtils;
import org.junit.jupiter.api.*;

final class QuestWarRuntimeTest {
    private static boolean scripts;

    @BeforeAll
    static void prepareScenes() throws Exception {
        ScriptLoader.init();
        scripts = Grasscutter.getConfig().server.game.enableScriptInBigWorld;
        Grasscutter.getConfig().server.game.enableScriptInBigWorld = false;
    }

    @AfterAll
    static void restoreScripts() {
        Grasscutter.getConfig().server.game.enableScriptInBigWorld = scripts;
    }

    private static final class SavedPlayer extends TestWorld.TestPlayer {
        int saves;
        private final QuestManager quests = new QuestManager(this);

        @Override public void save() { saves++; }
        @Override public QuestManager getQuestManager() { return quests; }
    }

    private static GameQuest quest(Player player) {
        int mainId = 990211;
        var previous = GameData.getMainQuestDataMap().put(mainId,
                JsonUtils.decode("{id:990211,subQuests:[]}", MainQuestData.class));
        try {
            return new GameQuest(new GameMainQuest(player, mainId), JsonUtils.decode(
                    "{mainId:990211,subId:99021101,finishCond:[],failCond:[]}", QuestData.class));
        } finally {
            if (previous == null) GameData.getMainQuestDataMap().remove(mainId);
            else GameData.getMainQuestDataMap().put(mainId, previous);
        }
    }

    @Test
    void levelTransitionIsSavedAndAnInvalidBatchHasNoEffects() {
        var player = new SavedPlayer();
        TestWorld.create(player);
        assertTrue(QuestWarRuntime.setLevels(player, new int[] {1}, 1));
        assertTrue(QuestWarRuntime.setLevels(player, new int[] {1}, 3));
        assertEquals(2, player.saves);
        assertFalse(QuestWarRuntime.setLevels(player, new int[] {1, Integer.MAX_VALUE}, 4));
        assertFalse(QuestWarRuntime.setLevels(player, null, 4));
        assertEquals(3, player.getQuestWar().getLevelState(1));
        assertEquals(2, player.saves);
    }

    @Test
    void livePredicateRequiresOwnersLoadedWarSceneAndSavedState() {
        var player = new SavedPlayer();
        var world = TestWorld.create(player);
        var scene = new TestWorld.TestScene(world, 20238, SceneType.SCENE_ROOM);
        player.setScene(scene);
        player.setSceneId(20238);
        player.setSceneLoadState(Player.SceneLoadState.LOADED);
        var quest = quest(player);
        var condition = JsonUtils.decode(
                "{type:'QUEST_CONTENT_ABYSS_WAR_LEVEL_STATE_EQUAL',param:[1,3]}",
                QuestData.QuestContentCondition.class);
        assertFalse(QuestWarRuntime.matchesCurrent(quest, condition));
        player.getQuestWar().setLevels(new int[] {1}, 3);
        assertTrue(QuestWarRuntime.matchesCurrent(quest, condition));
        player.getQuestWar().setLevels(new int[] {1}, 4);
        assertFalse(QuestWarRuntime.matchesCurrent(quest, condition));
        player.getQuestWar().setLevels(new int[] {1}, 3);
        player.setSceneLoadState(Player.SceneLoadState.LOADING);
        assertFalse(QuestWarRuntime.matchesCurrent(quest, condition));
        player.setSceneLoadState(Player.SceneLoadState.LOADED);
        player.setWorld(TestWorld.create(new SavedPlayer()));
        assertFalse(QuestWarRuntime.matchesCurrent(quest, condition));
        assertEquals(-1, QuestWarRuntime.setScriptLevelState(scene, 1, 3));
    }

    @Test
    void nativeCatalogIncludesVerifiedWarIdsAndScene() {
        var catalog = QuestWarCatalog.installed();
        assertEquals(20238, catalog.sceneId());
        assertTrue(catalog.hasLevel(1));
        assertTrue(catalog.hasRegion(20238002));
        assertTrue(catalog.hasAccount(1001));
        assertFalse(catalog.hasRegion(0));
    }
}
