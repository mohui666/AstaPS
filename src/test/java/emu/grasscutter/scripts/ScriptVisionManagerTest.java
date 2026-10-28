package emu.grasscutter.scripts;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.SceneType;
import emu.grasscutter.game.world.*;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.GadgetInteractReqOuterClass.GadgetInteractReq;
import emu.grasscutter.net.proto.SceneEntityAppearNotifyOuterClass.SceneEntityAppearNotify;
import emu.grasscutter.net.proto.SceneEntityInfoOuterClass.SceneEntityInfo;
import emu.grasscutter.net.proto.GroupSuiteNotifyOuterClass.GroupSuiteNotify;
import emu.grasscutter.net.proto.GroupUnloadNotifyOuterClass.GroupUnloadNotify;
import emu.grasscutter.net.proto.VisionTypeOuterClass.VisionType;
import emu.grasscutter.scripts.constants.ScriptRegionShape;
import emu.grasscutter.scripts.data.*;
import emu.grasscutter.server.packet.send.PacketSceneEntityAppearNotify;
import emu.grasscutter.server.packet.send.PacketGroupSuiteNotify;
import it.unimi.dsi.fastutil.ints.*;
import java.util.*;
import org.junit.jupiter.api.*;

final class ScriptVisionManagerTest {
    private boolean scripts;
    private Scene scene;
    private TestWorld.TestPlayer owner;
    private TestWorld.TestPlayer guest;
    private TestEntity common;
    private TestEntity normal;
    private TestEntity dream;
    private TestEntity background;
    private ScriptLib lib;

    @BeforeEach
    void prepare() throws Exception {
        ScriptLoader.init();
        scripts = Grasscutter.getConfig().server.game.enableScriptInBigWorld;
        Grasscutter.getConfig().server.game.enableScriptInBigWorld = false;
        owner = new VisionPlayer();
        owner.setUid(101);
        guest = new VisionPlayer();
        guest.setUid(102);
        var world = TestWorld.create(owner);
        var groups = new HashMap<Integer,SceneGroupInstance>();
        scene = new TestWorld.TestScene(world,3,SceneType.SCENE_WORLD) {
            SceneScriptManager manager;
            @Override public SceneScriptManager getScriptManager() {
                if (manager == null) manager = new SceneScriptManager(this) {
                    @Override public SceneGroupInstance getGroupInstanceById(int id) { return groups.get(id); }
                    @Override public Map<Integer,SceneGroupInstance> getCachedGroupInstances() { return groups; }
                };
                return manager;
            }
        };
        for (var player : List.of(owner,guest)) {
            player.setWorld(world);
            player.setScene(scene);
            player.setSceneLoadState(Player.SceneLoadState.LOADED);
            player.getPosition().set(0,0,0);
            scene.getPlayers().add(player);
            player.getTeamManager().setEntity(new EntityTeam(player));
        }
        int id=100;
        for (int type : List.of(0,1,33010004,33010022)) {
            var group = SceneGroup.of(++id);
            group.vision_type=type;
            var instance = new SceneGroupInstance(group,owner);
            instance.setActiveSuiteId(1);
            groups.put(id,instance);
        }
        common = new TestEntity(scene,101);
        normal = new TestEntity(scene,102);
        dream = new TestEntity(scene,103);
        background = new TestEntity(scene,104);
        for (var entity : List.of(common,normal,dream,background)) scene.getEntities().put(entity.getId(),entity);
        lib = ScriptLoader.getScriptLib();
        lib.setSceneScriptManager(scene.getScriptManager());
        lib.setCurrentGroup(SceneGroup.of(133301077));
    }

    @AfterEach
    void restore() {
        lib.removeSceneScriptManager();
        lib.removeCurrentGroup();
        Grasscutter.getConfig().server.game.enableScriptInBigWorld = scripts;
    }

    @Test
    void forcedDreamlandVisionHidesAndBlocksInteractionWithoutUnloadingTheGuestsWorld() {
        assertFalse(scene.getScriptVision().canSee(owner,background));
        assertFalse(scene.getScriptVision().canSee(guest,background));
        assertEquals(0,lib.AddPlayerGroupVisionType(new int[]{102},new int[]{33010022}));
        assertEquals(0,lib.SetPlayerGroupVisionType(new int[]{101},new int[]{33010004}));
        var vision = scene.getScriptVision();
        assertTrue(vision.canSee(owner,common));
        assertTrue(vision.canSee(owner,dream));
        assertFalse(vision.canSee(owner,normal));
        assertFalse(vision.canSee(owner,background));
        assertTrue(vision.canSee(guest,background));
        owner.interactWith(background.getId(),GadgetInteractReq.getDefaultInstance());
        assertEquals(0,background.interactions);
        assertEquals(4,scene.getEntities().size());
        assertEquals(0,lib.SetPlayerGroupVisionType(new int[]{101},new int[]{1}));
        owner.interactWith(normal.getId(),GadgetInteractReq.getDefaultInstance());
        assertEquals(1,normal.interactions);
        assertEquals(-1,lib.SetPlayerGroupVisionType(new int[]{101,999},new int[]{33010004}));
        assertTrue(vision.canSee(owner,normal));
    }

    @Test
    void regionVisionCombinesWithManualVisionAndForbidAndRevertReallyChangeIt() {
        var meta = new SceneRegion();
        meta.config_id=77001;
        meta.group=SceneGroup.of(133301077);
        meta.pos=new Position();
        meta.radius=5;
        meta.shape=ScriptRegionShape.SPHERE;
        meta.vision_type_list=List.of(33010004);
        var region = new EntityRegion(scene,meta);
        scene.getScriptVision().updateRegions(List.of(region));
        assertTrue(scene.getScriptVision().canSee(owner,dream));
        assertEquals(0,lib.ForbidPlayerRegionVision(101));
        assertFalse(scene.getScriptVision().canSee(owner,dream));
        assertEquals(0,lib.AddPlayerGroupVisionType(new int[]{101},new int[]{33010022}));
        assertTrue(scene.getScriptVision().canSee(owner,background));
        assertEquals(0,lib.RevertPlayerRegionVision(101));
        assertTrue(scene.getScriptVision().canSee(owner,dream));
        owner.getPosition().set(50,0,0);
        scene.getScriptVision().updateRegions(List.of(region));
        assertFalse(scene.getScriptVision().canSee(owner,dream));
        assertTrue(scene.getScriptVision().canSee(owner,background));
        assertEquals(0,lib.DelPlayerGroupVisionType(new int[]{101},new int[]{33010022}));
        assertFalse(scene.getScriptVision().canSee(owner,background));
    }

    @Test
    void nativeRegionMutexDoesNotExcludeAnExplicitManualVisionType() {
        var meta = new SceneRegion();
        meta.config_id=77002;
        meta.group=SceneGroup.of(133301077);
        meta.pos=new Position();
        meta.radius=5;
        meta.shape=ScriptRegionShape.SPHERE;
        meta.vision_type_list=List.of(33010026,33010020);
        scene.getScriptVision().updateRegions(List.of(new EntityRegion(scene,meta)));
        assertTrue(scene.getScriptVision().canSeeType(owner,33010026));
        assertFalse(scene.getScriptVision().canSeeType(owner,33010020));
        assertEquals(0,lib.AddPlayerGroupVisionType(new int[]{101},new int[]{33010020}));
        assertTrue(scene.getScriptVision().canSeeType(owner,33010020));
    }

    @Test
    void npcGroupNotificationsUseEachPlayersVisionAndRestoreTheirActualSuite() throws Exception {
        var vision = scene.getScriptVision();
        assertNull(vision.filterPacket(owner,new PacketGroupSuiteNotify(104,1)));
        assertEquals(0,lib.SetPlayerGroupVisionType(new int[]{101},new int[]{33010004}));
        var packets = ((VisionPlayer)owner).packets;
        var unload = packets.stream().filter(packet -> packet.getOpcode() == emu.grasscutter.net.packet.PacketOpcodes.GroupUnloadNotify)
                .findFirst().orElseThrow();
        assertEquals(List.of(102),GroupUnloadNotify.parseFrom(unload.getData()).getGroupListList());
        var appear = packets.stream().filter(packet -> packet.getOpcode() == emu.grasscutter.net.packet.PacketOpcodes.GroupSuiteNotify)
                .findFirst().orElseThrow();
        assertEquals(Map.of(103,1),GroupSuiteNotify.parseFrom(appear.getData()).getGroupMapMap());
        assertNull(vision.filterPacket(guest,appear));
        assertSame(appear,vision.filterPacket(owner,appear));
    }

    @Test
    void laterSpawnNotificationsAreFilteredAndTransferInProgressIsNotTransmittable() throws Exception {
        lib.SetPlayerGroupVisionType(new int[]{101},new int[]{33010004});
        var packet = new PacketSceneEntityAppearNotify(List.of(common,dream,background),VisionType.VisionType_VISION_BORN);
        var filtered = scene.getScriptVision().filterPacket(owner,packet);
        assertEquals(Set.of(common.getId(),dream.getId()),SceneEntityAppearNotify.parseFrom(filtered.getData())
                .getEntityListList().stream().map(SceneEntityInfo::getEntityId).collect(java.util.stream.Collectors.toSet()));
        lib.AddPlayerGroupVisionType(new int[]{102},new int[]{33010004,33010022});
        assertSame(packet,scene.getScriptVision().filterPacket(guest,packet));
        assertNull(scene.getScriptVision().filterPacket(owner,new PacketSceneEntityAppearNotify(background)));
        assertTrue(lib.IsPlayerTransmittable(101));
        owner.setSceneLoadState(Player.SceneLoadState.LOADING);
        assertFalse(lib.IsPlayerTransmittable(101));
        assertFalse(lib.IsPlayerTransmittable(999));
    }

    private static final class VisionPlayer extends TestWorld.TestPlayer {
        private final emu.grasscutter.game.player.TeamManager team = new emu.grasscutter.game.player.TeamManager(this);
        private final List<BasePacket> packets = new ArrayList<>();
        @Override public emu.grasscutter.game.player.TeamManager getTeamManager() { return team; }
        @Override public void sendPacket(BasePacket packet) { packets.add(packet); }
    }

    private static final class TestEntity extends GameEntity {
        int interactions;
        TestEntity(Scene scene,int group) { super(scene); setGroupId(group); setId(100000+group); }
        @Override public void initAbilities() {}
        @Override public int getEntityTypeId() { return 1; }
        @Override public Int2FloatMap getFightProperties() { return new Int2FloatArrayMap(); }
        @Override public Position getPosition() { return new Position(); }
        @Override public Position getRotation() { return new Position(); }
        @Override public SceneEntityInfo toProto() { return SceneEntityInfo.newBuilder().setEntityId(getId()).build(); }
        @Override public void onInteract(Player player,GadgetInteractReq req) { interactions++; }
    }
}
