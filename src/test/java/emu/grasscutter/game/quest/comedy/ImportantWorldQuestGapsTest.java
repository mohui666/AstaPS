package emu.grasscutter.game.quest.comedy;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.CodedOutputStream;
import com.mongodb.client.MongoClients;
import dev.morphia.Morphia;
import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.MainQuestData;
import emu.grasscutter.data.excels.*;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.entity.EntityGadget;
import emu.grasscutter.game.inventory.*;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.SceneType;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.check.QuestCheckState;
import emu.grasscutter.game.gcg.story.StoryCardState;
import emu.grasscutter.game.quest.content.ContentServerState;
import emu.grasscutter.game.quest.enums.*;
import emu.grasscutter.game.quest.rename.*;
import emu.grasscutter.game.world.*;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.AddQuestContentProgressReqOuterClass.AddQuestContentProgressReq;
import emu.grasscutter.net.proto.AddQuestContentProgressBatchReq._AddQuestContentProgressBatchReq;
import emu.grasscutter.net.proto.QuestProgressInfo._QuestProgressInfo;
import emu.grasscutter.net.proto.SelectWorktopOptionReqOuterClass.SelectWorktopOptionReq;
import emu.grasscutter.net.proto.QuestRenameMiscRspOuterClass.QuestRenameMiscRsp;
import emu.grasscutter.net.proto.ItemRenameMiscRspOuterClass.ItemRenameMiscRsp;
import emu.grasscutter.net.proto.MiscRenameDataNotifyOuterClass.MiscRenameDataNotify;
import emu.grasscutter.scripts.ScriptLoader;
import emu.grasscutter.server.game.*;
import emu.grasscutter.server.packet.recv.*;
import emu.grasscutter.utils.*;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.bson.*;
import org.bson.codecs.*;
import org.bson.io.BasicOutputBuffer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import sun.misc.Unsafe;

@ExtendWith(ServerResourceFixture.class)
@ResourceLock("GameData")
final class ImportantWorldQuestGapsTest {
    private final Map<Integer,MainQuestData> oldMains=new HashMap<>();
    private final Map<Integer,QuestData> oldQuests=new HashMap<>(),nativeQuests=new HashMap<>();
    private final Map<Integer,ItemData> oldItems=new HashMap<>();
    private final List<SavedPlayer> players=new ArrayList<>();
    private GadgetData oldGadget;
    @BeforeAll static void scripts() throws Exception { ScriptLoader.init(); }
    @BeforeEach void nativeRows() throws Exception {
        for(int parent:List.of(74025,75000)) {
            var root=JsonParser.parseString(Files.readString(FileUtils.getResourcePath("BinOutput/Quest/"+parent+".json"))).getAsJsonObject();
            var subs=new ArrayList<String>();
            for(var value:root.getAsJsonArray("subQuests")) {
                var row=value.getAsJsonObject().deepCopy();int id=row.get("subId").getAsInt();
                if(!Set.of(7402526,7402529,7402534,7402539,7500007,7500008).contains(id))continue;
                for(var name:List.of("acceptCond","finishCond","failCond","beginExec","finishExec","failExec"))if(!row.has(name))row.add(name,new JsonArray());
                var data=JsonUtils.decode(row,QuestData.class);data.onLoad();nativeQuests.put(id,data);oldQuests.put(id,GameData.getQuestDataMap().put(id,data));subs.add("{subId:"+id+"}");
            }
            oldMains.put(parent,GameData.getMainQuestDataMap().put(parent,JsonUtils.decode("{id:"+parent+",subQuests:["+String.join(",",subs)+"]}",MainQuestData.class)));
        }
        var items=JsonParser.parseString(Files.readString(FileUtils.getResourcePath("ExcelBinOutput/MaterialExcelConfigData.json"))).getAsJsonArray();
        for(var value:items)if(Set.of(120400,1302).contains(value.getAsJsonObject().get("id").getAsInt())) {
            var data=JsonUtils.decode(value,ItemData.class);data.onLoad();oldItems.put(data.getId(),GameData.getItemDataMap().put(data.getId(),data));
        }
        oldGadget=GameData.getGadgetDataMap().put(70350081,JsonUtils.decode("{id:70350081,type:'Worktop',isInteractive:true}",GadgetData.class));
    }
    @AfterEach void restore() throws Exception {
        drain();players.forEach(ComedyVoucherRuntime::onLogout);
        oldMains.forEach((id,value)->{if(value==null)GameData.getMainQuestDataMap().remove(id);else GameData.getMainQuestDataMap().put(id,value);});
        oldQuests.forEach((id,value)->{if(value==null)GameData.getQuestDataMap().remove(id);else GameData.getQuestDataMap().put(id,value);});
        oldItems.forEach((id,value)->{if(value==null)GameData.getItemDataMap().remove(id);else GameData.getItemDataMap().put(id,value);});
        if(oldGadget==null)GameData.getGadgetDataMap().remove(70350081);else GameData.getGadgetDataMap().put(70350081,oldGadget);
    }
    private static void drain() throws Exception { QuestManager.eventExecutor.submit(()->{}).get(10,TimeUnit.SECONDS); }
    private SavedPlayer player(int quest) throws Exception {
        var player=new SavedPlayer();players.add(player);var world=TestWorld.create(player);
        player.setScene(new TestWorld.TestScene(world,3,SceneType.SCENE_WORLD));player.setSceneId(3);player.setSceneLoadState(Player.SceneLoadState.LOADED);
        var main=new TrackingMain(player,quest/100);player.main=main;
        for(var data:nativeQuests.values())if(data.getMainId()==quest/100)main.getChildQuests().put(data.getId(),new TrackingQuest(main,data));
        player.getQuestManager().getMainQuests().put(quest/100,main);
        var goal=main.getChildQuestById(quest);goal.setStartTime(1000);goal.setState(QuestState.QUEST_STATE_UNFINISHED);
        if(quest/100==74025)player.getPosition().set(ComedyVoucherCatalog.installed().stages.get(quest).anchor());
        return player;
    }
    private static EntityGadget actor(SavedPlayer player,int index) {
        return (EntityGadget)player.getScene().getEntities().values().stream().filter(entity->entity.getConfigId()==910000+index).findFirst().orElseThrow();
    }
    private static void click(SavedPlayer player,EntityGadget actor) throws Exception {
        player.getPosition().set(actor.getPosition());new HandlerSelectWorktopOptionReq().handle(new Session(player),new byte[0],
                SelectWorktopOptionReq.newBuilder().setGadgetEntityId(actor.getId()).setOptionId(7).build().toByteArray());
    }
    private static void step(SavedPlayer player) throws Exception {
        var progress=player.comedy.get(player.goal().getSubQuestId());click(player,actor(player,progress.getOrder().get(progress.getNextAction())));
    }
    private static void piece(SavedPlayer player) throws Exception { for(int action=0;action<3;action++)step(player); }
    private static GameItem item(int id,int count) { return new UnsavedItem(GameData.getItemDataMap().get(id),count); }

    @Test void fourOriginalObjectivesRequireRealThreeThreeFiveThreeWorkpieces() throws Exception {
        for(int id:List.of(7402526,7402529,7402534,7402539)) {
            var player=player(id);var quest=player.goal();var stage=ComedyVoucherCatalog.installed().stages.get(id);
            assertTrue(quest.getQuestData().getFinishCond().getFirst().getType().isServerStatePredicate());
            assertFalse(ContentServerState.matchesCurrent(quest,quest.getQuestData().getFinishCond().getFirst()));
            ComedyVoucherRuntime.onTick(player);assertEquals(0,player.comedy.get(id).total());
            for(int index=0;index<stage.target()/100;index++) {
                piece(player);drain();assertEquals((index+1)*100,player.comedy.get(id).getWorkpiecePoints());
                assertEquals(index+1==stage.target()/100?1:0,quest.finishes);
            }
            assertEquals(0,player.getInventory().getItemCountById(120400));assertEquals(0,player.comedy.get(id).getEarnedItems());
            ComedyVoucherRuntime.onLogout(player);
        }
    }
    @Test void incorrectRemoteAndReplayedWorktopRequestsDoNotAddPointsAndResumeKeepsActualOrder() throws Exception {
        var player=player(7402526);ComedyVoucherRuntime.onTick(player);var progress=player.comedy.get(7402526);
        int wrong=progress.getOrder().getFirst()%3+1;click(player,actor(player,wrong));assertEquals(0,progress.total());assertEquals(0,progress.getNextAction());
        var first=actor(player,progress.getOrder().getFirst());player.getPosition().addX(100);
        assertTrue(ComedyVoucherRuntime.tryInteract(player,first,7));assertEquals(0,progress.getNextAction());
        click(player,first);assertEquals(1,progress.getNextAction());click(player,first);assertEquals(1,progress.getNextAction());
        var order=List.copyOf(progress.getOrder());ComedyVoucherRuntime.onLogout(player);
        player.comedy=JsonUtils.decode(JsonUtils.encode(player.comedy),ComedyVoucherState.class);
        assertNull(player.main.resumeOnLogin());ComedyVoucherRuntime.onTick(player);progress=player.comedy.get(7402526);
        assertEquals(order,progress.getOrder());assertEquals(1,progress.getNextAction());assertEquals(1000,player.goal().getStartTime());
        step(player);step(player);assertEquals(100,progress.total());assertEquals(0,progress.getNextAction());
    }
    @Test void actualInventoryAcceptedQuantityCountsOnlyDuringCurrentObjectiveAndRejectedStackDoesNotCount() throws Exception {
        var player=player(7402526);player.goal().setState(QuestState.QUEST_STATE_UNSTARTED);
        assertTrue(player.inventory.addItems(List.of(item(120400,20))).allAccepted());assertNull(player.comedy.get(7402526));
        player.goal().setState(QuestState.QUEST_STATE_UNFINISHED);
        assertTrue(player.inventory.addItems(List.of(item(120400,280))).allAccepted());
        assertEquals(280,player.comedy.get(7402526).getEarnedItems());assertEquals(300,player.inventory.getItemCountById(120400));
        assertFalse(player.inventory.addItems(List.of(item(120400,10000))).allAccepted());
        assertEquals(280,player.comedy.get(7402526).getEarnedItems());
        ComedyVoucherRuntime.onTick(player);piece(player);drain();assertEquals(1,player.goal().finishes);
        assertEquals(100,player.comedy.get(7402526).getWorkpiecePoints());assertEquals(300,player.inventory.getItemCountById(120400));
    }
    @Test void forgedClientCountersAndOldPhaseProofCannotFinishAnotherCurrentRound() throws Exception {
        var player=player(7402534);var session=new Session(player);
        new HandlerAddQuestContentProgressReq().handle(session,new byte[0],AddQuestContentProgressReq.newBuilder().setContentType(166).setParam(13504001).build().toByteArray());
        new HandlerAddQuestContentProgressBatchReq().handle(session,new byte[0],_AddQuestContentProgressBatchReq.newBuilder()
                .addProgressInfoList(_QuestProgressInfo.newBuilder().setContentType(166).setParam(13504001)).build().toByteArray());
        player.getQuestManager().triggerEvent(QuestContent.QUEST_CONTENT_EXHIBITION_ACCUMULATE_GT_EQ,"",13504001,999999);drain();assertEquals(0,player.goal().finishes);
        ComedyVoucherRuntime.onTick(player);piece(player);assertEquals(100,player.comedy.get(7402534).total());
        player.goal().setStartTime(1001);ComedyVoucherRuntime.onQuestStart(player.goal());assertNull(player.comedy.get(7402534));
        assertFalse(ContentServerState.matchesCurrent(player.goal(),player.goal().getQuestData().getFinishCond().getFirst()));
    }

    private static byte[] renameRequest(String name,boolean check,int field) throws Exception {
        var inner=new ByteArrayOutputStream();var info=CodedOutputStream.newInstance(inner);info.writeString(4,name);info.writeUInt32(8,2);info.flush();
        var outer=new ByteArrayOutputStream();var wire=CodedOutputStream.newInstance(outer);wire.writeByteArray(field,inner.toByteArray());if(check)wire.writeBool(15,true);wire.flush();return outer.toByteArray();
    }
    @Test void nativeMiscRenameCheckThenConfirmationStoresRealNameAndCompletes179OnlyOnce() throws Exception {
        var player=player(7500007);var session=new Session(player);var condition=player.goal().getQuestData().getFinishCond().getFirst();
        assertEquals(2,QuestMiscRenameCatalog.byId(2).id());assertTrue(condition.getType().isServerStatePredicate());
        new HandlerQuestRenameMiscReq().handle(session,new byte[0],renameRequest("赤团",true,8));
        assertEquals(0,QuestRenameMiscRsp.parseFrom(session.sent.getLast().getData()).getRetcode());assertFalse(player.misc.hasName(2));assertEquals(0,player.saves);
        new HandlerQuestRenameMiscReq().handle(session,new byte[0],renameRequest("赤团",false,8));drain();
        var reply=QuestRenameMiscRsp.parseFrom(session.sent.getLast().getData());assertEquals(26749,session.sent.getLast().getOpcode());
        assertEquals("赤团",reply.getName());assertEquals(2,reply.getRenameId());assertEquals("赤团",player.misc.getName(2));
        assertEquals(1,player.goal().finishes);assertTrue(ContentServerState.matchesCurrent(player.goal(),condition));
        new HandlerQuestRenameMiscReq().handle(session,new byte[0],renameRequest("赤团",false,8));drain();assertEquals(1,player.goal().finishes);assertEquals(1,player.saves);
        var data=MiscRenameDataNotify.parseFrom(player.sent.stream().filter(packet->packet.getOpcode()==7064).findFirst().orElseThrow().getData());
        assertEquals(2,data.getData().getRenameList(0).getRenameId());assertEquals("赤团",data.getData().getRenameList(0).getName());
    }
    @Test void miscRenameRejectsInvalidNameAndNoncurrentQuestAndOriginalItemConfirmationPaysOnce() throws Exception {
        var player=player(7500007);
        for(var name:List.of(" ","bad\nname","<color>赤团</color>"))assertNotEquals(0,QuestMiscRenameService.questRename(player,2,name,false));
        assertNotEquals(0,QuestMiscRenameService.questRename(player,99,"赤团",false));
        player.goal().setState(QuestState.QUEST_STATE_UNSTARTED);assertNotEquals(0,QuestMiscRenameService.questRename(player,2,"赤团",false));
        player.goal().setState(QuestState.QUEST_STATE_UNFINISHED);assertEquals(0,QuestMiscRenameService.questRename(player,2,"赤团",false));drain();
        assertTrue(player.inventory.addItems(List.of(item(1302,1))).allAccepted());
        assertFalse(QuestMiscRenameService.canOpenRenameDialog(player,1302));assertNotEquals(0,QuestMiscRenameService.itemRename(player,2,"火团"));
        player.main.getChildQuestById(7500008).setState(QuestState.QUEST_STATE_FINISHED);
        assertTrue(QuestMiscRenameService.canOpenRenameDialog(player,1302));var session=new Session(player);
        new HandlerItemRenameMiscReq().handle(session,new byte[0],renameRequest("火团",false,12));drain();
        assertEquals(22894,session.sent.getLast().getOpcode());assertEquals(0,ItemRenameMiscRsp.parseFrom(session.sent.getLast().getData()).getRetcode());
        assertEquals("火团",player.misc.getName(2));assertEquals(0,player.inventory.getItemCountById(1302));
        new HandlerItemRenameMiscReq().handle(session,new byte[0],renameRequest("火团",false,12));assertEquals(0,ItemRenameMiscRsp.parseFrom(session.sent.getLast().getData()).getRetcode());
        assertNotEquals(0,QuestMiscRenameService.itemRename(player,2,"新团"));assertEquals("火团",player.misc.getName(2));
    }
    @Test void morphiaActuallyEncodesAndDecodesNewEmbeddedGameplayStatesWithoutADatabaseConnection() throws Exception {
        var player=player(7402526);ComedyVoucherRuntime.onTick(player);step(player);pieceRemaining(player);
        var renamePlayer=player(7500007);assertEquals(0,QuestMiscRenameService.questRename(renamePlayer,2,"赤团",false));
        try(var client=MongoClients.create("mongodb://127.0.0.1:1/?serverSelectionTimeoutMS=1")) {
            var store=Morphia.createDatastore(client,"codec_only");
            store.getMapper().map(ComedyVoucherState.class,ComedyVoucherState.Progress.class,QuestMiscRenameState.class,
                    StoryCardState.class,StoryCardState.Duel.class,QuestCheckState.class,QuestCheckState.Result.class,QuestFreeMoonPhaseState.class,Position.class);
            var codecs=store.getDatabase().getCodecRegistry();
            var codec=codecs.get(ComedyVoucherState.class);
            var restored=bsonRoundTrip(codec,player.comedy);
            assertEquals(100,restored.get(7402526).total());assertEquals(1000,restored.get(7402526).getQuestStart());
            assertEquals(player.comedy.get(7402526).getOrder(),restored.get(7402526).getOrder());
            var nameCodec=codecs.get(QuestMiscRenameState.class);
            assertEquals("赤团",bsonRoundTrip(nameCodec,renamePlayer.misc).getName(2));
            var cards=JsonUtils.decode("{tutorialProgress:1,outcomes:['7066505:1000:30101:1']}",StoryCardState.class);
            var duel=new StoryCardState.Duel();duel.setQuestId(7066505);duel.setActionPoints(3);duel.getHand().addAll(List.of(1,2));duel.setAnchor(new Position(1,2,3));cards.setDuel(duel);
            var cardCodec=codecs.get(StoryCardState.class);
            var savedCards=bsonRoundTrip(cardCodec,cards);assertEquals(3,savedCards.getDuel().getActionPoints());assertEquals(List.of(1,2),savedCards.getDuel().getHand());
            assertEquals(1,savedCards.getTutorialProgress());assertTrue(savedCards.hasOutcome(7066505,1000,30101,1));
            var check=JsonUtils.decode("{results:{1005:{checkId:1005,parentQuestId:19182,dialogueQuestId:1918215,dialogueStartTime:900,dieRoll:20,totalRoll:25,success:true,appliedBonusIds:[100501]}}}",QuestCheckState.class);
            var checkCodec=codecs.get(QuestCheckState.class);
            var savedCheck=bsonRoundTrip(checkCodec,check);
            assertEquals(25,savedCheck.get(1005).getTotalRoll());assertTrue(savedCheck.get(1005).isSuccess());assertEquals(List.of(100501),savedCheck.get(1005).getAppliedBonusIds());
            var moon=JsonUtils.decode("{activatedAt:1800000000,questId:1718304}",QuestFreeMoonPhaseState.class);
            var moonCodec=codecs.get(QuestFreeMoonPhaseState.class);
            assertEquals(1800000000,bsonRoundTrip(moonCodec,moon).getActivatedAt());
        }
    }
    private static void pieceRemaining(SavedPlayer player) throws Exception {step(player);step(player);}
    private static <T> T bsonRoundTrip(Codec<T> codec,T value) {
        try(var output=new BasicOutputBuffer();var writer=new BsonBinaryWriter(output)) {
            codec.encode(writer,value,EncoderContext.builder().build());
            try(var reader=new BsonBinaryReader(ByteBuffer.wrap(output.toByteArray()))) {
                return codec.decode(reader,DecoderContext.builder().build());
            }
        }
    }

    private static final class TrackingMain extends GameMainQuest { TrackingMain(Player player,int parent){super(player,parent);}@Override public void save(){}@Override public void checkProgress(){} }
    private static final class TrackingQuest extends GameQuest {
        int finishes;TrackingQuest(GameMainQuest main,QuestData data){super(main,data);}@Override public void finish(){finishes++;setState(QuestState.QUEST_STATE_FINISHED);}@Override public void save(){}
    }
    private static final class UnsavedItem extends GameItem {UnsavedItem(ItemData data,int count){super(data,count);}@Override public void save(){} }
    private static final class SavedPlayer extends TestWorld.TestPlayer {
        ComedyVoucherState comedy=new ComedyVoucherState();QuestMiscRenameState misc=new QuestMiscRenameState();
        final Inventory inventory=new Inventory(this);final List<BasePacket> sent=new ArrayList<>();final GameServer server;TrackingMain main;int saves;
        SavedPlayer() throws Exception {Field field=Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);server=(GameServer)((Unsafe)field.get(null)).allocateInstance(GameServer.class);var system=GameServer.class.getDeclaredField("questSystem");system.setAccessible(true);system.set(server,new QuestSystem(server));}
        TrackingQuest goal(){return (TrackingQuest)main.getChildQuests().values().stream().filter(quest->quest.getStartTime()>0).findFirst().orElseThrow();}
        @Override public GameServer getServer(){return server;}@Override public Inventory getInventory(){return inventory;}
        @Override public ComedyVoucherState getComedyVouchers(){return comedy;}@Override public QuestMiscRenameState getQuestMiscRenames(){return misc;}
        @Override public void save(){saves++;}@Override public void sendPacket(BasePacket packet){sent.add(packet);}
    }
    private static final class Session extends GameSession {final Player player;final List<BasePacket> sent=new ArrayList<>();Session(Player player){super(null);this.player=player;}@Override public Player getPlayer(){return player;}@Override public void send(BasePacket packet){sent.add(packet);} }
}
