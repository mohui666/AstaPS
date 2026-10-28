package emu.grasscutter.game.quest.world;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.CodedInputStream;
import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.MainQuestData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.data.excels.scene.SceneData;
import emu.grasscutter.game.CoopRequest;
import emu.grasscutter.game.player.*;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.*;
import emu.grasscutter.game.systems.TestPlayers;
import emu.grasscutter.game.world.TestWorld;
import emu.grasscutter.game.world.World;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.server.game.*;
import emu.grasscutter.server.packet.send.*;
import emu.grasscutter.scripts.ScriptLoader;
import emu.grasscutter.utils.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

@ExtendWith(ServerResourceFixture.class)
@ResourceLock("GameData")
final class QuestSceneMechanicsTest {
    private static final int REGION=31001001;
    private final List<Runnable> cleanup=new ArrayList<>();
    private GameServer server;
    private QuestSystem system;

    @BeforeAll static void scripts() throws Exception { ScriptLoader.init(); }

    @BeforeEach void setup() throws Exception {
        server=TestPlayers.server(); system=new QuestSystem(server);
        var field=GameServer.class.getDeclaredField("questSystem"); field.setAccessible(true); field.set(server,system);
        var rows=JsonParser.parseString(Files.readString(FileUtils.getResourcePath(
                "ExcelBinOutput/SceneExcelConfigData.json"))).getAsJsonArray();
        for (var row:rows) {
            int id=row.getAsJsonObject().get("id").getAsInt();
            if (id==3||id==103) install(GameData.getSceneDataMap(),id,JsonUtils.decode(row,SceneData.class));
        }
    }

    @AfterEach void restore() throws Exception {
        drain(); Collections.reverse(cleanup); cleanup.forEach(Runnable::run);
    }

    @Test void nativeFinalStepUnlocksOnlyTheConfiguredSceneAndTheRealMpApplicationUsesIt() throws Exception {
        var host=player(5111,103); var guest=player(5112,3);
        assertEquals("SCENE_MP_DEFAULT_SINGLE_CAN_UNLOCK_MP",GameData.getSceneDataMap().get(103).getMpType());
        assertTrue(QuestSceneMechanicsRuntime.isMultiplayerBlocked(host));
        server.getMultiplayerSystem().applyEnterMp(guest,host.getUid());
        assertTrue(host.getCoopRequests().isEmpty());
        assertFalse(QuestSceneMechanicsRuntime.unlockMultiplayer(host,3));
        assertFalse(QuestSceneMechanicsRuntime.unlockMultiplayer(host,999999));
        var quest=quest(host,7609623);
        execute(quest,QuestExec.QUEST_EXEC_UNLOCK_SCENE_MP);
        assertEquals(Set.of(103),host.getQuestSceneMechanics().unlockedSceneMps());
        assertFalse(QuestSceneMechanicsRuntime.isMultiplayerBlocked(host));
        var packed=fieldBytes(last(host,9135).getData(),3);
        assertEquals(103,CodedInputStream.newInstance(packed).readUInt32());
        int saves=host.saves;
        execute(quest,QuestExec.QUEST_EXEC_UNLOCK_SCENE_MP);
        assertEquals(saves,host.saves);
        server.getMultiplayerSystem().applyEnterMp(guest,host.getUid());
        assertSame(guest,host.getCoopRequests().get(guest.getUid()).getRequester());
        assertTrue(host.packets.stream().anyMatch(p->p.getOpcode()==PacketOpcodes.PlayerApplyEnterMpNotify));
        assertFalse(QuestSceneMechanicsRuntime.isMultiplayerBlocked(guest));
    }

    @Test void mpReplyRechecksTheCurrentNativeSceneLockAndOrdinaryWorldsRemainAdmissible() throws Exception {
        var host=player(5121,3); var guest=player(5122,3);
        server.getMultiplayerSystem().applyEnterMp(guest,host.getUid());
        assertTrue(host.getCoopRequests().containsKey(guest.getUid()));
        host.setSceneId(103);
        server.getMultiplayerSystem().applyEnterMpReply(host,guest.getUid(),true);
        assertTrue(host.getCoopRequests().isEmpty());
        assertFalse(guest.isInMultiplayer());
        assertSame(guest,guest.getWorld().getHost());
        host.setSceneId(3);
        server.getMultiplayerSystem().applyEnterMp(guest,host.getUid());
        assertTrue(host.getCoopRequests().containsKey(guest.getUid()));
    }

    @Test void nativePalaceRegionExecChangesTheActualClientPolygonSnapshotAndRetainsCreatorOwnership() throws Exception {
        var host=player(5131,3); var other=player(5132,3);
        var nativeShape=JsonParser.parseString(Files.readString(FileUtils.getResourcePath(
                "BinOutput/LevelDesign/SimplePolygons/BigWorld_GraffitiIsland_OpenArea_001.json"))).getAsJsonObject();
        assertTrue(nativeShape.getAsJsonArray("BFLLDPOAJGD").size()>3);
        assertEquals(List.of(REGION),regionIds(new PacketSceneDataNotify(host).getData()));
        execute(quest(host,1504301),QuestExec.QUEST_EXEC_SET_LIMIT_REGION_STATE);
        var notify=last(host,29381).getData();
        assertTrue(fields(notify).contains(9),"an explicit empty native region message clears the prior barrier");
        assertEquals(List.of(),regionIds(notify));
        assertEquals(List.of(REGION),regionIds(new PacketSceneDataNotify(other).getData()));
        assertFalse(QuestSceneMechanicsRuntime.setLimitRegion(host,31001002,0));
        assertFalse(QuestSceneMechanicsRuntime.setLimitRegion(host,REGION,2));
        String persisted=JsonUtils.encode(host.getQuestSceneMechanics());
        var relogged=player(5131,3); installState(relogged,persisted);
        assertEquals(List.of(),regionIds(new PacketSceneDataNotify(relogged).getData()));
        other.setWorld(relogged.getWorld()); relogged.getWorld().getPlayers().add(other);
        assertEquals(List.of(),regionIds(new PacketSceneDataNotify(other).getData()),"creator share type uses this world's host");
        assertTrue(other.getQuestSceneMechanics().regionEnabled(REGION,true));
    }

    @Test void realCompletedFinalStepsRecoverTheirEffectsWithoutGrantingOtherScenesOrRegions() throws Exception {
        var owner=player(5141,103);
        quest(owner,7609623).setState(QuestState.QUEST_STATE_FINISHED);
        quest(owner,1504301).setState(QuestState.QUEST_STATE_FINISHED);
        assertTrue(owner.getQuestSceneMechanics().unlockedSceneMps().isEmpty());
        assertFalse(QuestSceneMechanicsRuntime.isMultiplayerBlocked(owner));
        assertEquals(List.of(),QuestSceneMechanicsRuntime.limitRegions(owner,3).getLimitedRegionListList());
        QuestSceneMechanicsRuntime.notifySceneEntry(owner);
        assertEquals(103,CodedInputStream.newInstance(fieldBytes(last(owner,9135).getData(),3)).readUInt32());
        assertTrue(QuestSceneMechanicsRuntime.limitRegions(owner,103).getLimitedRegionListList().isEmpty());
    }

    @Test void sceneVariablePacketsCarryNativeFloatAndSignedHashTagsAndSnapshotsRetainZeroOverrides() throws Exception {
        var owner=player(5151,3); int hash=-1234567;
        assertFalse(QuestSceneMechanicsRuntime.setSceneVariable(owner,999999,hash,0));
        assertFalse(QuestSceneMechanicsRuntime.setSceneVariable(owner,3,hash,Float.NaN));
        assertTrue(QuestSceneMechanicsRuntime.setSceneVariable(owner,3,hash,-1.25f));
        var update=last(owner,29407).getData();
        assertEquals(3,varint(update,8));
        var entry=fieldBytes(update,12);
        assertEquals(hash,(int)varint(entry,9));
        assertEquals(-1.25f,float32(entry,6));
        assertTrue(QuestSceneMechanicsRuntime.setSceneVariable(owner,3,hash,0));
        var relogged=player(5151,3); installState(relogged,JsonUtils.encode(owner.getQuestSceneMechanics()));
        QuestSceneMechanicsRuntime.notifySceneEntry(relogged);
        var snapshot=last(relogged,1292).getData();
        assertEquals(3,varint(snapshot,12));
        var saved=fieldBytes(snapshot,9);
        assertEquals(hash,(int)varint(saved,9));
        assertEquals(0,float32(saved,6));
    }

    private StoryPlayer player(int uid,int sceneId) {
        var player=new StoryPlayer(); player.setUid(uid); player.setSession(new GameSession(server));
        player.setSceneId(sceneId); player.setSceneLoadState(Player.SceneLoadState.LOADED);
        server.getPlayers().put(uid,player);
        var world=new World(player,false); world.getPlayers().add(player); player.setWorld(world);
        return player;
    }

    private GameQuest quest(StoryPlayer player,int id) throws Exception {
        var parent=JsonParser.parseString(Files.readString(FileUtils.getResourcePath("BinOutput/Quest/"+id/100+".json"))).getAsJsonObject();
        var row=java.util.stream.StreamSupport.stream(parent.getAsJsonArray("subQuests").spliterator(),false)
                .map(JsonElement::getAsJsonObject).filter(q->q.get("subId").getAsInt()==id).findFirst().orElseThrow().deepCopy();
        row.addProperty("mainId",id/100);
        if (!row.has("failCond")) row.add("failCond",new JsonArray());
        var data=JsonUtils.decode(row,QuestData.class);
        install(GameData.getQuestDataMap(),id,data);
        install(GameData.getMainQuestDataMap(),id/100,JsonUtils.decode("{id:"+id/100+",subQuests:[]}",MainQuestData.class));
        var main=new GameMainQuest(player,id/100) { @Override public void save(){} };
        var quest=new GameQuest(main,data); quest.setState(QuestState.QUEST_STATE_UNFINISHED);
        main.getChildQuests().put(id,quest); player.getQuestManager().getMainQuests().put(id/100,main);
        return quest;
    }

    private void execute(GameQuest quest,QuestExec type) throws Exception {
        var exec=quest.getQuestData().getFinishExec().stream().filter(e->e.getType()==type).findFirst().orElseThrow();
        system.triggerExec(quest,exec,exec.getParam()); drain();
    }
    private static void drain() throws Exception { QuestManager.eventExecutor.submit(()->{}).get(10,TimeUnit.SECONDS); }
    private <K,V> void install(Map<K,V> map,K key,V value) {
        var old=map.put(key,value); cleanup.add(()->{if(old==null)map.remove(key);else map.put(key,old);});
    }
    private static void installState(Player player,String json) throws Exception {
        var field=Player.class.getDeclaredField("questSceneMechanics"); field.setAccessible(true);
        field.set(player,JsonUtils.decode(json,QuestSceneMechanicsState.class));
    }
    private static BasePacket last(StoryPlayer player,int opcode) {
        return player.packets.reversed().stream().filter(p->p.getOpcode()==opcode).findFirst().orElseThrow();
    }
    private static List<Integer> regionIds(byte[] notify) throws Exception {
        var info=fieldBytes(notify,9); var result=new ArrayList<Integer>(); var in=CodedInputStream.newInstance(info);
        for (int tag;(tag=in.readTag())!=0;) {
            if (tag==90) { var packed=CodedInputStream.newInstance(in.readByteArray()); while (!packed.isAtEnd()) result.add(packed.readUInt32()); }
            else if (tag==88) result.add(in.readUInt32()); else in.skipField(tag);
        }
        return result;
    }
    private static Set<Integer> fields(byte[] data) throws Exception {
        var result=new HashSet<Integer>(); var in=CodedInputStream.newInstance(data);
        for (int tag;(tag=in.readTag())!=0;) { result.add(tag>>>3); in.skipField(tag); } return result;
    }
    private static byte[] fieldBytes(byte[] data,int id) throws Exception {
        var in=CodedInputStream.newInstance(data);
        for (int tag;(tag=in.readTag())!=0;) { if (tag==(id<<3|2)) return in.readByteArray(); in.skipField(tag); }
        throw new AssertionError("missing native field "+id);
    }
    private static long varint(byte[] data,int id) throws Exception {
        var in=CodedInputStream.newInstance(data);
        for (int tag;(tag=in.readTag())!=0;) { if (tag==id<<3) return in.readUInt64(); in.skipField(tag); } return 0;
    }
    private static float float32(byte[] data,int id) throws Exception {
        var in=CodedInputStream.newInstance(data);
        for (int tag;(tag=in.readTag())!=0;) { if (tag==(id<<3|5)) return in.readFloat(); in.skipField(tag); } return 0;
    }
    private static final class StoryPlayer extends TestWorld.TestPlayer {
        final List<BasePacket> packets=new ArrayList<>();
        final TeamManager team=new TeamManager(this);
        int saves;
        @Override public void save(){saves++;}
        @Override public void sendPacket(BasePacket packet){packets.add(packet);}
        @Override public TeamManager getTeamManager(){return team;}
        @Override public String getNickname(){return "story"+getUid();}
        @Override public String getSignature(){return "";}
        @Override public boolean isOnline(){return true;}
    }
}
