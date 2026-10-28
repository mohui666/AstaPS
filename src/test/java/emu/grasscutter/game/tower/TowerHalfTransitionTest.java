package emu.grasscutter.game.tower;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.excels.dungeon.DungeonData;
import emu.grasscutter.game.dungeons.DungeonManager;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.SceneType;
import emu.grasscutter.game.world.Position;
import emu.grasscutter.game.world.TestWorld;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.packet.PacketOpcodes;
import emu.grasscutter.scripts.ScriptLoader;
import emu.grasscutter.server.game.GameServer;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.scheduler.ServerTaskScheduler;
import emu.grasscutter.utils.JsonUtils;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import sun.misc.Unsafe;

@ExtendWith(ServerResourceFixture.class)
@ResourceLock("GameData")
final class TowerHalfTransitionTest {
    private RecordingPlayer player;
    private TestWorld world;
    private TestWorld.TestScene scene;

    @BeforeEach
    void setUp() throws Exception {
        ScriptLoader.init();
        var server = (GameServer) unsafe().allocateInstance(GameServer.class);
        field(GameServer.class, "scheduler").set(server, new ServerTaskScheduler());
        player = new RecordingPlayer(server);
        player.getTowerData().currentFloorId = 1134;
        player.getTowerData().currentLevel = 0;
        player.getTowerManager().rememberAbyssTeams(List.of(List.of(101L), List.of(102L)), 0);
        world = TestWorld.create(player);
        scene = dungeonScene();
        player.setScene(scene);
        player.setSceneId(scene.getId());
        player.getPosition().set(new Position(2, 0, 8));
    }

    @Test
    void startingTheLowerHalfKeepsTheDungeonAndDoesNotLaunchAnotherCutscene() {
        player.getTowerManager().mirrorTeamSetUp(1);

        assertTrue(player.getTowerManager().isMidHalfCutscenePending());
        assertSame(scene, player.getScene());
        assertEquals(33881, player.getSceneId());
        assertTrue(player.packets.stream().anyMatch(packet -> packet.getOpcode() == PacketOpcodes.CanUseSkillNotify));
        assertFalse(player.packets.stream().anyMatch(packet -> packet.getOpcode() == PacketOpcodes.CutSceneBeginNotify));
        assertFalse(player.packets.stream().anyMatch(packet -> packet.getOpcode() == 0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"scene_instance", "floor", "chamber"})
    void anOldSwapCannotTouchAnotherDungeonOrChamber(String change) throws Exception {
        var tower = player.getTowerManager();
        tower.mirrorTeamSetUp(1);
        var token = token(tower);
        switch (change) {
            case "scene_instance" -> player.setScene(dungeonScene()); // Same id, new dungeon run.
            case "floor" -> player.getTowerData().currentFloorId = 1135;
            case "chamber" -> player.getTowerData().currentLevel = 1;
            default -> fail(change);
        }
        var expectedScene = player.getScene();
        var position = new Position(player.getPosition());
        int sent = player.packets.size();

        complete(tower, token);

        assertFalse(tower.isMidHalfCutscenePending());
        assertSame(expectedScene, player.getScene());
        assertEquals(position.toProto(), player.getPosition().toProto());
        assertEquals(sent, player.packets.size(), "no old scene packets after leaving the originating chamber");
    }

    @Test
    void aStaleTimerCannotConsumeANewerSwapOnTheSameScene() throws Exception {
        var tower = player.getTowerManager();
        tower.mirrorTeamSetUp(1);
        var oldToken = token(tower);
        tower.mirrorTeamSetUp(1);
        var currentToken = token(tower);
        assertNotSame(oldToken, currentToken);

        complete(tower, oldToken);

        assertTrue(tower.isMidHalfCutscenePending());
        assertSame(currentToken, token(tower));
    }

    @Test
    void leavingTheTowerCancelsTheSwapAndReleasesItsSkillHold() throws Exception {
        var tower = player.getTowerManager();
        tower.mirrorTeamSetUp(1);
        var oldToken = token(tower);

        tower.clearAbyssResume();

        assertFalse(tower.isMidHalfCutscenePending());
        var last = player.packets.getLast();
        assertEquals(PacketOpcodes.CanUseSkillNotify, last.getOpcode());
        assertTrue(emu.grasscutter.net.proto.CanUseSkillNotifyOuterClass.CanUseSkillNotify
                .parseFrom(last.getData()).getIsCanUseSkill());
        int sent = player.packets.size();
        complete(tower, oldToken);
        assertEquals(sent, player.packets.size());
        assertEquals(-1, player.getTowerData().abyssTempTeamIndex);
    }

    private TestWorld.TestScene dungeonScene() {
        var result = new TestWorld.TestScene(world, 33881, SceneType.SCENE_DUNGEON);
        var data = JsonUtils.decode("{\"id\":3665,\"sceneId\":33881}", DungeonData.class);
        var dungeon = new DungeonManager(result, data);
        dungeon.setTowerDungeon(true);
        result.setDungeonManager(dungeon);
        return result;
    }

    private static Position token(TowerManager tower) throws Exception {
        return (Position) field(TowerManager.class, "pendingMidHalfPos").get(tower);
    }

    private static void complete(TowerManager tower, Position token) throws Exception {
        var method = TowerManager.class.getDeclaredMethod("completeMidHalfSwap", Position.class);
        method.setAccessible(true);
        method.invoke(tower, token);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        var result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static Unsafe unsafe() throws Exception {
        return (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
    }

    private static final class RecordingPlayer extends Player {
        private final GameServer server;
        private final List<BasePacket> packets = new ArrayList<>();
        private final GameSession session = new GameSession(null) {
            @Override public Player getPlayer() { return RecordingPlayer.this; }
            @Override public void send(BasePacket packet) { packets.add(packet); }
        };

        private RecordingPlayer(GameServer server) { this.server = server; }
        @Override public GameServer getServer() { return server; }
        @Override public GameSession getSession() { return session; }
        @Override public void sendPacket(BasePacket packet) { packets.add(packet); }
    }
}
