package emu.grasscutter.game.quest.reconstruction;

import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.entity.gadget.GadgetWorktop;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.*;
import emu.grasscutter.game.world.*;
import java.util.*;

/** Story-only replacement entry for the absent Labyrinth Warriors activity interface. */
public final class QuestRogueStory {
    private static final Map<Integer,Integer> STAGES = Map.ofEntries(
            Map.entry(4002012,1),Map.entry(4002024,1),Map.entry(4002027,1),
            Map.entry(4002104,2),Map.entry(4002115,2),Map.entry(4002116,2),Map.entry(4002117,2),
            Map.entry(4002202,3),Map.entry(4002207,3),Map.entry(4002210,3),Map.entry(4002212,3));
    // Authored first-floor mapping: native sequence 1/7/13 and the original story's scene callbacks.
    private static final Map<Integer,Integer> DUNGEONS = Map.of(1,3017,2,3018,3,3022);
    private QuestRogueStory() {}
    private static GameQuest active(Player player,int id) {
        var quest=player.getQuestManager().getQuestById(id);var stage=STAGES.get(id);
        return stage!=null&&quest!=null&&quest.getState()==QuestState.QUEST_STATE_UNFINISHED&&quest.getQuestData()!=null
                &&quest.getQuestData().getFinishCond().stream().anyMatch(c->c.getType()==QuestContent.QUEST_CONTENT_ENTER_ROGUE_DUNGEON
                    &&c.getParam()!=null&&c.getParam().length>=2&&c.getParam()[0]==stage&&c.getParam()[1]==0)?quest:null;
    }
    public static void onQuestStart(GameQuest quest) {
        if (!STAGES.containsKey(quest.getSubQuestId())) return;
        var state=quest.getOwner().getQuestActivityState();var old=state.getTrials().get(quest.getSubQuestId());
        if (old!=null) QuestActivityRuntime.retire(old);
        var saved=new QuestActivityState.Trial();saved.setQuestStart(quest.getStartTime());
        state.getTrials().put(quest.getSubQuestId(),saved);quest.getOwner().save();
    }
    public static void onTick(Player player) {
        var state=player.getQuestActivityState();
        synchronized(state) {
            boolean selected=false;
            for (int id:STAGES.keySet().stream().sorted().toList()) {
                var saved=state.getTrials().get(id);if(saved==null)continue;
                var quest=active(player,id);
                if(quest==null||saved.getQuestStart()!=quest.getStartTime()||!ready(player)||player.getSceneId()!=3||selected) {QuestActivityRuntime.retire(saved);continue;}
                int stage=STAGES.get(id);
                if(saved.getCompleted().getOrDefault("rogue:"+stage,0)>0) {
                    QuestActivityRuntime.retire(saved);player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_ENTER_ROGUE_DUNGEON,stage,0);continue;
                }
                var anchor=QuestSceneConditions.dummyPoint(3,"Q40020guide");
                if(anchor==null||player.getPosition().computeDistance(anchor)>40) {QuestActivityRuntime.retire(saved);continue;}
                selected=true;
                if(saved.getActorScene()!=player.getScene())QuestActivityRuntime.retire(saved);
                if(!saved.getActors().isEmpty()&&saved.getActors().values().stream().anyMatch(entity->player.getScene().getEntities().get(entity.getId())!=entity))QuestActivityRuntime.retire(saved);
                if(saved.getActors().isEmpty()) {
                    saved.setActorScene(player.getScene());var point=anchor.clone();point.setZ(point.getZ()+2);
                    var entity=new EntityGadget(player.getScene(),70350081,point);entity.buildContent();
                    if(!(entity.getContent() instanceof GadgetWorktop worktop))throw new IllegalStateException("Rogue story entry is not a worktop");
                    worktop.addWorktopOptions(new int[]{7});saved.getActors().put(0,entity);player.getScene().addEntity(entity);
                    player.dropMessage("【重建活动剧情】谜境悬兵第"+stage+"阶段\n原活动入口界面未恢复；操作此入口实际进入剧情秘境。只有副本真实开局后才计入任务，原对白仍需正常完成。");
                }
            }
        }
    }
    private static boolean ready(Player p) {return p.getScene()!=null&&p.getWorld()!=null&&p.getWorld().getHost()==p&&!p.isInMultiplayer()
            &&p.getWorld().getPlayers().size()==1&&p.getSceneLoadState()==Player.SceneLoadState.LOADED&&p.getScene().getWorld()==p.getWorld();}
    public static boolean tryInteract(Player player,GameEntity entity,int option) {
        var state=player.getQuestActivityState();synchronized(state) {
            for(int id:STAGES.keySet()) {
                var saved=state.getTrials().get(id);if(saved==null||saved.getActors().get(0)!=entity)continue;
                var quest=active(player,id);int dungeon=DUNGEONS.get(STAGES.get(id));var data=GameData.getDungeonDataMap().get(dungeon);
                double distance=player.getPosition().computeDistance(entity.getPosition());
                if(option!=7||!ready(player)||player.getSceneId()!=3||quest==null||saved.getQuestStart()!=quest.getStartTime()
                        ||saved.getActorScene()!=player.getScene()||player.getScene().getEntities().get(entity.getId())!=entity
                        ||!Double.isFinite(distance)||distance>6||data==null||player.getLevel()<data.getLimitLevel()
                        ||data.getType()!=emu.grasscutter.game.dungeons.enums.DungeonType.DUNGEON_ROGUELIKE)return true;
                saved.setPendingDungeon(dungeon);player.save();
                if(!player.getServer().getDungeonSystem().enterDungeon(player,0,dungeon,true)) {saved.setPendingDungeon(0);player.save();}
                return true;
            }
        }
        return false;
    }
    public static void onDungeonStarted(Scene scene) {
        var manager=scene.getDungeonManager();if(manager==null||!manager.isStarted()||scene.getWorld().getPlayers().size()!=1)return;
        var player=scene.getWorld().getHost();if(player==null||player.getScene()!=scene||player.getWorld()!=scene.getWorld()
                ||scene.getId()!=manager.getDungeonData().getSceneId()||!scene.getPlayers().contains(player))return;
        var state=player.getQuestActivityState();synchronized(state) {
            for(int id:STAGES.keySet()) {
                var quest=active(player,id);var saved=state.getTrials().get(id);int stage=STAGES.get(id);
                if(quest==null||saved==null||saved.getQuestStart()!=quest.getStartTime()||saved.getPendingDungeon()!=DUNGEONS.get(stage)
                        ||manager.getDungeonData().getId()!=saved.getPendingDungeon())continue;
                saved.getCompleted().put("rogue:"+stage,1);saved.setPendingDungeon(0);player.save();
                player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_ENTER_ROGUE_DUNGEON,stage,0);
            }
        }
    }
    public static boolean matchesCurrent(GameQuest quest,QuestData.QuestContentCondition condition) {
        var p=condition.getParam();var stage=STAGES.get(quest.getSubQuestId());
        if(stage==null||p==null||p.length<2||p[0]!=stage||p[1]!=0)return false;
        var saved=quest.getOwner().getQuestActivityState().getTrials().get(quest.getSubQuestId());
        return saved!=null&&saved.getQuestStart()==quest.getStartTime()&&saved.getCompleted().getOrDefault("rogue:"+stage,0)>0;
    }
    public static boolean preserveOnLogin(GameMainQuest main) {
        return main.getActiveQuests().stream().anyMatch(q->STAGES.containsKey(q.getSubQuestId())
                &&main.getOwner().getQuestActivityState().getTrials().containsKey(q.getSubQuestId()));
    }
}
