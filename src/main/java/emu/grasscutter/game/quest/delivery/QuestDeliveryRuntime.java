package emu.grasscutter.game.quest.delivery;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.entity.gadget.GadgetWorktop;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.*;
import java.util.*;

/** Reconstructed legacy delivery failures. Original quest failExec owns rollback and item removal. */
public final class QuestDeliveryRuntime {
    public static final int ABANDON=9;
    private QuestDeliveryRuntime(){}
    private static boolean target(GameQuest quest){
        var entry=QuestDeliveryCatalog.entry(quest.getSubQuestId());
        return entry!=null&&quest.getMainQuestId()==entry.parent()&&quest.getQuestData()!=null
                &&quest.getQuestData().getFailCond().stream().anyMatch(c->c.getType()==QuestContent.QUEST_CONTENT_ACTIVITY_TRIGGER_FAILED
                        &&c.getParam()!=null&&c.getParam().length>=2&&c.getParam()[0]==5006&&c.getParam()[1]==0);
    }
    private static boolean running(GameQuest quest){return target(quest)&&quest.getState()==QuestState.QUEST_STATE_UNFINISHED;}
    private static boolean sameRound(GameQuest quest,QuestDeliveryState.Round round){
        return round!=null&&round.getQuestStart()==quest.getStartTime()
                &&(quest.getStartSequence()==0||round.getStartSequence()==quest.getStartSequence());
    }
    private static boolean ready(Player player){
        return player.getWorld()!=null&&player.getWorld().getHost()==player&&player.getWorld().getPlayers().size()==1
                &&player.getScene()!=null&&player.getScene().getWorld()==player.getWorld()
                &&player.getSceneId()==3&&player.getScene().getId()==3
                &&player.getSceneLoadState()==Player.SceneLoadState.LOADED;
    }
    private static void retire(QuestDeliveryState.Round round){
        var gadget=round.getAbandon();if(gadget!=null&&gadget.getScene().getEntities().get(gadget.getId())==gadget)
            gadget.getScene().removeEntity(gadget);
        round.setAbandon(null);
    }
    public static void onQuestStart(GameQuest quest){
        if(!target(quest))return;
        var player=quest.getOwner();var state=player.getQuestDeliveryState();
        synchronized(state){
            var old=state.getRounds().get(quest.getSubQuestId());if(old!=null)retire(old);
            var round=new QuestDeliveryState.Round();round.setQuestStart(quest.getStartTime());
            round.setStartSequence(quest.getStartSequence());round.setAnchor(player.getPosition().clone());
            round.setDeadline(System.currentTimeMillis()+QuestDeliveryCatalog.entry(quest.getSubQuestId()).seconds()*1000L);
            state.getRounds().put(quest.getSubQuestId(),round);player.save();
            player.dropMessage("【重建送餐】服务端时限"+QuestDeliveryCatalog.entry(quest.getSubQuestId()).seconds()+"秒；原始送餐Lua时限缺失，采用本地替代规则。"
                    +"按该任务原配置检查攀爬、飞行、冲刺和真实受伤；未恢复原能力状态判定。起点旁机关选项9可实际放弃。");
        }
    }
    public static boolean preserveOnLogin(GameMainQuest main){
        for(var quest:main.getActiveQuests())if(running(quest)){
            var round=main.getOwner().getQuestDeliveryState().getRounds().get(quest.getSubQuestId());
            if(sameRound(quest,round)){
                if(round.isFailed())main.getOwner().getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_ACTIVITY_TRIGGER_FAILED,5006,0);
                return true;
            }
        }
        return false;
    }
    private static void fail(Player player,GameQuest quest,QuestDeliveryState.Round round,String reason){
        if(!running(quest)||!sameRound(quest,round)||round.isFailed())return;
        round.setFailed(true);round.setFailure(reason);retire(round);player.save();
        player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_ACTIVITY_TRIGGER_FAILED,5006,0);
    }
    public static void onAvatarHurt(Player player){
        if(!ready(player))return;var state=player.getQuestDeliveryState();
        synchronized(state){
            for(var entry:state.getRounds().entrySet()){
                var quest=player.getQuestManager().getQuestById(entry.getKey());
                if(quest!=null&&running(quest)&&QuestDeliveryCatalog.entry(entry.getKey()).prohibited().contains("TRIGGER_CUR_AVATAR_HURT"))
                    fail(player,quest,entry.getValue(),"hurt");
            }
        }
    }
    public static void onTick(Player player){
        var state=player.getQuestDeliveryState();synchronized(state){
            for(var item:state.getRounds().entrySet()){
                var quest=player.getQuestManager().getQuestById(item.getKey());var round=item.getValue();
                if(quest==null||!running(quest)||!sameRound(quest,round)){retire(round);continue;}
                if(round.isFailed()){retire(round);continue;}
                if(System.currentTimeMillis()>=round.getDeadline()){fail(player,quest,round,"timeout");continue;}
                if(!ready(player)){retire(round);continue;}
                var avatar=player.getTeamManager().getCurrentAvatarEntity();
                String motion=avatar==null||avatar.getMotionState()==null?"":avatar.getMotionState().name();
                var prohibited=QuestDeliveryCatalog.entry(quest.getSubQuestId()).prohibited();
                String trigger=motion.contains("CLIMB")?"TRIGGER_CLIMB":motion.contains("FLY")?"TRIGGER_FLY":motion.contains("DASH")?"TRIGGER_DASH":"";
                if(!trigger.isEmpty()&&prohibited.contains(trigger)){fail(player,quest,round,trigger);continue;}
                if(player.getPosition().computeDistance(round.getAnchor())>30){retire(round);continue;}
                var old=round.getAbandon();if(old!=null&&old.getScene()==player.getScene()&&player.getScene().getEntities().get(old.getId())==old)continue;
                retire(round);var point=round.getAnchor().clone();point.addZ(2.5f);
                var gadget=new EntityGadget(player.getScene(),70350081,point);gadget.buildContent();
                if(!(gadget.getContent() instanceof GadgetWorktop worktop))throw new IllegalStateException("Delivery abandon object is not a worktop");
                worktop.addWorktopOptions(new int[]{ABANDON});
                worktop.setOnSelectWorktopOptionEvent((selected,option)->{
                    synchronized(state){
                        if(option!=ABANDON||!ready(player)||state.getRounds().get(quest.getSubQuestId())!=round
                                ||round.getAbandon()!=gadget||player.getScene().getEntities().get(gadget.getId())!=gadget
                                ||player.getPosition().computeDistance(gadget.getPosition())>4)return false;
                        if(round.isFailed()||!running(quest)||!sameRound(quest,round))return false;
                        fail(player,quest,round,"abandon");return true;
                    }
                });
                round.setAbandon(gadget);player.getScene().addEntity(gadget);
            }
        }
    }
    public static boolean matchesCurrent(GameQuest quest,QuestData.QuestContentCondition condition){
        var params=condition.getParam();if(params==null||params.length<2||params[0]!=5006||params[1]!=0||!running(quest))return false;
        var round=quest.getOwner().getQuestDeliveryState().getRounds().get(quest.getSubQuestId());
        return sameRound(quest,round)&&round.isFailed();
    }
}
