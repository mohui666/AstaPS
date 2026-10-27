package emu.grasscutter.game.entity;

import static emu.grasscutter.config.Configuration.GAME_OPTIONS;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.command.CommandOutputCapture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.monster.MonsterData;
import emu.grasscutter.game.player.Player;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/** Loaded monster IDs shared by the operator panel and in-game assistant. */
public final class MonsterCatalog {
    public record Entry(int id, String name, String en, String internalName, String type, boolean named) {}
    public record Receipt(int requested, int spawned, int sceneId, int monsterId,
                          List<Integer> entityIds, List<String> output) {}

    private static final JsonObject NAMES = readNames();

    private MonsterCatalog() {}

    private static JsonObject readNames() {
        var stream = MonsterCatalog.class.getResourceAsStream("/html/monster-names.json");
        if (stream == null) throw new IllegalStateException("Monster dictionary is missing.");
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("monsters");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read monster dictionary.", e);
        }
    }

    public static List<Entry> catalog() {
        return GameData.getMonsterDataMap().values().stream().map(MonsterCatalog::entry)
                .sorted(Comparator.comparingInt(Entry::id)).toList();
    }

    public static Entry entry(MonsterData monster) {
        var names = NAMES.getAsJsonArray(String.valueOf(monster.getId()));
        String chinese = names == null ? "" : names.get(0).getAsString();
        String english = names == null ? "" : names.get(1).getAsString();
        String internal = monster.getMonsterName() == null ? "" : monster.getMonsterName();
        String display = !chinese.isBlank() ? chinese : !english.isBlank() ? english
                : !internal.isBlank() ? internal : "ID " + monster.getId();
        return new Entry(monster.getId(), display, english, internal,
                monster.getType() == null ? "" : monster.getType().name(), !chinese.isBlank() || !english.isBlank());
    }

    public static List<Integer> levels() {
        return GameData.getMonsterCurveDataMap().keySet().intStream().filter(level -> level > 0).sorted().boxed().toList();
    }

    /** Synchronous: callers must already be on the game's logic thread and authorized to spawn. */
    public static Receipt spawn(Player player, int monsterId, int amount, int level, double distance) {
        if (player == null || !player.isOnline() || player.getScene() == null)
            throw new IllegalArgumentException("该玩家没有在线场景，请先进入游戏。");
        if (!GameData.getMonsterDataMap().containsKey(monsterId))
            throw new IllegalArgumentException("当前服务端没有这个怪物 ID。");
        if (amount < 1) throw new IllegalArgumentException("数量必须为正整数。");
        if (!GameData.getMonsterCurveDataMap().containsKey(level))
            throw new IllegalArgumentException("当前资源没有这个怪物等级的数据。");
        if (!Double.isFinite(distance) || distance < 0 || distance > 100)
            throw new IllegalArgumentException("生成距离须为 0–100 米。");
        var scene = player.getScene();
        int allowed = allowedAmount(amount, scene.getEntities().size(), GAME_OPTIONS.sceneEntityLimit);
        if (allowed == 0) return new Receipt(amount, 0, scene.getId(), monsterId, List.of(),
                List.of("场景实体数量已达上限，没有生成怪物。"));
        var before = new HashSet<>(scene.getEntities().keySet());
        double yaw = Math.toRadians(player.getRotation().getY());
        float x = player.getPosition().getX() + (float) (Math.sin(yaw) * distance);
        float z = player.getPosition().getZ() + (float) (Math.cos(yaw) * distance);
        var output = CommandOutputCapture.capture(() -> {
            var cli = Grasscutter.getCommandMap().getHandler("spawn").createCommandLine(null, player);
            var errors = new StringWriter();
            cli.setExpandAtFiles(false);
            cli.setErr(new PrintWriter(errors));
            int exit = cli.execute(String.valueOf(monsterId), "--amount", String.valueOf(allowed),
                    "--level", String.valueOf(level), "--pos", Float.toString(x),
                    Float.toString(player.getPosition().getY()), Float.toString(z));
            if (exit != 0) throw new IllegalStateException("刷怪执行失败：" + errors.toString().strip());
        });
        var spawned = scene.getEntities().values().stream()
                .filter(entity -> !before.contains(entity.getId()) && entity instanceof EntityMonster monster
                        && monster.getMonsterData().getId() == monsterId)
                .map(GameEntity::getId).sorted().toList();
        if (allowed < amount) output.addFirst("场景实体上限仅允许本次生成 " + allowed + " 个，实际新增 " + spawned.size() + " 个。");
        return new Receipt(amount, spawned.size(), scene.getId(), monsterId, spawned, List.copyOf(output));
    }

    static int allowedAmount(int requested, int existing, int limit) {
        return (int) Math.min(requested, Math.max(0L, (long) limit - existing));
    }
}
