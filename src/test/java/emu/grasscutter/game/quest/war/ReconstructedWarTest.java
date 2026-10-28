package emu.grasscutter.game.quest.war;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.MainQuestData;
import emu.grasscutter.data.excels.GadgetData;
import emu.grasscutter.data.excels.dungeon.DungeonData;
import emu.grasscutter.data.excels.monster.MonsterData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.dungeons.DungeonManager;
import emu.grasscutter.game.dungeons.DungeonSystem;
import emu.grasscutter.game.dungeons.enums.DungeonPassConditionType;
import emu.grasscutter.game.avatar.Avatar;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.player.TeamManager;
import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.entity.gadget.GadgetWorktop;
import emu.grasscutter.game.props.*;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.*;
import emu.grasscutter.game.world.*;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.server.game.GameServer;
import emu.grasscutter.scripts.*;
import emu.grasscutter.scripts.constants.EventType;
import emu.grasscutter.scripts.data.*;
import emu.grasscutter.utils.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import sun.misc.Unsafe;

final class ReconstructedWarTest {
    private boolean scripts;
    private final List<Runnable> cleanup = new ArrayList<>();
    private final List<WarScene> scenes = new ArrayList<>();
    private WarPlayer player;

    @BeforeEach void setup() throws Exception {
        ScriptLoader.init();
        scripts = Grasscutter.getConfig().server.game.enableScriptInBigWorld;
        // Test the real scene/Lua/death pipeline without asynchronously running unrelated quests.
        Grasscutter.getConfig().server.game.enableScriptInBigWorld = false;
        player = new WarPlayer(); player.setUid(42);
        install(GameData.getSceneDataMap(),3,TestWorld.sceneData(3,SceneType.SCENE_WORLD));
        for (var stage : catalog().stages()) for (var enemy : stage.enemies()) {
            if (GameData.getMonsterDataMap().containsKey(enemy.monsterId)) continue;
            var data=JsonUtils.decode("{id:"+enemy.monsterId+",type:'MONSTER_ORDINARY',hpBase:100,"
                    +"attackBase:1,defenseBase:0,affix:[],equips:[],hpDrops:[],propGrowCurves:[]}",MonsterData.class);
            data.onLoad(); install(GameData.getMonsterDataMap(),enemy.monsterId,data);
        }
        for (var data : JsonUtils.loadToList(FileUtils.getExcelPath("GadgetExcelConfigData.json"),GadgetData.class))
            if (data.getId()==70360001 || data.getId()==70710704) install(GameData.getGadgetDataMap(),data.getId(),data);
    }

    @AfterEach void restore() throws Exception {
        for (var scene : scenes) scene.getScriptManager().onDestroy();
        QuestManager.eventExecutor.submit(() -> {}).get(5,TimeUnit.SECONDS);
        Collections.reverse(cleanup); cleanup.forEach(Runnable::run);
        Grasscutter.getConfig().server.game.enableScriptInBigWorld = scripts;
    }

    private <T> void install(Map<Integer,T> map,int id,T value) {
        var old=map.put(id,value); cleanup.add(() -> {if(old==null)map.remove(id);else map.put(id,old);});
    }
    private static ReconstructedWarCatalog catalog() { return ReconstructedWarCatalog.installed(); }
    private static final class WarPlayer extends TestWorld.TestPlayer {
        QuestWarState war = new QuestWarState(); int saves;
        final GameServer server = createServer();
        final TeamManager team = new TeamManager(this);
        @Override public TeamManager getTeamManager() { return team; }
        @Override public GameServer getServer() { return server; }
        @Override public QuestWarState getQuestWar() { return war; }
        @Override public void save() { saves++; }
    }
    private static GameServer createServer() {
        try {
            var field=Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);
            var server=(GameServer)((Unsafe)field.get(null)).allocateInstance(GameServer.class);
            var quests=GameServer.class.getDeclaredField("questSystem");quests.setAccessible(true);quests.set(server,new QuestSystem(server));
            var dungeons=GameServer.class.getDeclaredField("dungeonSystem");dungeons.setAccessible(true);dungeons.set(server,new DungeonSystem(server));
            return server;
        } catch(ReflectiveOperationException error) {throw new AssertionError(error);}
    }
    private static final class SavedMain extends GameMainQuest {
        SavedMain(WarPlayer owner,int id){super(owner,id);}
        @Override public void save() {}
    }
    private static final class WarScene extends Scene {
        private final WarManager manager;
        WarScene(WarPlayer owner) throws Exception {
            super(TestWorld.create(owner),TestWorld.sceneData(20238,SceneType.SCENE_DUNGEON));
            manager=new WarManager(this,owner);
            var ready=new CountDownLatch(1);manager.whenInitialized(ok -> ready.countDown());
            assertTrue(ready.await(5,TimeUnit.SECONDS));assertTrue(manager.isInit());
            setDungeonManager(new DungeonManager(this,JsonUtils.decode(
                    "{id:1222,sceneId:20238,passCond:0,type:'DUNGEON_BIGWORLD_MIRROR',involveType:'INVOLVE_ONLY_SINGLE'}",DungeonData.class)));
            owner.setScene(this); owner.setSceneId(20238); owner.setSceneLoadState(emu.grasscutter.game.player.Player.SceneLoadState.LOADED);
        }
        @Override public SceneScriptManager getScriptManager(){return manager;}
        @Override public void broadcastPacket(BasePacket packet){}
        @Override public void saveGroups(){}
        @Override public void triggerDungeonEvent(DungeonPassConditionType type,int... values){}
        @Override public void unloadGroup(SceneBlock block,int id) {
            var group=manager.groups.get(id);
            getEntities().values().stream().filter(e->e.getGroupId()==id).toList().forEach(this::removeEntity);
            // Keep this fixture's instances cached in memory; Mongo persistence is substituted.
            if(group!=null)getLoadedGroups().remove(group);
        }
    }
    private static final class WarManager extends SceneScriptManager {
        final WarPlayer owner; final Map<Integer,SceneGroup> groups=new HashMap<>();
        WarManager(Scene scene,WarPlayer owner){super(scene);this.owner=owner;}
        @Override public SceneGroup getGroupById(int id) {
            if(getGroupInstanceById(id)!=null)return groups.get(id);
            var group=SceneGroup.of(id).load(20238);group.block_id=20238;groups.put(id,group);
            var instance=new SceneGroupInstance(group,owner);
            put("sceneGroupsInstances",id,instance);put("cachedSceneGroupsInstances",id,instance);put("sceneGroups",id,group);
            getScene().getLoadedGroups().add(group);
            return group;
        }
        @SuppressWarnings("unchecked") private void put(String field,int key,Object value) {
            try {Field f=SceneScriptManager.class.getDeclaredField(field);f.setAccessible(true);((Map<Integer,Object>)f.get(this)).put(key,value);}
            catch(ReflectiveOperationException error){throw new AssertionError(error);}
        }
        @Override public SceneGroupInstance getCachedGroupInstanceById(int id){return getCachedGroupInstances().get(id);}
    }

    private GameQuest activate(WarPlayer owner,ReconstructedWarCatalog.Stage stage) throws Exception {
        var rows=JsonParser.parseString(Files.readString(FileUtils.getExcelPath("QuestExcelConfigData.json"))).getAsJsonArray();
        var row=java.util.stream.StreamSupport.stream(rows.spliterator(),false).map(JsonElement::getAsJsonObject)
                .filter(q->q.get("subId").getAsInt()==stage.questId).findFirst().orElseThrow();
        var data=JsonUtils.decode(row.toString(),QuestData.class);
        install(GameData.getQuestDataMap(),stage.questId,data);
        install(GameData.getMainQuestDataMap(),stage.mainId,JsonUtils.decode(
                "{id:"+stage.mainId+",subQuests:[{subId:"+stage.questId+"}]}",MainQuestData.class));
        owner.getQuestManager().getMainQuests().clear();
        var main=new SavedMain(owner,stage.mainId);owner.getQuestManager().getMainQuests().put(stage.mainId,main);
        // Finish only the assertion's quest state: real condition dispatch is under test, while
        // unrelated achievements, rewards and automatic following-quest entry need a live account.
        var quest=new GameQuest(main,data) {
            @Override public void finish(){setState(QuestState.QUEST_STATE_FINISHED);}
            @Override public void save(){}
        };
        main.getChildQuests().put(stage.questId,quest);quest.setState(QuestState.QUEST_STATE_UNFINISHED);
        owner.getPosition().set(stage.position);
        return quest;
    }
    private WarScene scene(WarPlayer owner) throws Exception {var scene=new WarScene(owner);scenes.add(scene);return scene;}
    private static List<EntityMonster> monsters(Scene scene) {
        return scene.getEntities().values().stream().filter(EntityMonster.class::isInstance).map(EntityMonster.class::cast).toList();
    }
    private static void drainQuests() throws Exception {QuestManager.eventExecutor.submit(()->{}).get(5,TimeUnit.SECONDS);}
    private static EntityAvatar avatar(WarPlayer owner,Scene scene) {
        var avatar=new Avatar(){@Override public Player getPlayer(){return owner;}};
        avatar.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP,100);
        var entity=new EntityAvatar(scene,avatar);scene.getEntities().put(entity.getId(),entity);
        owner.team.getActiveTeam().add(entity);return entity;
    }

    @Test void allOriginalNodesAndTheirActualLuaSuitesAndActorsAreClosed() throws Exception {
        var expected=new HashSet<Integer>();
        for(int main:new int[]{5011,5012,5013}) {
            var data=JsonUtils.loadToClass(FileUtils.getResourcePath("BinOutput/Quest/"+main+".json"),MainQuestData.class);
            for(var q:data.getSubQuests())expected.add(q.getSubId());
        }
        assertEquals(expected,catalog().stages().stream().map(s->s.questId).collect(java.util.stream.Collectors.toSet()));
        assertEquals(23,catalog().stages().stream().filter(s->s.kind.equals("BATTLE")).count());
        assertEquals(158,catalog().stages().stream().mapToInt(s->s.enemies().size()).sum());
        var npcIds=new HashSet<Integer>();
        for(var row:JsonParser.parseString(Files.readString(FileUtils.getExcelPath("NpcExcelConfigData.json"))).getAsJsonArray())
            npcIds.add(row.getAsJsonObject().get("id").getAsInt());
        for(var stage:catalog().stages()) {
            if(stage.groupId==0)continue;
            var group=SceneGroup.of(stage.groupId).load(20238);
            assertNotNull(group.getSuiteByIndex(stage.suite));
            for(var npc:stage.npcs){assertTrue(npcIds.contains(npc.id));assertEquals(npc.id,group.npcs.get(npc.configId).npc_id);}
            for(int w=0;w<stage.waves.size();w++)assertEquals(stage.waves.get(w).stream().map(e->e.configId).toList(),group.getSuiteByIndex(stage.suite+w).monsters);
        }
        assertTrue(Files.isRegularFile(FileUtils.getScriptPath("Scene/20238/scene20238_block20238.lua")));
    }

    @Test void actualLoginResumeKeepsTheCurrentWarObjectiveAndItsSavedCombat() throws Exception {
        var stage = catalog().stage(501103);
        var quest = activate(player,stage);
        var scene = scene(player);
        var main = quest.getMainQuest();
        assertFalse(ReconstructedWarRuntime.preserveOnLogin(main));
        player.getPersistentDungeons().activate(20238);
        player.getPersistentDungeons().update(scene.getDungeonManager().capturePersistentProgress());
        assertFalse(ReconstructedWarRuntime.preserveOnLogin(main));
        scene.getDungeonManager().startDungeon();
        player.getPersistentDungeons().update(scene.getDungeonManager().capturePersistentProgress());
        ReconstructedWarRuntime.onTick(player);
        var enemy = monsters(scene).getFirst();
        enemy.damage(25);
        ReconstructedWarRuntime.capture(player);
        var health = player.getQuestWar().getReconstruction().findEncounter(stage.questId)
                .healthRatio(enemy.getConfigId());
        assertNotNull(health);
        assertTrue(ReconstructedWarRuntime.preserveOnLogin(main));
        assertNull(main.resumeOnLogin());
        assertEquals(QuestState.QUEST_STATE_UNFINISHED,quest.getState());
        assertSame(quest,main.getChildQuestById(stage.questId));
        assertEquals(health,player.getQuestWar().getReconstruction().findEncounter(stage.questId)
                .healthRatio(enemy.getConfigId()));
    }

    @Test void realDamageAndRealLuaDeathCallbacksRequireEveryMonsterOfEveryWave() throws Exception {
        var stage=catalog().stage(501103);activate(player,stage);var scene=scene(player);
        ReconstructedWarRuntime.onTick(player);
        assertEquals(3,monsters(scene).size());
        var first=monsters(scene).getFirst();
        first.damage(10,0,ElementType.None);
        assertEquals(0,player.war.getReconstruction().killCount());
        first.damage(1000,0,ElementType.None);
        assertEquals(1,player.war.getReconstruction().killCount());
        assertEquals(0,player.war.getReconstruction().encounter(stage.questId).wave());
        for(var enemy:monsters(scene))enemy.damage(1000,0,ElementType.None);
        ReconstructedWarRuntime.onTick(player);
        assertEquals(1,player.war.getReconstruction().encounter(stage.questId).wave());
        assertEquals(3,monsters(scene).size());
        for(var enemy:monsters(scene))enemy.damage(1000,0,ElementType.None);
        ReconstructedWarRuntime.onTick(player);
        assertEquals(2,player.war.getReconstruction().encounter(stage.questId).wave());
        assertEquals(6,player.war.getReconstruction().killCount());
        assertTrue(monsters(scene).isEmpty());
        assertEquals(3,scene.getScriptManager().getTriggerInvocationCount("CUSTOM_WAR_DIE"));
        assertEquals(-1,ReconstructedWarRuntime.monsterDied(scene,stage.groupId,first.getConfigId()));
        assertEquals(6,player.war.getReconstruction().killCount());
    }

    @Test void everySeparateBattleIncludingRepeatedKeysAndTheThreeWaveFinalNeedsItsOwnRealKills() throws Exception {
        var scene=scene(player);int kills=0;
        for(var stage:catalog().stages().stream().filter(s->s.kind.equals("BATTLE")).toList()) {
            var quest=activate(player,stage);ReconstructedWarRuntime.onTick(player);drainQuests();
            assertEquals(QuestState.QUEST_STATE_UNFINISHED,quest.getState(),"must not reuse an earlier victory: "+stage.questId);
            // Even a pre-existing global progress counter is insufficient for this distinct encounter.
            player.getPlayerProgress().addToCurrentProgress(stage.luaKey,1);
            player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_LUA_NOTIFY,stage.luaKey,0);drainQuests();
            assertEquals(QuestState.QUEST_STATE_UNFINISHED,quest.getState());
            for(int wave=0;wave<stage.waves.size();wave++) {
                assertEquals(stage.waves.get(wave).size(),monsters(scene).size(),"wave "+wave+" of "+stage.questId);
                for(var enemy:monsters(scene))enemy.damage(1000,0,ElementType.None);
                kills+=stage.waves.get(wave).size();ReconstructedWarRuntime.onTick(player);drainQuests();
                if(wave+1<stage.waves.size()||stage.gates)assertEquals(QuestState.QUEST_STATE_UNFINISHED,quest.getState());
            }
            if(stage.gates) {
                var device=scene.getEntityByConfigId(stage.operationConfig,stage.groupId);player.getPosition().set(device.getPosition());
                scene.getScriptManager().callEvent(new ScriptArgs(stage.groupId,EventType.EVENT_SELECT_OPTION,
                        stage.operationConfig,7).setUid(42)).get(5,TimeUnit.SECONDS);
                ReconstructedWarRuntime.onTick(player);drainQuests();
                assertNull(scene.getEntityByConfigId(stage.portalConfig,stage.groupId));
            }
            assertEquals(QuestState.QUEST_STATE_FINISHED,quest.getState(),"actual QuestSystem completion "+stage.questId);
            assertEquals(kills,player.war.getReconstruction().killCount());
            ReconstructedWarRuntime.onTick(player);drainQuests();
            assertTrue(monsters(scene).isEmpty());
        }
        assertEquals(158,kills);
        var settlement=activate(player,catalog().stage(501313));
        var exec=new emu.grasscutter.game.quest.exec.ExecAddAbyssWarAccountData();
        assertTrue(exec.execute(settlement,null,"1002","1","150,150","-300,-200"));
        assertTrue(exec.execute(settlement,null,"1003","1","2880,2880","-2880,-2880"));
        assertTrue(exec.execute(settlement,null,"1004","1","1,1","0,0"));
        assertEquals(0,player.war.getAccountValue(1002));assertEquals(158,player.war.getAccountValue(1003));
        assertEquals(8,player.war.getAccountValue(1004));
        assertTrue(exec.execute(settlement,null,"1003","1","2880,2880","-2880,-2880"));
        assertEquals(158,player.war.getAccountValue(1003));
        assertFalse(exec.execute(settlement,null,"1003","1","2881,2881","-2880,-2880"));
    }

    @Test void anActualGateCannotBeSealedBeforeBattleAndRequiresASeparateOwnedInteraction() throws Exception {
        var stage=catalog().stage(501303);activate(player,stage);var scene=scene(player);
        ReconstructedWarRuntime.onTick(player);
        var gadget=(EntityGadget)scene.getEntityByConfigId(stage.operationConfig,stage.groupId);
        assertTrue(((GadgetWorktop)gadget.getContent()).getWorktopOptions().isEmpty());
        assertEquals(-1,ReconstructedWarRuntime.select(scene,42,stage.groupId,stage.operationConfig,7));
        for(int wave=0;wave<2;wave++) {
            for(var enemy:monsters(scene))enemy.damage(1000,0,ElementType.None);
            ReconstructedWarRuntime.onTick(player);
        }
        var progress=player.war.getReconstruction().encounter(stage.questId);
        assertFalse(progress.sealed());assertNotNull(scene.getEntityByConfigId(stage.portalConfig,stage.groupId));
        assertEquals(-1,ReconstructedWarRuntime.select(scene,43,stage.groupId,stage.operationConfig,7));
        player.getPosition().set(gadget.getPosition());
        scene.getScriptManager().callEvent(new ScriptArgs(stage.groupId,EventType.EVENT_SELECT_OPTION,
                stage.operationConfig,7).setUid(42)).get(5,TimeUnit.SECONDS);
        assertTrue(progress.sealed());assertNull(scene.getEntityByConfigId(stage.portalConfig,stage.groupId));
        assertFalse(ReconstructedWarRuntime.shouldSpawn(scene,stage.groupId,stage.portalConfig));
    }

    @Test void disconnectRestoresActualResidualHealthAndDoesNotResurrectTheAlreadyKilledConfig() throws Exception {
        var stage=catalog().stage(501103);activate(player,stage);var first=scene(player);
        ReconstructedWarRuntime.onTick(player);
        var actors=monsters(first);int dead=actors.get(0).getConfigId();int survivor=actors.get(1).getConfigId();
        actors.get(0).damage(1000,0,ElementType.None);actors.get(1).damage(37,0,ElementType.None);
        float hp=actors.get(1).getFightProperty(FightProperty.FIGHT_PROP_CUR_HP);
        ReconstructedWarRuntime.capture(player);
        var restored=new WarPlayer();restored.setUid(42);restored.war=JsonUtils.decode(JsonUtils.encode(player.war),QuestWarState.class);
        activate(restored,stage);var second=scene(restored);ReconstructedWarRuntime.onTick(restored);
        assertNull(second.getEntityByConfigId(dead,stage.groupId));
        var alive=(EntityMonster)second.getEntityByConfigId(survivor,stage.groupId);
        assertNotNull(alive);assertEquals(hp,alive.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP),0.0001);
        assertEquals(1,restored.war.getReconstruction().killCount());assertEquals(2,monsters(second).size());
    }

    @Test void aDurableActualVictoryCanReachTheQuestEngineAfterReconnectWithoutRespawningRewards() throws Exception {
        var stage=catalog().stage(501103);activate(player,stage);var first=scene(player);ReconstructedWarRuntime.onTick(player);
        for(int wave=0;wave<stage.waves.size();wave++) {
            for(var enemy:monsters(first))enemy.damage(1000,0,ElementType.None);
            ReconstructedWarRuntime.onTick(player);drainQuests();
        }
        var restored=new WarPlayer();restored.setUid(42);restored.war=JsonUtils.decode(JsonUtils.encode(player.war),QuestWarState.class);
        var quest=activate(restored,stage);var resumed=scene(restored);
        ReconstructedWarRuntime.onTick(restored);drainQuests();
        assertTrue(monsters(resumed).isEmpty());assertEquals(6,restored.war.getReconstruction().killCount());
        assertEquals(QuestState.QUEST_STATE_FINISHED,quest.getState());assertEquals(0,restored.itemsAdded);
    }

    @Test void rescueAccountingCountsOnlyObservedFinishedOriginalActorsAndDoesNotInventHistory() throws Exception {
        var stage=catalog().stage(501118);var quest=activate(player,stage);var scene=scene(player);
        ReconstructedWarRuntime.onTick(player);drainQuests();
        assertEquals(0,player.war.getReconstruction().rescueCount());
        assertEquals(QuestState.QUEST_STATE_UNFINISHED,quest.getState());
        player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_COMPLETE_TALK,501118,0);drainQuests();
        assertEquals(QuestState.QUEST_STATE_FINISHED,quest.getState());
        ReconstructedWarRuntime.onTick(player);
        int actors=(int)stage.npcs.stream().map(n->n.id).distinct().count();
        assertEquals(actors,player.war.getReconstruction().rescueCount());
        ReconstructedWarRuntime.onTick(player);assertEquals(actors,player.war.getReconstruction().rescueCount());
        var historical=activate(player,catalog().stage(501104));historical.setState(QuestState.QUEST_STATE_FINISHED);
        ReconstructedWarRuntime.onTick(player);assertEquals(actors,player.war.getReconstruction().rescueCount());
    }

    @Test void oldSelectedAndVisitedStatesCannotCompleteAnotherObjectiveWithoutItsOwnInteraction() throws Exception {
        var select=catalog().stage(501129);var quest=activate(player,select);var scene=scene(player);
        player.war.setLevels(new int[]{1},3);
        assertFalse(QuestWarRuntime.matchesCurrent(quest,quest.getQuestData().getFinishCond().getFirst()));
        ReconstructedWarRuntime.onTick(player);int config=select.selectionGadgets.keySet().iterator().next();
        var gadget=scene.getEntityByConfigId(config,select.groupId);player.getPosition().set(gadget.getPosition());
        scene.getScriptManager().callEvent(new ScriptArgs(select.groupId,EventType.EVENT_SELECT_OPTION,config,7).setUid(0)).get(5,TimeUnit.SECONDS);
        assertFalse(player.war.getReconstruction().selected(select.questId,1));
        scene.getScriptManager().callEvent(new ScriptArgs(select.groupId,EventType.EVENT_SELECT_OPTION,config,7).setUid(42)).get(5,TimeUnit.SECONDS);
        assertTrue(QuestWarRuntime.matchesCurrent(quest,quest.getQuestData().getFinishCond().getFirst()));
        var arrival=catalog().stage(501132);var arrived=activate(player,arrival);
        player.war.setRegions(new int[]{arrival.regionId},3);
        assertFalse(QuestWarRuntime.matchesCurrent(arrived,arrived.getQuestData().getFinishCond().getFirst()));
        assertEquals(-1,ReconstructedWarRuntime.entered(scene,43,arrival.groupId,arrival.regionConfig));
        assertEquals(0,ReconstructedWarRuntime.entered(scene,42,arrival.groupId,arrival.regionConfig));
        assertTrue(QuestWarRuntime.matchesCurrent(arrived,arrived.getQuestData().getFinishCond().getFirst()));
    }

    @Test void allFourteenSelectionsAndEighteenArrivalsRequireCurrentOwnedPresence() throws Exception {
        var scene=scene(player);var avatar=avatar(player,scene);
        for(var stage:catalog().stages().stream().filter(s->s.kind.equals("SELECT")||s.kind.equals("ARRIVE")).toList()) {
            var quest=activate(player,stage);
            player.getPosition().set(stage.position.clone().addX(40));
            ReconstructedWarRuntime.onTick(player);drainQuests();
            assertEquals(QuestState.QUEST_STATE_UNFINISHED,quest.getState());
            if(stage.kind.equals("SELECT")) {
                int config=stage.selectionGadgets.keySet().iterator().next();
                var gadget=scene.getEntityByConfigId(config,stage.groupId);player.getPosition().set(gadget.getPosition());
                for(int uid:new int[]{0,43}) scene.getScriptManager().callEvent(new ScriptArgs(stage.groupId,
                        EventType.EVENT_SELECT_OPTION,config,7).setUid(uid)).get(5,TimeUnit.SECONDS);
                assertFalse(player.war.getReconstruction().selected(stage.questId,stage.selectionGadgets.get(config)));
                assertTrue(scene.getScriptManager().getTriggersByEvent(EventType.EVENT_SELECT_OPTION).stream()
                        .anyMatch(t->t.getCurrentGroup().id==stage.groupId));
                scene.getScriptManager().callEvent(new ScriptArgs(stage.groupId,EventType.EVENT_SELECT_OPTION,
                        config,7).setUid(42)).get(5,TimeUnit.SECONDS);
            } else {
                player.war.setRegions(new int[]{stage.regionId},3);
                assertFalse(QuestWarRuntime.matchesCurrent(quest,quest.getQuestData().getFinishCond().getFirst()));
                player.getPosition().set(stage.position);
                scene.getScriptManager().callEvent(new ScriptArgs(stage.groupId,EventType.EVENT_ENTER_REGION,
                        stage.regionConfig).setTargetEntityId(avatar.getId())).get(5,TimeUnit.SECONDS);
                assertTrue(player.war.getReconstruction().arrived(stage.questId));
            }
            drainQuests();assertEquals(QuestState.QUEST_STATE_FINISHED,quest.getState(),"real condition "+stage.questId);
        }
    }

    @Test void everyTravelAllowsWalkingAndAFailedShuttleDoesNotReportArrival() throws Exception {
        var scene=scene(player);var world=(TestWorld)player.getWorld();world.transferResult=false;
        for(var stage:catalog().stages().stream().filter(s->s.kind.equals("TRAVEL")).toList()) {
            var quest=activate(player,stage);ReconstructedWarRuntime.onTick(player);drainQuests();
            var operation=scene.getEntityByConfigId(stage.operationConfig,stage.groupId);player.getPosition().set(operation.getPosition());
            scene.getScriptManager().callEvent(new ScriptArgs(stage.groupId,EventType.EVENT_SELECT_OPTION,
                    stage.operationConfig,7).setUid(42)).get(5,TimeUnit.SECONDS);
            assertFalse(player.war.getReconstruction().journeyed(stage.questId));
            assertEquals(QuestState.QUEST_STATE_UNFINISHED,quest.getState());
            ReconstructedWarRuntime.onTick(player);
            assertTrue(scene.getScriptManager().getTriggersByEvent(EventType.EVENT_SELECT_OPTION).stream()
                    .anyMatch(t->t.getCurrentGroup().id==stage.groupId));
            assertTrue(stage.landing.computeDistance(stage.destination)>12);
            player.getPosition().set(stage.destination);ReconstructedWarRuntime.onTick(player);drainQuests();
            assertTrue(player.war.getReconstruction().journeyed(stage.questId));
            assertEquals(QuestState.QUEST_STATE_FINISHED,quest.getState());
        }
        assertEquals(7,world.transfers.size());
    }

    @Test void nativeQuestFinishNotificationActivatesTheActualNextArrivalRegion() throws Exception {
        var stage=catalog().stage(501135);var quest=activate(player,stage);var scene=scene(player);ReconstructedWarRuntime.onTick(player);
        int config=stage.selectionGadgets.keySet().iterator().next();player.getPosition().set(scene.getEntityByConfigId(config,stage.groupId).getPosition());
        scene.getScriptManager().callEvent(new ScriptArgs(stage.groupId,EventType.EVENT_SELECT_OPTION,config,7).setUid(42)).get(5,TimeUnit.SECONDS);
        drainQuests();assertEquals(QuestState.QUEST_STATE_FINISHED,quest.getState());
        scene.getScriptManager().callEvent(new ScriptArgs(stage.groupId,EventType.EVENT_QUEST_FINISH,
                stage.questId,1).setEventSource(stage.questId)).get(5,TimeUnit.SECONDS);
        var next=catalog().stage(501108);
        assertNotNull(scene.getScriptManager().getGroupInstanceById(next.groupId));
        assertNotNull(scene.getScriptManager().getRegionByConfigId(next.groupId,next.regionConfig));
        assertFalse(player.war.getReconstruction().arrived(next.questId));
    }

    @Test void originalFinalPlotMustFinishBeforeTheRealExitQueuesItsStandardDungeonTransfer() throws Exception {
        var finalPlot=catalog().stage(501311);var quest=activate(player,finalPlot);var scene=scene(player);
        ReconstructedWarRuntime.onTick(player);drainQuests();
        assertEquals(QuestState.QUEST_STATE_UNFINISHED,quest.getState());
        assertNull(scene.getEntityByConfigId(catalog().exit().operationConfig,catalog().exit().groupId));
        player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_FINISH_PLOT,501311,0);drainQuests();
        assertEquals(QuestState.QUEST_STATE_FINISHED,quest.getState());
        ReconstructedWarRuntime.onTick(player);avatar(player,scene);
        var exit=catalog().exit();var device=scene.getEntityByConfigId(exit.operationConfig,exit.groupId);
        assertNotNull(device);player.getPosition().set(device.getPosition());
        var world=(TestWorld)player.getWorld();world.transferResult=true;
        assertEquals(0,ReconstructedWarRuntime.select(scene,42,exit.groupId,exit.operationConfig,7));
        player.getQueuedTeleport().get(5,TimeUnit.SECONDS);
        assertEquals(3,world.transfers.getLast().getSceneId());
        assertEquals(0,player.itemsAdded);
    }

    @Test void duplicateNativeLuaKeysAreOwnedButTalkAndPlotStagesRemainOriginalAndUntouched() throws Exception {
        assertEquals("501211",catalog().stage(501239).luaKey);
        assertEquals("501220",catalog().stage(501241).luaKey);
        assertNotEquals(catalog().stage(501239).suite,catalog().stage(501211).suite);
        assertTrue(ReconstructedWarRuntime.isServerOwnedLuaNotification("501103"));
        assertTrue(ReconstructedWarRuntime.isServerOwnedLuaNotification("501220"));
        assertFalse(ReconstructedWarRuntime.isServerOwnedLuaNotification("501101"));
        assertFalse(ReconstructedWarRuntime.isServerOwnedLuaNotification("unrelated_world_quest"));
        var talk=catalog().stage(501101);var q=activate(player,talk);var scene=scene(player);ReconstructedWarRuntime.onTick(player);
        assertEquals(QuestState.QUEST_STATE_UNFINISHED,q.getState());
        for(var npc:talk.npcs)assertEquals(npc.id,((EntityNPC)scene.getEntityByConfigId(npc.configId,talk.groupId)).getEntityTypeId());
    }

    @Test void consecutiveActorSuitesRemoveTheirObsoleteNpcsBeforeOfferingTheNextOriginalTalk() throws Exception {
        var first=catalog().stage(501101);activate(player,first);var scene=scene(player);ReconstructedWarRuntime.onTick(player);
        assertFalse(first.npcs.isEmpty());
        var next=catalog().stage(501104);assertEquals(first.groupId,next.groupId);
        activate(player,next);ReconstructedWarRuntime.onTick(player);
        for(var npc:first.npcs)assertNull(scene.getEntityByConfigId(npc.configId,first.groupId));
        for(var npc:next.npcs)assertNotNull(scene.getEntityByConfigId(npc.configId,next.groupId));
        assertEquals(next.npcs.size(),scene.getEntities().values().stream().filter(EntityNPC.class::isInstance).count());
    }
}
