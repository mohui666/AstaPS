package emu.grasscutter.game.world;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import emu.grasscutter.GameConstants;
import emu.grasscutter.command.Command;
import emu.grasscutter.command.commands.NpcCommand;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.config.ConfigEntityGadget;
import emu.grasscutter.data.excels.GadgetData;
import emu.grasscutter.game.entity.EntityGadget;
import emu.grasscutter.game.entity.EntityNPC;
import emu.grasscutter.game.entity.GameEntity;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.EntityIdType;
import emu.grasscutter.game.props.EntityType;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.MotionStateOuterClass.MotionState;
import emu.grasscutter.net.proto.ProtEntityTypeOuterClass.ProtEntityType;
import emu.grasscutter.net.proto.SceneEntityAppearNotifyOuterClass.SceneEntityAppearNotify;
import emu.grasscutter.net.proto.SceneEntityMoveNotifyOuterClass.SceneEntityMoveNotify;
import emu.grasscutter.net.proto.VisionTypeOuterClass.VisionType;
import emu.grasscutter.server.game.GameServer;
import emu.grasscutter.scripts.data.SceneBlock;
import emu.grasscutter.server.packet.send.PacketSceneEntityAppearNotify;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sun.misc.Unsafe;

final class PaimonCompanionsTest {
    @TempDir Path directory;
    private GadgetData oldGadget;
    private ConfigEntityGadget oldConfig;
    private PaimonCompanions service;
    private SceneStub scene;
    private PlayerStub player;

    @BeforeEach
    void setup() throws Exception {
        oldGadget = GameData.getGadgetDataMap().get(PaimonCompanions.GADGET_ID);
        oldConfig = GameData.getGadgetConfigData().get("NPC_Guide_Paimon");
        GameData.getGadgetDataMap().put(PaimonCompanions.GADGET_ID, new Gson().fromJson(
                "{\"id\":90000003,\"jsonName\":\"NPC_Guide_Paimon\",\"type\":\"Gadget\"}", GadgetData.class));
        GameData.getGadgetConfigData().put("NPC_Guide_Paimon", new ConfigEntityGadget());
        service = new PaimonCompanions(directory);
        scene = scene(3, null);
        player = new PlayerStub(2, scene);
    }

    @AfterEach
    void restore() {
        if (oldGadget == null) GameData.getGadgetDataMap().remove(PaimonCompanions.GADGET_ID);
        else GameData.getGadgetDataMap().put(PaimonCompanions.GADGET_ID, oldGadget);
        if (oldConfig == null) GameData.getGadgetConfigData().remove("NPC_Guide_Paimon");
        else GameData.getGadgetConfigData().put("NPC_Guide_Paimon", oldConfig);
    }

    @Test
    void fiveFollowersMoveThroughNativePacketsWithoutRespawning() throws Exception {
        service.configureNow(player, 5);
        assertEquals(5, followers(scene, 2).size());
        assertEquals(5, savedCount(2));
        var original = ids(scene, 2);
        var appearPacket = new PacketSceneEntityAppearNotify(followers(scene, 2), VisionType.VisionType_VISION_BORN);
        var appear = SceneEntityAppearNotify.parseFrom(appearPacket.getData());
        assertEquals(5, appear.getEntityListCount());
        for (var entity : appear.getEntityListList()) {
            assertEquals(ProtEntityType.ProtEntityType_PROT_ENTITY_GADGET, entity.getEntityType());
            assertEquals(90000003, entity.getGadget().getGadgetId());
            assertFalse(entity.hasNpc());
        }
        for (var follower : followers(scene, 2)) {
            assertEquals(EntityType.Gadget, follower.getEntityType());
            assertEquals(3001, follower.getBlockId());
            assertNotNull(follower.getContent());
            assertTrue(follower.getPosition().getZ() > player.position.getZ());
        }

        player.position.addX(6);
        service.tick(Map.of(2, player));
        assertEquals(original, ids(scene, 2));
        assertEquals(5, scene.added);
        assertEquals(5, scene.packets.size());
        for (var packet : scene.packets) {
            var move = SceneEntityMoveNotify.parseFrom(packet.getData());
            assertTrue(original.contains(move.getEntityId()));
            assertEquals(MotionState.MotionState_MOTION_FLY, move.getMotionInfo().getState());
            assertNotEquals(0f, move.getMotionInfo().getSpeed().getX());
        }
        for (int i = 0; i < 100; i++) service.tick(Map.of(2, player));
        assertEquals(original, ids(scene, 2));
        assertEquals(5, scene.added);
        assertTrue(scene.removed.isEmpty());
    }

    @Test
    void teleportAndSceneLoadingReplaceOnlyTheOwnedFollowers() throws Exception {
        var storyNpc = new EntityNPC(scene, 1005, new Position(), new Position());
        storyNpc.setGroupId(12345);
        scene.addEntity(storyNpc);
        service.configureNow(player, 5);
        var original = ids(scene, 2);
        player.setSceneLoadState(Player.SceneLoadState.LOADING);
        service.tick(Map.of(2, player));
        assertTrue(followers(scene, 2).isEmpty());
        assertSame(storyNpc, scene.getEntityById(storyNpc.getId()));

        var next = scene(1006, scene.world);
        player.scene = next;
        player.setSceneLoadState(Player.SceneLoadState.LOADED);
        service.tick(Map.of(2, player));
        assertEquals(5, followers(next, 2).size());
        assertTrue(ids(next, 2).stream().noneMatch(original::contains));
        assertSame(storyNpc, scene.getEntityById(storyNpc.getId()));
        assertEquals(5, savedCount(2));
    }

    @Test
    void logoutUnloadsEntitiesButReloginRestoresTheSavedCount() throws Exception {
        service.configureNow(player, 5);
        player.online = false;
        service.tick(Map.of());
        assertTrue(followers(scene, 2).isEmpty());
        assertEquals(5, savedCount(2));

        var afterRestart = new PaimonCompanions(directory);
        var reconnected = new PlayerStub(2, scene(3, null));
        afterRestart.tick(Map.of(2, reconnected));
        assertEquals(5, followers(reconnected.scene, 2).size());
    }

    @Test
    void disablingOnePlayerDoesNotRemoveAnotherPlayerOrStoryPaimon() {
        var other = new PlayerStub(3, scene);
        var storyNpc = new EntityNPC(scene, 1005, new Position(), new Position());
        scene.addEntity(storyNpc);
        var unrelatedPaimon = new EntityGadget(scene, PaimonCompanions.GADGET_ID, new Position());
        scene.addEntity(unrelatedPaimon);
        service.configureNow(player, 5);
        service.configureNow(other, 2);
        var otherIds = ids(scene, 3);
        service.configureNow(player, 0);
        assertTrue(followers(scene, 2).isEmpty());
        assertEquals(otherIds, ids(scene, 3));
        assertSame(storyNpc, scene.getEntityById(storyNpc.getId()));
        assertSame(unrelatedPaimon, scene.getEntityById(unrelatedPaimon.getId()));
    }

    @Test
    void anIndividuallyRemovedFollowerIsReplacedWithoutRecreatingTheOthers() {
        service.configureNow(player, 5);
        var original = ids(scene, 2);
        int removed = original.iterator().next();
        scene.entities.remove(removed);
        service.tick(Map.of(2, player));
        assertEquals(5, followers(scene, 2).size());
        assertEquals(4, ids(scene, 2).stream().filter(original::contains).count());
        assertFalse(ids(scene, 2).contains(removed));
        assertEquals(6, scene.added);
    }

    @Test
    void invalidCountsAndMissingGadgetDoNotWriteOrSpawnAndCommandPermissionsStayIntact() {
        for (int count : new int[] {-1, 21}) {
            assertThrows(IllegalArgumentException.class, () -> service.configureNow(player, count));
        }
        GameData.getGadgetDataMap().remove(PaimonCompanions.GADGET_ID);
        assertThrows(IllegalArgumentException.class, () -> service.configureNow(player, 5));
        assertFalse(Files.exists(directory.resolve("2.json")));
        assertTrue(followers(scene, 2).isEmpty());
        var annotation = NpcCommand.class.getAnnotation(Command.class);
        assertEquals("server.npc", annotation.permission());
        assertEquals("server.npc.others", annotation.permissionTargeted());
        assertDoesNotThrow(() -> new NpcCommand().createCommandLine(null, null).parseArgs("follow", "5"));
    }

    private int savedCount(int uid) throws Exception {
        return JsonParser.parseString(Files.readString(directory.resolve(uid + ".json")))
                .getAsJsonObject().get("count").getAsInt();
    }

    private static Set<Integer> ids(SceneStub scene, int uid) {
        return followers(scene, uid).stream().map(GameEntity::getId).collect(Collectors.toSet());
    }

    private static List<PaimonCompanions.Follower> followers(SceneStub scene, int uid) {
        return scene.entities.values().stream().filter(entity -> entity instanceof PaimonCompanions.Follower)
                .map(entity -> (PaimonCompanions.Follower) entity).filter(entity -> entity.getOwnerUid() == uid).toList();
    }

    private static SceneStub scene(int id, WorldStub existingWorld) throws Exception {
        var scene = allocate(SceneStub.class);
        scene.sceneId = id;
        scene.entities = new ConcurrentHashMap<>();
        scene.packets = new ArrayList<>();
        scene.removed = new ArrayList<>();
        var block = new SceneBlock();
        block.id = 3001;
        block.min = new Position(-10000, -10000, -10000);
        block.max = new Position(10000, 10000, 10000);
        scene.blocks = Set.of(block);
        scene.world = existingWorld == null ? allocate(WorldStub.class) : existingWorld;
        if (existingWorld == null) scene.world.nextId = 0x100;
        return scene;
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
    }

    private static final class PlayerStub extends Player {
        boolean online = true;
        SceneStub scene;
        Position position = new Position(10, 20, 30);
        Position rotation = new Position();
        PlayerStub(int uid, SceneStub scene) {
            setUid(uid);
            this.scene = scene;
            setSceneLoadState(SceneLoadState.LOADED);
        }
        @Override public boolean isOnline() { return online; }
        @Override public Scene getScene() { return scene; }
        @Override public Position getPosition() { return position; }
        @Override public Position getRotation() { return rotation; }
    }

    private static final class WorldStub extends World {
        int nextId;
        private WorldStub() { super((GameServer) null, null); }
        @Override public int getNextEntityId(EntityIdType type) {
            return (type.getId() << GameConstants.ENTITY_ID_BIT_SHIFT) + nextId++;
        }
    }

    private static final class SceneStub extends Scene {
        int sceneId;
        int added;
        WorldStub world;
        Map<Integer, GameEntity> entities;
        List<BasePacket> packets;
        List<GameEntity> removed;
        Set<SceneBlock> blocks;
        private SceneStub() { super(null, null); }
        @Override public int getId() { return sceneId; }
        @Override public int getSceneTime() { return 1000; }
        @Override public World getWorld() { return world; }
        @Override public Set<SceneBlock> getLoadedBlocks() { return blocks; }
        @Override public Map<Integer, GameEntity> getEntities() { return entities; }
        @Override public GameEntity getEntityById(int id) { return entities.get(id); }
        @Override public void addEntity(GameEntity entity) { entities.put(entity.getId(), entity); added++; }
        @Override public void broadcastPacket(BasePacket packet) { packets.add(packet); }
        @Override public void removeEntity(GameEntity entity, VisionType vision) {
            entities.remove(entity.getId());
            removed.add(entity);
        }
    }
}
