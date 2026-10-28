package emu.grasscutter.game.quest.reconstruction;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.grasscutter.*;
import emu.grasscutter.data.*;
import emu.grasscutter.data.binout.MainQuestData;
import emu.grasscutter.data.binout.config.ConfigEntityGadget;
import emu.grasscutter.data.binout.config.ConfigEntityMonster;
import emu.grasscutter.data.binout.AbilityData;
import emu.grasscutter.data.server.MonsterMapping;
import emu.grasscutter.data.excels.monster.MonsterData;
import emu.grasscutter.data.excels.GadgetData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.entity.gadget.GadgetWorktop;
import emu.grasscutter.game.icecreature.*;
import emu.grasscutter.game.player.*;
import emu.grasscutter.game.ability.AbilityManager;
import emu.grasscutter.game.battlepass.BattlePassManager;
import emu.grasscutter.game.props.*;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.*;
import emu.grasscutter.game.world.*;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.IceCreatureBuildReqOuterClass.IceCreatureBuildReq;
import emu.grasscutter.net.proto.IceCreatureGadgetRemoveReqOuterClass.IceCreatureGadgetRemoveReq;
import emu.grasscutter.net.proto.SelectWorktopOptionReqOuterClass.SelectWorktopOptionReq;
import emu.grasscutter.net.proto.EvtCreateGadgetNotifyOuterClass.EvtCreateGadgetNotify;
import emu.grasscutter.net.proto.PropChangeReasonOuterClass.PropChangeReason;
import emu.grasscutter.net.proto.ChangHpReasonOuterClass.ChangHpReason;
import emu.grasscutter.scripts.*;
import emu.grasscutter.scripts.data.ScriptArgs;
import emu.grasscutter.server.game.*;
import emu.grasscutter.server.packet.recv.HandlerSelectWorktopOptionReq;
import emu.grasscutter.utils.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import sun.misc.Unsafe;

@ExtendWith(ServerResourceFixture.class)
@ResourceLock("GameData")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
final class SnezhnayaIceQuestReconstructionTest {
    private final Map<Integer,QuestData> oldQuests = new HashMap<>();
    private final Map<Integer,GadgetData> oldGadgets = new HashMap<>();
    private final Map<Integer,MonsterData> oldMonsters = new HashMap<>();
    private final Map<Integer,MonsterMapping> oldMappings = new HashMap<>();
    private final Map<String,ConfigEntityMonster> oldMonsterConfigs = new HashMap<>();
    private final Map<String,AbilityData> oldAbilities = new HashMap<>();
    private final Map<Integer,MainQuestData> oldBossMains = new HashMap<>();
    private MainQuestData oldMain;
    private ConfigEntityGadget oldMineConfig;
    private boolean scripts;
    private IcePlayer player;

    @BeforeEach void fixtures() throws Exception {
        ScriptLoader.init();
        scripts=Grasscutter.getConfig().server.game.enableScriptInBigWorld;
        Grasscutter.getConfig().server.game.enableScriptInBigWorld=false;
        oldMain=GameData.getMainQuestDataMap().put(7011,JsonUtils.decode("{id:7011,subQuests:[]}",MainQuestData.class));
        var original=JsonParser.parseString(Files.readString(FileUtils.getResourcePath("BinOutput/Quest/7011.json"))).getAsJsonObject();
        for(int id:new int[]{701104,701114,701116,701118,701122,701136}) {
            var json=java.util.stream.StreamSupport.stream(original.getAsJsonArray("subQuests").spliterator(),false)
                    .map(JsonElement::getAsJsonObject).filter(o->o.get("subId").getAsInt()==id).findFirst().orElseThrow().deepCopy();
            for(String field:List.of("acceptCond","failCond","beginExec","finishExec","failExec"))
                if(!json.has(field))json.add(field,new JsonArray());
            // QuestData.onLoad installs LOGIC_NONE for omitted native combinators.
            if(!json.has("finishCondComb"))json.addProperty("finishCondComb","LOGIC_NONE");
            oldQuests.put(id,GameData.getQuestDataMap().put(id,JsonUtils.decode(json,QuestData.class)));
        }
        for(int id:new int[]{70291631,70360001,73094010,73094183,73168001,73094084,73094121})
            oldGadgets.put(id,GameData.getGadgetDataMap().put(id,JsonUtils.decode("{id:"+id+",type:'Worktop',jsonName:'TestIceQuest',tags:[]}",GadgetData.class)));
        String mineName="SceneObj_Area_Zd_Property_Ani_Prop_KresnikMine_01";
        var worldConfigs=JsonParser.parseString(Files.readString(FileUtils.getResourcePath("BinOutput/Gadget/ConfigGadget_Scene_V7_0_World.json"))).getAsJsonObject();
        oldMineConfig=GameData.getGadgetConfigData().put(mineName,JsonUtils.decode(worldConfigs.get(mineName),ConfigEntityGadget.class));
        GameData.getGadgetDataMap().put(73094084,JsonUtils.decode("{id:73094084,type:'Gadget',jsonName:'"+mineName+"',tags:[]}",GadgetData.class));
        player=new IcePlayer();
        player.setUid(89501);
        var world=TestWorld.create(player);
        player.setScene(new IceScene(world));
        player.setSceneLoadState(Player.SceneLoadState.LOADED);
        var unsafeField=Unsafe.class.getDeclaredField("theUnsafe");unsafeField.setAccessible(true);
        player.server=(GameServer)((Unsafe)unsafeField.get(null)).allocateInstance(GameServer.class);
        var qs=GameServer.class.getDeclaredField("questSystem");qs.setAccessible(true);qs.set(player.server,new QuestSystem(player.server));
        player.main=new GameMainQuest(player,7011){@Override public void tryFailSubQuests(QuestContent t,String s,int...p){}};
        player.manager.getMainQuests().put(7011,player.main);
    }

    @AfterEach void restore() throws Exception {
        StoryReconstructionRuntime.capture(player);
        flush();
        oldQuests.forEach((id,data)->{if(data==null)GameData.getQuestDataMap().remove(id);else GameData.getQuestDataMap().put(id,data);});
        oldGadgets.forEach((id,data)->{if(data==null)GameData.getGadgetDataMap().remove(id);else GameData.getGadgetDataMap().put(id,data);});
        if(oldMain==null)GameData.getMainQuestDataMap().remove(7011);else GameData.getMainQuestDataMap().put(7011,oldMain);
        if(oldMineConfig==null)GameData.getGadgetConfigData().remove("SceneObj_Area_Zd_Property_Ani_Prop_KresnikMine_01");
        else GameData.getGadgetConfigData().put("SceneObj_Area_Zd_Property_Ani_Prop_KresnikMine_01",oldMineConfig);
        oldMonsters.forEach((id,data)->{if(data==null)GameData.getMonsterDataMap().remove(id);else GameData.getMonsterDataMap().put(id,data);});
        oldMappings.forEach((id,data)->{if(data==null)GameData.getMonsterMappingMap().remove(id);else GameData.getMonsterMappingMap().put(id,data);});
        oldMonsterConfigs.forEach((id,data)->{if(data==null)GameData.getMonsterConfigData().remove(id);else GameData.getMonsterConfigData().put(id,data);});
        oldAbilities.forEach((id,data)->{if(data==null)GameData.getAbilityDataMap().remove(id);else GameData.getAbilityDataMap().put(id,data);});
        oldBossMains.forEach((id,data)->{if(data==null)GameData.getMainQuestDataMap().remove(id);else GameData.getMainQuestDataMap().put(id,data);});
        Grasscutter.getConfig().server.game.enableScriptInBigWorld=scripts;
    }

    @Test @Order(1) void coldNativeEventAndRescueRequireActualNearbySessionWorktopAndRejectBareCounter() throws Exception {
        var quest=activate(701104,true);
        var trap=gadget(133700006,6016,70291631,new Position(7191.207f,208.291f,8575.295f));
        player.getPosition().set(trap.getPosition());tick();
        player.progress.addToCurrentProgress("Q70110401",99);
        player.manager.queueEvent(QuestContent.QUEST_CONTENT_LUA_NOTIFY,"Q70110401",0);flush();
        assertEquals(QuestState.QUEST_STATE_UNFINISHED,quest.getState());
        player.getPosition().addX(10);select(trap);flush();
        assertFalse(player.state.get(701104).isComplete());
        player.getPosition().set(trap.getPosition());select(trap);flush();
        assertEquals(QuestState.QUEST_STATE_FINISHED,quest.getState());
    }

    @Test void realRemoveRequestMustRemoveTheAuthoredObstacleAndRelogRestoresItsIdentity() throws Exception {
        var quest=activate(701114,true);
        player.getPosition().set(7510.101f,207.910f,8305.221f);tick();
        assertEquals(1,player.records.size());
        var obstacle=player.records.getFirst();
        assertEquals(701114,obstacle.getSourceQuestId());assertEquals(0,obstacle.getCost());
        var guid=obstacle.getGuid();
        player.scene().getEntities().clear();
        player.records=JsonUtils.loadToList(new java.io.StringReader(new Gson().toJson(player.records)),IceCreatureRecord.class);
        player.state=JsonUtils.decode(JsonUtils.encode(player.state),StoryReconstructionState.class);
        tick();
        obstacle=player.records.getFirst();
        assertEquals(guid,obstacle.getGuid());assertNotNull(player.getScene().getEntityById(obstacle.getEntityId()));
        IceCreatureSystem.onRemove(player,IceCreatureGadgetRemoveReq.newBuilder().setSceneId(3)
                .addEntityIdList(obstacle.getEntityId()).build());flush();
        assertTrue(player.records.isEmpty());
        assertEquals(QuestState.QUEST_STATE_FINISHED,quest.getState());
        assertEquals(1,player.progress.getCurrentProgress("Q70111401"));
    }

    @Test void gateNeedsANewRealCubeAndRaisesBothOriginalDoorPlatformsOnlyOnce() throws Exception {
        var quest=activate(701116,true);
        player.getPosition().set(7530.195f,204.210f,8326.852f);
        var low=gadget(133700011,11002,73094183,new Position(7530.748f,204.510f,8326.253f));
        var high=gadget(133700011,11011,73094183,new Position(7530.748f,205.669f,8326.253f));
        tick();var control=operator(701116);select(control);flush();
        assertEquals(QuestState.QUEST_STATE_UNFINISHED,quest.getState());
        buildCube(player.getPosition().clone());select(control);flush();
        assertTrue(player.state.get(701116).isComplete());
        assertEquals(QuestState.QUEST_STATE_FINISHED,quest.getState());
        assertEquals(low.getBornPos().getY()+3,low.getPosition().getY(),0.001);
        assertEquals(high.getBornPos().getY()+3,high.getPosition().getY(),0.001);
        tick();assertEquals(low.getBornPos().getY()+3,low.getPosition().getY(),0.001);
    }

    @Test void blueprintNeedsConstructionThenPhysicalPickupAndRestoresConstructionProof() throws Exception {
        var quest=activate(701118,true);
        var drawing=gadget(133700021,21008,73168001,new Position(7566.945f,216.624f,8358.829f));
        player.getPosition().set(drawing.getPosition());tick();select(operator(701118));flush();
        assertEquals(QuestState.QUEST_STATE_UNFINISHED,quest.getState());
        buildCube(drawing.getPosition().clone().addY(-2));
        player.state=JsonUtils.decode(JsonUtils.encode(player.state),StoryReconstructionState.class);
        player.getPosition().addY(-6);select(operator(701118));flush();
        assertEquals(QuestState.QUEST_STATE_UNFINISHED,quest.getState());
        player.getPosition().set(drawing.getPosition());select(operator(701118));flush();
        assertEquals(QuestState.QUEST_STATE_FINISHED,quest.getState());
        assertNull(player.getScene().getEntityById(drawing.getId()));
    }

    @Test void invincibleNativeOreNeedsThreeOwnedHitsAcrossRelogAndInvestigationStillNeedsNativePlot() throws Exception {
        var oreQuest=activate(701122,true);
        var ore=new EntityGadget(player.getScene(),73094084,new Position(7607.093f,235.085f,8268.915f),new Position());
        ore.setGroupId(133700014);ore.setConfigId(14001);player.getScene().addEntity(ore);
        assertTrue(ore.getConfigGadget().getCombat().getProperty().isInvincible());
        assertEquals(Float.POSITIVE_INFINITY,ore.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP));
        player.getPosition().set(ore.getPosition());
        SnezhnayaIceQuestReconstruction.onGadgetDeath(ore);flush();
        assertEquals(QuestState.QUEST_STATE_UNFINISHED,oreQuest.getState());
        ore.damage(1000);flush(); // An anonymous hit cannot acquire the private quest's proof.
        assertEquals(QuestState.QUEST_STATE_UNFINISHED,oreQuest.getState());
        var strike=new EntityClientGadget(player.getScene(),player,EvtCreateGadgetNotify.newBuilder()
                .setEntityId(player.getWorld().getNextEntityId(EntityIdType.GADGET)).setConfigId(70360001)
                .setInitPos(player.getPosition().toProto()).build());
        player.getScene().addEntity(strike);
        for(int i=0;i<2;i++)ore.damage(1000,strike.getId(),ElementType.None,
                PropChangeReason.PropChangeReason_PROP_CHANGE_ABILITY,ChangHpReason.ChangHpReason_CHANGE_HP_SUB_ABILITY);
        flush();assertEquals(QuestState.QUEST_STATE_UNFINISHED,oreQuest.getState());
        player.state=JsonUtils.decode(JsonUtils.encode(player.state),StoryReconstructionState.class);
        ore.damage(1000,strike.getId(),ElementType.None,
                PropChangeReason.PropChangeReason_PROP_CHANGE_ABILITY,ChangHpReason.ChangHpReason_CHANGE_HP_SUB_ABILITY);flush();
        assertEquals(QuestState.QUEST_STATE_FINISHED,oreQuest.getState());
        assertNull(player.getScene().getEntityById(ore.getId()));
        assertTrue(ore.getConfigGadget().getCombat().getProperty().isInvincible());
        var inspectQuest=activate(701136,true);
        player.getPosition().set(7511.360f,202.638f,8357.278f);tick();select(operator(701136));flush();
        assertEquals(1,player.progress.getCurrentProgress("Q70113601"));
        assertEquals(QuestState.QUEST_STATE_UNFINISHED,inspectQuest.getState());
        player.manager.queueEvent(QuestContent.QUEST_CONTENT_FINISH_PLOT,701136);flush();
        assertEquals(QuestState.QUEST_STATE_FINISHED,inspectQuest.getState());
    }

    @Test void guestCannotOperateOwnersRescueAndWrongCubeOrFarBuildDoesNotQualify() throws Exception {
        activate(701116,true);player.getPosition().set(7530.195f,204.210f,8326.852f);tick();
        buildCube(player.getPosition().clone().addX(20));
        assertTrue(player.state.get(701116).getSwitches().isEmpty());
        var record=new IceCreatureRecord();record.setType(1);record.setSourceQuestId(0);
        record.setSceneId(3);record.setGadgetId(73094010);record.setPos(player.getPosition());
        var entity=gadget(0,0,73094010,player.getPosition());record.setEntityId(entity.getId());
        SnezhnayaIceQuestReconstruction.onBuilt(player,record);
        assertTrue(player.state.get(701116).getSwitches().isEmpty());
        var guest=new IcePlayer();guest.setUid(89502);guest.setWorld(player.getWorld());guest.setScene(player.getScene());
        guest.setSceneLoadState(Player.SceneLoadState.LOADED);guest.getPosition().set(player.getPosition());
        assertTrue(SnezhnayaIceQuestReconstruction.tryInteract(guest,operator(701116),7));
        assertFalse(player.state.get(701116).isComplete());
    }

    @Test void realBossConfigsUseExplicitClientModelsAndAbilitiesWhileOriginalHpAndDeathCompleteEachBattle() throws Exception {
        var ids=Set.of(29090102,26260101,25010301,25010201);
        for(var row:JsonParser.parseString(Files.readString(FileUtils.getExcelPath("MonsterExcelConfigData.json"))).getAsJsonArray()) {
            if(!ids.contains(row.getAsJsonObject().get("id").getAsInt()))continue;
            var data=JsonUtils.decode(row,MonsterData.class);data.onLoad();
            oldMonsters.put(data.getId(),GameData.getMonsterDataMap().put(data.getId(),data));
        }
        for(var mapping:JsonUtils.loadToList(FileUtils.getResourcePath("Server/MonsterMapping.json"),MonsterMapping.class)) {
            if(!ids.contains(mapping.getMonsterId()))continue;
            oldMappings.put(mapping.getMonsterId(),GameData.getMonsterMappingMap().put(mapping.getMonsterId(),mapping));
            String name=mapping.getMonsterJson();
            var config=JsonUtils.loadToClass(FileUtils.getResourcePath("BinOutput/Monster/"+name+".json"),ConfigEntityMonster.class);
            oldMonsterConfigs.put(name,GameData.getMonsterConfigData().put(name,config));
        }
        var wanted=new HashSet<String>();
        for(int id:ids)for(var ability:GameData.getMonsterConfigData().get(GameData.getMonsterMappingMap().get(id).getMonsterJson()).getAbilities())
            wanted.add(ability.abilityName);
        for(String file:List.of("ConfigAbility_Monster_Thoarder_Common.json","ConfigAbility_Monster_Thoarder_Male_NoWeapon.json")) {
            for(var wrapper:JsonUtils.loadToListLenient(FileUtils.getResourcePath("BinOutput/Ability/Temp/MonsterAbilities/"+file),AbilityWrapper.class))
                if(wanted.contains(wrapper.Default.abilityName))oldAbilities.put(wrapper.Default.abilityName,
                        GameData.getAbilityDataMap().put(wrapper.Default.abilityName,wrapper.Default));
        }
        for(int questId:new int[]{402415,7500509}) {
            StoryReconstructionRuntime.capture(player);
            var stage=StoryReconstructionCatalog.installed().stage(questId);assertNotNull(stage);
            int originalId=stage.getWaves().getFirst().getFirst();
            int clientId=questId==402415?25010301:25010201;
            var originalConfig=GameData.getMonsterConfigData().get(GameData.getMonsterMappingMap().get(originalId).getMonsterJson());
            assertTrue(originalConfig.getAbilities().stream().anyMatch(a->a.abilityName.contains(questId==402415?"Ptahur":"Wayob")));
            player.setScene(new IceScene(player.getWorld(),stage.getSceneId()));
            player.team.setEntity(new EntityTeam(player));
            player.getScene().getPlayers().add(player);
            player.getPosition().set(stage.getPosition());
            int parent=questId/100;
            oldBossMains.put(parent,GameData.getMainQuestDataMap().put(parent,JsonUtils.decode("{id:"+parent+",subQuests:[]}",MainQuestData.class)));
            var source=JsonParser.parseString(Files.readString(FileUtils.getResourcePath("BinOutput/Quest/"+parent+".json"))).getAsJsonObject();
            var row=java.util.stream.StreamSupport.stream(source.getAsJsonArray("subQuests").spliterator(),false)
                    .map(JsonElement::getAsJsonObject).filter(o->o.get("subId").getAsInt()==questId).findFirst().orElseThrow().deepCopy();
            for(String field:List.of("acceptCond","failCond","beginExec","finishExec","failExec"))
                if(!row.has(field))row.add(field,new JsonArray());
            if(!row.has("finishCondComb"))row.addProperty("finishCondComb","LOGIC_NONE");
            oldQuests.put(questId,GameData.getQuestDataMap().put(questId,JsonUtils.decode(row,QuestData.class)));
            var kills=new ArrayList<Integer>();
            var main=new GameMainQuest(player,parent){
                @Override public void tryFailSubQuests(QuestContent t,String s,int...p){}
                @Override public void tryFinishSubQuests(QuestContent t,String s,int...p){
                    if(t==QuestContent.QUEST_CONTENT_KILL_MONSTER)kills.add(p[0]);
                    super.tryFinishSubQuests(t,s,p);
                }
            };
            player.manager.getMainQuests().put(parent,main);
            var quest=new GameQuest(main,GameData.getQuestDataMap().get(questId)){
                @Override public void finish(){setState(QuestState.QUEST_STATE_FINISHED);}
                @Override public void save(){}
            };
            main.getChildQuests().put(questId,quest);quest.setState(QuestState.QUEST_STATE_UNFINISHED);
            StoryReconstructionRuntime.onQuestStart(quest);StoryReconstructionRuntime.onTick(player);
            var monster=(ReconstructedStoryMonster)player.getScene().getEntityByConfigId(stage.configId(1),stage.getGroupId());
            assertNotNull(monster);
            assertEquals(originalId,monster.getMonsterData().getId());
            assertEquals(clientId,monster.toProto().getMonster().getMonsterId());
            assertTrue(monster.toProto().getMonster().getAffixListList().isEmpty());
            var modelConfig=GameData.getMonsterConfigData().get(GameData.getMonsterMappingMap().get(clientId).getMonsterJson());
            assertSame(modelConfig,monster.getConfigEntityMonster());
            assertFalse(monster.getInstancedAbilities().isEmpty());
            assertTrue(monster.getInstancedAbilities().stream().allMatch(a->a.getData().abilityName.startsWith("Thoarder_")));
            assertEquals(GameData.getMonsterDataMap().get(originalId).getBaseHp(),monster.getFightProperty(FightProperty.FIGHT_PROP_MAX_HP),0.001);
            monster.damage(100000); // Anonymous damage cannot end a private story fight.
            assertFalse(monster.isDead());
            var strike=new EntityClientGadget(player.getScene(),player,EvtCreateGadgetNotify.newBuilder()
                    .setEntityId(player.getWorld().getNextEntityId(EntityIdType.GADGET)).setConfigId(70360001)
                    .setInitPos(player.getPosition().toProto()).build());player.getScene().addEntity(strike);
            float hp=monster.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP);
            monster.damage(hp/2,strike.getId(),ElementType.None,PropChangeReason.PropChangeReason_PROP_CHANGE_ABILITY,ChangHpReason.ChangHpReason_CHANGE_HP_SUB_ABILITY);
            StoryReconstructionRuntime.onTick(player);flush();
            assertFalse(monster.isDead());assertEquals(QuestState.QUEST_STATE_UNFINISHED,quest.getState());
            monster.damage(hp*2,strike.getId(),ElementType.None,PropChangeReason.PropChangeReason_PROP_CHANGE_ABILITY,ChangHpReason.ChangHpReason_CHANGE_HP_SUB_ABILITY);
            StoryReconstructionRuntime.onTick(player);flush();
            assertTrue(monster.isDead());assertEquals(QuestState.QUEST_STATE_FINISHED,quest.getState());
            assertTrue(kills.contains(originalId));
            assertSame(originalConfig,GameData.getMonsterConfigData().get(GameData.getMonsterMappingMap().get(originalId).getMonsterJson()));
        }
    }

    private static final class AbilityWrapper {AbilityData Default;}

    private GameQuest activate(int id,boolean fresh) {
        var q=new GameQuest(player.main,GameData.getQuestDataMap().get(id)){
            @Override public void finish(){setState(QuestState.QUEST_STATE_FINISHED);}
            @Override public void save(){}
        };
        player.main.getChildQuests().put(id,q);q.setState(QuestState.QUEST_STATE_UNFINISHED);
        if(fresh)SnezhnayaIceQuestReconstruction.onQuestStart(q);return q;
    }
    private void tick(){SnezhnayaIceQuestReconstruction.onTick(player);}
    private EntityGadget operator(int quest){return (EntityGadget)player.getScene().getEntityByConfigId(quest,901700011);}
    private EntityGadget gadget(int group,int config,int id,Position pos) {
        var g=new EntityGadget(player.getScene(),id,pos,new Position());g.setGroupId(group);g.setConfigId(config);
        g.replaceContent(new GadgetWorktop(g));g.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP,100);
        g.setFightProperty(FightProperty.FIGHT_PROP_MAX_HP,100);player.getScene().addEntity(g);return g;
    }
    private void buildCube(Position position) {
        IceCreatureSystem.onBuild(player,IceCreatureBuildReq.newBuilder().setGadgetId(73094010)
                .setPos(position.toProto()).build());
    }
    private void select(EntityGadget g)throws Exception {
        new HandlerSelectWorktopOptionReq().handle(new GameSession(null){
            @Override public Player getPlayer(){return player;}
            @Override public void send(BasePacket packet){}
        },new byte[0],SelectWorktopOptionReq.newBuilder().setGadgetEntityId(g.getId()).setOptionId(7).build().toByteArray());
    }
    private static void flush()throws Exception {QuestManager.eventExecutor.submit(()->{}).get(5,TimeUnit.SECONDS);}
    private static final class IcePlayer extends TestWorld.TestPlayer {
        StoryReconstructionState state=new StoryReconstructionState();
        List<IceCreatureRecord> records=new ArrayList<>();
        final PlayerProgress progress=new PlayerProgress();
        final QuestManager manager=new QuestManager(this);
        GameMainQuest main;GameServer server;
        final AbilityManager abilities=new AbilityManager(this);
        final TeamManager team=new TeamManager(this);
        final BattlePassManager battlePass=new BattlePassManager(this){
            @Override public void triggerMission(WatcherTriggerType type,int parameter,int count){}
        };
        @Override public BattlePassManager getBattlePassManager(){return battlePass;}
        @Override public AbilityManager getAbilityManager(){return abilities;}
        @Override public TeamManager getTeamManager(){return team;}
        @Override public StoryReconstructionState getStoryReconstruction(){return state;}
        @Override public PlayerProgress getPlayerProgress(){return progress;}
        @Override public QuestManager getQuestManager(){return manager;}
        @Override public List<IceCreatureRecord> getIceCreatures(){return records;}
        @Override public GameServer getServer(){return server;}
        @Override public void save(){}
        IceScene scene(){return (IceScene)getScene();}
    }
    private static final class IceScene extends TestWorld.TestScene {
        SceneScriptManager scripts;
        IceScene(World world){this(world,3);}
        IceScene(World world,int scene){super(world,scene,scene==3?SceneType.SCENE_WORLD:SceneType.SCENE_DUNGEON);}
        @Override public SceneScriptManager getScriptManager(){
            if(scripts==null)scripts=new SceneScriptManager(this){
                @Override public Future<?> callEvent(ScriptArgs args){return CompletableFuture.completedFuture(null);}
            };return scripts;
        }
        @Override public synchronized void addEntity(GameEntity entity){getEntities().put(entity.getId(),entity);}
        @Override public void broadcastPacket(BasePacket packet){}
        @Override public void killEntity(GameEntity entity,int killer){getEntities().remove(entity.getId());entity.onDeath(killer);}
        @Override public void saveGroups(){}
    }
}
