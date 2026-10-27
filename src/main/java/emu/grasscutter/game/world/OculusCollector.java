package emu.grasscutter.game.world;

import static com.mongodb.client.model.Filters.*;

import com.google.gson.JsonParser;
import com.mongodb.client.model.ReplaceOneModel;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.WriteModel;
import emu.grasscutter.database.DatabaseManager;
import emu.grasscutter.game.entity.EntityGadget;
import emu.grasscutter.game.inventory.*;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.ActionReason;
import emu.grasscutter.game.reward.RewardAreaResolver;
import emu.grasscutter.game.reward.RewardContext;
import emu.grasscutter.net.proto.VisionTypeOuterClass.VisionType;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.bson.Document;

/** Collect the indexed oculi of the player's present country in that player's own world. */
public final class OculusCollector {
    public record Mark(int groupId, int configId, int gadgetId, int pointType) {
        String key() { return groupId + ":" + configId; }
    }
    public record Point(int sceneId, int cityId, int itemId, float x, float y, float z, List<Mark> marks) {}
    private record Plan(int cityId, int itemId, List<Point> points, List<Point> remaining) {}
    private static final Map<Integer,Integer> ITEMS = Map.of(
            1,107001,2,107003,3,107014,4,107017,5,107023,6,107028,7,107030,8,107035);
    private static final Map<Integer,String> NAMES = Map.of(
            1,"蒙德",2,"璃月",3,"稻妻",4,"须弥",5,"枫丹",6,"纳塔",7,"挪德卡莱",8,"至冬");
    private static final Map<Integer,String> ELEMENTS = Map.of(
            1,"风神瞳",2,"岩神瞳",3,"雷神瞳",4,"草神瞳",5,"水神瞳",6,"火神瞳",7,"月神瞳",8,"冰神瞳");
    private static volatile List<Point> catalog;
    private static volatile Set<Integer> gadgetIds;
    private static final boolean INDEX_PRESENT_AT_START = Files.isRegularFile(Path.of("data/oculus-points.json"));

    private OculusCollector() {}

    public static boolean isOculusGadget(int gadgetId) {
        if (!INDEX_PRESENT_AT_START) return false;
        points();
        return gadgetIds.contains(gadgetId);
    }

    private static List<Point> points() {
        if (catalog != null) return catalog;
        synchronized (OculusCollector.class) {
            if (catalog != null) return catalog;
            var file = Path.of("data/oculus-points.json");
            if (!Files.isRegularFile(file)) throw new IllegalArgumentException("尚未建立当前版本的神瞳索引。");
            try (var reader = Files.newBufferedReader(file)) {
                var loaded = readPoints(reader);
                var ids = new HashSet<Integer>();
                for (var point : loaded) for (var mark : point.marks()) ids.add(mark.gadgetId());
                gadgetIds = Set.copyOf(ids);
                catalog = loaded;
                return catalog;
            } catch (IOException error) {
                throw new IllegalStateException("无法读取神瞳索引。",error);
            }
        }
    }

    // This project's Gson predates Java record deserialization support.
    static List<Point> readPoints(Reader reader) {
        var result = new ArrayList<Point>();
        for (var element : JsonParser.parseReader(reader).getAsJsonArray()) {
            var point = element.getAsJsonObject();
            var marks = new ArrayList<Mark>();
            for (var entry : point.getAsJsonArray("marks")) {
                var mark = entry.getAsJsonObject();
                marks.add(new Mark(mark.get("groupId").getAsInt(),mark.get("configId").getAsInt(),
                        mark.get("gadgetId").getAsInt(),mark.get("pointType").getAsInt()));
            }
            result.add(new Point(point.get("sceneId").getAsInt(),point.get("cityId").getAsInt(),
                    point.get("itemId").getAsInt(),point.get("x").getAsFloat(),point.get("y").getAsFloat(),
                    point.get("z").getAsFloat(),List.copyOf(marks)));
        }
        return List.copyOf(result);
    }

    static List<Point> remaining(List<Point> points, Set<String> claimed) {
        return points.stream().filter(p -> p.marks().stream().noneMatch(m -> claimed.contains(m.key()))).toList();
    }

    private static Plan plan(Player player) {
        var scene = player.getScene();
        if (scene == null || scene.getId() != 3) throw new IllegalArgumentException("请回到大世界主地图后使用。");
        if (scene.getWorld().getHost().getUid() != player.getUid())
            throw new IllegalArgumentException("请回到自己的世界，不能领取其他玩家世界的神瞳。");
        var pos = player.getPosition();
        int area = WorldAreaIndex.forScene(scene.getId()).level1AreaAt(pos.getX(),pos.getZ());
        int city = RewardAreaResolver.resolve(new RewardContext(scene.getId(),area,null)).cityId();
        if (city == 9) city = 8;
        if (!ITEMS.containsKey(city)) throw new IllegalArgumentException("当前位置尚不能准确识别所属国家，请移动到该地区后刷新。");
        int item = ITEMS.get(city), country = city;
        var points = points().stream().filter(p -> p.sceneId()==scene.getId() && p.cityId()==country && p.itemId()==item).toList();
        if (points.isEmpty()) throw new IllegalArgumentException("本机资源尚未定位这个国家的神瞳。");
        var claimed = new HashSet<String>();
        var collection = DatabaseManager.getGameDatabase().getCollection("open_world_spawns");
        for (var record : collection.find(and(eq("ownerUid",player.getUid()),eq("sceneId",scene.getId()),eq("gatherItemId",item)))) {
            if (record.get("consumedAt") instanceof Number time && time.longValue()>0)
                claimed.add(record.get("groupId")+":"+record.get("configId"));
        }
        // Loaded Lua groups can also retain older collection progress.
        for (var point : points) for (var mark : point.marks()) {
            var group = scene.getScriptManager().getGroupInstanceById(mark.groupId());
            if (group != null && group.getDeadEntities().contains(mark.configId())) claimed.add(mark.key());
        }
        return new Plan(city,item,points,remaining(points,claimed));
    }

    public static Map<String,Object> preview(Player player) {
        try {
            var plan = plan(player);
            return Map.of("available",true,"cityId",plan.cityId(),"country",NAMES.get(plan.cityId()),
                    "oculus",ELEMENTS.get(plan.cityId()),"itemId",plan.itemId(),
                    "total",plan.points().size(),"remaining",plan.remaining().size(),
                    "collected",plan.points().size()-plan.remaining().size());
        } catch (IllegalArgumentException error) {
            return Map.of("available",false,"reason",error.getMessage());
        }
    }

    /** Called by the existing console on the game's single logic thread. */
    public static String collect(Player player, int expectedCity) {
        var plan = plan(player);
        if (plan.cityId() != expectedCity) throw new IllegalArgumentException("角色所在国家已变化，请刷新后再收集。");
        int count = plan.remaining().size();
        if (count == 0) return NAMES.get(plan.cityId()) + "的已索引神瞳已经全部收集，没有重复发放。";
        var scene = player.getScene();
        var changed = new HashSet<String>();
        List<WriteModel<Document>> records = new ArrayList<>();
        long now = System.currentTimeMillis()/1000L;
        for (var point : plan.points()) for (var mark : point.marks()) {
            changed.add(mark.key());
            var document = new Document("ownerUid",player.getUid()).append("sceneId",scene.getId())
                    .append("groupId",mark.groupId()).append("configId",mark.configId())
                    .append("gadgetId",mark.gadgetId()).append("monsterId",0).append("gatherItemId",point.itemId())
                    .append("posX",point.x()).append("posY",point.y()).append("posZ",point.z())
                    .append("consumedAt",now).append("refreshId",0);
            records.add(new ReplaceOneModel<>(and(eq("ownerUid",player.getUid()),eq("sceneId",scene.getId()),
                    eq("groupId",mark.groupId()),eq("configId",mark.configId())),document,new ReplaceOptions().upsert(true)));
        }
        var result = player.getInventory().addItems(List.of(new GameItem(plan.itemId(),count)),ActionReason.Gather,
                InventoryAddPolicy.ALL_OR_NOTHING,() -> true,
                () -> DatabaseManager.getGameDatabase().getCollection("open_world_spawns").bulkWrite(records));
        if (!result.allAccepted()) throw new IllegalArgumentException("背包未能接收神瞳，未执行收集。");
        var updatedGroups = new HashSet<Integer>();
        for (var point : plan.points()) for (var mark : point.marks()) {
            var group = scene.getScriptManager().getGroupInstanceById(mark.groupId());
            if (group != null) {
                group.getDeadEntities().add(mark.configId());
                updatedGroups.add(mark.groupId());
            }
        }
        for (int id : updatedGroups) scene.getScriptManager().getGroupInstanceById(id).save();
        for (var entity : new ArrayList<>(scene.getEntities().values()))
            if (entity instanceof EntityGadget && changed.contains(entity.getGroupId()+":"+entity.getConfigId()))
                scene.removeEntity(entity,VisionType.VisionType_VISION_REMOVE);
        player.save();
        return "已收集"+NAMES.get(plan.cityId())+"的"+ELEMENTS.get(plan.cityId())+" × "+count+"，已保存采集进度。";
    }
}
