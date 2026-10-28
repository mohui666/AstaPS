package emu.grasscutter.game.quest.comedy;

import emu.grasscutter.GameConstants;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.entity.gadget.GadgetWorktop;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.*;
import emu.grasscutter.game.world.*;
import emu.grasscutter.net.proto.VisionTypeOuterClass.VisionType;
import emu.grasscutter.server.packet.send.*;
import emu.grasscutter.utils.Utils;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Four explicit world-quest scores, never a generic client-controlled Exhibition counter. */
public final class ComedyVoucherRuntime {
    private static final Set<Integer> QUESTS=Set.of(7402526,7402529,7402534,7402539);
    private static final Map<Integer,Board> BOARDS=new ConcurrentHashMap<>();
    private static final Map<Integer,Player> RESTORED=new ConcurrentHashMap<>();
    private static final class Board {
        final Player player; final Scene scene; final ComedyVoucherCatalog.Stage stage; final int start;
        final Map<Integer,EntityGadget> actors=new ConcurrentHashMap<>();
        Board(Player player,ComedyVoucherCatalog.Stage stage,int start) { this.player=player;scene=player.getScene();this.stage=stage;this.start=start; }
    }
    private ComedyVoucherRuntime() {}
    private static GameQuest active(Player player,ComedyVoucherCatalog.Stage stage) {
        var quest=player.getQuestManager().getQuestById(stage.quest());
        return quest!=null && quest.getState()==QuestState.QUEST_STATE_UNFINISHED && quest.getStartTime()>0
                && !quest.getMainQuest().isFinished() ? quest:null;
    }
    private static boolean ready(Player player) {
        return player.getWorld()!=null && player.getWorld().getHost()==player && player.getWorld().getPlayers().size()==1
                && player.getScene()!=null && player.getScene().getWorld()==player.getWorld()
                && player.getScene().getId()==player.getSceneId() && player.getSceneLoadState()==Player.SceneLoadState.LOADED;
    }
    private static ComedyVoucherState.Progress ensure(Player player,ComedyVoucherCatalog.Stage stage,GameQuest quest) {
        var progress=player.getComedyVouchers().get(stage.quest());
        if(progress==null || progress.getQuestStart()!=quest.getStartTime()) {
            progress=new ComedyVoucherState.Progress(); progress.setQuestStart(quest.getStartTime());progress.setAnchor(stage.anchor().clone());
            progress.getOrder().addAll(List.of(1,2,3));Collections.shuffle(progress.getOrder(),Utils.random);
            player.getComedyVouchers().put(stage.quest(),progress);
        }
        return progress;
    }
    public static void onItemAdded(Player player,int item,int acceptedQuantity) {
        if(item!=120400 || acceptedQuantity<=0 || player.getQuestManager().getMainQuestById(74025)==null) return;
        synchronized(player.getComedyVouchers()) {
            for(var stage:ComedyVoucherCatalog.installed().stages.values()) {
                var quest=active(player,stage);if(quest==null)continue;
                var progress=ensure(player,stage,quest);
                progress.setEarnedItems((int)Math.min(Integer.MAX_VALUE,(long)progress.getEarnedItems()+acceptedQuantity));
                saveAndSignal(player,stage,progress);
            }
        }
    }
    public static void onTick(Player player) {
        var existing=BOARDS.get(player.getUid());
        if(!ready(player)) { retire(player.getUid(),existing);return; }
        if(player.getQuestManager().getMainQuestById(74025)==null) return;
        synchronized(player.getComedyVouchers()) {
            var catalog=ComedyVoucherCatalog.installed();
            if(RESTORED.put(player.getUid(),player)!=player) for(var stage:catalog.stages.values()) {
                var quest=active(player,stage);var progress=player.getComedyVouchers().get(stage.quest());
                if(quest!=null && progress!=null && progress.getQuestStart()==quest.getStartTime() && progress.total()>=stage.target())
                    player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_EXHIBITION_ACCUMULATE_GT_EQ,stage.exhibition());
            }
            var stage=catalog.stages.values().stream().filter(candidate -> candidate.scene()==player.getSceneId() && active(player,candidate)!=null
                    && Double.isFinite(player.getPosition().computeDistance(candidate.anchor()))
                    && player.getPosition().computeDistance(candidate.anchor())<=40).findFirst().orElse(null);
            if(stage==null) { retire(player.getUid(),existing);return; }
            var quest=active(player,stage);var saved=player.getComedyVouchers().get(stage.quest());var progress=ensure(player,stage,quest);
            if(saved!=progress)player.save();
            if(progress.total()>=stage.target()) { retire(player.getUid(),existing);return; }
            if(existing!=null && existing.player==player && existing.scene==player.getScene() && existing.stage==stage && existing.start==quest.getStartTime())return;
            retire(player.getUid(),existing);var board=new Board(player,stage,quest.getStartTime());BOARDS.put(player.getUid(),board);rebuild(board);
            tell(player,catalog.notice);show(board,progress);
        }
    }
    public static boolean tryInteract(Player player,GameEntity entity,int option) {
        var board=BOARDS.get(player.getUid());
        if(board!=null && board.actors.containsValue(entity)) { synchronized(player.getComedyVouchers()) { interact(board,player,entity,option); }return true; }
        var host=player.getWorld()==null?null:player.getWorld().getHost();var owned=host==null?null:BOARDS.get(host.getUid());
        return owned!=null && owned.actors.containsValue(entity);
    }
    private static void interact(Board board,Player player,GameEntity entity,int option) {
        var quest=active(player,board.stage);var progress=player.getComedyVouchers().get(board.stage.quest());
        if(!ready(player)||board.player!=player||board.scene!=player.getScene()||quest==null||quest.getStartTime()!=board.start
                ||progress==null||progress.getQuestStart()!=board.start||progress.total()>=board.stage.target()||option!=7
                ||board.scene.getEntityById(entity.getId())!=entity||!(entity instanceof EntityGadget gadget)
                ||!(gadget.getContent() instanceof GadgetWorktop worktop)||!worktop.getWorktopOptions().contains(option)
                ||!Double.isFinite(player.getPosition().computeDistance(entity.getPosition()))||player.getPosition().computeDistance(entity.getPosition())>4)return;
        int selected=board.actors.entrySet().stream().filter(entry -> entry.getValue()==entity).mapToInt(Map.Entry::getKey).findFirst().orElse(-1);
        if(selected!=progress.getOrder().get(progress.getNextAction())) {
            progress.setNextAction(0);tell(player,"加工顺序错误，本件重做；已取得记分保留。");
        } else if(progress.getNextAction()+1==progress.getOrder().size()) {
            progress.setWorkpiecePoints(progress.getWorkpiecePoints()+ComedyVoucherCatalog.installed().points);progress.setNextAction(0);
            Collections.shuffle(progress.getOrder(),Utils.random);
        } else progress.setNextAction(progress.getNextAction()+1);
        saveAndSignal(player,board.stage,progress);rebuild(board);show(board,progress);
    }
    private static void saveAndSignal(Player player,ComedyVoucherCatalog.Stage stage,ComedyVoucherState.Progress progress) {
        boolean signal=progress.total()>=stage.target()&&!progress.isEmitted();if(signal)progress.setEmitted(true);
        player.save();if(signal)player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_EXHIBITION_ACCUMULATE_GT_EQ,stage.exhibition());
    }
    public static boolean matchesCurrent(GameQuest quest,QuestData.QuestContentCondition condition) {
        if(!QUESTS.contains(quest.getSubQuestId()))return false;
        var stage=ComedyVoucherCatalog.installed().stages.get(quest.getSubQuestId());var params=condition.getParam();
        var progress=quest.getOwner().getComedyVouchers().get(quest.getSubQuestId());
        return params!=null&&params.length>=2&&params[0]==stage.exhibition()&&params[1]==0&&condition.getCount()==stage.target()
                &&progress!=null&&progress.getQuestStart()==quest.getStartTime()&&progress.total()>=stage.target();
    }
    public static void onQuestStart(GameQuest quest) {
        if(!QUESTS.contains(quest.getSubQuestId()))return;
        var player=quest.getOwner();synchronized(player.getComedyVouchers()) {
            if(player.getComedyVouchers().reset(quest.getSubQuestId()))player.save();
            var board=BOARDS.get(player.getUid());if(board!=null&&board.stage.quest()==quest.getSubQuestId())retire(player.getUid(),board);
        }
    }
    public static boolean preserveOnLogin(GameMainQuest main) {
        return main.getParentQuestId()==74025&&!main.isFinished()&&main.getActiveQuests().stream().anyMatch(quest -> {
            var saved=main.getOwner().getComedyVouchers().get(quest.getSubQuestId());return saved!=null&&saved.getQuestStart()==quest.getStartTime();
        });
    }
    public static void onLogout(Player player) {
        var board=BOARDS.get(player.getUid());if(board!=null&&board.player==player)retire(player.getUid(),board);RESTORED.remove(player.getUid(),player);
    }
    private static void retire(int uid,Board board) { if(board!=null){BOARDS.remove(uid,board);removeActors(board);} }
    private static void removeActors(Board board) {
        for(var actor:board.actors.values())if(board.scene.getEntityById(actor.getId())==actor)board.scene.removeEntity(actor,VisionType.VisionType_VISION_REMOVE);
        board.actors.clear();
    }
    @SuppressWarnings("removal")
    private static void rebuild(Board board) {
        removeActors(board);var progress=board.player.getComedyVouchers().get(board.stage.quest());if(progress.total()>=board.stage.target())return;
        for(int index=1;index<=3;index++) {
            if(!GameData.getGadgetDataMap().containsKey(70350081))throw new IllegalStateException("Missing comedy worktop gadget");
            var position=progress.getAnchor().clone().addX((index-2)*4).addZ(3);
            var actor=new EntityGadget(board.scene,70350081,position,new Position());actor.setConfigId(910000+index);
            actor.setContent(new GadgetWorktop(actor));((GadgetWorktop)actor.getContent()).addWorktopOptions(new int[]{7});
            board.actors.put(index,actor);board.scene.addEntity(actor);board.scene.broadcastPacket(new PacketWorktopOptionNotify(actor));
        }
    }
    private static void show(Board board,ComedyVoucherState.Progress progress) {
        tell(board.player,"【本轮剧情记分】"+progress.total()+"/"+board.stage.target()+"（实际新增120400："+progress.getEarnedItems()
                +"，重建加工："+progress.getWorkpiecePoints()+"）。三台装置由小X到大X为1/2/3；当前加工顺序 "+progress.getOrder()
                +"，已做"+progress.getNextAction()+"步。"+(progress.total()>=board.stage.target()?"本轮达标。":"每件完整三步记100分。"));
    }
    private static void tell(Player player,String message) { player.sendPacket(new PacketPrivateChatNotify(GameConstants.SERVER_CONSOLE_UID,player.getUid(),message)); }
}
