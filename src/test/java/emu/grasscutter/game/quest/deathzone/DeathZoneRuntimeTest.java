package emu.grasscutter.game.quest.deathzone;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.game.props.SceneType;
import emu.grasscutter.game.world.SceneGroupInstance;
import emu.grasscutter.game.world.TestWorld;
import emu.grasscutter.scripts.SceneScriptManager;
import emu.grasscutter.scripts.ScriptLoader;
import emu.grasscutter.scripts.data.SceneGroup;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;

final class DeathZoneRuntimeTest {
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
        @Override public void save() { saves++; }
    }

    @SuppressWarnings("unchecked")
    private static void activeGroup(TestWorld.TestScene scene, int groupId) throws Exception {
        var field = SceneScriptManager.class.getDeclaredField("sceneGroupsInstances");
        field.setAccessible(true);
        ((Map<Integer, SceneGroupInstance>) field.get(scene.getScriptManager())).put(groupId,
                new SceneGroupInstance(SceneGroup.of(groupId), scene.getHost()));
    }

    @Test
    void aRealChangeSavesTheWorldOwnerAndNotifiesOnlyActiveMappedGroups() throws Exception {
        var owner = new SavedPlayer();
        owner.setUid(42);
        var world = TestWorld.create(owner);
        var scene = new TestWorld.TestScene(world, 3, SceneType.SCENE_ROOM);
        owner.setScene(scene);
        var guest = new SavedPlayer();
        guest.setWorld(world);
        guest.setScene(scene);
        var zone = DeathZoneCatalog.installed().zones().get(330111);
        int group = zone.groups().getFirst(), other = zone.groups().get(1);
        activeGroup(scene, group);
        activeGroup(scene, other);
        activeGroup(scene, DeathZoneCatalog.installed().zones().get(330112).groups().getFirst());
        var events = new ArrayList<List<Integer>>();
        DeathZoneRuntime.StatusListener listener = (s, g, id, state, uid) -> {
            assertSame(scene, s);
            assertEquals(0, DeathZoneRuntime.getStatus(s, g, id));
            events.add(List.of(g, id, state, uid));
        };
        assertEquals(1, DeathZoneRuntime.getStatus(scene, group, 0));
        assertEquals(0, DeathZoneRuntime.change(scene, group, 0, false, listener));
        assertEquals(List.of(List.of(group, 330111, 0, 42), List.of(other, 330111, 0, 42)), events);
        assertEquals(1, owner.saves);
        assertEquals(0, guest.saves);
        assertTrue(guest.getDeathZones().isOpen(zone));
        assertEquals(0, DeathZoneRuntime.change(scene, group, 0, false, listener));
        assertEquals(2, events.size());
        assertEquals(1, owner.saves);
    }

    @Test
    void unmappedAndForeignSceneZonesCannotChangeState() {
        var owner = new SavedPlayer();
        var world = TestWorld.create(owner);
        var scene = new TestWorld.TestScene(world, 1, SceneType.SCENE_ROOM);
        owner.setScene(scene);
        DeathZoneRuntime.StatusListener listener = (s, g, id, state, uid) -> fail("Invalid zone emitted an event");
        assertEquals(-1, DeathZoneRuntime.getStatus(scene, 133399999, 0));
        assertEquals(-1, DeathZoneRuntime.change(scene, 133301020, 330111, false, listener));
        assertTrue(owner.getDeathZones().getOpenOverrides().isEmpty());
        assertEquals(0, owner.saves);
    }

    @Test
    void aQueuedScriptFromAnOldSceneCannotChangeTheOwnersCurrentWorld() {
        var owner = new SavedPlayer();
        var world = TestWorld.create(owner);
        var scene = new TestWorld.TestScene(world, 3, SceneType.SCENE_ROOM);
        owner.setScene(new TestWorld.TestScene(world, 1, SceneType.SCENE_ROOM));
        assertEquals(-1, DeathZoneRuntime.change(scene, 133301020, 330111, false,
                (s, g, id, state, uid) -> fail("Old scene emitted an event")));
        assertEquals(0, owner.saves);
    }
}
