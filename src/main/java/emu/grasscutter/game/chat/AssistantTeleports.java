package emu.grasscutter.game.chat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import emu.grasscutter.data.GameData;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.reward.RewardAreaResolver;
import emu.grasscutter.game.reward.RewardContext;
import emu.grasscutter.game.world.Position;
import emu.grasscutter.server.event.player.PlayerTeleportEvent.TeleportType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/** Personal saved locations use the player's UID, never a chat-supplied file path. */
final class AssistantTeleports {
    static final Map<String, Integer> REGIONS = Map.of(
            "蒙德", 1, "璃月", 2, "稻妻", 3, "须弥", 4,
            "枫丹", 5, "纳塔", 6, "挪德卡莱", 7, "至冬", 8);
    record Destination(int scene, float x, float y, float z) {
        static Destination current(Player p) {
            return new Destination(p.getSceneId(), p.getPosition().getX(), p.getPosition().getY(), p.getPosition().getZ());
        }

        Destination {
            if (scene < 1 || !Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                    || Math.abs(x) > 100_000 || y < -10_000 || y > 100_000 || Math.abs(z) > 100_000)
                throw new IllegalArgumentException("场景或坐标无效。");
        }

        JsonObject json() {
            var json = new JsonObject();
            json.addProperty("scene", scene);
            json.addProperty("x", x);
            json.addProperty("y", y);
            json.addProperty("z", z);
            return json;
        }

        static Destination read(JsonObject json) {
            return new Destination(json.get("scene").getAsInt(), json.get("x").getAsFloat(),
                    json.get("y").getAsFloat(), json.get("z").getAsFloat());
        }
    }

    private final Map<Integer, Destination> previous = new HashMap<>();

    void clear(int uid) { previous.remove(uid); }

    static Path path(int uid) { return Path.of("data", "assistant-teleports", uid + ".json"); }

    private static JsonObject saved(int uid) throws IOException {
        var path = path(uid);
        if (!Files.exists(path)) return new JsonObject();
        try (var reader = Files.newBufferedReader(path)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    String list(Player player) throws IOException {
        var names = saved(player.getUid()).keySet();
        return "地区：蒙德 / 璃月 / 稻妻 / 须弥 / 枫丹 / 纳塔 / 挪德卡莱 / 至冬\n"
                + "发送：传送 地区名（到本服该地区的原生锚点）\n"
                + "我的地点：" + (names.isEmpty() ? "暂无；发送“记点 名字”保存脚下位置。" : String.join(" / ", names))
                + "\n传送 名字 / 返回 / 删除地点 名字\n坐标：瞬移 x y z [场景ID]\n锚点：传送点 ID [场景ID]\n找朋友：找人 UID";
    }

    String remember(Player player, String name, boolean delete) throws IOException {
        if (name.isBlank() || name.length() > 30 || REGIONS.containsKey(name))
            throw new IllegalArgumentException("地点名须为 1–30 字，且不能与地区名相同。");
        var saved = saved(player.getUid());
        if (delete) {
            if (saved.remove(name) == null) throw new IllegalArgumentException("没有这个已存地点。");
        } else saved.add(name, Destination.current(player).json());
        var path = path(player.getUid());
        Files.createDirectories(path.getParent());
        Files.writeString(path, saved.toString());
        return delete ? "已删除地点：" + name : "已记住：" + name + "；发送“传送 " + name + "”即可回来。";
    }

    String named(Player player, String name) throws IOException {
        var saved = saved(player.getUid());
        if (saved.has(name)) return transfer(player, Destination.read(saved.getAsJsonObject(name)));
        Integer country = REGIONS.get(name);
        if (country == null) throw new IllegalArgumentException("没有这个地点。发送“传送”查看地区与已存地点。");
        var point = GameData.getScenePointEntryMap().values().stream()
                .filter(entry -> entry.getSceneId() == 3)
                .map(entry -> entry.getPointData())
                .filter(p -> p != null && "SceneTransPoint".equals(p.getType()) && p.getTranPos() != null
                        && !p.isModelHidden() && !p.isGroupLimit())
                .filter(p -> {
                    int city = RewardAreaResolver.resolve(new RewardContext(3, p.getAreaId(), null)).cityId();
                    return city == country || (country == 8 && city == 9);
                })
                .min(Comparator.comparingInt(p -> p.getId()))
                .orElseThrow(() -> new IllegalArgumentException("当前资源中没有该地区可用的原生锚点。"));
        var pos = point.getTranPos();
        return transfer(player, new Destination(3, pos.getX(), pos.getY(), pos.getZ()));
    }

    String back(Player player) {
        var destination = previous.get(player.getUid());
        if (destination == null) throw new IllegalArgumentException("本次登录还没有助手传送记录。");
        return transfer(player, destination);
    }

    String transfer(Player player, Destination destination) {
        if (!GameData.getSceneDataMap().containsKey(destination.scene()))
            throw new IllegalArgumentException("当前资源中没有这个场景。");
        var before = Destination.current(player);
        boolean moved = player.getWorld().transferPlayerToScene(player, destination.scene(), TeleportType.COMMAND,
                new Position(destination.x(), destination.y(), destination.z()));
        if (!moved) throw new IllegalArgumentException("传送未成功，请先完成当前场景加载。");
        previous.put(player.getUid(), before);
        return "已发送传送：" + destination.x() + ", " + destination.y() + ", " + destination.z()
                + "（场景 " + destination.scene() + "）。发送“返回”可回到传送前的位置。";
    }
}
