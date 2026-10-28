package emu.grasscutter.game.quest.field;

import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.entity.gadget.*;
import emu.grasscutter.game.inventory.*;
import emu.grasscutter.game.player.*;
import emu.grasscutter.game.props.ActionReason;
import emu.grasscutter.game.props.PlayerProperty;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.*;
import emu.grasscutter.game.world.*;
import emu.grasscutter.net.proto.VisionTypeOuterClass.VisionType;
import emu.grasscutter.server.packet.send.*;
import java.util.*;

/** Missing field games are explicitly reconstructed physical actions, not client quest counters. */
public final class QuestFieldRuntime {
    private static final int WORKTOP=70350081,OPTION=7,COMPASS=220009;
    private enum Kind { FISH, CAPTURE }
    private static final class Controller extends EntityGadget {
        final int uid,binding;final Kind kind;
        int phase,phaseAt,reels,animal;
        Controller(Scene scene,Position position,int uid,Kind kind,int binding) {
            super(scene,WORKTOP,position,new Position(),null,0,0,false);
            this.uid=uid;this.kind=kind;this.binding=binding;
            setContent(new GadgetWorktop(this));((GadgetWorktop)getContent()).addWorktopOptions(new int[]{OPTION});
        }
    }
    private static final class Compass extends EntityGadget {
        final int uid,created;
        Compass(Scene scene,Position position,Position rotation,int uid) {
            super(scene,70500008,position,rotation,null,0,0,false);this.uid=uid;created=scene.getSceneTime();
        }
    }
    private QuestFieldRuntime(){}
    private static QuestFieldCatalog catalog(){return QuestFieldCatalog.installed();}
    private static boolean owner(Player p){return p.getWorld()!=null&&p.getWorld().getHost()==p&&!p.isInMultiplayer();}
    private static boolean ready(Player p){return owner(p)&&p.getScene()!=null&&p.getScene().getWorld()==p.getWorld()
            &&p.getScene().getId()==p.getSceneId()&&p.getSceneLoadState()==Player.SceneLoadState.LOADED&&!p.isPaused();}
    private static int avatar(Player p){var team=p.getTeamManager();var actor=team==null?null:team.getCurrentAvatarEntity();
        return actor==null?0:actor.getEntityTypeId();}
    private static GameQuest active(Player p,int id){var q=p.getQuestManager().getQuestById(id);
        return q!=null&&q.getState()==QuestState.QUEST_STATE_UNFINISHED?q:null;}
    public static void onQuestStarted(GameQuest quest){if(catalog().ownsQuest(quest.getSubQuestId())){
        quest.getOwner().getFieldGames().reset(quest.getSubQuestId());quest.getOwner().save();}}
    public static boolean preserveOnLogin(GameMainQuest main){return main.getActiveQuests().stream().anyMatch(q->
            catalog().ownsQuest(q.getSubQuestId())&&main.getOwner().getFieldGames().getRounds().containsKey(q.getSubQuestId()));}
    private static boolean fishMatches(QuestData.QuestContentCondition c,QuestFieldCatalog.Fish fish,int pool){
        int[] p=c.getParam();return c.getType()==QuestContent.QUEST_CONTENT_FISHING_SUCC&&p!=null&&p.length>0
                &&p[0]>=0&&p[0]<=2&&(p[0]!=1||!fish.furniture())&&(p[0]!=2||fish.furniture())
                &&(p.length<2||p[1]==0||p[1]==pool);
    }
    public static boolean matchesCurrent(GameQuest quest,QuestData.QuestContentCondition condition){
        if(!catalog().ownsQuest(quest.getSubQuestId()))return false;
        var saved=quest.getOwner().getFieldGames().getRounds().get(quest.getSubQuestId());
        if(saved==null||saved.getCount()<Math.max(1,condition.getCount()))return false;
        return switch(condition.getType()){
            case QUEST_CONTENT_FISHING_SUCC -> {var fish=catalog().fish(saved.getSourceId());
                yield fish!=null&&fishMatches(condition,fish,saved.getPoolId());}
            case QUEST_CONTENT_CAPTURE_USE_MATERIAL_LIST -> saved.getMaterialId()>0&&condition.getParam()!=null
                    &&Arrays.stream(condition.getParam()).anyMatch(id->id==saved.getMaterialId());
            case QUEST_CONTENT_USE_WIDGET -> condition.getParam()!=null&&condition.getParam().length>0
                    &&condition.getParam()[0]==saved.getMaterialId();
            case QUEST_CONTENT_AVATAR_CAPTURE_ANIMAL -> {
                var text=condition.getParamStr();boolean result=false;
                if(text!=null){var pair=text.split(";",-1);if(pair.length==2)try{
                    result=Integer.parseInt(pair[0])==saved.getAvatarId()&&Integer.parseInt(pair[1])==saved.getSourceId();
                }catch(NumberFormatException error){result=false;}}
                yield result;
            }
            default -> false;
        };
    }
    private static List<GameQuest> waiting(Player player,QuestFieldCatalog.Fishing fishing){
        var fish=catalog().fish(fishing.getFish());var result=new ArrayList<GameQuest>();
        for(int id:fishing.getQuests()) {var quest=active(player,id);if(quest==null)continue;
            if(quest.getQuestData().getFinishCond().stream().anyMatch(c->fishMatches(c,fish,fishing.getPool())&&!matchesCurrent(quest,c)))result.add(quest);}
        return result;
    }
    private static boolean captureWaiting(Player player,QuestFieldCatalog.Capture entry){
        var quest=active(player,entry.getQuest());return quest!=null&&quest.getQuestData().getFinishCond().stream()
                .anyMatch(c->(c.getType()==QuestContent.QUEST_CONTENT_AVATAR_CAPTURE_ANIMAL
                        ||c.getType()==QuestContent.QUEST_CONTENT_CAPTURE_USE_MATERIAL_LIST)&&!matchesCurrent(quest,c));
    }
    private static boolean prepareTrial(Player p,QuestFieldCatalog.Capture c){
        if(c.getAvatar()==0||avatar(p)==c.getAvatar())return true;
        var team=p.getTeamManager();var current=team.getQuestPartyLock();
        if(current!=null&&current.getQuestMainId()!=c.getQuest()/100)return false;
        var trial=GameData.getTrialAvatarDataMap().get(c.getTrial());
        if(trial==null||trial.getTrialAvatarParamList().isEmpty()||trial.getTrialAvatarParamList().getFirst()!=c.getAvatar())return false;
        return team.lockQuestParty(new QuestPartyLock(c.getQuest()/100,QuestPartyLock.Kind.MIRROR_TRIAL,
                List.of(c.getTrial()),team.getCurrentCharacterIndex(),0))&&avatar(p)==c.getAvatar();
    }
    private static List<Controller> controllers(Player p){return p.getScene().getEntities().values().stream()
            .filter(Controller.class::isInstance).map(Controller.class::cast).filter(c->c.uid==p.getUid()).toList();}
    private static void removeActor(Controller c){var actor=c.getScene().getEntityById(c.animal);
        if(actor!=null)c.getScene().removeEntity(actor,VisionType.VisionType_VISION_REMOVE);c.animal=0;}
    private static void remove(Controller c){removeActor(c);c.getScene().removeEntity(c,VisionType.VisionType_VISION_REMOVE);}
    private static EntityMonster spawn(Controller c,int monster,int pose,Position position){
        var data=GameData.getMonsterDataMap().get(monster);if(data==null)throw new IllegalStateException("Missing explicit field-game animal "+monster);
        var animal=new EntityMonster(c.getScene(),data,position,new Position(),1);animal.setPoseId(pose);
        c.getScene().addEntity(animal);c.animal=animal.getId();return animal;
    }
    private static EntityMonster animal(Controller c){var entity=c.getScene().getEntityById(c.animal);
        return entity instanceof EntityMonster m&&m.isAlive()?m:null;}
    public static void onTick(Player player){
        recoverDiveUnlock(player);
        var scene=player.getScene();if(scene==null)return;
        for(var entity:List.copyOf(scene.getEntities().values()))if(entity instanceof Compass compass&&compass.uid==player.getUid()
                &&Integer.toUnsignedLong(scene.getSceneTime()-compass.created)>5000)scene.removeEntity(compass,VisionType.VisionType_VISION_REMOVE);
        if(!ready(player)){for(var controller:controllers(player))remove(controller);return;}
        for(var c:controllers(player)){
            boolean wanted=c.kind==Kind.FISH?!waiting(player,catalog().fishing(c.binding)).isEmpty():captureWaiting(player,catalog().capture(c.binding));
            if(!wanted){remove(c);continue;}
            if(c.kind==Kind.CAPTURE&&c.animal!=0&&animal(c)==null){
                var lost=scene.getEntityById(c.animal);var quest=active(player,c.binding);
                if(lost!=null&&!lost.isAlive()&&quest!=null)quest.fail();
                remove(c);continue;
            }
            if(c.phase!=0&&(player.getPosition().computeDistance(c.getPosition())>6||animal(c)==null)){
                if(c.kind==Kind.CAPTURE&&c.animal!=0&&scene.getEntityById(c.animal)!=null&&!scene.getEntityById(c.animal).isAlive()){
                    var q=active(player,c.binding);if(q!=null)q.fail();
                }
                removeActor(c);c.phase=0;continue;
            }
            int elapsed=(int)Integer.toUnsignedLong(scene.getSceneTime()-c.phaseAt);
            if(c.kind==Kind.FISH){
                if(c.phase==1&&elapsed>=3000){c.phase=2;c.phaseAt=scene.getSceneTime();player.dropMessage("咬钩了！三秒内操作钓具提竿。");}
                else if((c.phase==2||c.phase==3)&&elapsed>3000){removeActor(c);c.phase=0;player.dropMessage("鱼儿脱钩，请重新抛线。");}
            }else{
                var capture=catalog().capture(c.binding);
                if(c.phase==0&&c.animal==0)spawn(c,capture.getAnimal(),0,c.getPosition().clone().addX(3));
                if(c.phase==4){if(!captureActorValid(player,c,capture)){c.phase=0;continue;}
                    if(elapsed>=3000)completeCapture(player,c,capture);}
            }
        }
        if(scene.getId()!=3||!GameData.getGadgetDataMap().containsKey(WORKTOP))return;
        for(var fishing:catalog().getFishing()){
            var targets=waiting(player,fishing);if(targets.isEmpty()||controllers(player).stream().anyMatch(c->c.kind==Kind.FISH&&c.binding==fishing.getId()))continue;
            Position bank=fishing.getBank();
            if(fishing.isBankAtPlayer()){
                if(player.getPosition().computeDistance(fishing.getWater())>50)continue;
                var saved=player.getFieldGames().round(targets.getFirst().getSubQuestId());
                if(saved.getAnchor()==null){saved.setAnchor(player.getPosition().clone());player.save();}bank=saved.getAnchor();
            }
            if(player.getPosition().computeDistance(bank)>65)continue;
            scene.addEntity(new Controller(scene,bank.clone(),player.getUid(),Kind.FISH,fishing.getId()));
            player.dropMessage("【重建钓具】"+fishing.getLabel()+"：操作抛线，等咬钩提示后提竿，再间隔半秒至三秒操作收线三次。钓具自带饵料。");
        }
        for(var capture:catalog().getCapture()){
            if(!captureWaiting(player,capture)||controllers(player).stream().anyMatch(c->c.kind==Kind.CAPTURE&&c.binding==capture.getQuest()))continue;
            var saved=player.getFieldGames().round(capture.getQuest());
            if(saved.getAnchor()==null){saved.setAnchor(capture.isAnchorAtPlayer()?player.getPosition().clone():capture.getPosition().clone());player.save();}
            if(player.getPosition().computeDistance(saved.getAnchor())>45||!prepareTrial(player,capture))continue;
            var c=new Controller(scene,saved.getAnchor().clone().addZ(2),player.getUid(),Kind.CAPTURE,capture.getQuest());
            scene.addEntity(c);spawn(c,capture.getAnimal(),0,c.getPosition().clone().addX(3));
            player.dropMessage(capture.getAvatar()>0?"【重建调查】使用当前试用角色靠近小动物，操作调查台并保持三秒。":
                    "【重建捕捉】装备四方八方之网，靠近小动物后操作捕捉台并保持三秒；成功才消耗一张网并收取原表奖励。");
        }
    }
    private static boolean captureActorValid(Player p,Controller c,QuestFieldCatalog.Capture entry){
        var animal=animal(c);return ready(p)&&captureWaiting(p,entry)&&animal!=null&&animal.getMonsterData().getId()==entry.getAnimal()
                &&p.getPosition().computeDistance(animal.getPosition())<=6
                &&(entry.getAvatar()==0||avatar(p)==entry.getAvatar())
                &&(entry.getMaterial()==0||p.getWidgetId()==entry.getMaterial()&&p.getInventory().getItemCountById(entry.getMaterial())>0);
    }
    private static void record(Player p,GameQuest q,int source,int pool,int actor,int material,QuestContent type){
        var saved=p.getFieldGames().round(q.getSubQuestId());saved.setCount(saved.getCount()+1);saved.setSourceId(source);
        saved.setPoolId(pool);saved.setAvatarId(actor);saved.setMaterialId(material);p.save();
        switch(type){
            case QUEST_CONTENT_USE_WIDGET,QUEST_CONTENT_CAPTURE_USE_MATERIAL_LIST -> p.getQuestManager().queueEvent(type,material);
            case QUEST_CONTENT_AVATAR_CAPTURE_ANIMAL -> p.getQuestManager().queueEvent(type,actor,source);
            default -> p.getQuestManager().queueEvent(type,source,pool);
        }
    }
    private static void completeCapture(Player p,Controller c,QuestFieldCatalog.Capture entry){
        var quest=active(p,entry.getQuest());if(quest==null||!captureActorValid(p,c,entry))return;
        if(entry.getMaterial()>0){
            var result=p.getInventory().addItems(List.of(new GameItem(entry.getReward(),1)),ActionReason.QuestAction,
                    InventoryAddPolicy.ALL_OR_NOTHING,()->captureActorValid(p,c,entry)&&p.getInventory().removeItem(entry.getMaterial(),1),()->c.phase=5);
            if(!result.allAccepted()){c.phase=0;p.dropMessage("背包未能接收捕获奖励，本次没有计入成功。");return;}
        }
        record(p,quest,entry.getAnimal(),0,avatar(p),entry.getMaterial(),entry.getAvatar()>0
                ?QuestContent.QUEST_CONTENT_AVATAR_CAPTURE_ANIMAL:QuestContent.QUEST_CONTENT_CAPTURE_USE_MATERIAL_LIST);
        remove(c);
    }
    private static void catchFish(Player p,Controller c,QuestFieldCatalog.Fishing fishing){
        var fish=catalog().fish(fishing.getFish());var targets=waiting(p,fishing);
        if(targets.isEmpty()||animal(c)==null)return;
        var result=p.getInventory().addItems(List.of(new GameItem(fish.item(),1)),ActionReason.QuestAction,
                InventoryAddPolicy.ALL_OR_NOTHING,()->ready(p)&&animal(c)!=null&&!waiting(p,fishing).isEmpty(),()->c.phase=5);
        if(!result.allAccepted()){c.phase=0;removeActor(c);p.dropMessage("背包未能接收鱼获，本次没有计入成功。");return;}
        for(var quest:targets)record(p,quest,fish.id(),fishing.getPool(),0,0,QuestContent.QUEST_CONTENT_FISHING_SUCC);
        removeActor(c);c.phase=0;p.dropMessage(fish.furniture()?"捕获了一条观赏鱼。":"钓到了一条鱼。");
    }
    public static boolean tryInteract(Player player,GameEntity entity,int option){
        if(!(entity instanceof Controller c))return false;
        if(c.uid!=player.getUid()||option!=OPTION||!ready(player)||player.getScene()!=entity.getScene()
                ||player.getScene().getEntities().get(entity.getId())!=entity||player.getPosition().computeDistance(entity.getPosition())>4)return true;
        int now=player.getScene().getSceneTime();
        if(c.kind==Kind.CAPTURE){var entry=catalog().capture(c.binding);
            if(c.phase==0&&captureActorValid(player,c,entry)){c.phase=4;c.phaseAt=now;player.dropMessage("保持靠近目标三秒，正在处理…");}
        }else{
            var fishing=catalog().fishing(c.binding);if(waiting(player,fishing).isEmpty())return true;
            if(c.phase==0){var fish=catalog().fish(fishing.getFish());spawn(c,fish.monster(),fish.pose(),fishing.getWater());
                c.phase=1;c.phaseAt=now;c.reels=0;player.dropMessage("已抛线，请等待咬钩提示。");}
            else if(c.phase==1){removeActor(c);c.phase=0;player.dropMessage("提竿过早，请重新抛线。");}
            else if(c.phase==2){if(Integer.toUnsignedLong(now-c.phaseAt)<=3000){c.phase=3;c.phaseAt=now;player.dropMessage("已钩住！间隔半秒至三秒收线三次。");}}
            else if(c.phase==3){long elapsed=Integer.toUnsignedLong(now-c.phaseAt);if(elapsed>=500&&elapsed<=3000){
                c.reels++;c.phaseAt=now;if(c.reels==3)catchFish(player,c,fishing);}}
        }
        player.sendPacket(new PacketSelectWorktopOptionRsp(entity.getId(),option));return true;
    }
    /** A real original widget effect, with source-backed gadget and an explicit reconstructed cooldown. */
    public static boolean useCompass(Player player){
        long now=System.currentTimeMillis();var state=player.getFieldGames();
        if(!ready(player)||player.getWidgetId()!=COMPASS||player.getInventory().getItemCountById(COMPASS)<1
                ||!compassCityAllowed(player)||now-state.getLastCompassUse()<10000||!GameData.getGadgetDataMap().containsKey(70500008))return false;
        var chest=player.getScene().getEntities().values().stream().filter(EntityGadget.class::isInstance).map(EntityGadget.class::cast)
                .filter(g->g.getContent() instanceof GadgetChest&&g.getState()!=102&&player.getPosition().computeDistance(g.getPosition())<=200)
                .min(Comparator.comparingDouble(g->player.getPosition().computeDistance(g.getPosition()))).orElse(null);
        var rotation=player.getRotation().clone();if(chest!=null)rotation.setY((float)Math.toDegrees(Math.atan2(
                chest.getPosition().getX()-player.getPosition().getX(),chest.getPosition().getZ()-player.getPosition().getZ())));
        player.getScene().addEntity(new Compass(player.getScene(),player.getPosition().clone().addY(1),rotation,player.getUid()));
        state.setLastCompassUse(now);player.save();
        var quest=active(player,99809);if(quest!=null)record(player,quest,0,0,0,COMPASS,QuestContent.QUEST_CONTENT_USE_WIDGET);
        player.sendPacket(new PacketWidgetCoolDownNotify(2,now+10000,true));
        player.dropMessage(chest==null?"罗盘没有探测到附近可见的未开启宝箱。":"罗盘指向附近的未开启宝箱，距离约"+
                Math.round(player.getPosition().computeDistance(chest.getPosition()))+"米。");return true;
    }
    private static boolean compassCityAllowed(Player player){
        var area=GameData.getWorldAreaDataMap().get(player.getAreaId());var city=GameData.getCityDataMap().get(1);
        return player.getSceneId()==3&&area!=null&&area.getSceneId()==3&&city!=null&&city.getAreaIdVec()!=null
                &&city.getAreaIdVec().contains(area.getParentArea());
    }
    /** Equipping/arming a net never consumes it or emits capture success. */
    public static boolean armNet(Player player){
        if(!ready(player)||player.getWidgetId()!=220043||player.getInventory().getItemCountById(220043)<1)return false;
        onTick(player);
        return controllers(player).stream().anyMatch(c->c.kind==Kind.CAPTURE
                &&catalog().capture(c.binding).getMaterial()==220043&&captureActorValid(player,c,catalog().capture(c.binding)));
    }
    /** Repair only the old ignored-exec gap. A later genuine disable exec is never overwritten. */
    public static void recoverDiveUnlock(Player player){
        var state=player.getFieldGames();if(state.isDiveExecObserved())return;
        var quest=player.getQuestManager().getQuestById(7466801);
        if(quest==null||quest.getState()!=QuestState.QUEST_STATE_FINISHED)return;
        if(player.getProperty(PlayerProperty.PROP_PLAYER_CAN_DIVE)==0)
            player.setProperty(PlayerProperty.PROP_PLAYER_CAN_DIVE,1);
        state.setDiveExecObserved(true);player.save();
    }
    public static void onLogin(Player player){recoverDiveUnlock(player);}
    public static void onLogout(Player player){if(player.getScene()==null)return;
        for(var c:controllers(player))remove(c);
        for(var e:List.copyOf(player.getScene().getEntities().values()))if(e instanceof Compass c&&c.uid==player.getUid())
            player.getScene().removeEntity(c,VisionType.VisionType_VISION_REMOVE);
    }
}
