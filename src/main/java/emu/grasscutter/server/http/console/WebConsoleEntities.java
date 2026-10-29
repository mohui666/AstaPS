package emu.grasscutter.server.http.console;

import com.google.gson.*;
import emu.grasscutter.data.*;
import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.*;
import emu.grasscutter.game.world.Position;
import emu.grasscutter.utils.lang.Language;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static emu.grasscutter.config.Configuration.GAME_OPTIONS;
import static emu.grasscutter.server.http.console.WebConsoleItems.Grant.integer;

/** Typed console spawning. Router authorization and the game logic thread are required. */
final class WebConsoleEntities {
    record Property(int id, String name, boolean percent) {}
    record Entry(int id, String kind, String type, String name, boolean named) {}
    record Receipt(int requested, int spawned, int sceneId, List<Integer> entityIds, List<String> output) {}
    static final List<Property> PROPERTIES = readProperties();
    static final Set<String> KINDS = Set.of("monster", "npc", "gadget", "vehicle", "item");

    private static List<Property> readProperties() {
        try (var stream = Objects.requireNonNull(WebConsoleEntities.class.getResourceAsStream("/html/entity-properties.json"));
             var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            var result = new ArrayList<Property>();
            for (var element : JsonParser.parseReader(reader).getAsJsonArray()) {
                var row = element.getAsJsonObject();
                result.add(new Property(row.get("id").getAsInt(), row.get("name").getAsString(), row.get("percent").getAsBoolean()));
            }
            return List.copyOf(result);
        } catch (IOException e) { throw new IllegalStateException("实体属性字典读取失败。", e); }
    }

    static String name(String candidate, String fallback, int id) {
        return candidate != null && candidate.matches("(?s).*[\\p{IsHan}].*") ? candidate : fallback + "（编号 " + id + "）";
    }

    private static String chinese(long hash) {
        if (hash == 0) return null;
        var text = Language.getTextMapKey(hash);
        return text == null ? null : text.get("CHS");
    }

    static List<Entry> catalog() {
        var entries = new ArrayList<Entry>();
        for (var data : GameData.getMonsterDataMap().values()) {
            var m = MonsterCatalog.entry(data);
            String display = name(m.name(), "未命名怪物", m.id());
            entries.add(new Entry(m.id(), "monster", switch (m.type()) {
                case "MONSTER_BOSS" -> "首领"; case "MONSTER_ENV_ANIMAL" -> "动物";
                case "MONSTER_FISH" -> "鱼类"; case "MONSTER_LITTLE_MONSTER" -> "小型怪物";
                default -> "普通怪物";
            }, display, !display.startsWith("未命名")));
        }
        for (var data : GameData.getNpcDataMap().values()) {
            var text = name(chinese(data.getNameTextMapHash()), "未命名角色模型", data.getId());
            entries.add(new Entry(data.getId(), "npc", "角色模型", text, !text.startsWith("未命名")));
        }
        for (var data : GameData.getGadgetDataMap().values()) {
            boolean vehicle = data.getType() == EntityType.Vehicle;
            var text = name(chinese(data.getNameTextMapHash()), vehicle ? "载具" : "未命名装置", data.getId());
            entries.add(new Entry(data.getId(), vehicle ? "vehicle" : "gadget", vehicle ? "载具" : "装置", text, !text.contains("编号 ")));
        }
        for (var data : GameData.getItemDataMap().values()) {
            if (data.getGadgetId() <= 0 || !ItemCatalog.supported(data.getItemType())) continue;
            var item = ItemCatalog.entry(data);
            var text = name(item.name(), "未命名掉落物", data.getId());
            entries.add(new Entry(data.getId(), "item", "掉落物", text, !text.startsWith("未命名")));
        }
        entries.sort(Comparator.comparing(Entry::kind).thenComparingInt(Entry::id));
        return entries;
    }

    record Request(int uid, String kind, int id, int amount, int level, double distance,
                   Position position, Position rotation, Integer ai, Integer pose, Integer camp, Integer state,
                   Boolean invincible, Boolean lockHp, Map<FightProperty, Float> properties) {
        static Request parse(String body) {
            var json = JsonParser.parseString(body).getAsJsonObject();
            int uid = positive(json, "uid", "玩家编号", null), id = positive(json, "id", "实体编号", null);
            String kind = WebConsolePlayerTools.string(json, "kind");
            if (!KINDS.contains(kind)) throw new IllegalArgumentException("实体类型无效。");
            int amount = positive(json, "amount", "数量", 1), level = positive(json, "level", "等级", 90);
            double distance = json.has("distance") ? WebConsolePlayerTools.number(json, "distance", 0, 100) : 5;
            var properties = new EnumMap<FightProperty, Float>(FightProperty.class);
            if (json.has("properties")) {
                if (!json.get("properties").isJsonObject()) throw new IllegalArgumentException("战斗属性格式错误。");
                for (var row : json.getAsJsonObject("properties").entrySet()) {
                    Property property;
                    try { property = PROPERTIES.stream().filter(p -> p.id() == Integer.parseInt(row.getKey())).findFirst().orElseThrow(); }
                    catch (RuntimeException e) { throw new IllegalArgumentException("未知的战斗属性编号。"); }
                    var value = row.getValue();
                    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
                        throw new IllegalArgumentException(property.name() + "必须为数值。");
                    double number = value.getAsDouble() / (property.percent() ? 100 : 1);
                    float converted = (float) number;
                    if (!Double.isFinite(number) || !Float.isFinite(converted) || (value.getAsBigDecimal().signum() != 0 && converted == 0))
                        throw new IllegalArgumentException(property.name() + "超出有效数值范围。");
                    if ((property.id() == 1010 || property.id() == 2000) && number <= 0)
                        throw new IllegalArgumentException(property.name() + "必须大于零。");
                    properties.put(FightProperty.getPropById(property.id()), converted);
                }
            }
            Integer ai = optionalInt(json, "ai", "人工智能配置"), pose = optionalInt(json, "pose", "姿态编号"),
                    camp = optionalInt(json, "camp", "阵营编号"), state = optionalInt(json, "state", "装置状态");
            Boolean invincible = json.has("invincible") ? WebConsolePlayerTools.bool(json, "invincible") : null;
            Boolean lockHp = json.has("lockHp") ? WebConsolePlayerTools.bool(json, "lockHp") : null;
            if (!kind.equals("monster") && (!properties.isEmpty() || ai != null || pose != null || camp != null || invincible != null || lockHp != null))
                throw new IllegalArgumentException("怪物属性只能用于怪物类型。");
            if (state != null && !kind.equals("gadget")) throw new IllegalArgumentException("装置状态只能用于装置类型。");
            return new Request(uid, kind, id, amount, level, distance, vector(json, "position"), vector(json, "rotation"),
                    ai, pose, camp, state, invincible, lockHp, Map.copyOf(properties));
        }
    }

    private static int positive(JsonObject json, String key, String label, Integer defaultValue) {
        int value;
        try { value = integer(json, key, defaultValue); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException(label + "必须为有效整数。"); }
        if (value <= 0) throw new IllegalArgumentException(label + "必须为正整数。");
        return value;
    }
    private static Integer optionalInt(JsonObject json, String key, String label) {
        if (!json.has(key)) return null;
        int value;
        try { value = integer(json, key, null); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException(label + "必须为有效整数。"); }
        if (value < 0) throw new IllegalArgumentException(label + "不能为负数。");
        return value;
    }
    private static Position vector(JsonObject json, String key) {
        if (!json.has(key)) return null;
        if (!json.get(key).isJsonObject()) throw new IllegalArgumentException("坐标或朝向格式错误。");
        var value = json.getAsJsonObject(key);
        return new Position((float)WebConsolePlayerTools.number(value, "x", -100000, 100000),
                (float)WebConsolePlayerTools.number(value, "y", -100000, 100000), (float)WebConsolePlayerTools.number(value, "z", -100000, 100000));
    }

    static Receipt spawn(Request request) {
        Player player = WebConsolePlayerTools.player(request.uid());
        var scene = player.getScene();
        if (scene == null) throw new IllegalArgumentException("该玩家尚未进入游戏场景。");
        boolean exists = switch (request.kind()) {
            case "monster" -> GameData.getMonsterDataMap().containsKey(request.id());
            case "npc" -> GameData.getNpcDataMap().containsKey(request.id());
            case "item" -> { var item = GameData.getItemDataMap().get(request.id());
                yield item != null && item.getGadgetId() > 0 && ItemCatalog.supported(item.getItemType()); }
            default -> { var data = GameData.getGadgetDataMap().get(request.id());
                yield data != null && (data.getType() == EntityType.Vehicle) == request.kind().equals("vehicle"); }
        };
        if (!exists) throw new IllegalArgumentException("当前资源中没有此类型的实体编号。");
        if (request.kind().equals("monster") && !GameData.getMonsterCurveDataMap().containsKey(request.level()))
            throw new IllegalArgumentException("当前资源没有该怪物等级数据。");
        int allowed = (int)Math.min(request.amount(), Math.max(0L, (long)GAME_OPTIONS.sceneEntityLimit - scene.getEntities().size()));
        var ids = new ArrayList<Integer>();
        var origin = request.position() == null ? player.getPosition().clone() : request.position().clone();
        var rotation = request.rotation() == null ? player.getRotation().clone() : request.rotation().clone();
        if (request.position() == null) {
            double yaw = Math.toRadians(player.getRotation().getY());
            origin.addX((float)(Math.sin(yaw) * request.distance())).addZ((float)(Math.cos(yaw) * request.distance()));
        }
        for (int i = 0; i < allowed; i++) {
            var position = origin.clone();
            if (i > 0) { double angle = i * 2.399963; double radius = .7 * Math.sqrt(i);
                position.addX((float)(Math.cos(angle) * radius)).addZ((float)(Math.sin(angle) * radius)); }
            GameEntity entity = switch (request.kind()) {
                case "monster" -> new EntityMonster(scene, GameData.getMonsterDataMap().get(request.id()), position, rotation, request.level());
                case "npc" -> new EntityNPC(scene, request.id(), position, rotation);
                case "item" -> new EntityItem(scene, null, GameData.getItemDataMap().get(request.id()), position.addY(1), rotation, 1, true);
                case "vehicle" -> new EntityVehicle(scene, player, request.id(), 0, position, rotation);
                default -> new EntityGadget(scene, request.id(), position, rotation);
            };
            if (entity instanceof EntityMonster monster) {
                monster.setSpawnFightProperties(request.properties());
                if (request.ai() != null) monster.setAiId(request.ai());
                if (request.pose() != null) monster.setPoseId(request.pose());
                if (request.camp() != null) monster.setCampId(request.camp());
                if (request.invincible() != null) monster.setSpawnInvincible(request.invincible());
                if (request.lockHp() != null) monster.setSpawnLockHp(request.lockHp());
            } else if (entity instanceof EntityGadget gadget) {
                gadget.buildContent();
                if (request.state() != null) gadget.setState(request.state());
                if (scene.getSceneType() == SceneType.SCENE_WORLD) scene.getBlossomManager().initBlossom(gadget);
            }
            scene.addEntity(entity);
            if (scene.getEntityById(entity.getId()) == entity) ids.add(entity.getId());
        }
        return new Receipt(request.amount(), ids.size(), scene.getId(), List.copyOf(ids), allowed < request.amount()
                ? List.of("场景实体数量达到上限，本次最多允许生成 " + allowed + " 个。") : List.of());
    }
}
