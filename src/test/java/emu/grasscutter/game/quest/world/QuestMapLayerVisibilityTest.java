package emu.grasscutter.game.quest.world;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.CodedInputStream;
import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.scene.*;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.SceneType;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.QuestValueExec;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.exec.ExecSetMapLayerUnlockState;
import emu.grasscutter.game.quest.exec.ExecShowMapLayerGroup;
import emu.grasscutter.game.world.TestWorld;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.PlayerWorldSceneInfoListNotifyOuterClass.PlayerWorldSceneInfoListNotify;
import emu.grasscutter.server.packet.send.PacketPlayerWorldSceneInfoListNotify;
import emu.grasscutter.utils.JsonUtils;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

@ExtendWith(ServerResourceFixture.class)
@ResourceLock("GameData")
final class QuestMapLayerVisibilityTest {
    private final Map<Integer,MapLayerGroupData> groups = new HashMap<>(GameData.getMapLayerGroupDataMap());
    private final Map<Integer,MapLayerData> layers = new HashMap<>(GameData.getMapLayerDataMap());
    private final Map<Integer,SceneData> scenes = new HashMap<>(GameData.getSceneDataMap());

    private static final class MapPlayer extends TestWorld.TestPlayer {
        final QuestSceneOverrides overrides;
        final List<BasePacket> sent = new ArrayList<>();
        int saves;
        MapPlayer() { this(new QuestSceneOverrides()); }
        MapPlayer(QuestSceneOverrides overrides) { this.overrides = overrides; }
        @Override public QuestSceneOverrides getQuestSceneOverrides() { return overrides; }
        @Override public void save() { saves++; }
        @Override public void sendPacket(BasePacket packet) { sent.add(packet); }
    }

    @BeforeEach
    void resources() {
        for (int id : List.of(33104,33001))
            GameData.getMapLayerGroupDataMap().put(id,JsonUtils.decode("{id:"+id+"}",MapLayerGroupData.class));
        for (int id : List.of(3310401,3300101,3300102))
            GameData.getMapLayerDataMap().put(id,JsonUtils.decode("{id:"+id+"}",MapLayerData.class));
        GameData.getSceneDataMap().put(3,TestWorld.sceneData(3,SceneType.SCENE_WORLD));
    }

    @AfterEach
    void restore() {
        GameData.getMapLayerGroupDataMap().clear();
        GameData.getMapLayerGroupDataMap().putAll(groups);
        GameData.getMapLayerDataMap().clear();
        GameData.getMapLayerDataMap().putAll(layers);
        GameData.getSceneDataMap().clear();
        GameData.getSceneDataMap().putAll(scenes);
    }

    private static GameQuest quest(Player owner) {
        return new GameQuest() { @Override public Player getOwner() { return owner; } };
    }

    @Test
    void theTwoNativeQuestGroupsAreRegisteredAndShowingDoesNotUnlockTheirLayers() {
        assertEquals(QuestExec.QUEST_EXEC_SHOW_MAP_LAYER_GROUP,
                ExecShowMapLayerGroup.class.getAnnotation(QuestValueExec.class).value());
        var owner = new MapPlayer();
        var other = new MapPlayer();
        var show = new ExecShowMapLayerGroup();
        var unlock = new ExecSetMapLayerUnlockState();
        // 7323205 and 7325115 use group IDs, then separately invoke action 84 for child layers.
        assertTrue(show.execute(quest(owner),null,"33104"));
        assertEquals(Set.of(33104),Set.copyOf(owner.overrides.shownMapLayerGroups(List.of())));
        assertTrue(owner.overrides.unlockedMapLayers(List.of()).isEmpty());
        assertTrue(unlock.execute(quest(owner),null,"3310401","0"));
        assertTrue(owner.overrides.unlockedMapLayers(List.of(3310401)).isEmpty());
        assertTrue(show.execute(quest(owner),null,"33001"));
        assertTrue(unlock.execute(quest(owner),null,"3300101;3300102","1"));
        assertEquals(Set.of(33104,33001),Set.copyOf(owner.overrides.shownMapLayerGroups(List.of())));
        assertEquals(Set.of(3300101,3300102),Set.copyOf(owner.overrides.unlockedMapLayers(List.of())));
        assertTrue(other.overrides.shownMapLayerGroups(List.of()).isEmpty());
        assertTrue(other.overrides.unlockedMapLayers(List.of()).isEmpty());
        assertEquals(0,other.saves);
        assertTrue(other.sent.isEmpty());
        assertTrue(show.execute(quest(owner),null,"33104"));
        assertEquals(4,owner.saves,"a repeated show does not write the same permanent state again");
    }

    @Test
    void persistedVisibilityAndLocksAreRestoredIndependentlyAndOldStateCanShowAGroup() throws Exception {
        var state = new QuestSceneOverrides();
        state.showMapLayerGroup(33104);
        state.setMapLayerUnlocks(List.of(3310401),false);
        var restored = JsonUtils.decode(JsonUtils.encode(state),QuestSceneOverrides.class);
        assertEquals(List.of(33104),restored.shownMapLayerGroups(List.of()));
        assertTrue(restored.unlockedMapLayers(List.of(3310401)).isEmpty());
        var old = JsonUtils.decode("{mapLayerUnlocks:{3310401:false},mapLayerShownGroups:null}",QuestSceneOverrides.class);
        assertTrue(old.showMapLayerGroup(33001));
        assertEquals(List.of(33001),old.shownMapLayerGroups(List.of()));
        assertTrue(old.unlockedMapLayers(List.of(3310401)).isEmpty());
        var owner = new MapPlayer(restored);
        var snapshot = PlayerWorldSceneInfoListNotify.parseFrom(
                new PacketPlayerWorldSceneInfoListNotify(owner).getData());
        var map = snapshot.getInfoListList().stream().filter(info -> info.getSceneId()==3)
                .findFirst().orElseThrow().getMapLayerInfo();
        assertTrue(map.getUnlockMapLayerGroupListList().contains(33104));
        assertFalse(map.getUnlockMapLayerListList().contains(3310401));
        assertEquals(0,owner.saves);
    }

    @Test
    void actualSevenOneWireSeparatesShownGroupsAndUnlockedLayersAndUsesTheCurrentSceneEnvelope() throws Exception {
        var owner = new MapPlayer();
        owner.getSceneTags().put(3,new HashSet<>(Set.of(3077)));
        assertTrue(new ExecShowMapLayerGroup().execute(quest(owner),null,"33104"));
        assertEquals(21302,owner.sent.getLast().getOpcode());
        assertTrue(new ExecSetMapLayerUnlockState().execute(quest(owner),null,"3310401","0"));
        var outer = CodedInputStream.newInstance(owner.sent.getLast().getData());
        byte[] map = null;
        List<Integer> tags = null;
        Set<Integer> sceneFields = null;
        while (!outer.isAtEnd()) {
            int tag = outer.readTag();
            if (tag != 42) { outer.skipField(tag); continue; } // Native AGFDHNAFLBL info_list=5.
            var info = CodedInputStream.newInstance(outer.readByteArray());
            int scene = 0;
            byte[] candidate = null;
            var candidateTags = new ArrayList<Integer>();
            var fields = new HashSet<Integer>();
            while (!info.isAtEnd()) {
                int field = info.readTag();
                fields.add(field >>> 3);
                if (field == 104) scene = info.readUInt32(); // FKCDBJPOIJI scene_id=13.
                else if (field == 114) candidate = info.readByteArray(); // map_layer_info=14.
                else if ((field >>> 3) == 4) readUInts(info,field,candidateTags);
                else info.skipField(field);
            }
            if (scene == 3) { map=candidate; tags=candidateTags; sceneFields=fields; }
        }
        assertNotNull(map);
        assertEquals(List.of(3077),tags);
        assertEquals(Set.of(4,13,14),sceneFields);
        var encoded = CodedInputStream.newInstance(map);
        var lists = new HashMap<Integer,List<Integer>>();
        while (!encoded.isAtEnd()) {
            int field = encoded.readTag();
            readUInts(encoded,field,lists.computeIfAbsent(field >>> 3,ignored -> new ArrayList<>()));
        }
        // IOOBEFIGOOB parser 99F4DF0 and LJAJLLGANBO consumer 9C67630:
        // field 2 indexes individual layers; field 7 indexes their group models.
        assertEquals(Set.of(2,7),lists.keySet());
        assertTrue(lists.get(7).contains(33104));
        assertFalse(lists.get(7).contains(3310401));
        assertTrue(lists.get(2).contains(3300101));
        assertFalse(lists.get(2).contains(33104));
        assertFalse(lists.get(2).contains(3310401));
    }

    private static void readUInts(CodedInputStream stream, int tag, List<Integer> values) throws Exception {
        if ((tag & 7) == 0) values.add(stream.readUInt32());
        else {
            assertEquals(2,tag & 7);
            int previous = stream.pushLimit(stream.readRawVarint32());
            while (stream.getBytesUntilLimit()>0) values.add(stream.readUInt32());
            stream.popLimit(previous);
        }
    }

    @Test
    void invalidGroupParametersCannotMutateOrNotifyAndLayerIdsAreRejectedAsGroupIds() {
        var owner = new MapPlayer();
        var handler = new ExecShowMapLayerGroup();
        for (String value : List.of("0","-1","3310401","99999999","33104;33001","bad"))
            assertFalse(handler.execute(quest(owner),null,value));
        assertFalse(handler.execute(quest(owner),null));
        assertFalse(handler.execute(quest(owner),null,"33104","1"));
        assertFalse(handler.execute(quest(owner),null,new String[] {null}));
        assertEquals(0,owner.saves);
        assertTrue(owner.sent.isEmpty());
        assertTrue(owner.overrides.shownMapLayerGroups(List.of()).isEmpty());
        assertTrue(owner.overrides.unlockedMapLayers(List.of()).isEmpty());
    }
}
