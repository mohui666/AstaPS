package emu.grasscutter.game.quest.coop;

import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.entity.gadget.GadgetWorktop;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.enums.QuestState;
import emu.grasscutter.game.quest.enums.QuestContent;
import emu.grasscutter.game.world.*;
import emu.grasscutter.net.proto.VisionTypeOuterClass.VisionType;
import emu.grasscutter.server.packet.send.PacketSelectWorktopOptionRsp;
import java.util.*;

/** A selected stage advances only after its real quest finishes. Selection never completes a quest. */
public final class CoopProgression {
    private static final int GADGET = 70350081;
    private static final int OPTION = 7;
    private enum Kind { STAGE, POINT, AUXILIARY, CHECKPOINT }
    private record Choice(Kind kind,int id,String label) {}
    private static final class Selector extends EntityGadget {
        final int playerUid, pointId;
        final Choice choice;
        Selector(Scene scene,Position position,int uid,int point,Choice choice) {
            super(scene,GADGET,position,new Position(),null,0,0,false);
            playerUid=uid; pointId=point; this.choice=choice;
            setContent(new GadgetWorktop(this));
            ((GadgetWorktop)getContent()).addWorktopOptions(new int[]{OPTION});
        }
    }
    private CoopProgression() {}
    private static CoopProgressionCatalog catalog() { return CoopProgressionCatalog.installed(); }
    private static CoopState.Run run(Player player) {
        var state=player.getHangouts(); var run=state==null?null:state.getRun();
        return run!=null&&run.getCurrent()!=null&&run.getCurrent().getStatus()==1?run:null;
    }
    /** The old flattened acceptance chain is damaged. Only this controller starts listed stages. */
    public static boolean mayAccept(Player player,QuestData quest) {
        return CoopCatalog.chapterForQuest(quest.getMainId())==0;
    }
    /** Called at a real stage start, before its beginExec can emit any new Lua progress. */
    public static void onQuestStarted(GameQuest quest) {
        var run=run(quest.getOwner());
        if(run==null||run.getStageQuestId()!=quest.getSubQuestId()) return;
        run.getStageLuaBaselines().clear();
        for(var condition:quest.getQuestData().getFinishCond())
            if(condition.getType()==QuestContent.QUEST_CONTENT_LUA_NOTIFY)
                run.getStageLuaBaselines().put(condition.getParamStr(),Math.max(0,
                        quest.getOwner().getPlayerProgress().getCurrentProgress(condition.getParamStr())));
        quest.getOwner().save();
    }
    public static boolean ownsLuaCondition(GameQuest quest,QuestData.QuestContentCondition condition) {
        return condition.getType()==QuestContent.QUEST_CONTENT_LUA_NOTIFY
                &&CoopCatalog.chapterForQuest(quest.getMainQuestId())>0
                &&catalog().stageQuest(quest.getSubQuestId());
    }
    public static boolean matchesLuaCondition(GameQuest quest,QuestData.QuestContentCondition condition) {
        if(!ownsLuaCondition(quest,condition))return false;
        var run=run(quest.getOwner());
        if(run==null||run.getStageQuestId()!=quest.getSubQuestId())return false;
        var baseline=run.getStageLuaBaselines().get(condition.getParamStr());
        if(baseline==null)return false;
        int current=quest.getOwner().getPlayerProgress().getCurrentProgress(condition.getParamStr());
        return current>=baseline&&(long)current-baseline>=Math.max(1,condition.getCount());
    }
    public static boolean onPointStarted(Player player,int pointId) {
        var run=run(player); var point=catalog().point(pointId);
        if(run==null||point==null||run.getPointId()!=pointId||run.getChapterId()!=point.chapter()) return false;
        run.setStageQuestId(point.entryQuest()); run.setLastCompletedStage(0);
        run.setLastCompletedPoint(pointId); run.setAuxiliaryStage(false); run.setPhaseCompleted(false);
        run.getPendingStageIds().clear(); run.getPendingPointIds().clear();
        run.getCompletedAuxiliary().clear(); run.getStartedObservers().clear();
        run.getStageLuaBaselines().clear();
        cleanup(player.getScene(),player.getUid());
        return true;
    }
    public static boolean isPendingPoint(Player player,int id) {
        var run=run(player);
        return run!=null&&run.getStageQuestId()==0&&run.getPendingPointIds().contains(id);
    }
    /** Forward selection keeps actual progress; ordinary replay remains CoopSystem.start's job. */
    public static boolean advanceToPoint(Player player,int pointId) {
        var state=player.getHangouts();
        synchronized(state) {
            var run=run(player); var point=catalog().point(pointId);
            if(run==null||point==null||!isPendingPoint(player,pointId)
                    ||player.getWorld()==null||player.isInMultiplayer()||player.getWorld().getHost()!=player
                    ||point.chapter()!=run.getChapterId()||!GameData.getQuestDataMap().containsKey(point.forwardEntryQuest()))
                return false;
            state.point(run.getPointId()).setState(2);
            var progress=state.point(pointId);
            if(point.forwardEntryQuest()==point.entryQuest()) {
                if(progress.getState()==0) progress.setState(1);
                progress.setStart(run.getCurrent().copy());
            }
            run.setPointId(pointId);
            run.getQuestStarts().clear(); onPointStarted(player,pointId);
            run.setStageQuestId(point.forwardEntryQuest());
            player.save(); player.getQuestManager().addQuest(point.forwardEntryQuest());
            CoopSystem.notifyState(player);
            return true;
        }
    }
    public static void onQuestFinished(GameQuest quest) {
        if(quest.getState()!=QuestState.QUEST_STATE_FINISHED) return;
        var player=quest.getOwner(); var state=player.getHangouts();
        synchronized(state) {
            var run=run(player); if(run==null) return;
            var point=catalog().point(run.getPointId()); if(point==null) return;
            int id=quest.getSubQuestId();
            if(point.auxiliary().containsKey(id)&&run.getStartedObservers().contains(id)) {
                run.getCompletedAuxiliary().add(id); player.save(); return;
            }
            if(run.getStageQuestId()!=id) return;
            run.setLastCompletedStage(id); run.setLastCompletedPoint(point.id());
            run.setStageQuestId(0);
            if(run.isAuxiliaryStage()) {
                run.getCompletedAuxiliary().add(id); run.setAuxiliaryStage(false);
            } else {
                var stage=point.stages().get(id); if(stage==null) return;
                run.getPendingStageIds().clear(); run.getPendingStageIds().addAll(stage.nextStages());
                run.getPendingPointIds().clear(); run.getPendingPointIds().addAll(stage.nextPoints());
                run.setPhaseCompleted(stage.nextStages().isEmpty());
            }
            player.save(); advanceSingle(player); onTick(player);
        }
    }
    /** Restrict graph callbacks to this point's current stage or its just-completed source. */
    public static boolean mayReportSave(Player player,int savePoint) {
        var run=run(player); if(run==null) return false;
        var point=catalog().point(run.getPointId()); if(point==null) return false;
        for(int id:new int[]{run.getStageQuestId(),run.getLastCompletedPoint()==point.id()
                ?run.getLastCompletedStage():0}) {
            var stage=point.stages().get(id);
            if(stage!=null&&stage.reportSaves().contains(savePoint)) return true;
            var auxiliary=point.auxiliary().get(id);
            if(auxiliary!=null&&auxiliary.reportSaves().contains(savePoint)) return true;
        }
        return false;
    }
    public static void onSave(Player player) { if(run(player)!=null) onTick(player); }
    private static List<Choice> choices(Player player) {
        var run=run(player); if(run==null) return List.of();
        var point=catalog().point(run.getPointId()); if(point==null) return List.of();
        if(run.getStageQuestId()!=0) {
            var stage=point.stages().get(run.getStageQuestId());
            var auxiliary=point.auxiliary().get(run.getStageQuestId());
            if(stage!=null&&stage.kind().equals("checkpoint"))
                return List.of(new Choice(Kind.CHECKPOINT,stage.quest(),stage.label()));
            if(auxiliary!=null&&auxiliary.kind().equals("checkpoint"))
                return List.of(new Choice(Kind.CHECKPOINT,auxiliary.quest(),auxiliary.label()));
            return List.of();
        }
        var choices=new ArrayList<Choice>();
        for(int id:run.getPendingStageIds()) {
            var stage=point.stages().get(id);
            if(stage!=null) choices.add(new Choice(Kind.STAGE,id,stage.label()));
        }
        for(int id:run.getPendingPointIds()) {
            var target=catalog().point(id);
            if(target!=null) choices.add(new Choice(Kind.POINT,id,target.label()));
        }
        if(run.isPhaseCompleted()) for(var auxiliary:point.auxiliary().values())
            if(!auxiliary.observer()&&!run.getCompletedAuxiliary().contains(auxiliary.quest()))
                choices.add(new Choice(Kind.AUXILIARY,auxiliary.quest(),"补充剧情："+auxiliary.label()));
        return choices;
    }
    private static boolean select(Player player,Choice choice) {
        var run=run(player); if(run==null||!choices(player).contains(choice)) return false;
        if(choice.kind()==Kind.CHECKPOINT) {
            var quest=player.getQuestManager().getQuestById(choice.id());
            if(quest==null||quest.getState()!=QuestState.QUEST_STATE_UNFINISHED
                    ||!quest.getQuestData().getFinishCond().isEmpty()) return false;
            cleanup(player.getScene(),player.getUid());
            quest.finish();
            return true;
        }
        if(choice.kind()==Kind.POINT) return advanceToPoint(player,choice.id());
        if(!GameData.getQuestDataMap().containsKey(choice.id())) return false;
        run.setStageQuestId(choice.id()); run.setAuxiliaryStage(choice.kind()==Kind.AUXILIARY);
        var point=catalog().point(run.getPointId());
        if(choice.kind()==Kind.STAGE&&choice.id()==point.entryQuest()) {
            var progress=player.getHangouts().point(point.id());
            if(progress.getState()==0) progress.setState(1);
            progress.setStart(run.getCurrent().copy());
        }
        if(choice.kind()==Kind.STAGE) run.getPendingStageIds().clear();
        cleanup(player.getScene(),player.getUid());
        player.save(); player.getQuestManager().addQuest(choice.id()); CoopSystem.notifyState(player);
        return true;
    }
    private static void advanceSingle(Player player) {
        var next=choices(player);
        if(next.size()==1&&next.getFirst().kind()!=Kind.CHECKPOINT) select(player,next.getFirst());
    }
    public static void onTick(Player player) {
        var scene=player.getScene();
        if(scene==null||player.getWorld()!=scene.getWorld()
                ||player.getSceneLoadState()!=Player.SceneLoadState.LOADED) return;
        var run=run(player);
        if(run==null||player.isInMultiplayer()||scene.getWorld().getHost()!=player) {
            cleanup(scene,player.getUid()); return;
        }
        var point=catalog().point(run.getPointId()); if(point==null) return;
        // Explicit passive helpers may observe actual changes alongside the selected story stage.
        for(var auxiliary:point.auxiliary().values()) if(auxiliary.observer()
                &&!run.getStartedObservers().contains(auxiliary.quest())
                &&GameData.getQuestDataMap().containsKey(auxiliary.quest())) {
            run.getStartedObservers().add(auxiliary.quest());
            var existing=player.getQuestManager().getQuestById(auxiliary.quest());
            if(existing==null||existing.getState()==QuestState.QUEST_STATE_UNSTARTED)
                player.getQuestManager().addQuest(auxiliary.quest());
            player.save();
        }
        var choices=choices(player);
        if(choices.size()==1&&choices.getFirst().kind()!=Kind.CHECKPOINT) { advanceSingle(player); return; }
        var existing=selectors(scene,player.getUid());
        if(choices.isEmpty()) { cleanup(scene,player.getUid()); return; }
        if(existing.size()==choices.size()&&existing.stream().allMatch(selector->
                selector.pointId==run.getPointId()&&choices.contains(selector.choice))) return;
        cleanup(scene,player.getUid());
        if(!GameData.getGadgetDataMap().containsKey(GADGET)) return;
        var message=new StringBuilder("邀约路线（重建）：附近机关从西到东分别对应：");
        for(int i=0;i<choices.size();i++) {
            var choice=choices.get(i);
            var position=new Position(player.getPosition()).add(new Position((i-(choices.size()-1)/2f)*2.5f,0,2));
            scene.addEntity(new Selector(scene,position,player.getUid(),run.getPointId(),choice));
            message.append('\n').append(i+1).append(". ").append(choice.label());
        }
        player.dropMessage(message.toString());
    }
    /** A foreign/expired selector is consumed here, never forwarded to generic Lua quest progress. */
    public static boolean tryInteract(Player player,GameEntity entity,int option) {
        if(!(entity instanceof Selector selector)) return false;
        var state=player.getHangouts();
        synchronized(state) {
            var run=run(player);
            if(selector.playerUid!=player.getUid()||run==null||run.getPointId()!=selector.pointId
                    ||option!=OPTION||player.getScene()!=selector.getScene()
                    ||player.getScene().getEntities().get(entity.getId())!=entity
                    ||player.getSceneLoadState()!=Player.SceneLoadState.LOADED||player.isInMultiplayer()
                    ||player.getScene().getWorld().getHost()!=player
                    ||player.getPosition().computeDistance(selector.getPosition())>4) return true;
            if(select(player,selector.choice)) player.sendPacket(new PacketSelectWorktopOptionRsp(entity.getId(),option));
        }
        return true;
    }
    private static List<Selector> selectors(Scene scene,int uid) {
        return scene.getEntities().values().stream().filter(Selector.class::isInstance)
                .map(Selector.class::cast).filter(selector->selector.playerUid==uid).toList();
    }
    private static void cleanup(Scene scene,int uid) {
        if(scene!=null) for(var selector:selectors(scene,uid)) scene.removeEntity(selector,VisionType.VisionType_VISION_REMOVE);
    }
    /** No static cache retains Player, Scene, or gadget references. */
    public static void onLogout(Player player) { cleanup(player.getScene(),player.getUid()); }
}
