package emu.grasscutter.server.packet.recv;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.ScenePointEntry;
import emu.grasscutter.data.common.PointData;
import emu.grasscutter.game.home.HomeWorld;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.world.Position;
import emu.grasscutter.game.world.Scene;
import emu.grasscutter.game.world.World;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.packet.PacketOpcodes;
import emu.grasscutter.net.proto.DungeonQuickOpenReq;
import emu.grasscutter.net.proto.DungeonQuickOpenRsp;
import emu.grasscutter.net.proto.PersonalSceneJumpReqOuterClass.PersonalSceneJumpReq;
import emu.grasscutter.net.proto.PersonalSceneJumpRspOuterClass.PersonalSceneJumpRsp;
import emu.grasscutter.net.proto.RetcodeOuterClass.Retcode;
import emu.grasscutter.net.proto.SceneTransToPointReqOuterClass.SceneTransToPointReq;
import emu.grasscutter.net.proto.SceneTransToPointRspOuterClass.SceneTransToPointRsp;
import emu.grasscutter.server.event.player.PlayerTeleportEvent.TeleportType;
import emu.grasscutter.server.game.GameServer;
import emu.grasscutter.server.game.GameSession;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

class DungeonEntryTransferResponsesTest {
    private static final int POINT = 60007, SOURCE = 3, DESTINATION = 100;
    private static final int KEY = (SOURCE << 16) + POINT;
    private ScenePointEntry oldPoint;
    private PlayerStub player;
    private RecordingSession session;

    @BeforeEach
    void setup() throws Exception {
        oldPoint = GameData.getScenePointEntryMap().get(KEY);
        player = allocate(PlayerStub.class);
        player.scene = allocate(SceneStub.class);
        player.scene.id = SOURCE;
        player.scene.setPrevScene(71);
        player.world = allocate(WorldStub.class);
        player.world.destination = allocate(SceneStub.class);
        player.world.destination.id = DESTINATION;
        player.home = allocate(HomeStub.class);
        player.unlocked = new HashSet<>();
        session = new RecordingSession(player);
    }

    @AfterEach
    void restorePoint() {
        if (oldPoint == null) GameData.getScenePointEntryMap().remove(KEY);
        else GameData.getScenePointEntryMap().put(KEY, oldPoint);
    }

    @Test
    void unknownAndMalformedQuickOpenRequestsReceiveTypedFailureInsteadOfEmptySuccess() throws Exception {
        byte[] unknown = DungeonQuickOpenReq._DungeonQuickOpenReq.newBuilder()
                .setDungeonEntryConfigId(Integer.MAX_VALUE).build().toByteArray();
        for (byte[] payload : new byte[][] {unknown, new byte[] {(byte) 0xFF}, new byte[0]}) {
            session.packets.clear();
            new HandlerDungeonQuickOpenReq().handle(session, new byte[0], payload);
            assertEquals(1, session.packets.size());
            var packet = session.packets.getFirst();
            assertEquals(PacketOpcodes.DungeonQuickOpenRsp, packet.getOpcode());
            var response = DungeonQuickOpenRsp._DungeonQuickOpenRsp.parseFrom(packet.getData());
            assertEquals(Retcode.RET_DUNGEON_ENTER_FAIL_VALUE, response.getRetcode());
            assertEquals(payload == unknown ? Integer.MAX_VALUE : 0, response.getDungeonEntryConfigId());
            assertEquals(0, player.world.calls);
            assertEquals(SOURCE, player.getSceneId());
        }
    }

    @Test
    void missingJumpPointOrPositionReceivesAnErrorWithoutChangingScenes() throws Exception {
        GameData.getScenePointEntryMap().remove(KEY);
        assertJumpFailure(Retcode.RET_NOT_FOUND_CONFIG_VALUE);
        installPoint(false);
        session.packets.clear();
        assertJumpFailure(Retcode.RET_NOT_FOUND_CONFIG_VALUE);
        assertEquals(0, player.world.calls);
    }

    @Test
    void rejectedTransferDoesNotReportDestinationOrOverwriteTheOldSceneOrigin() throws Exception {
        installPoint(true);
        player.world.succeed = false;
        assertJumpFailure(Retcode.RET_ENTER_SCENE_FAIL_VALUE);
        assertEquals(1, player.world.calls);
        assertEquals(71, player.scene.getPrevScene());
        assertEquals(SOURCE, player.getSceneId());
    }

    @Test
    void successfulTransferReportsTheActualDestinationAndOnlyThenStoresTheOrigin() throws Exception {
        installPoint(true);
        player.world.succeed = true;
        new HandlerPersonalSceneJumpReq().handle(session, new byte[0], jumpRequest());
        assertEquals(1, session.packets.size());
        var response = PersonalSceneJumpRsp.parseFrom(session.packets.getFirst().getData());
        assertEquals(Retcode.RET_SUCC_VALUE, response.getRetcode());
        assertEquals(DESTINATION, response.getDestSceneId());
        assertTrue(response.hasDestPos());
        assertEquals(1f, response.getDestPos().getX());
        assertEquals(2f, response.getDestPos().getY());
        assertEquals(3f, response.getDestPos().getZ());
        assertSame(player.world.destination, player.scene);
        assertEquals(SOURCE, player.scene.getPrevScene());
    }

    @Test
    void waypointRejectionsDistinguishMissingConfigLocksAndFailedTransfer() throws Exception {
        GameData.getScenePointEntryMap().remove(KEY);
        assertWaypointFailure(Retcode.RET_NOT_FOUND_CONFIG_VALUE);
        installPoint(false);
        assertWaypointFailure(Retcode.RET_NOT_FOUND_CONFIG_VALUE);
        installPoint(true);
        assertWaypointFailure(Retcode.RET_POINT_NOT_UNLOCKED_VALUE);
        player.unlocked.add(POINT);
        player.forceLocked = true;
        assertWaypointFailure(Retcode.RET_POINT_NOT_UNLOCKED_VALUE);
        player.forceLocked = false;
        player.world.succeed = false;
        assertWaypointFailure(Retcode.RET_ENTER_SCENE_FAIL_VALUE);
        assertEquals(1, player.world.calls);
        assertEquals(SOURCE, player.getSceneId());
        assertEquals(71, player.scene.getPrevScene());
    }

    private void assertWaypointFailure(int code) throws Exception {
        session.packets.clear();
        var request = SceneTransToPointReq.newBuilder().setPointId(POINT).setSceneId(SOURCE).build();
        new HandlerSceneTransToPointReq().handle(session, new byte[0], request.toByteArray());
        assertEquals(1, session.packets.size());
        var packet = session.packets.getFirst();
        assertEquals(PacketOpcodes.SceneTransToPointRsp, packet.getOpcode());
        var response = SceneTransToPointRsp.parseFrom(packet.getData());
        assertEquals(code, response.getRetcode());
        assertEquals(POINT, response.getPointId());
        assertEquals(SOURCE, response.getSceneId());
    }

    private void assertJumpFailure(int code) throws Exception {
        new HandlerPersonalSceneJumpReq().handle(session, new byte[0], jumpRequest());
        assertEquals(1, session.packets.size());
        var packet = session.packets.getFirst();
        assertEquals(PacketOpcodes.PersonalSceneJumpRsp, packet.getOpcode());
        var response = PersonalSceneJumpRsp.parseFrom(packet.getData());
        assertEquals(code, response.getRetcode());
        assertFalse(response.hasDestPos());
        assertEquals(0, response.getDestSceneId());
        assertEquals(71, player.scene.getPrevScene());
    }

    private static byte[] jumpRequest() {
        return PersonalSceneJumpReq.newBuilder().setPointId(POINT).build().toByteArray();
    }

    private static void installPoint(boolean position) {
        var point = new Gson().fromJson("{\"id\":" + POINT + ",\"tranSceneId\":" + DESTINATION
                + (position ? ",\"tranPos\":{\"x\":1,\"y\":2,\"z\":3}" : "") + "}", PointData.class);
        GameData.getScenePointEntryMap().put(KEY, new ScenePointEntry(SOURCE, point));
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
    }

    private static final class RecordingSession extends GameSession {
        final PlayerStub player;
        final List<BasePacket> packets = new ArrayList<>();
        RecordingSession(PlayerStub player) { super(null); this.player = player; }
        @Override public Player getPlayer() { return player; }
        @Override public void send(BasePacket packet) { packets.add(packet); }
    }

    private static final class PlayerStub extends Player {
        SceneStub scene;
        WorldStub world;
        HomeStub home;
        Set<Integer> unlocked;
        boolean forceLocked;
        @Override public int getSceneId() { return scene.getId(); }
        @Override public Scene getScene() { return scene; }
        @Override public World getWorld() { return world; }
        @Override public HomeWorld getCurHomeWorld() { return home; }
        @Override public Set<Integer> getUnlockedScenePoints(int sceneId) { return unlocked; }
        @Override public boolean isScenePointForceLocked(int sceneId, int pointId) { return forceLocked; }
    }

    private static final class SceneStub extends Scene {
        int id;
        private SceneStub() { super(null, null); }
        @Override public int getId() { return id; }
    }

    private static final class WorldStub extends World {
        boolean succeed;
        int calls;
        SceneStub destination;
        private WorldStub() { super((GameServer) null, null); }
        @Override public boolean transferPlayerToScene(Player player, int sceneId, Position position) {
            calls++;
            if (succeed) ((PlayerStub) player).scene = destination;
            return succeed;
        }
        @Override public boolean transferPlayerToScene(Player player, int sceneId, TeleportType type, Position position) {
            return transferPlayerToScene(player, sceneId, position);
        }
    }

    private static final class HomeStub extends HomeWorld {
        private HomeStub() { super(null, null); }
        @Override public boolean isInHome(Player player) { return false; }
    }
}
