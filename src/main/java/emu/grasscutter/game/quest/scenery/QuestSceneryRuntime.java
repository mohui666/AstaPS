package emu.grasscutter.game.quest.scenery;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.entity.gadget.GadgetWorktop;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.*;
import emu.grasscutter.game.world.*;
import emu.grasscutter.net.proto.QuestSceneryOuterClass.QuestScenery;
import emu.grasscutter.net.proto.QuestSceneryInfoOuterClass.QuestSceneryInfo;
import emu.grasscutter.net.proto.VisionTypeOuterClass.VisionType;
import emu.grasscutter.server.packet.send.*;
import java.util.*;

/** Native state and sub-scene IDs; the missing open/pause entry is an explicit physical reconstruction. */
public final class QuestSceneryRuntime {
    public static final int NONE=0, PAUSING=1, RUNNING=2, FINISH=3;
    private static final int GADGET=70350081, OPTION=7;
    private static final class Selector extends EntityGadget {
        final int uid,primary,target;
        Selector(Scene scene,Position position,int uid,int primary,int target) {
            super(scene,GADGET,position,new Position(),null,0,0,false);
            this.uid=uid; this.primary=primary; this.target=target;
            setContent(new GadgetWorktop(this));
            ((GadgetWorktop)getContent()).addWorktopOptions(new int[]{OPTION});
        }
    }
    private QuestSceneryRuntime() {}
    private static QuestSceneryCatalog catalog() { return QuestSceneryCatalog.installed(); }
    public static int state(Player player,int primary) {
        var entry=player.getScenery().getEntries().get(primary); return entry==null?NONE:entry.getState();
    }
    private static boolean owner(Player player) {
        return player.getWorld()!=null&&player.getWorld().getHost()==player&&!player.isInMultiplayer();
    }
    private static boolean completed(Player player,QuestSceneryCatalog.Primary primary) {
        return primary.completionQuests().stream().allMatch(id -> {
            var quest=player.getQuestManager().getQuestById(id);
            return quest!=null&&quest.getState()==QuestState.QUEST_STATE_FINISHED;
        });
    }
    private static boolean available(Player player,QuestSceneryCatalog.Primary primary) {
        if(completed(player,primary)||state(player,primary.id())==FINISH) return false;
        if(player.getScenery().getEntries().containsKey(primary.id())) return true;
        return primary.parents().stream().map(player.getQuestManager()::getMainQuestById)
                .filter(Objects::nonNull).anyMatch(main -> main.getState()!=ParentQuestState.PARENT_QUEST_STATE_FINISHED
                        &&!main.getActiveQuests().isEmpty());
    }
    /** Only an original linked quest may select a native variant. No arbitrary sub-ID is accepted. */
    public static boolean activate(GameQuest quest,int subId) {
        var sub=catalog().sub(subId); var player=quest.getOwner();
        if(sub==null||!owner(player)||!catalog().primary(sub.primary()).parents().contains(quest.getMainQuestId()))
            return false;
        var saved=player.getScenery().entry(sub.primary());
        if(saved.getState()==FINISH) return false;
        saved.setSubSceneryId(subId);
        if(saved.getState()==NONE) saved.setState(RUNNING);
        changed(player,sub.primary()); return true;
    }
    /** Native 190 uses the primary ID and a comma-separated set of enum values. */
    public static boolean matchesCurrent(GameQuest quest,QuestData.QuestContentCondition condition) {
        int[] params=condition.getParam();
        if(params==null||params.length<1||catalog().primary(params[0])==null
                ||condition.getParamStr()==null||condition.getParamStr().isBlank()) return false;
        boolean matched=false;
        try {
            for(String token:condition.getParamStr().split(",",-1)) {
                int value=Integer.parseInt(token.trim()); if(value<NONE||value>FINISH)return false;
                boolean current=value==state(quest.getOwner(),params[0]);
                int scene=catalog().runningSceneGate(quest.getSubQuestId());
                if(value==RUNNING&&scene>0)current&=quest.getOwner().getScene()!=null
                        &&quest.getOwner().getScene().getId()==scene&&quest.getOwner().getSceneId()==scene
                        &&quest.getOwner().getSceneLoadState()==Player.SceneLoadState.LOADED;
                matched|=current;
            }
        } catch(NumberFormatException error) { return false; }
        return matched;
    }
    /** Prevent a damaged flattened prerequisite from restarting a paused story. */
    public static boolean mayAccept(Player player,QuestData quest) {
        for(var primary:catalog().primaries())
            if(primary.parents().contains(quest.getMainId())&&state(player,primary.id())==PAUSING) return false;
        return true;
    }
    /** Native ROLLBACK_QUEST calls start directly. Keep its actual checkpoint until resume. */
    public static boolean mayStart(GameQuest quest) {
        var player=quest.getOwner();
        for(var primary:catalog().primaries()) if(primary.parents().contains(quest.getMainQuestId())
                &&state(player,primary.id())==PAUSING) {
            var saved=player.getScenery().entry(primary.id());
            saved.getResumeStarts().put(quest.getMainQuestId(),quest.getSubQuestId()); player.save();
            return false;
        }
        return true;
    }
    public static boolean preserveOnLogin(GameMainQuest main) {
        return catalog().primaries().stream().anyMatch(primary -> primary.parents().contains(main.getParentQuestId())
                &&state(main.getOwner(),primary.id())!=NONE);
    }
    private static boolean pauseCondition(GameQuest quest,int primary) {
        return quest.getQuestData().getFailCond().stream().anyMatch(condition ->
                condition.getType()==QuestContent.QUEST_CONTENT_SCENERY_STATE_EQUAL
                        &&condition.getParam()!=null&&condition.getParam().length>0&&condition.getParam()[0]==primary
                        &&Arrays.asList(condition.getParamStr().split(",")).contains("1"));
    }
    static boolean select(Player player,int id,int target) {
        var primary=catalog().primary(id);
        if(primary==null||!owner(player)||!available(player,primary)||(target!=RUNNING&&target!=PAUSING))return false;
        int old=state(player,id);
        if(target==PAUSING&&old!=RUNNING||target==RUNNING&&old!=NONE&&old!=PAUSING)return false;
        var saved=player.getScenery().entry(id);
        if(saved.getSubSceneryId()==0)saved.setSubSceneryId(primary.initialSub());
        if(target==PAUSING) for(int mainId:primary.parents()) {
            var main=player.getQuestManager().getMainQuestById(mainId); if(main==null)continue;
            for(var quest:main.getActiveQuests()) if(pauseCondition(quest,id))saved.getPausedQuests().add(quest.getSubQuestId());
        }
        saved.setState(target); changed(player,id);
        if(target==RUNNING) {
            for(var pending:Map.copyOf(saved.getResumeStarts()).entrySet()) {
                var quest=player.getQuestManager().getQuestById(pending.getValue());
                if(quest!=null&&quest.getMainQuestId()==pending.getKey()&&primary.parents().contains(pending.getKey())
                        &&quest.getState()!=QuestState.QUEST_STATE_FINISHED)quest.start();
            }
            for(int questId:List.copyOf(saved.getPausedQuests())) {
                var quest=player.getQuestManager().getQuestById(questId);
                if(quest!=null&&primary.parents().contains(quest.getMainQuestId())
                        &&!saved.getResumeStarts().containsKey(quest.getMainQuestId())
                        &&quest.getState()==QuestState.QUEST_STATE_FAILED&&pauseCondition(quest,id))quest.start();
            }
            saved.getPausedQuests().clear();saved.getResumeStarts().clear(); player.save();
        }
        return true;
    }
    private static void changed(Player player,int id) {
        player.save(); cleanup(player.getScene(),player.getUid());
        player.sendPacket(new PacketQuestSceneryInfoUpdateNotify(player));
        player.sendPacket(new PacketPlayerWorldSceneInfoListNotify(player));
        applyScene(player);
        player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_SCENERY_STATE_EQUAL,id);
    }
    /** A view of native conflict-tag IDs; no permanent account tag is removed. */
    public static Map<Integer,Set<Integer>> effectiveSceneTags(Player player,Map<Integer,Set<Integer>> original) {
        var result=new HashMap<Integer,Set<Integer>>();original.forEach((scene,tags)->result.put(scene,new HashSet<>(tags)));
        for(var entry:player.getScenery().getEntries().values()) if(entry.getState()==RUNNING) {
            var sub=catalog().sub(entry.getSubSceneryId());
            if(sub!=null) result.computeIfAbsent(3,ignored->new HashSet<>()).removeAll(sub.hiddenTags());
        }
        return result;
    }
    private static void applyScene(Player player) {
        var scene=player.getScene();
        if(!owner(player)||scene==null||scene.getWorld()!=player.getWorld()
                ||!scene.getScriptManager().isInit()||scene.getScriptManager().isDestroyed())return;
        var scripts=scene.getScriptManager();
        for(var primary:catalog().primaries()) {
            var saved=player.getScenery().getEntries().get(primary.id());
            var sub=saved==null?null:catalog().sub(saved.getSubSceneryId());
            var suites=saved!=null&&saved.getState()==RUNNING&&sub!=null?sub.suites():Map.<Integer,Integer>of();
            for(int groupId:catalog().groups(primary.id())) {
                if(catalog().groupScene(groupId)!=scene.getId())continue;
                var instance=scripts.getGroupInstanceById(groupId); Integer suite=suites.get(groupId);
                if(suite==null) {
                    if(instance!=null) {
                        instance.getLuaGroup().dontUnload=false;scene.unregisterDynamicGroup(groupId);
                    }
                    continue;
                }
                if(instance==null) {
                    var group=scripts.getGroupById(groupId); if(group==null)continue;
                    group.dontUnload=true;instance=scripts.getGroupInstanceById(groupId);
                }
                if(instance!=null)instance.getLuaGroup().dontUnload=true;
                if(instance!=null&&instance.getActiveSuiteId()!=suite)scripts.refreshGroupSuite(groupId,suite);
            }
        }
    }
    public static QuestSceneryInfo toProto(Player player) {
        var result=QuestSceneryInfo.newBuilder();
        for(var primary:catalog().primaries()) {
            var saved=player.getScenery().getEntries().get(primary.id()); if(saved==null)continue;
            if(saved.getState()<NONE||saved.getState()>FINISH)throw new IllegalStateException("Invalid saved scenery state");
            var sub=catalog().sub(saved.getSubSceneryId());
            if(sub==null||sub.primary()!=primary.id())throw new IllegalStateException("Invalid saved scenery variant");
            result.addSceneryList(QuestScenery.newBuilder().setPrimarySceneryId(primary.id())
                    .setSubSceneryId(saved.getSubSceneryId()).setState(saved.getState()));
        }
        return result.build();
    }
    public static void onLogin(Player player) { player.sendPacket(new PacketQuestSceneryInfoUpdateNotify(player)); }
    public static void onSceneLoaded(Player player) {
        applyScene(player);player.sendPacket(new PacketQuestSceneryInfoUpdateNotify(player));
    }
    public static void onTick(Player player) {
        var scene=player.getScene();
        if(scene==null||player.getSceneLoadState()!=Player.SceneLoadState.LOADED)return;
        if(!owner(player)) { cleanup(scene,player.getUid());return; }
        for(var primary:catalog().primaries()) if(state(player,primary.id())!=NONE
                &&state(player,primary.id())!=FINISH&&completed(player,primary)) {
            var saved=player.getScenery().entry(primary.id());saved.setState(FINISH);saved.getPausedQuests().clear();saved.getResumeStarts().clear();
            changed(player,primary.id());
        }
        applyScene(player);
        var existing=selectors(scene,player.getUid());
        var candidates=catalog().primaries().stream().filter(primary->available(player,primary)).toList();
        // Pausing inside a plot dungeon needs native dungeon-exit logic; the authored table is outside it.
        if(scene.getId()!=3&&scene.getId()!=1085&&scene.getId()!=1161)candidates=List.of();
        final var choices=candidates;
        if(existing.size()==choices.size()&&existing.stream().allMatch(selector->
                player.getPosition().computeDistance(selector.getPosition())<=30&&choices.stream()
                .anyMatch(primary->primary.id()==selector.primary&&(state(player,primary.id())==RUNNING?PAUSING:RUNNING)==selector.target)))return;
        cleanup(scene,player.getUid());
        if(!emu.grasscutter.data.GameData.getGadgetDataMap().containsKey(GADGET))return;
        for(int index=0;index<choices.size();index++) {
            var primary=choices.get(index);int target=state(player,primary.id())==RUNNING?PAUSING:RUNNING;
            scene.addEntity(new Selector(scene,new Position(player.getPosition()).add(new Position(index*3,0,3)),
                    player.getUid(),primary.id(),target));
            player.dropMessage("【重建剧情场景】"+primary.label()+"：附近机关可"+(target==RUNNING?"开启／继续":"暂停")+"。对白与战斗仍需实际完成。");
        }
    }
    public static boolean tryInteract(Player player,GameEntity entity,int option) {
        if(!(entity instanceof Selector selector))return false;
        if(selector.uid!=player.getUid()||option!=OPTION||player.getScene()!=entity.getScene()
                ||player.getScene().getEntities().get(entity.getId())!=entity
                ||player.getSceneLoadState()!=Player.SceneLoadState.LOADED
                ||player.getPosition().computeDistance(entity.getPosition())>4)return true;
        if(select(player,selector.primary,selector.target))player.sendPacket(new PacketSelectWorktopOptionRsp(entity.getId(),option));
        return true;
    }
    private static List<Selector> selectors(Scene scene,int uid) {
        return scene.getEntities().values().stream().filter(Selector.class::isInstance).map(Selector.class::cast)
                .filter(selector->selector.uid==uid).toList();
    }
    private static void cleanup(Scene scene,int uid) {
        if(scene!=null)for(var selector:selectors(scene,uid))scene.removeEntity(selector,VisionType.VisionType_VISION_REMOVE);
    }
    public static void onLogout(Player player) { cleanup(player.getScene(),player.getUid()); }
}
