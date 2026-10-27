package emu.grasscutter.server.http.console;

import com.google.gson.JsonObject;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.command.CommandOutputCapture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.game.avatar.Avatar;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.FightProperty;
import emu.grasscutter.game.props.PlayerProperty;
import emu.grasscutter.game.world.Position;
import emu.grasscutter.server.event.player.PlayerTeleportEvent.TeleportType;
import emu.grasscutter.server.packet.send.PacketResinChangeNotify;
import emu.grasscutter.utils.lang.Language;
import java.io.*;
import java.util.*;

/** Typed GM operations. All calls execute on the existing game logic thread. */
final class WebConsolePlayerTools {
    record Stat(int id, String name, String en, boolean percent, double min, double max) {}
    private static final List<Stat> STATS = List.of(
            new Stat(2000, "生命上限", "Max HP", false, 1, 10_000_000),
            new Stat(2001, "攻击力", "Attack", false, 0, 1_000_000),
            new Stat(2002, "防御力", "Defense", false, 0, 1_000_000),
            new Stat(20, "暴击率", "Critical rate", true, 0, 1000),
            new Stat(22, "暴击伤害", "Critical damage", true, 0, 10_000),
            new Stat(23, "元素充能效率", "Energy recharge", true, 0, 10_000),
            new Stat(28, "元素精通", "Elemental mastery", false, 0, 100_000),
            new Stat(24, "伤害加成", "Damage bonus", true, 0, 10_000),
            new Stat(26, "治疗加成", "Healing bonus", true, 0, 1000),
            new Stat(30, "物理伤害加成", "Physical damage", true, 0, 10_000),
            new Stat(40, "火元素伤害加成", "Pyro damage", true, 0, 10_000),
            new Stat(41, "雷元素伤害加成", "Electro damage", true, 0, 10_000),
            new Stat(42, "水元素伤害加成", "Hydro damage", true, 0, 10_000),
            new Stat(43, "草元素伤害加成", "Dendro damage", true, 0, 10_000),
            new Stat(44, "风元素伤害加成", "Anemo damage", true, 0, 10_000),
            new Stat(45, "岩元素伤害加成", "Geo damage", true, 0, 10_000),
            new Stat(46, "冰元素伤害加成", "Cryo damage", true, 0, 10_000),
            new Stat(80, "技能冷却缩减（服务端）", "Skill cooldown reduction (server)", true, 0, 100),
            new Stat(81, "护盾强效", "Shield strength", true, 0, 1000));

    static Player player(int uid) {
        var server = Grasscutter.getGameServer();
        var player = server == null ? null : server.getPlayers().get(uid);
        if (player == null || !player.isOnline()) throw new IllegalArgumentException("该角色未在线，请进入游戏后刷新。");
        return player;
    }

    static Map<String, Object> state(int uid, int avatarId) {
        var p = player(uid);
        var avatars = new ArrayList<Map<String, Object>>();
        for (var a : p.getAvatars()) {
            var texts = Language.getTextMapKey(a.getAvatarData().getNameTextMapHash());
            avatars.add(Map.of("id", a.getAvatarId(), "name", texts.get("CHS"), "en", texts.get("EN"), "level", a.getLevel()));
        }
        avatars.sort(Comparator.comparingInt(a -> (Integer) a.get("id")));
        var entity = p.getTeamManager().getCurrentAvatarEntity();
        if (avatarId == 0 && entity != null) avatarId = entity.getAvatar().getAvatarId();
        var avatar = p.getAvatars().getAvatarById(avatarId);
        var statRows = new ArrayList<Map<String, Object>>();
        if (avatar != null) for (var stat : STATS) {
            var value = avatar.getFightProperty(FightProperty.getPropById(stat.id()));
            statRows.add(Map.of("stat", stat, "value", value * (stat.percent() ? 100d : 1d),
                    "locked", avatar.getFightPropOverrides().containsKey(stat.id()) || avatar.getGmFightPropOverrides().containsKey(stat.id())));
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("uid", uid);
        result.put("avatarId", avatar == null ? 0 : avatar.getAvatarId());
        result.put("avatars", avatars);
        result.put("stats", statRows);
        result.put("godmode", p.isInGodMode());
        result.put("unlimitedStamina", p.isUnlimitedStamina());
        result.put("unlimitedEnergy", !p.getEnergyManager().isEnergyUsage());
        result.put("timeLocked", p.getWorld().isTimeLocked());
        result.put("gameTime", p.getWorld().getGameTime());
        result.put("worldLevel", p.getWorldLevel());
        result.put("resin", p.getProperty(PlayerProperty.PROP_PLAYER_RESIN));
        result.put("sceneId", p.getSceneId());
        result.put("position", Map.of("x", p.getPosition().getX(), "y", p.getPosition().getY(), "z", p.getPosition().getZ()));
        result.put("oculi",emu.grasscutter.game.world.OculusCollector.preview(p));
        return result;
    }

    static Map<String, Object> execute(JsonObject json) {
        int uid = WebConsoleItems.Grant.integer(json, "uid", null);
        String action = string(json, "action");
        var p = player(uid);
        int avatarId = WebConsoleItems.Grant.integer(json, "avatarId", 0);
        List<String> output;
        switch (action) {
            case "collectOculi" -> output = List.of(emu.grasscutter.game.world.OculusCollector.collect(
                    p,integer(json,"cityId",1,8)));
            case "heal" -> output = run(p, "restore", "hp", "--all");
            case "energy" -> output = run(p, "restore", "energy");
            case "restore" -> output = run(p, "restore", "all");
            case "godmode", "unlimitedStamina", "unlimitedEnergy" -> {
                boolean enabled = bool(json, "enabled");
                output = run(p, "prop", action.toLowerCase(Locale.ROOT), enabled ? "on" : "off");
            }
            case "timeLocked" -> {
                p.getWorld().lockTime(bool(json, "enabled"));
                output = List.of("世界时间锁定状态已更新。");
            }
            case "time" -> {
                int time = integer(json, "value", 0, 1439);
                if (p.getWorld().isTimeLocked()) throw new IllegalArgumentException("请先关闭时间锁定再调时。");
                p.getWorld().changeTime(time, 0);
                p.getWorld().updateTime();
                output = List.of("世界时间已更新（同一世界的玩家共同生效）。");
            }
            case "worldLevel" -> {
                int value = integer(json, "value", 0, 9);
                if (!p.setWorldLevel(value)) throw new IllegalArgumentException("服务端拒绝了此世界等级。");
                output = List.of("世界等级已设为 " + value + "，已有怪物可能需要重新加载场景。");
            }
            case "resin" -> {
                int value = integer(json, "value", 0, 2000);
                p.setProperty(PlayerProperty.PROP_PLAYER_RESIN, value);
                p.sendPacket(new PacketResinChangeNotify(p));
                output = List.of("原粹树脂已设为 " + value + "。");
            }
            case "teleport" -> {
                int sceneId = WebConsoleItems.Grant.integer(json, "sceneId", null);
                if (!GameData.getSceneDataMap().containsKey(sceneId)) throw new IllegalArgumentException("没有此场景 ID。");
                var position = new Position((float) number(json, "x", -100_000, 100_000),
                        (float) number(json, "y", -10_000, 100_000), (float) number(json, "z", -100_000, 100_000));
                if (!p.getWorld().transferPlayerToScene(p, sceneId, TeleportType.COMMAND, position))
                    throw new IllegalArgumentException("场景传送失败，请查看服务器日志。");
                output = List.of("已发送场景传送；请在游戏中确认落点。");
            }
            case "statBatch" -> {
                var avatar = avatar(p, avatarId);
                if (!json.has("stats") || !json.get("stats").isJsonArray()) throw new IllegalArgumentException("缺少属性列表。");
                var rows = json.getAsJsonArray("stats");
                if (rows.isEmpty() || rows.size() > STATS.size()) throw new IllegalArgumentException("请选择 1–" + STATS.size() + " 项属性。");
                var changes = new HashMap<Integer, Float>();
                for (var row : rows) {
                    if (!row.isJsonObject()) throw new IllegalArgumentException("属性格式错误。");
                    int id = WebConsoleItems.Grant.integer(row.getAsJsonObject(), "statId", null);
                    var stat = STATS.stream().filter(s -> s.id() == id).findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("未知属性 ID。"));
                    if (changes.put(id, (float) validatedStatValue(stat, row.getAsJsonObject())) != null)
                        throw new IllegalArgumentException("属性重复。");
                }
                avatar.getGmFightPropOverrides().putAll(changes);
                avatar.recalcStats();
                avatar.save();
                output = List.of("已保存 " + changes.size() + " 项属性覆盖；未勾选的属性不变。");
            }
            case "stat", "statReset", "statResetAll" -> {
                var avatar = avatar(p, avatarId);
                if (action.equals("statResetAll")) {
                    avatar.getFightPropOverrides().clear();
                    avatar.getGmFightPropOverrides().clear();
                }
                else {
                    int id = WebConsoleItems.Grant.integer(json, "statId", null);
                    var stat = STATS.stream().filter(s -> s.id() == id).findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("请选择可编辑的属性。"));
                    if (action.equals("statReset")) {
                        avatar.getFightPropOverrides().remove(id);
                        avatar.getGmFightPropOverrides().remove(id);
                    } else avatar.getGmFightPropOverrides().put(id, (float) validatedStatValue(stat, json));
                }
                avatar.recalcStats();
                avatar.save();
                output = List.of(action.equals("stat") ? "已保存此角色的属性覆盖，重算属性后仍保持；可用恢复按钮移除。"
                        : "已移除选定属性覆盖并按等级、武器、圣遗物重新计算。");
            }
            case "constellation", "talent", "friendship", "maxAvatar" -> {
                var avatar = avatar(p, avatarId);
                String id = String.valueOf(avatar.getAvatarId());
                output = switch (action) {
                    case "constellation" -> run(p, "avatar", "constellation", "set", String.valueOf(integer(json, "value", 0, 6)), "--avatar", id);
                    case "talent" -> run(p, "avatar", "talent", "all", String.valueOf(integer(json, "value", 1, 10)), "--avatar", id);
                    case "friendship" -> run(p, "avatar", "friendship", "set", String.valueOf(integer(json, "value", 1, 10)), "--avatar", id);
                    default -> run(p, "avatar", "max", "--avatar", id);
                };
                avatar.save();
            }
            default -> throw new IllegalArgumentException("未知的玩家操作。");
        }
        p.save();
        return Map.of("output", output, "state", state(uid, avatarId));
    }

    static double validatedStatValue(Stat stat, JsonObject json) {
        return number(json, "value", stat.min(), stat.max()) / (stat.percent() ? 100d : 1d);
    }

    private static Avatar avatar(Player p, int id) {
        var avatar = p.getAvatars().getAvatarById(id);
        if (avatar == null) throw new IllegalArgumentException("该玩家未拥有所选角色，请刷新后选择。");
        return avatar;
    }

    static int integer(JsonObject json, String key, int min, int max) {
        int value = WebConsoleItems.Grant.integer(json, key, null);
        if (value < min || value > max) throw new IllegalArgumentException(key + " 须为 " + min + "–" + max + "。");
        return value;
    }

    static double number(JsonObject json, String key, double min, double max) {
        var element = json.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException(key + " 必须为数字。");
        double value = element.getAsDouble();
        if (!Double.isFinite(value) || value < min || value > max) throw new IllegalArgumentException(key + " 超出范围 " + min + "–" + max + "。");
        return value;
    }

    static String string(JsonObject json, String key) {
        var value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("缺少 " + key + "。");
        return value.getAsString();
    }

    static boolean bool(JsonObject json, String key) {
        var value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException(key + " 必须为布尔值。");
        return value.getAsBoolean();
    }

    private static List<String> run(Player p, String command, String... args) {
        return CommandOutputCapture.capture(() -> {
            var cli = Grasscutter.getCommandMap().getHandler(command).createCommandLine(null, p);
            var errors = new StringWriter();
            cli.setErr(new PrintWriter(errors));
            if (cli.execute(args) != 0) throw new IllegalArgumentException(errors.toString().strip());
        });
    }
}
