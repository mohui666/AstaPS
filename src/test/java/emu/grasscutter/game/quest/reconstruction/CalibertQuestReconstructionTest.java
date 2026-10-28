package emu.grasscutter.game.quest.reconstruction;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.grasscutter.*;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.ScenePointEntry;
import emu.grasscutter.data.binout.MainQuestData;
import emu.grasscutter.data.common.PointData;
import emu.grasscutter.data.excels.GadgetData;
import emu.grasscutter.data.excels.dungeon.DungeonData;
import emu.grasscutter.data.excels.monster.MonsterData;
import emu.grasscutter.data.excels.scene.SceneData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.avatar.Avatar;
import emu.grasscutter.game.dungeons.*;
import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.entity.gadget.GadgetWorktop;
import emu.grasscutter.game.inventory.Inventory;
import emu.grasscutter.game.player.*;
import emu.grasscutter.game.props.*;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.content.ContentLuaNotify;
import emu.grasscutter.game.quest.enums.*;
import emu.grasscutter.game.quest.persistent.*;
import emu.grasscutter.game.world.*;
import emu.grasscutter.game.world.data.TeleportProperties;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.AddQuestContentProgressReqOuterClass.AddQuestContentProgressReq;
import emu.grasscutter.net.proto.AddQuestContentProgressBatchReq._AddQuestContentProgressBatchReq;
import emu.grasscutter.net.proto.QuestProgressInfo._QuestProgressInfo;
import emu.grasscutter.net.proto.SelectWorktopOptionReqOuterClass.SelectWorktopOptionReq;
import emu.grasscutter.net.proto.ClientScriptEventNotifyOuterClass.ClientScriptEventNotify;
import emu.grasscutter.net.proto.PlayerEnterDungeonReqOuterClass.PlayerEnterDungeonReq;
import emu.grasscutter.scripts.*;
import emu.grasscutter.scripts.constants.EventType;
import emu.grasscutter.scripts.data.*;
import emu.grasscutter.server.game.*;
import emu.grasscutter.server.packet.recv.*;
import emu.grasscutter.utils.JsonUtils;
import it.unimi.dsi.fastutil.ints.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import sun.misc.Unsafe;

@ExtendWith(ServerResourceFixture.class)
@ResourceLock("GameData")
final class CalibertQuestReconstructionTest {
    private static final int TEST_MAIN = 990080;
    private boolean scriptsEnabled;
    private GadgetData previousGadget;
    private final Map<Integer,DungeonData> previousDungeons = new HashMap<>();
    private final Map<Integer,SceneData> previousScenes = new HashMap<>();
    private final Map<Integer,ScenePointEntry> previousPoints = new HashMap<>();
    private final Map<Integer,QuestData> previousQuests = new HashMap<>();
    private MainQuestData previousMain;
    private List<QuestData> previousAccepts;
    private static final String CONTROLLER_ACCEPT = QuestData.questConditionKey(QuestCond.QUEST_COND_STATE_EQUAL,801301,"");
    private CalibertPlayer player;
    private CalibertWorld world;
    private CalibertScene scene;

    private record Event(QuestContent type, String key, int[] args) {}
    private static final class Quests {
        final QuestManager manager;
        GameMainQuest main;
        Map<Integer,GameQuest> quests;
        final List<Event> events = new CopyOnWriteArrayList<>();
        int rewinds;
        Quests(Player player) { manager=new QuestManager(player); }
        void init(Player player) {
            main=new GameMainQuest(player,TEST_MAIN) {
                @Override public List<Position> rewind() { rewinds++; return null; }
                @Override public void tryFinishSubQuests(QuestContent type,String key,int... args) {
                    events.add(new Event(type,key,args));
                    super.tryFinishSubQuests(type,key,args);
                }
            };
            quests=main.getChildQuests(); manager.getMainQuests().put(TEST_MAIN,main);
        }
        long notified(String key) { return events.stream().filter(e -> e.type == QuestContent.QUEST_CONTENT_LUA_NOTIFY && e.key.equals(key)).count(); }
    }
    private static final class CalibertPlayer extends TestWorld.TestPlayer {
        final PlayerProgress progress = new PlayerProgress();
        final Quests quests = new Quests(this);
        final PlayerProgressManager manager = new PlayerProgressManager(this);
        final TeamManager team = new TeamManager(this);
        final Map<Integer,Integer> items = new HashMap<>();
        final Inventory inventory = new Inventory(this) {
            @Override public int getItemCountById(int id) { return items.getOrDefault(id,0); }
            @Override public boolean addItem(int id, int amount, ActionReason reason) {
                if (!addItemResult) return false;
                items.merge(id,amount,Integer::sum); return true;
            }
        };
        GameServer server;
        @Override public PlayerProgress getPlayerProgress() { return progress; }
        @Override public PlayerProgressManager getProgressManager() { return manager; }
        @Override public QuestManager getQuestManager() { return quests.manager; }
        @Override public Inventory getInventory() { return inventory; }
        @Override public GameServer getServer() { return server; }
        @Override public TeamManager getTeamManager() { return team; }
        @Override public void save() {}
        GameQuest active(int id) {
            var quest = new GameQuest(quests.main,GameData.getQuestDataMap().get(id)) {
                @Override public int getMainQuestId() { return id/100; }
                @Override public void finish() {
                    setState(QuestState.QUEST_STATE_FINISHED);
                    if(getSubQuestId()==801301) triggerStateEvents();
                }
                @Override public void fail() { setState(QuestState.QUEST_STATE_FAILED); }
                @Override public void save() {}
            };
            quest.setState(QuestState.QUEST_STATE_UNFINISHED);
            quests.quests.put(id,quest);
            return quest;
        }
    }
    /** Only world transport is substituted; DungeonSystem and the Lua/kill pipelines remain real. */
    private static final class CalibertWorld extends World {
        Player owner;
        GameServer server;
        Int2ObjectMap<Scene> scenes;
        List<TeleportProperties> transfers;
        int next;
        boolean allowTransfer;
        CalibertWorld() { super((Player)null); }
        static CalibertWorld create(CalibertPlayer player) throws Exception {
            var field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
            var unsafe = (Unsafe)field.get(null);
            var world = (CalibertWorld)unsafe.allocateInstance(CalibertWorld.class);
            world.owner=player; world.server=(GameServer)unsafe.allocateInstance(GameServer.class);
            var dungeon = GameServer.class.getDeclaredField("dungeonSystem"); dungeon.setAccessible(true);
            dungeon.set(world.server,new DungeonSystem(world.server));
            var quests = GameServer.class.getDeclaredField("questSystem"); quests.setAccessible(true);
            quests.set(world.server,new QuestSystem(world.server));
            world.scenes=new Int2ObjectOpenHashMap<>(); world.transfers=new ArrayList<>(); world.allowTransfer=true;
            player.server=world.server; player.setWorld(world);
            return world;
        }
        @Override public Player getHost() { return owner; }
        @Override public GameServer getServer() { return server; }
        @Override public List<Player> getPlayers() { return List.of(owner); }
        @Override public Int2ObjectMap<Scene> getScenes() { return scenes; }
        @Override public Scene getSceneById(int id) { return scenes.get(id); }
        @Override public int getNextEntityId(EntityIdType type) { return (type.getId()<<24) + ++next; }
        @Override public int getLevelEntityId() { return 0; }
        @Override public boolean transferPlayerToScene(Player player, TeleportProperties properties) {
            if (!allowTransfer || !scenes.containsKey(properties.getSceneId())) return false;
            transfers.add(properties);
            player.setScene(scenes.get(properties.getSceneId())); player.setSceneId(properties.getSceneId());
            if (properties.getTeleportTo()!=null) player.getPosition().set(properties.getTeleportTo());
            return true;
        }
    }
    private static final class CalibertScene extends Scene {
        Scripts scripts;
        CalibertScene(CalibertWorld world,int id) { super(world,TestWorld.sceneData(id,id==3?SceneType.SCENE_WORLD:SceneType.SCENE_DUNGEON)); }
        @Override public SceneScriptManager getScriptManager() {
            if (scripts==null) scripts=new Scripts(this);
            return scripts;
        }
        @Override public void broadcastPacket(BasePacket packet) {}
        @Override public void saveGroups() {}
    }
    private static final class Scripts extends SceneScriptManager {
        final Map<Integer,SceneGroupInstance> instances = new HashMap<>();
        final List<Future<?>> pending = new CopyOnWriteArrayList<>();
        Scripts(Scene scene) { super(scene); }
        @Override public boolean isInit() { return true; }
        @Override public SceneGroupInstance getGroupInstanceById(int id) { return instances.get(id); }
        @Override public Map<Integer,SceneGroupInstance> getCachedGroupInstances() { return instances; }
        @Override public SceneGroup getGroupById(int id) { return instances.containsKey(id)?instances.get(id).getLuaGroup():null; }
        @Override public Map<String,Integer> getVariables(int id) { return instances.containsKey(id)?instances.get(id).getCachedVariables():super.getVariables(id); }
        @Override public Future<?> callEvent(ScriptArgs args) {
            var future=super.callEvent(args); pending.add(future); return future;
        }
        void drain() throws Exception {
            int seen;
            do { seen=pending.size(); for (var future:List.copyOf(pending)) future.get(5,TimeUnit.SECONDS); } while (seen!=pending.size());
        }
        void load(int id) throws Exception {
            if (instances.containsKey(id)) return;
            var group=SceneGroup.of(id).load(getScene().getId());
            var instance=new SceneGroupInstance(group,getScene().getWorld().getHost());
            instances.put(id,instance);
            refreshGroup(instance,1,false); drain();
        }
        @Override public EntityMonster createMonster(int group,int block,SceneMonster template) {
            var data=JsonUtils.decode("{id:"+template.monster_id+",type:'MONSTER_ORDINARY',affix:[]}",MonsterData.class);
            var monster=new EntityMonster(getScene(),data,template.pos,template.rot,template.level) {
                @Override public void recalcStats() { setFightProperty(FightProperty.FIGHT_PROP_MAX_HP,100); setFightProperty(FightProperty.FIGHT_PROP_CUR_HP,100); }
                @Override public void initAbilities() {}
                @Override public int getMonsterWeaponId() { return 0; }
            };
            monster.setGroupId(group); monster.setConfigId(template.config_id); monster.setMetaMonster(template);
            return monster;
        }
    }

    @BeforeEach void setup() throws Exception {
        ScriptLoader.init();
        scriptsEnabled=Grasscutter.getConfig().server.game.enableScriptInBigWorld;
        Grasscutter.getConfig().server.game.enableScriptInBigWorld=false;
        previousGadget=GameData.getGadgetDataMap().put(70360002,JsonUtils.decode(
                "{id:70360002,type:'Worktop',jsonName:'SceneObj_Gear_Operator_Normal_Operator',tags:[]}",GadgetData.class));
        previousMain=GameData.getMainQuestDataMap().put(TEST_MAIN,JsonUtils.decode("{id:"+TEST_MAIN+",subQuests:[]}",MainQuestData.class));
        var owned=Map.of(801010,"22018000701",801012,"22018000401",801014,"22018000801",801031,"22018000802",
                801105,"801105",801107,"801107",801109,"801109",801117,"801117",801121,"801121",801202,"801202");
        for(var entry:owned.entrySet()) installQuest(entry.getKey(),"{type:'QUEST_CONTENT_LUA_NOTIFY',param:[0,0],param_str:'"+entry.getValue()+"',count:1}");
        installQuest(801032,"{type:'QUEST_CONTENT_ADD_QUEST_PROGRESS',param:[801032,0],count:2}");
        installQuest(801103,"{type:'QUEST_CONTENT_ENTER_DUNGEON',param:[1175,2],count:1}");
        installQuest(801224,"{type:'QUEST_CONTENT_ENTER_DUNGEON',param:[1176,4],count:1}");
        installQuest(801114,"{type:'QUEST_CONTENT_ENTER_DUNGEON',param:[1175,778],count:1}");
        installQuest(801222,"{type:'QUEST_CONTENT_ENTER_DUNGEON',param:[1176,779],count:1}");
        installQuest(801005,"{type:'QUEST_CONTENT_OBTAIN_ITEM',param:[120246,0],count:1}");
        installQuest(801015,"{type:'QUEST_CONTENT_OBTAIN_ITEM',param:[120246,0],count:1}");
        installQuest(801110,"{type:'QUEST_CONTENT_COMPLETE_TALK',param:[801105,0],count:1}");
        installQuest(801203,"{type:'QUEST_CONTENT_COMPLETE_TALK',param:[801201,0],count:1}");
        installQuest(801301,"{type:'QUEST_CONTENT_LUA_NOTIFY',param:[0,0],param_str:'133302744',count:1}");
        installQuest(801401,"{type:'QUEST_CONTENT_QUEST_STATE_EQUAL',param:[801111,3]}");
        installQuest(801501,"{type:'QUEST_CONTENT_QUEST_STATE_EQUAL',param:[801204,3]}");
        previousQuests.put(801302,GameData.getQuestDataMap().put(801302,JsonUtils.decode("{mainId:"+TEST_MAIN+",subId:801302,"
                +"acceptCond:[{type:'QUEST_COND_STATE_EQUAL',param:[801301,3]}],"
                +"finishCond:[{type:'QUEST_CONTENT_QUEST_STATE_EQUAL',param:[801211,3]}],"
                +"failCond:[{type:'QUEST_CONTENT_ENTER_DUNGEON',param:[1177,776]}],beginExec:[],finishExec:[],failExec:[]}",QuestData.class)));
        previousAccepts=GameData.getBeginCondQuestMap().put(CONTROLLER_ACCEPT,List.of(GameData.getQuestDataMap().get(801302)));
        player=new CalibertPlayer(); player.setUid(4801);
        player.quests.init(player);
        for(int id:List.of(801301,801302,801401,801501)) player.active(id).setState(QuestState.QUEST_STATE_UNSTARTED);
        // The same minimal combat default TestWorld supplies, without starting a server.
        GameData.setConfigGlobalCombat(new emu.grasscutter.data.binout.config.ConfigGlobalCombat());
        world=CalibertWorld.create(player);
        for (var pair:Map.of(20180,1177,20178,1175,20181,1176).entrySet()) {
            int id=pair.getKey(),dungeon=pair.getValue();
            previousScenes.put(id,GameData.getSceneDataMap().put(id,TestWorld.sceneData(id,SceneType.SCENE_DUNGEON)));
            var data=JsonUtils.decode("{id:"+dungeon+",sceneId:"+id+",showLevel:35,type:'"
                    +(dungeon==1177?"DUNGEON_BIGWORLD_MIRROR":"DUNGEON_PLOT")
                    +"',involveType:'INVOLVE_ONLY_SINGLE'}",DungeonData.class);
            previousDungeons.put(dungeon,GameData.getDungeonDataMap().put(dungeon,data));
            var scene=new CalibertScene(world,id); scene.setDungeonManager(new DungeonManager(scene,data));
            world.scenes.put(id,scene);
        }
        scene=(CalibertScene)world.scenes.get(20180); player.setScene(scene); player.setSceneId(20180);
        player.team.setEntity(new EntityTeam(player) { @Override public void initAbilities() {} });
        var points=JsonParser.parseString(Files.readString(Path.of("resources/BinOutput/Scene/Point/scene20180_point.json"))).getAsJsonObject().getAsJsonObject("points");
        for (int id:List.of(2,4)) {
            var point=JsonUtils.decode(points.get(Integer.toString(id)).toString(),PointData.class); point.setId(id);
            int key=(20180<<16)+id;
            previousPoints.put(key,GameData.getScenePointEntryMap().put(key,new ScenePointEntry(20180,point)));
        }
    }
    @AfterEach void restore() throws Exception {
        for (var value:world.scenes.values()) ((Scripts)value.getScriptManager()).drain();
        drainQuests();
        previousDungeons.forEach((id,value)-> { if(value==null)GameData.getDungeonDataMap().remove(id);else GameData.getDungeonDataMap().put(id,value); });
        previousScenes.forEach((id,value)-> { if(value==null)GameData.getSceneDataMap().remove(id);else GameData.getSceneDataMap().put(id,value); });
        previousPoints.forEach((id,value)-> { if(value==null)GameData.getScenePointEntryMap().remove(id);else GameData.getScenePointEntryMap().put(id,value); });
        if(previousGadget==null)GameData.getGadgetDataMap().remove(70360002);else GameData.getGadgetDataMap().put(70360002,previousGadget);
        previousQuests.forEach((id,value)-> { if(value==null)GameData.getQuestDataMap().remove(id);else GameData.getQuestDataMap().put(id,value); });
        if(previousMain==null)GameData.getMainQuestDataMap().remove(TEST_MAIN);else GameData.getMainQuestDataMap().put(TEST_MAIN,previousMain);
        if(previousAccepts==null)GameData.getBeginCondQuestMap().remove(CONTROLLER_ACCEPT);else GameData.getBeginCondQuestMap().put(CONTROLLER_ACCEPT,previousAccepts);
        Grasscutter.getConfig().server.game.enableScriptInBigWorld=scriptsEnabled;
    }
    private Scripts scripts() { return (Scripts)player.getScene().getScriptManager(); }
    private void installQuest(int id,String condition) {
        previousQuests.put(id,GameData.getQuestDataMap().put(id,JsonUtils.decode("{mainId:"+TEST_MAIN+",subId:"+id
                +",acceptCond:[],finishCond:["+condition+"],failCond:[],beginExec:[],finishExec:[],failExec:[]}",QuestData.class)));
    }
    private static void drainQuests() throws Exception {
        // Content -> finished-state condition -> accepted child each appends to the same FIFO.
        for(int stage=0;stage<3;stage++) QuestManager.eventExecutor.submit(()->{}).get(5,TimeUnit.SECONDS);
    }
    private void select(int group,int config,int option) throws Exception {
        var entity=player.getScene().getEntityByConfigId(config,group); assertNotNull(entity);
        var session=new GameSession(null) {
            @Override public Player getPlayer() { return player; }
            @Override public void send(BasePacket packet) {}
        };
        var before=scripts();
        new HandlerSelectWorktopOptionReq().handle(session,new byte[0],SelectWorktopOptionReq.newBuilder()
                .setGadgetEntityId(entity.getId()).setOptionId(option).build().toByteArray());
        before.drain(); drainQuests();
    }
    private void near(int group,int config) { player.getPosition().set(player.getScene().getEntityByConfigId(config,group).getPosition()); }

    private void installRealOuterOrigin() throws Exception {
        var overworld=new CalibertScene(world,3);
        world.scenes.put(3,overworld);
        previousScenes.put(3,GameData.getSceneDataMap().put(3,TestWorld.sceneData(3,SceneType.SCENE_WORLD)));
        var points=JsonParser.parseString(Files.readString(Path.of("resources/BinOutput/Scene/Point/scene3_point.json")))
                .getAsJsonObject().getAsJsonObject("points");
        var point=JsonUtils.decode(points.get("776").toString(),PointData.class); point.setId(776);
        previousPoints.put((3<<16)+776,GameData.getScenePointEntryMap().put((3<<16)+776,new ScenePointEntry(3,point)));
        player.setScene(overworld); player.setSceneId(3); player.getPosition().set(point.getPos());
        assertTrue(player.getServer().getDungeonSystem().enterDungeon(player,776,1177,true));
        scene=(CalibertScene)player.getScene();
        scene.getPlayers().add(player);
        scene.getDungeonManager().startDungeon();
        drainQuests();
        assertTrue(QuestPersistentDungeonRuntime.canRestoreLogin(player));
    }

    private void restoreSavedRunIntoFreshLoginScene() throws Exception {
        assertTrue(QuestPersistentDungeonRuntime.capture(player.getScene()));
        var saved=player.getScene().getDungeonManager().capturePersistentProgress();
        var copy=JsonUtils.decode(JsonUtils.encode(player.getPersistentDungeons()),QuestPersistentDungeonState.class);
        var field=Player.class.getDeclaredField("persistentDungeons"); field.setAccessible(true); field.set(player,copy);
        scripts().drain(); drainQuests();
        scene=new CalibertScene(world,player.getSceneId());
        world.scenes.put(scene.getId(),scene); player.setScene(scene); scene.getPlayers().add(player);
        assertNull(scene.getDungeonManager());
        assertTrue(QuestPersistentDungeonRuntime.canRestoreLogin(player));
        assertTrue(QuestPersistentDungeonRuntime.restoreLogin(player));
        assertEquals(saved.getDungeonId(),scene.getDungeonManager().getDungeonData().getId());
        assertEquals(saved.getOriginSceneId(),scene.getPrevScene());
        assertEquals(saved.getOriginPointId(),scene.getPrevScenePoint());
        assertEquals(saved.getClaimRunToken(),scene.getDungeonManager().capturePersistentProgress().getClaimRunToken());
        assertTrue(scene.getDungeonManager().isPersistentProgressRestored());
    }

    @Test void allMissingBlocksAndNative71StagesHaveRealPlayableEntities() throws Exception {
        for (var entry:Map.of(20180,List.of(220180004,220180007,220180008,220180009,220180010),
                20178,List.of(220178003,220178004,220178005),20181,List.of(220181001)).entrySet()) {
            var block=new SceneBlock(); block.id=entry.getKey();
            assertNotNull(block.load(entry.getKey(),ScriptLoader.getEngine().createBindings()));
            assertEquals(new HashSet<>(entry.getValue()),block.groups.keySet());
            for (int id:entry.getValue()) {
                var group=block.groups.get(id).load(entry.getKey());
                assertFalse(group.gadgets.isEmpty()); assertFalse(group.getSuiteByIndex(1).gadgets.isEmpty());
                assertEquals(entry.getKey(),SceneGroup.findSceneId(id));
            }
        }
        for(int id:List.of(220180004,220180007,220180009,220180010)) {
            var group=SceneGroup.of(id).load(20180);
            assertEquals(1,group.init_config.io_type); assertEquals(1,group.init_config.sub_flow_type);
            assertNotNull(group.getSuiteByIndex(1).flow);
        }
        assertArrayEquals(new int[]{1175},GameData.getScenePointEntryById(20180,2).getPointData().getDungeonIds());
        assertArrayEquals(new int[]{1176},GameData.getScenePointEntryById(20180,4).getPointData().getDungeonIds());
        var outer=new SceneBlock(); outer.id=990180003;
        assertNotNull(outer.load(3,ScriptLoader.getEngine().createBindings()));
        assertEquals(Set.of(133302744),outer.groups.keySet());
        assertEquals(3,SceneGroup.findSceneId(133302744));
        var sceneScript=ScriptLoader.getEngine().createBindings();
        ScriptLoader.eval(ScriptLoader.getScript("Scene/3/scene3.lua"),sceneScript);
        var blocks=(org.luaj.vm2.LuaTable)sceneScript.get("blocks");
        var rects=(org.luaj.vm2.LuaTable)sceneScript.get("block_rects");
        assertEquals(blocks.length(),rects.length());
        int portalBlock=0;
        for(int index=1;index<=blocks.length();index++) if(blocks.get(index).toint()==990180003) portalBlock=index;
        assertTrue(portalBlock>0);
        var rect=rects.get(portalBlock);
        assertTrue(rect.get("min").get("x").tofloat()<=-694.553f && rect.get("max").get("x").tofloat()>=-694.553f);
        assertTrue(rect.get("min").get("z").tofloat()<=2234.657f && rect.get("max").get("z").tofloat()>=2234.657f);
    }

    @Test void realWorktopRequestAndLuaRequireTheActingPlayerNearbyAndKeepRejectedSelectionsRetryable() throws Exception {
        scripts().load(220180007);
        player.active(801010);
        player.getPosition().set(new Position()); select(220180007,7001,7);
        assertEquals(-1,player.progress.getCurrentProgress("22018000701"));
        near(220180007,7001); select(220180007,7001,99);
        assertEquals(0,player.quests.notified("22018000701"));
        var forged=new GameSession(null) {
            @Override public Player getPlayer() { return player; }
            @Override public void send(BasePacket packet) {}
        };
        new HandlerClientScriptEventNotify().handle(forged,new byte[0],ClientScriptEventNotify.newBuilder()
                .setEventType(EventType.EVENT_SELECT_OPTION).addParamList(7001).addParamList(7).build().toByteArray());
        scripts().drain(); drainQuests();
        assertEquals(0,player.quests.notified("22018000701"));
        player.addItemResult=false; select(220180007,7001,7);
        assertEquals(0,player.inventory.getItemCountById(120247));
        assertEquals(0,player.quests.notified("22018000701"));
        player.addItemResult=true;
        select(220180007,7001,7);
        assertEquals(1,player.progress.getCurrentProgress("22018000701"));
        assertEquals(1,player.inventory.getItemCountById(120247));
        assertEquals(1,player.quests.notified("22018000701"));
        select(220180007,7001,7);
        assertEquals(1,player.quests.notified("22018000701"));
    }

    @Test void twoActualPickupsDriveTheCountAndLaterHiddenQuestsReuseOnlySavedPickupEvidence() throws Exception {
        scripts().load(220180008);
        var countQuest=player.active(801032); CalibertQuestReconstruction.onQuestStart(countQuest);
        near(220180008,8001); player.addItemResult=false; select(220180008,8001,7);
        assertEquals(0,player.progress.getCurrentProgress("801032"));
        player.addItemResult=true; select(220180008,8001,7); select(220180008,8001,7);
        assertEquals(1,player.inventory.getItemCountById(120245));
        near(220180008,8002); select(220180008,8002,7);
        assertEquals(2,player.progress.getCurrentProgress("801032"));
        assertEquals(2,player.inventory.getItemCountById(120245));
        for (int id:List.of(801014,801031)) {
            String key=id==801014?"22018000801":"22018000802";
            long before=player.quests.notified(key);
            CalibertQuestReconstruction.onQuestStart(player.active(id));
            drainQuests();
            assertEquals(before+1,player.quests.notified(key));
            var condition=JsonUtils.decode("{param_str:'"+key+"',count:1}",emu.grasscutter.data.excels.quest.QuestData.QuestContentCondition.class);
            assertTrue(new ContentLuaNotify().execute(player.quests.quests.get(id),condition,"",0));
        }
        countQuest.setState(QuestState.QUEST_STATE_UNFINISHED);
        CalibertQuestReconstruction.onQuestStart(countQuest);
        assertEquals(0,player.progress.getCurrentProgress("801032"));
        assertEquals(0,player.progress.getCurrentProgress("22018000801"));
        near(220180008,8001); select(220180008,8001,7);
        assertEquals(1,player.progress.getCurrentProgress("801032"));
    }

    @Test void defenseAndHeraldRequireRealSpawnedMonstersToDieThroughSceneKillEntity() throws Exception {
        for(var setup:List.of(new int[]{20180,220180004,4002,801012,3},new int[]{20178,220178004,4001,801107,1})) {
            scene=(CalibertScene)world.scenes.get(setup[0]); player.setScene(scene); player.setSceneId(setup[0]);
            var quest=player.active(setup[3]); CalibertQuestReconstruction.onQuestStart(quest);
            scripts().load(setup[1]); near(setup[1],setup[2]);
            assertEquals(-1,CalibertQuestReconstruction.monsterDied(scripts(),setup[1],4101));
            select(setup[1],setup[2],7);
            var monsters=scene.getEntities().values().stream().filter(e->e instanceof EntityMonster && e.getGroupId()==setup[1]).toList();
            assertEquals(setup[4],monsters.size());
            String key=setup[3]==801012?"22018000401":"801107";
            for(int i=0;i<monsters.size();i++) {
                assertEquals(i==monsters.size()?1:0,player.quests.notified(key));
                scene.killEntity(monsters.get(i),0);
            }
            scripts().drain();
            drainQuests();
            assertEquals(1,player.quests.notified(key));
            assertEquals(1,player.progress.getCurrentProgress(key));
            assertTrue(scene.getEntities().values().stream().noneMatch(e->e instanceof EntityMonster));
        }
    }

    @Test void manualGatewaysMoveToAuthoredPointsAndOnlyActualAvatarArrivalCreditsTheQuest() throws Exception {
        for(var setup:List.of(new int[]{20178,220178003,3001,801105,3101},
                new int[]{20178,220178003,3002,801117,3102},new int[]{20178,220178003,3003,801121,3103},
                new int[]{20178,220178005,5001,801109,5101},new int[]{20181,220181001,1001,801202,1101})) {
            scene=(CalibertScene)world.scenes.get(setup[0]); player.setScene(scene); player.setSceneId(setup[0]);
            player.active(setup[3]); scripts().load(setup[1]); near(setup[1],setup[2]);
            var avatar=new Avatar() { @Override public Player getPlayer() { return player; } };
            avatar.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP,100);
            var entity=new EntityAvatar(scene,avatar); scene.getEntities().put(entity.getId(),entity);
            assertEquals(-1,CalibertQuestReconstruction.entered(scripts(),entity,setup[1],setup[4]));
            int before=world.transfers.size();
            world.allowTransfer=false; select(setup[1],setup[2],7);
            assertEquals(before,world.transfers.size());
            world.allowTransfer=true; select(setup[1],setup[2],7);
            assertEquals(setup[0],world.transfers.getLast().getSceneId());
            assertEquals(0,player.quests.notified(Integer.toString(setup[3])));
            scripts().checkRegions(); scripts().drain();
            drainQuests();
            assertEquals(1,player.quests.notified(Integer.toString(setup[3])));
        }
    }

    @Test void interruptedPartialCombatRestartsAFullWaveWithoutCreditingMissRemovals() throws Exception {
        player.active(801012); scripts().load(220180004); near(220180004,4002);
        select(220180004,4002,7);
        scene.killEntity(scene.getEntityByConfigId(4101,220180004),0);
        var survivors=scene.getEntities().values().stream().filter(e->e instanceof EntityMonster).toList();
        for(var entity:survivors) scene.removeEntity(entity,emu.grasscutter.net.proto.VisionTypeOuterClass.VisionType.VisionType_VISION_MISS);
        scripts().drain(); drainQuests();
        assertEquals(0,player.quests.notified("22018000401"));
        select(220180004,4002,7);
        var restarted=scene.getEntities().values().stream().filter(e->e instanceof EntityMonster).toList();
        assertEquals(3,restarted.size());
        select(220180004,4002,7);
        assertEquals(3,scene.getEntities().values().stream().filter(e->e instanceof EntityMonster).count());
        for(var entity:restarted) scene.killEntity(entity,0);
        scripts().drain(); drainQuests();
        assertEquals(1,player.quests.notified("22018000401"));
    }

    @Test void bothMedicineStepsNeedARealRecipeInteractionAndFailedGrantsDoNotFinishThem() throws Exception {
        scripts().load(220180004); var first=player.active(801005); near(220180004,4001);
        player.addItemResult=false; select(220180004,4001,7);
        assertEquals(QuestState.QUEST_STATE_UNFINISHED,first.getState());
        assertEquals(0,player.inventory.getItemCountById(120246));
        player.addItemResult=true; select(220180004,4001,7);
        assertEquals(QuestState.QUEST_STATE_FINISHED,first.getState());
        assertEquals(1,player.inventory.getItemCountById(120246));
        var second=player.active(801015); select(220180004,4001,7);
        assertEquals(QuestState.QUEST_STATE_FINISHED,second.getState());
        assertEquals(1,player.inventory.getItemCountById(120246));
    }

    @Test void leavingTheHeraldInstanceRequiresRealCombatAndTheOriginalFinalTalk() throws Exception {
        scene=(CalibertScene)world.scenes.get(20178); player.setScene(scene); player.setSceneId(20178);
        player.active(801107); player.active(801110);
        scripts().load(220178004); scripts().load(220178005);
        near(220178005,5002); select(220178005,5002,7);
        assertEquals(20178,player.getSceneId());
        near(220178004,4001); select(220178004,4001,7);
        scene.killEntity(scene.getEntityByConfigId(4101,220178004),0); scripts().drain(); drainQuests();
        near(220178005,5002); select(220178005,5002,7);
        assertEquals(20178,player.getSceneId());
        player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_COMPLETE_TALK,801105);
        drainQuests();
        select(220178005,5002,7);
        assertEquals(20180,player.getSceneId());
        assertEquals(1177,player.getScene().getDungeonManager().getDungeonData().getId());
        assertEquals(QuestState.QUEST_STATE_FINISHED,player.getQuestManager().getQuestById(801107).getState());

        scene=(CalibertScene)world.scenes.get(20181); player.setScene(scene); player.setSceneId(20181);
        player.active(801202); scripts().load(220181001);
        near(220181001,1002); select(220181001,1002,7);
        assertEquals(20181,player.getSceneId());
        var avatar=new Avatar() { @Override public Player getPlayer() { return player; } };
        avatar.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP,100);
        var entity=new EntityAvatar(scene,avatar); scene.getEntities().put(entity.getId(),entity);
        near(220181001,1001); select(220181001,1001,7); scripts().checkRegions(); scripts().drain(); drainQuests();
        assertEquals(QuestState.QUEST_STATE_FINISHED,player.getQuestManager().getQuestById(801202).getState());
        player.active(801203); near(220181001,1002); select(220181001,1002,7);
        assertEquals(20181,player.getSceneId());
        player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_COMPLETE_TALK,801201); drainQuests();
        select(220181001,1002,7);
        assertEquals(20180,player.getSceneId());
        assertEquals(1177,player.getScene().getDungeonManager().getDungeonData().getId());
        assertEquals(3,player.getScene().getPrevScene());
        assertEquals(776,player.getScene().getPrevScenePoint());
    }

    @Test void actualDungeonEntryUsesTheOriginalDungeonManagerAndNotAQuestCompletionReport() throws Exception {
        for(var setup:List.of(new int[]{220180009,9001,801114,1175,20178,801103},new int[]{220180010,10001,801222,1176,20181,801224})) {
            scene=(CalibertScene)world.scenes.get(20180); player.setScene(scene); player.setSceneId(20180);
            scripts().load(setup[0]); player.active(setup[2]); near(setup[0],setup[1]);
            if (!scene.getDungeonManager().capturePersistentProgress().isStarted()) {
                scene.getPlayers().add(player);
                scene.getDungeonManager().startDungeon();
                drainQuests();
            }
            player.quests.events.clear();
            var cleanup=player.active(setup[3]==1175?801401:801501);
            cleanup.setState(QuestState.QUEST_STATE_UNSTARTED);
            select(setup[0],setup[1],7);
            assertEquals(setup[4],player.getSceneId());
            assertEquals(setup[3],player.getScene().getDungeonManager().getDungeonData().getId());
            assertTrue(player.quests.events.stream().noneMatch(e->e.type==QuestContent.QUEST_CONTENT_ENTER_DUNGEON));
            player.getScene().getPlayers().add(player);
            player.getScene().getDungeonManager().startDungeon();
            drainQuests();
            assertEquals(20180,player.getScene().getPrevScene());
            assertEquals(setup[3]==1175?2:4,player.getScene().getPrevScenePoint());
            assertTrue(QuestPersistentDungeonRuntime.canRestoreLogin(player));
            assertEquals(QuestState.QUEST_STATE_UNFINISHED,cleanup.getState());
            assertEquals(QuestState.QUEST_STATE_FINISHED,player.getQuestManager().getQuestById(setup[2]).getState());
            player.quests.events.clear();
            var followup=player.active(setup[5]);
            CalibertQuestReconstruction.onQuestStart(followup); drainQuests();
            assertEquals(QuestState.QUEST_STATE_FINISHED,followup.getState());
            player.quests.events.clear();
            // A real scene initialization emits it; the custom entry action itself does not.
            assertEquals(emu.grasscutter.net.proto.EnterTypeOuterClass.EnterType.EnterType_ENTER_DUNGEON,world.transfers.getLast().getEnterType());
        }
        var wrongScene=player.active(801103);
        CalibertQuestReconstruction.onQuestStart(wrongScene); drainQuests();
        assertEquals(QuestState.QUEST_STATE_UNFINISHED,wrongScene.getState());
    }

    @Test void bothClientProgressRequestsRejectOwnedMechanicsAndStillDeliverOtherTalkAndLuaEvents() throws Exception {
        var session=new GameSession(null) {
            @Override public Player getPlayer() { return player; }
            @Override public void send(BasePacket packet) {}
        };
        for(var pair:List.of(new int[]{QuestContent.QUEST_CONTENT_LUA_NOTIFY.getValue(),801105},
                new int[]{QuestContent.QUEST_CONTENT_ADD_QUEST_PROGRESS.getValue(),801032},
                new int[]{QuestContent.QUEST_CONTENT_ENTER_DUNGEON.getValue(),1175},
                new int[]{QuestContent.QUEST_CONTENT_ENTER_DUNGEON.getValue(),1176},
                new int[]{QuestContent.QUEST_CONTENT_LUA_NOTIFY.getValue(),133302744},
                new int[]{QuestContent.QUEST_CONTENT_LUA_NOTIFY.getValue(),Integer.parseInt(emu.grasscutter.game.quest.war.ReconstructedWarCatalog
                        .installed().stages().stream().filter(s->s.luaKey!=null&&!s.luaKey.isEmpty()).findFirst().orElseThrow().luaKey)})) {
            new HandlerAddQuestContentProgressReq().handle(session,new byte[0],AddQuestContentProgressReq.newBuilder()
                    .setContentType(pair[0]).setParam(pair[1]).build().toByteArray());
            new HandlerAddQuestContentProgressBatchReq().handle(session,new byte[0],_AddQuestContentProgressBatchReq.newBuilder()
                    .addProgressInfoList(_QuestProgressInfo.newBuilder().setContentType(pair[0]).setParam(pair[1])).build().toByteArray());
        }
        drainQuests();
        assertTrue(player.quests.events.isEmpty());
        for(var type:List.of(QuestContent.QUEST_CONTENT_COMPLETE_TALK,QuestContent.QUEST_CONTENT_LUA_NOTIFY)) {
            new HandlerAddQuestContentProgressReq().handle(session,new byte[0],AddQuestContentProgressReq.newBuilder()
                    .setContentType(type.getValue()).setParam(type==QuestContent.QUEST_CONTENT_COMPLETE_TALK?801105:12345).build().toByteArray());
        }
        drainQuests();
        assertEquals(2,player.quests.events.size());
    }

    @Test void aRealInnerRunRestoresBeforeQuestResumeAndCanFinishItsPendingJourney() throws Exception {
        installRealOuterOrigin();
        scripts().load(220180009); player.active(801114); near(220180009,9001);
        select(220180009,9001,7);
        scene=(CalibertScene)player.getScene();
        assertEquals(20178,scene.getId());
        assertFalse(QuestPersistentDungeonRuntime.canRestoreLogin(player),"entry alone must not fabricate a started run");
        scene.getPlayers().add(player); scene.getDungeonManager().startDungeon(); drainQuests();
        assertEquals(QuestState.QUEST_STATE_FINISHED,player.getQuestManager().getQuestById(801114).getState());
        var acknowledgement=player.active(801103); CalibertQuestReconstruction.onQuestStart(acknowledgement); drainQuests();
        assertEquals(QuestState.QUEST_STATE_FINISHED,acknowledgement.getState());
        var journey=player.active(801105);
        scripts().load(220178003); near(220178003,3001); select(220178003,3001,7);
        assertEquals(1,player.progress.getCurrentProgress("asta.calibert.journey.801105"));
        assertEquals(QuestState.QUEST_STATE_UNFINISHED,journey.getState());
        restoreSavedRunIntoFreshLoginScene();
        assertTrue(CalibertQuestReconstruction.shouldPreserveOnLogin(journey));
        assertNull(player.quests.main.resumeOnLogin());
        assertEquals(0,player.quests.rewinds);
        assertEquals(1,player.progress.getCurrentProgress("asta.calibert.journey.801105"));
        assertEquals(QuestState.QUEST_STATE_FINISHED,acknowledgement.getState());
        scripts().load(220178003);
        var avatar=new Avatar() { @Override public Player getPlayer() { return player; } };
        avatar.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP,100);
        var entity=new EntityAvatar(scene,avatar); scene.getEntities().put(entity.getId(),entity);
        scripts().checkRegions(); scripts().drain(); drainQuests();
        assertEquals(QuestState.QUEST_STATE_FINISHED,journey.getState());
        assertFalse(CalibertQuestReconstruction.shouldPreserveOnLogin(journey),"finished stages must not be protected");
    }

    @Test void aRealOuterRunKeepsAnUnfinishedMushroomPickupAcrossTheFullResumeChain() throws Exception {
        installRealOuterOrigin();
        var mushrooms=player.active(801032); CalibertQuestReconstruction.onQuestStart(mushrooms);
        scripts().load(220180008); near(220180008,8001); select(220180008,8001,7);
        assertEquals(1,player.progress.getCurrentProgress("801032"));
        assertEquals(1,player.inventory.getItemCountById(120245));
        restoreSavedRunIntoFreshLoginScene();
        assertTrue(CalibertQuestReconstruction.shouldPreserveOnLogin(mushrooms));
        assertNull(player.quests.main.resumeOnLogin());
        assertEquals(0,player.quests.rewinds);
        assertEquals(1,player.progress.getCurrentProgress("801032"));
        assertEquals(1,player.progress.getCurrentProgress("22018000801"));
        assertEquals(1,player.inventory.getItemCountById(120245));
        scripts().load(220180008); near(220180008,8001); select(220180008,8001,7);
        assertEquals(1,player.inventory.getItemCountById(120245));
        near(220180008,8002); select(220180008,8002,7);
        assertEquals(2,player.inventory.getItemCountById(120245));
        assertEquals(QuestState.QUEST_STATE_FINISHED,mushrooms.getState());
    }

    @Test void restoringCalibertRejectsWrongNestedOriginsOffersCoordinatesAndUnrelatedPlotDungeons() throws Exception {
        installRealOuterOrigin();
        scripts().load(220180009); player.active(801114); near(220180009,9001); select(220180009,9001,7);
        scene=(CalibertScene)player.getScene(); scene.getPlayers().add(player);
        scene.getDungeonManager().startDungeon(); drainQuests();
        var saved=scene.getDungeonManager().capturePersistentProgress();
        assertTrue(QuestPersistentDungeonRuntime.canRestoreLogin(player));
        for(var origin:List.of(new int[]{20181,2},new int[]{20180,4},new int[]{3,2},new int[]{20180,999})) {
            var json=JsonParser.parseString(JsonUtils.encode(saved)).getAsJsonObject();
            json.addProperty("originSceneId",origin[0]); json.addProperty("originPointId",origin[1]);
            var invalid=JsonUtils.decode(json,PersistentDungeonProgress.class);
            player.getPersistentDungeons().update(invalid);
            assertFalse(QuestPersistentDungeonRuntime.canRestoreLogin(player));
            var manager=new DungeonManager(scene,GameData.getDungeonDataMap().get(1175));
            var token=manager.capturePersistentProgress().getClaimRunToken();
            assertFalse(manager.restorePersistentProgress(invalid));
            assertEquals(token,manager.capturePersistentProgress().getClaimRunToken());
        }
        player.getPersistentDungeons().update(saved);
        var validPoint=GameData.getScenePointEntryById(20180,2);
        for(String invalidPoint:List.of(
                "{id:2,$type:'DungeonEntry',pos:{x:1,y:2,z:3},tranPos:{x:1,y:2,z:3},dungeonIds:[1176]}",
                "{id:2,$type:'DungeonEntry',tranPos:{x:1,y:2,z:3},dungeonIds:[1175]}")) {
            GameData.getScenePointEntryMap().put((20180<<16)+2,new ScenePointEntry(20180,JsonUtils.decode(invalidPoint,PointData.class)));
            assertFalse(QuestPersistentDungeonRuntime.canRestoreLogin(player));
        }
        GameData.getScenePointEntryMap().put((20180<<16)+2,validPoint);
        assertTrue(QuestPersistentDungeonRuntime.canRestoreLogin(player));
        var unrelated=JsonUtils.decode("{id:1199,sceneId:20178,type:'DUNGEON_PLOT',involveType:'INVOLVE_ONLY_SINGLE'}",DungeonData.class);
        previousDungeons.put(1199,GameData.getDungeonDataMap().put(1199,unrelated));
        scene.setPrevScene(3); scene.setPrevScenePoint(776);
        scene.setDungeonManager(new DungeonManager(scene,unrelated)); scene.getDungeonManager().startDungeon();
        var foreign=scene.getDungeonManager().capturePersistentProgress();
        player.getPersistentDungeons().update(foreign);
        assertFalse(QuestPersistentDungeonRuntime.canRestoreLogin(player));
        assertFalse(new DungeonManager(scene,unrelated).restorePersistentProgress(foreign));
        assertFalse(CalibertQuestReconstruction.shouldPreserveOnLogin(player.active(801105)));
    }

    @Test void anOuterPlayerMayContinueOnlyAnOwnedRetainedInnerRunAfterBothEntryAcknowledgementsFinished() throws Exception {
        installRealOuterOrigin();
        scripts().load(220180009); player.active(801114); near(220180009,9001); select(220180009,9001,7);
        scene=(CalibertScene)player.getScene(); scene.getPlayers().add(player);
        scene.getDungeonManager().startDungeon(); drainQuests();
        var acknowledgement=player.active(801103); CalibertQuestReconstruction.onQuestStart(acknowledgement); drainQuests();
        player.active(801105);
        assertTrue(QuestPersistentDungeonRuntime.capture(scene));
        scene=(CalibertScene)world.scenes.get(20180); player.setScene(scene); player.setSceneId(20180);
        assertTrue(CalibertQuestReconstruction.canUsePrivateEntry(player,2,1175));
        assertFalse(CalibertQuestReconstruction.canUsePrivateEntry(player,4,1175));
        near(220180009,9001); select(220180009,9001,7);
        assertEquals(20178,player.getSceneId());
        assertTrue(player.getScene().getDungeonManager().isPersistentProgressRestored());
        assertEquals(QuestState.QUEST_STATE_FINISHED,acknowledgement.getState());
        player.getPersistentDungeons().clear(20178);
        scene=(CalibertScene)world.scenes.get(20180); player.setScene(scene); player.setSceneId(20180);
        assertFalse(CalibertQuestReconstruction.canUsePrivateEntry(player,2,1175));
    }

    @Test void nativeAndReconstructedOuterEntrancesEmitEvidenceOnlyAfterRealStartAndBeforeAcceptingTheFailSensitiveController() throws Exception {
        var overworld=new CalibertScene(world,3); world.scenes.put(3,overworld);
        previousScenes.put(3,GameData.getSceneDataMap().put(3,TestWorld.sceneData(3,SceneType.SCENE_WORLD)));
        var points=JsonParser.parseString(Files.readString(Path.of("resources/BinOutput/Scene/Point/scene3_point.json"))).getAsJsonObject().getAsJsonObject("points");
        var point=JsonUtils.decode(points.get("776").toString(),PointData.class); point.setId(776);
        previousPoints.put((3<<16)+776,GameData.getScenePointEntryMap().put((3<<16)+776,new ScenePointEntry(3,point)));
        for(boolean nativeEntry:List.of(true,false)) {
            player.setScene(overworld); player.setSceneId(3); player.getPosition().set(point.getPos());
            player.active(801005); // An actual Calibert main quest permits the reconstruction's hidden controller.
            var controller=player.active(801301); controller.setState(QuestState.QUEST_STATE_UNSTARTED);
            var next=player.active(801302); next.setState(QuestState.QUEST_STATE_UNSTARTED);
            player.getQuestManager().getAcceptProgressLists().remove(801302);
            player.progress.resetCurrentProgress("133302744"); player.quests.events.clear();
            if(nativeEntry) {
                var session=new GameSession(null) {
                    @Override public Player getPlayer() { return player; }
                    @Override public GameServer getServer() { return player.server; }
                    @Override public void send(BasePacket packet) {}
                };
                new HandlerPlayerEnterDungeonReq().handle(session,new byte[0],PlayerEnterDungeonReq.newBuilder()
                        .setDungeonId(1177).setPointId(776).build().toByteArray());
            } else {
                scripts().load(133302744); near(133302744,744001); select(133302744,744001,7);
            }
            assertEquals(20180,player.getSceneId());
            assertEquals(0,player.quests.notified("133302744"));
            var entered=player.getScene(); entered.getPlayers().clear(); entered.getPlayers().add(player);
            entered.getDungeonManager().startDungeon(); drainQuests();
            assertEquals(1,player.quests.notified("133302744"));
            assertEquals(QuestState.QUEST_STATE_FINISHED,controller.getState());
            assertEquals(QuestState.QUEST_STATE_UNFINISHED,next.getState());
            assertEquals(0,next.getFailProgressList()[0]);
            int firstEnter=-1,lua=-1;
            for(int i=0;i<player.quests.events.size();i++) {
                var event=player.quests.events.get(i);
                if(event.type==QuestContent.QUEST_CONTENT_ENTER_DUNGEON && firstEnter<0) firstEnter=i;
                if(event.type==QuestContent.QUEST_CONTENT_LUA_NOTIFY && event.key.equals("133302744")) lua=i;
            }
            assertTrue(firstEnter>=0 && lua>firstEnter);
            entered.getPlayers().clear();
        }
    }

    @Test void originalPrivatePointRequestsKeepTheSameRealEntryAndSourceSceneGuards() throws Exception {
        for(var setup:List.of(new int[]{2,1175,801114,20178,801401},new int[]{4,1176,801222,20181,801501})) {
            scene=(CalibertScene)world.scenes.get(20180); player.setScene(scene); player.setSceneId(20180);
            scene.setDungeonManager(new DungeonManager(scene,GameData.getDungeonDataMap().get(1177)));
            player.active(setup[2]);
            player.active(801301).setState(QuestState.QUEST_STATE_FINISHED);
            player.active(801302).setState(QuestState.QUEST_STATE_UNSTARTED);
            player.active(setup[4]).setState(QuestState.QUEST_STATE_UNSTARTED);
            player.getPosition().set(GameData.getScenePointEntryById(20180,setup[0]).getPointData().getPos());
            var session=new GameSession(null) {
                @Override public Player getPlayer() { return player; }
                @Override public GameServer getServer() { return player.server; }
                @Override public void send(BasePacket packet) {}
            };
            var request=PlayerEnterDungeonReq.newBuilder().setPointId(setup[0]).setDungeonId(setup[1]).build().toByteArray();
            new HandlerPlayerEnterDungeonReq().handle(session,new byte[0],request);
            assertEquals(20180,player.getSceneId()); // An unstarted shell cannot use the nested-entry exception.
            scene.getPlayers().clear(); scene.getPlayers().add(player); scene.getDungeonManager().startDungeon(); drainQuests();
            new HandlerPlayerEnterDungeonReq().handle(session,new byte[0],PlayerEnterDungeonReq.newBuilder()
                    .setPointId(setup[0]==2?4:2).setDungeonId(setup[1]).build().toByteArray());
            assertEquals(20180,player.getSceneId()); // The other original point does not offer this dungeon.
            new HandlerPlayerEnterDungeonReq().handle(session,new byte[0],request);
            assertEquals(setup[3],player.getSceneId());
            assertEquals(setup[1],player.getScene().getDungeonManager().getDungeonData().getId());
            var target=player.getScene(); target.getPlayers().clear(); target.getPlayers().add(player);
            target.getDungeonManager().startDungeon(); drainQuests();
            assertEquals(QuestState.QUEST_STATE_FINISHED,player.getQuestManager().getQuestById(setup[2]).getState());
            assertEquals(QuestState.QUEST_STATE_UNFINISHED,player.getQuestManager().getQuestById(setup[4]).getState());
            target.getPlayers().clear();
        }
    }
}
