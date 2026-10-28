package emu.grasscutter.game.quest;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.scene.*;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.SceneType;
import emu.grasscutter.game.world.TestWorld;
import emu.grasscutter.scripts.ScriptLoader;
import emu.grasscutter.utils.*;
import java.util.*;
import org.junit.jupiter.api.*;

final class QuestSceneTagsTest {
    private Map<Integer, LevelTagData> previousTags;
    private Map<Integer, LevelTagGroupData> previousGroups;
    private boolean scripts;
    private Player player;
    private int saves;

    @BeforeEach
    void setUp() throws Exception {
        ScriptLoader.init();
        scripts = Grasscutter.getConfig().server.game.enableScriptInBigWorld;
        Grasscutter.getConfig().server.game.enableScriptInBigWorld = false;
        previousTags = new HashMap<>(GameData.getLevelTagDataMap());
        previousGroups = new HashMap<>(GameData.getLevelTagGroupDataMap());
        for (var data : JsonUtils.loadToList(FileUtils.getExcelPath("LevelTagExcelConfigData.json"), LevelTagData.class))
            GameData.getLevelTagDataMap().put(data.getId(), data);
        for (var data : JsonUtils.loadToList(FileUtils.getExcelPath("LevelTagGroupsExcelConfigData.json"), LevelTagGroupData.class))
            GameData.getLevelTagGroupDataMap().put(data.getId(), data);
        player = new TestWorld.TestPlayer() { @Override public void save() { saves++; } };
        var world = TestWorld.create(player);
        player.setScene(new TestWorld.TestScene(world, 3, SceneType.SCENE_WORLD));
        player.setSceneId(3);
        player.setSceneLoadState(Player.SceneLoadState.LOADED);
        player.getSceneTags().put(3, new HashSet<>(Set.of(1094, 999999)));
    }

    @AfterEach
    void tearDown() {
        GameData.getLevelTagDataMap().clear();
        GameData.getLevelTagDataMap().putAll(previousTags);
        GameData.getLevelTagGroupDataMap().clear();
        GameData.getLevelTagGroupDataMap().putAll(previousGroups);
        Grasscutter.getConfig().server.game.enableScriptInBigWorld = scripts;
    }

    @Test
    void nativeDreamStoneTagsSwitchExclusivelyAndPreserveUnrelatedSceneTags() {
        assertEquals(List.of(38), QuestSceneTags.current(player, 12));
        assertEquals("3.0_Dream", GameData.getLevelTagDataMap().get(38).getLevelTagName());
        assertTrue(QuestSceneTags.change(player, 39));
        assertEquals(List.of(39), QuestSceneTags.current(player, 12));
        assertEquals(Set.of(1093, 999999), player.getSceneTags().get(3));
        assertEquals(1, saves);
        assertTrue(QuestSceneTags.change(player, 39));
        assertEquals(1, saves);
        assertTrue(QuestSceneTags.change(player, 38));
        assertEquals(Set.of(1094, 999999), player.getSceneTags().get(3));
        assertEquals(List.of(38), QuestSceneTags.current(player, 12));
    }

    @Test
    void visitorCannotChangeTheHostsDreamAndInvalidTagsDoNotWriteState() {
        assertFalse(QuestSceneTags.change(player, 99999999));
        player.setWorld(TestWorld.create(new TestWorld.TestPlayer()));
        assertFalse(QuestSceneTags.change(player, 39));
        assertEquals(Set.of(1094, 999999), player.getSceneTags().get(3));
        assertEquals(0, saves);
    }

    @Test
    void questSnapshotFollowsTheCurrentEnvironmentAndLoadedScene() {
        var data = GameData.getLevelTagDataMap().get(39);
        assertEquals(List.of(133301163), data.getLoadDynamicGroupList());
        assertFalse(QuestSceneTags.matchesCurrent(quest(), 39));
        assertTrue(QuestSceneTags.change(player, 39));
        assertTrue(QuestSceneTags.matchesCurrent(quest(), 39));
        player.setSceneLoadState(Player.SceneLoadState.LOADING);
        assertFalse(QuestSceneTags.matchesCurrent(quest(), 39));
        player.setSceneLoadState(Player.SceneLoadState.LOADED);
        assertTrue(QuestSceneTags.change(player, 38));
        assertFalse(QuestSceneTags.matchesCurrent(quest(), 39));
    }

    private GameQuest quest() {
        var result = new GameQuest();
        var parent = new GameMainQuest();
        parent.setOwner(player);
        result.setMainQuest(parent);
        return result;
    }
}
