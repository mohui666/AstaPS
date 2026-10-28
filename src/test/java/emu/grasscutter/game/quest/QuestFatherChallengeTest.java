package emu.grasscutter.game.quest;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.dungeon.DungeonChallengeConfigData;
import emu.grasscutter.game.dungeons.challenge.FatherChallenge;
import emu.grasscutter.game.dungeons.challenge.enums.FatherChallengeProperty;
import emu.grasscutter.game.props.SceneType;
import emu.grasscutter.game.world.*;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.scripts.*;
import emu.grasscutter.scripts.constants.EventType;
import emu.grasscutter.scripts.data.*;
import emu.grasscutter.utils.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.jse.JsePlatform;

final class QuestFatherChallengeTest {
    private boolean scripts;
    private Map<Integer,DungeonChallengeConfigData> previous;
    private ClockScene scene;
    private SceneGroup group;
    private ScriptLib lib;
    private Globals lua;
    private final List<ScriptArgs> events = new ArrayList<>();

    @BeforeEach
    void prepare() throws Exception {
        ScriptLoader.init();
        scripts = Grasscutter.getConfig().server.game.enableScriptInBigWorld;
        Grasscutter.getConfig().server.game.enableScriptInBigWorld = false;
        previous = new HashMap<>(GameData.getDungeonChallengeConfigDataMap());
        for (var data : JsonUtils.loadToList(FileUtils.getExcelPath("DungeonChallengeConfigData.json"), DungeonChallengeConfigData.class))
            if (data.getId() >= 267 && data.getId() <= 269)
                GameData.getDungeonChallengeConfigDataMap().put(data.getId(),data);
        var owner = new TestWorld.TestPlayer();
        var world = TestWorld.create(owner);
        scene = new ClockScene(world);
        scene.manager = new SceneScriptManager(scene) {
            @Override public Future<?> callEvent(ScriptArgs args) {
                events.add(args);
                return CompletableFuture.completedFuture(null);
            }
            @Override public void removeMonstersInGroup(SceneGroup target) { scene.cleanups++; }
        };
        group = SceneGroup.of(133301077);
        lib = ScriptLoader.getScriptLib();
        lib.setSceneScriptManager(scene.getScriptManager());
        lib.setCurrentGroup(group);
        lua = JsePlatform.standardGlobals();
        lua.set("ScriptLib",ScriptLoader.getScriptLibLua());
        lua.set("context",ScriptLoader.getScriptLibLua());
    }

    @AfterEach
    void restore() {
        lib.removeSceneScriptManager();
        lib.removeCurrentGroup();
        GameData.getDungeonChallengeConfigDataMap().clear();
        GameData.getDungeonChallengeConfigDataMap().putAll(previous);
        Grasscutter.getConfig().server.game.enableScriptInBigWorld = scripts;
    }

    private FatherChallenge startDreamland() {
        assertEquals(0,lua.load("return ScriptLib.CreateFatherChallenge(context,267,267,99999999,{success=200,fail=2})").call().toint());
        assertEquals(0,lua.load("return ScriptLib.AttachChildChallenge(context,267,268,268,{90,106,99,15},{},{success=100,fail=3})").call().toint());
        assertEquals(0,lib.StartFatherChallenge(267));
        return (FatherChallenge) scene.getChallenge();
    }

    private SceneTrigger trigger(String tag) {
        var result = new SceneTrigger();
        result.setCurrentGroup(group);
        result.setTag(tag);
        return result;
    }

    @Test
    void nativeDreamlandNeedsAllFifteenPickupsAndTheReturnToTheDoor() {
        var father = startDreamland();
        assertEquals(267,father.child(268).getFatherIndex());
        for (int i=0;i<14;i++) father.onGroupTriggerDeath(trigger("99"));
        assertTrue(father.child(268).inProgress());
        father.onGroupTriggerDeath(trigger("unrelated"));
        assertEquals(14,father.child(268).getScore().get());
        father.onGroupTriggerDeath(trigger("99"));
        assertFalse(father.child(268).inProgress());
        assertTrue(father.inProgress());
        assertEquals(100,father.getScore().get());
        assertEquals(0,scene.cleanups);
        assertEquals(0,lua.load("return ScriptLib.AttachChildChallenge(context,267,269,269,{90,9,98,1},{},{success=100,fail=3})").call().toint());
        father.onGroupTriggerDeath(trigger("98"));
        assertFalse(father.inProgress());
        assertTrue(father.isSuccess());
        assertEquals(1,scene.cleanups);
        assertEquals(List.of(268,269,267),events.stream().filter(e -> e.type==EventType.EVENT_CHALLENGE_SUCCESS)
                .map(e -> e.param1).toList());
        father.onGroupTriggerDeath(trigger("98"));
        assertEquals(3,events.size());
    }

    @Test
    void pickupResetsRemainingTimeAndDamageCanReallyExpireTheChildAndFather() {
        var father = startDreamland();
        scene.now=160;
        assertEquals(0,lib.SetChallengeDuration(268,90));
        assertEquals(250,father.child(268).getStartedAt()+father.child(268).getTimeLimit());
        assertEquals(0,lib.AddChallengeDuration(268,-30,false));
        assertEquals(220,father.child(268).getStartedAt()+father.child(268).getTimeLimit());
        scene.now=221;
        father.onCheckTimeOut();
        assertFalse(father.inProgress());
        assertFalse(father.isSuccess());
        assertEquals(List.of(268,267),events.stream().filter(e -> e.type==EventType.EVENT_CHALLENGE_FAIL)
                .map(e -> e.param1).toList());
        assertEquals(-1,lib.SetChallengeDuration(268,90));
    }

    @Test
    void nativeCurrentPropertiesAreDeltasAndSumPropertiesChangeTheTarget() {
        var father = startDreamland();
        assertTrue(father.modify(FatherChallengeProperty.CUR_SUCC,50));
        assertTrue(father.modify(FatherChallengeProperty.CUR_SUCC,50));
        assertEquals(100,father.getScore().get());
        assertTrue(father.modify(FatherChallengeProperty.SUM_SUCC,-50));
        assertEquals(150,father.getGoal());
        assertTrue(father.modify(FatherChallengeProperty.CUR_SUCC,-200));
        assertEquals(0,father.getScore().get());
        assertTrue(father.modify(FatherChallengeProperty.CUR_SUCC,150));
        assertTrue(father.isSuccess());
    }

    private static final class ClockScene extends TestWorld.TestScene {
        int now=100;
        int cleanups;
        SceneScriptManager manager;
        ClockScene(World world) { super(world,3,SceneType.SCENE_WORLD); }
        @Override public int getSceneTimeSeconds() { return now; }
        @Override public SceneScriptManager getScriptManager() { return manager==null?super.getScriptManager():manager; }
        @Override public void broadcastPacket(BasePacket packet) {}
    }
}
