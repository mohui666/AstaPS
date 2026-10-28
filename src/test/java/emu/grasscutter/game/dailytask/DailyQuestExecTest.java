package emu.grasscutter.game.dailytask;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.google.protobuf.CodedInputStream;
import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.MainQuestData;
import emu.grasscutter.data.excels.*;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.exec.*;
import emu.grasscutter.game.quest.enums.*;
import emu.grasscutter.game.systems.TestPlayers;
import emu.grasscutter.game.world.TestWorld;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.server.game.*;
import emu.grasscutter.utils.*;
import java.nio.file.Files;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

@ExtendWith(ServerResourceFixture.class) @ResourceLock("GameData")
final class DailyQuestExecTest {
    private final List<Runnable> cleanup=new ArrayList<>();
    private final Map<Integer,JsonObject> original=new HashMap<>();
    private GameServer server;
    @BeforeEach void load() throws Exception {
        server=TestPlayers.server();var field=GameServer.class.getDeclaredField("questSystem");field.setAccessible(true);field.set(server,new QuestSystem(server));
        for(var row:JsonParser.parseString(Files.readString(FileUtils.getResourcePath("ExcelBinOutput/DailyTaskExcelConfigData.json"))).getAsJsonArray()){
            var data=JsonUtils.decode(row,DailyTaskData.class);install(GameData.getDailyTaskDataMap(),data.getId(),data);
        }
        try(var files=Files.list(FileUtils.getResourcePath("BinOutput/Quest"))){
            for(var file:files.filter(p->p.toString().endsWith(".json")).toList()){
                var parent=JsonParser.parseString(Files.readString(file)).getAsJsonObject();if(!parent.has("subQuests"))continue;
                for(var q:parent.getAsJsonArray("subQuests"))original.put(q.getAsJsonObject().get("subId").getAsInt(),q.getAsJsonObject());
            }
        }
    }
    @AfterEach void restore() throws Exception {drain();Collections.reverse(cleanup);cleanup.forEach(Runnable::run);}
    @Test void everyOriginalSetAndIncrementUsesRealIndependentPersistentTaskVectors() throws Exception {
        var player=player();int sets=0,incs=0;
        for(var raw:original.values())for(String list:List.of("beginExec","finishExec","failExec")){
            if(!raw.has(list))continue;
            for(var e:raw.getAsJsonArray(list)){
                var exec=JsonUtils.decode(e,QuestData.QuestExecParam.class);
                if(exec.getType()==QuestExec.QUEST_EXEC_SET_DAILY_TASK_VAR){
                    assertTrue(new ExecSetDailyTaskVar().execute(quest(player,raw.get("subId").getAsInt()),exec,exec.getParam()));sets++;
                }else if(exec.getType()==QuestExec.QUEST_EXEC_INC_DAILY_TASK_VAR){
                    assertTrue(new ExecIncDailyTaskVar().execute(quest(player,raw.get("subId").getAsInt()),exec,exec.getParam()));incs++;
                }
            }
        }
        assertEquals(68,sets);assertEquals(34,incs);assertTrue(player.daily.getTaskVariables().size()>10);
        var restored=new Gson().fromJson(new Gson().toJson(player.daily),SavedDaily.class);restored.setPlayer(player);
        assertEquals(player.daily.getTaskVariables(),restored.getTaskVariables());
        assertEquals(0,restored.getDailyTaskVar(999999,0));
    }
    @Test void signedValuesAndExplicitZeroSurviveNativeTagsAndFreshLoginWithoutReroll() throws Exception {
        var player=player();assertTrue(player.daily.setDailyTaskVar(29400,1,-7));assertTrue(player.daily.setDailyTaskVar(29500,1,0));
        assertEquals(-7,player.daily.getDailyTaskVar(29400,1));assertEquals(0,player.daily.getDailyTaskVar(29500,1));
        assertTrue(player.daily.incDailyTaskVar(29400,1));assertEquals(-6,player.daily.getDailyTaskVar(29400,1));
        assertTrue(player.daily.setDailyTaskVar(29400,4,Integer.MAX_VALUE));assertFalse(player.daily.incDailyTaskVar(29400,4));
        assertFalse(player.daily.setDailyTaskVar(29400,5,1));assertFalse(player.daily.setDailyTaskVar(999999,0,1));
        String saved=new Gson().toJson(player.daily);var restored=new Gson().fromJson(saved,SavedDaily.class);restored.setPlayer(player);player.daily=restored;
        player.daily.getDailyTasks().addAll(List.of(task(20500),task(29400),task(29500),task(43200)));
        date(player.daily);int serial=player.daily.getGenerationSerial();player.daily.onPlayerLogin();
        assertEquals(serial,player.daily.getGenerationSerial());assertEquals(4,player.daily.getDailyTasks().size());
        var packet=player.packets.reversed().stream().filter(p->p.getOpcode()==29103).findFirst().orElseThrow();
        var vectors=readVectors(packet.getData());assertEquals(List.of(0,-6,0,0,Integer.MAX_VALUE),vectors.get(29400));assertEquals(List.of(0,0),vectors.get(29500));
    }
    @Test void realNotifyRequiresIssuedOwnerBoardAndFinishedSourceThenPaysOnlyOnce() throws Exception {
        var player=player();var quest=quest(player,2000104);var exec=quest.getQuestData().getFinishExec().stream().filter(e->e.getType()==QuestExec.QUEST_EXEC_NOTIFY_DAILY_TASK).findFirst().orElseThrow();
        var reward=JsonUtils.decode("{id:990020,previewItems:[{id:202,count:'20'}]}",RewardPreviewData.class);reward.onLoad();install(GameData.getRewardPreviewDataMap(),990020,reward);
        install(GameData.getItemDataMap(),202,JsonUtils.decode("{id:202,itemType:'ITEM_VIRTUAL'}",ItemData.class));
        var action=new ExecNotifyDailyTask();quest.setState(QuestState.QUEST_STATE_FINISHED);
        assertFalse(action.execute(quest,exec,exec.getParam()));assertTrue(player.daily.getDailyTasks().isEmpty());
        player.daily.getDailyTasks().add(JsonUtils.decode("{taskId:20500,finishProgress:1,rewardId:990020}",DailyTask.class));
        assertFalse(action.execute(quest,exec,exec.getParam()));assertEquals(0,player.getMora());date(player.daily);
        var own=player.getWorld();var stranger=new StoryPlayer();player.setWorld(TestWorld.create(stranger));
        assertFalse(action.execute(quest,exec,exec.getParam()));assertEquals(0,player.getMora());player.setWorld(own);
        quest.setState(QuestState.QUEST_STATE_UNFINISHED);assertFalse(action.execute(quest,exec,exec.getParam()));
        quest.setState(QuestState.QUEST_STATE_FINISHED);assertTrue(action.execute(quest,exec,exec.getParam()));
        assertTrue(player.daily.getDailyTask(20500).isFinished());assertEquals(1,player.daily.getFinishedCount());
        assertEquals(20,player.getMora());
        int saves=player.daily.saves;assertFalse(action.execute(quest,exec,exec.getParam()));assertEquals(saves,player.daily.saves);
        assertEquals(20,player.getMora());
        assertTrue(player.packets.stream().anyMatch(p->p.getOpcode()==PacketOpcodes.DailyTaskProgressNotify));
        assertFalse(player.daily.isScoreRewardTaken());
    }
    @Test void allClosedDrawsStayInsideOriginalBoundsAndDegenerateUpperEndpointWorks() throws Exception {
        var player=player();var action=new ExecRandomClosedQuestVar();int draws=0;
        for(var raw:original.values())for(String list:List.of("beginExec","finishExec","failExec")){
            if(!raw.has(list))continue;
            for(var e:raw.getAsJsonArray(list)){
                var exec=JsonUtils.decode(e,QuestData.QuestExecParam.class);if(exec.getType()!=QuestExec.QUEST_EXEC_RANDOM_CLOSED_QUEST_VAR)continue;
                var q=quest(player,raw.get("subId").getAsInt());assertTrue(action.execute(q,exec,exec.getParam()));draws++;
                int value=q.getMainQuest().getQuestVar(Integer.parseInt(exec.getParam()[0]));assertTrue(value>=Integer.parseInt(exec.getParam()[1])&&value<=Integer.parseInt(exec.getParam()[2]));
            }
        }
        assertEquals(11,draws);var q=quest(player,2400301);
        assertTrue(action.execute(q,null,"0","2147483647","2147483647"));assertEquals(Integer.MAX_VALUE,q.getMainQuest().getQuestVar(0));
        assertTrue(action.execute(q,null,"1","3","2"));assertTrue(q.getMainQuest().getQuestVar(1)>=2&&q.getMainQuest().getQuestVar(1)<=3);
        int[] before=q.getMainQuest().getQuestVars().clone();assertFalse(action.execute(q,null,"5","0","2"));assertFalse(action.execute(q,null,"1","-1","2"));assertArrayEquals(before,q.getMainQuest().getQuestVars());
    }
    @Test void originalInterviewDrawsThreeDifferentPromptsAndRejectsOtherTaskOrMalformedPool() throws Exception {
        var player=player();var q=quest(player,2503601);var exec=q.getQuestData().getFinishExec().stream().filter(e->e.getType()==QuestExec.QUEST_EXEC_UNIQUE_RANDOM_QUEST_VAR).findFirst().orElseThrow();
        var action=new ExecUniqueRandomQuestVar();q.getMainQuest().getQuestVars()[0]=17;q.getMainQuest().getQuestVars()[4]=19;
        for(int attempt=0;attempt<12;attempt++){
            assertTrue(action.execute(q,exec,exec.getParam()));var vars=q.getMainQuest().getQuestVars();assertEquals(3,Set.of(vars[1],vars[2],vars[3]).size());
            for(int i=1;i<=3;i++)assertTrue(vars[i]>=1&&vars[i]<=6);assertEquals(17,vars[0]);assertEquals(19,vars[4]);
        }
        int[] before=q.getMainQuest().getQuestVars().clone();assertFalse(action.execute(q,exec,"1,1,3","1","6"));assertArrayEquals(before,q.getMainQuest().getQuestVars());
        assertFalse(action.execute(quest(player,2400301),exec,exec.getParam()));
    }
    private static Map<Integer,List<Integer>> readVectors(byte[] data) throws Exception {
        var result=new HashMap<Integer,List<Integer>>();var outer=CodedInputStream.newInstance(data);
        for(int t;(t=outer.readTag())!=0;){assertEquals(50,t);var entry=CodedInputStream.newInstance(outer.readByteArray());int id=0;var values=new ArrayList<Integer>();
            for(int s;(s=entry.readTag())!=0;){if(s==112)id=entry.readUInt32();else if(s==26){var packed=CodedInputStream.newInstance(entry.readByteArray());while(!packed.isAtEnd())values.add(packed.readInt32());}else fail("unexpected native tag "+s);}
            result.put(id,values);
        }return result;
    }
    private StoryPlayer player(){var p=new StoryPlayer();p.setUid(7101);p.setSession(new GameSession(server));p.daily=new SavedDaily(p);TestWorld.create(p);return p;}
    private GameQuest quest(StoryPlayer p,int id){
        var raw=original.get(id).deepCopy();raw.addProperty("mainId",id/100);if(!raw.has("failCond"))raw.add("failCond",new JsonArray());
        var data=JsonUtils.decode(raw,QuestData.class);install(GameData.getMainQuestDataMap(),id/100,JsonUtils.decode("{id:"+id/100+",subQuests:[]}",MainQuestData.class));
        var main=new GameMainQuest(p,id/100){@Override public void save(){}};return new GameQuest(main,data);
    }
    private static DailyTask task(int id){return JsonUtils.decode("{taskId:"+id+",finishProgress:1,rewardId:0}",DailyTask.class);}
    private static void date(DailyTaskManager manager)throws Exception{var f=DailyTaskManager.class.getDeclaredField("lastGenerationDate");f.setAccessible(true);var d=LocalDate.now(ZoneId.systemDefault());f.setInt(manager,d.getYear()*10000+d.getMonthValue()*100+d.getDayOfMonth());}
    private static void drain()throws Exception{QuestManager.eventExecutor.submit(()->{}).get(10,TimeUnit.SECONDS);}
    private <K,V> void install(Map<K,V> map,K id,V value){var old=map.put(id,value);cleanup.add(()->{if(old==null)map.remove(id);else map.put(id,old);});}
    private static final class SavedDaily extends DailyTaskManager {int saves;SavedDaily(Player p){super(p);}@Override public void save(){saves++;}}
    private static final class StoryPlayer extends TestWorld.TestPlayer {SavedDaily daily;final List<BasePacket> packets=new ArrayList<>();@Override public DailyTaskManager getDailyTaskManager(){return daily;}@Override public void sendPacket(BasePacket p){packets.add(p);}@Override public void save(){}@Override public boolean isOnline(){return true;}}
}
