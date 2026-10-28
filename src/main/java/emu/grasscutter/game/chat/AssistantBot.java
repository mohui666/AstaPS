package emu.grasscutter.game.chat;

import emu.grasscutter.GameConstants;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.command.CommandMap;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.ItemCatalog;
import emu.grasscutter.data.ItemCatalog.Entry;
import emu.grasscutter.game.avatar.Avatar;
import emu.grasscutter.game.entity.MonsterCatalog;
import emu.grasscutter.game.inventory.*;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.ActionReason;
import emu.grasscutter.game.props.FightProperty;
import emu.grasscutter.game.props.PlayerProperty;
import emu.grasscutter.game.world.OculusCollector;
import emu.grasscutter.server.packet.send.PacketResinChangeNotify;
import java.io.IOException;
import java.util.*;

/** A private-chat interface to the existing player's GM tools; never acts as the console. */
final class AssistantBot {
    static final String MENU = "派蒙助手\n"
            + "点击领取：派蒙菜单 → 商城 → 礼包商城。物品免费，库存和购买次数不限。角色默认100级并附培养材料；武器默认100级、精炼5。\n"
            + "发送“商城搜索”获取游戏内搜索框，可按名称或ID查找并点击领取。\n"
            + "直接发“原石 10000”“摩拉 1000000”或物品名即可领取。重名时回复序号选择。\n"
            + "回复分类：常用 / 物品 / 圣遗物 / 传送 / 角色 / 状态 / 世界 / 刷怪 / 命令\n"
            + "常用：回血、复活、回能、无敌、无限体力、无限能量、解除边界\n"
            + "随时发送“菜单”回到这里。";
    private static final int PAGE_SIZE = 8;
    private static final Map<String, String> MENUS = Map.of(
            "物品", "领取：名称 数量 [等级] [精炼]\n例：原石 10000 / 纠缠之缘 100 / 雾切 1 90 5\n"
                    + "按名称、英文名或 ID 查找；“搜索 名称”只查不领。发“常用”查看常用补给。\n圣遗物默认0级，武器默认1级；数量默认1。",
            "角色", "领取：角色 名称 [等级]，例：角色 芙宁娜 90\n"
                    + "培养当前角色：命座 6 / 天赋 10 / 好感 10 / 拉满\n"
                    + "指定角色：命座 芙宁娜 6 / 拉满 芙宁娜；“全部”作用于全部已拥有角色。\n"
                    + "拉满也会处理该角色已装备武器。属性 攻击力 10000 / 属性 暴击率 100 / 属性恢复\n我的角色：列出已拥有角色。",
            "状态", "回血 / 复活 / 回能 / 恢复\n无敌 开/关 / 无限体力 开/关 / 无限能量 开/关（省略开关则切换）\n"
                    + "解除边界：开启个人自由探索，保留主线进度；恢复边界：恢复任务原本的限制。\n树脂 200 / 冒险等级 60 / 开放滑翔 / 开放潜水",
            "世界", "时间 12:00 / 时间锁定 开/关 / 世界等级 8\n"
                    + "天气 晴天/阴天/雨天/雷雨/雪天/雾天\n解锁地图 / 解锁传送点 / 清怪\n神瞳：查看当前地区剩余神瞳；收集神瞳：收集本机已索引点位。\n"
                    + "纪行等级 50 / 深渊层数 12 / 神像 地区编号 等级\n高级功能：命令（全部现有 GM 命令）",
            "刷怪", "刷怪 名称 [数量] [等级]\n例：刷怪 丘丘人 3 90\n可用中文、英文或怪物ID；重名会列出可选项。\n清怪：清除当前场景怪物。",
            "圣遗物", AssistantArtifacts.HELP);

    record Request(String query, Integer amount, Integer level, Integer refinement) {
        static Request parse(String text) {
            var words = new ArrayList<>(Arrays.asList(text.strip().split("\\s+")));
            if (text.isBlank()) throw new IllegalArgumentException("请填写名称或 ID。");
            var numbers = new ArrayList<String>();
            while (words.size() > 1 && words.getLast().matches("[+\\-]?\\d.*")) {
                numbers.addFirst(words.removeLast());
            }
            if (numbers.size() > 3) throw new IllegalArgumentException("格式：名称 数量 [等级] [精炼]。");
            return new Request(String.join(" ", words), numbers.isEmpty() ? null : integer(numbers.get(0), 1, Integer.MAX_VALUE, "数量"),
                    numbers.size() < 2 ? null : integer(numbers.get(1), 0, 1000, "等级"),
                    numbers.size() < 3 ? null : integer(numbers.get(2), 1, 5, "精炼"));
        }
    }

    record Choice(Entry entry, Request request) {}
    private static final class Session {
        List<Choice> choices = List.of();
        int page;
    }

    private final ChatSystem chat;
    private final Map<Integer, Session> sessions = new HashMap<>();
    private final AssistantTeleports teleports = new AssistantTeleports();

    AssistantBot(ChatSystem chat) { this.chat = chat; }

    void clear(int uid) { sessions.remove(uid); teleports.clear(uid); }

    void reply(Player player, String message) { chat.sendPrivateMessageFromServer(player.getUid(), message); }

    void handle(Player player, String message) {
        if (!player.isOnline()) return;
        String text = message.strip();
        var session = sessions.computeIfAbsent(player.getUid(), uid -> new Session());
        try {
            if (Set.of("菜单", "帮助", "你好", "派蒙", "menu", "help", "?", "？").contains(text.toLowerCase(Locale.ROOT))) {
                session.choices = List.of();
                reply(player, MENU);
                return;
            }
            if (MENUS.containsKey(text)) {
                session.choices = List.of();
                reply(player, MENUS.get(text));
                return;
            }
            String[] split = text.split("\\s+", 2);
            String verb = split[0], args = split.length == 2 ? split[1].strip() : "";
            switch (verb) {
                case "商城搜索" -> {
                    emu.grasscutter.game.shop.ShopSearch.sendEntry(player);
                    reply(player, "已发送商城搜索邮件。打开游戏邮箱，点击“打开商城搜索”，即可搜索并领取商品。");
                    return;
                }
                case "常用" -> { common(player, session); return; }
                case "下一页", "下页", "上一页", "上页" -> {
                    if (session.choices.isEmpty()) throw new IllegalArgumentException("先发送物品名或“常用”。");
                    int next = session.page + ((verb.equals("下一页") || verb.equals("下页")) ? 1 : -1);
                    if (next < 0 || next * PAGE_SIZE >= session.choices.size()) throw new IllegalArgumentException("已经到头了。");
                    session.page = next;
                    showChoices(player, session);
                    return;
                }
                case "传送", "瞬移" -> {
                    if (args.isEmpty()) reply(player, teleports.list(player));
                    else if (permitted(player, "player.teleport")) {
                        var tokens = args.split("\\s+");
                        if (isCoordinateInput(tokens)) {
                            int scene = tokens.length == 4 ? integer(tokens[3], 1, Integer.MAX_VALUE, "场景ID") : player.getSceneId();
                            reply(player, teleports.transfer(player, new AssistantTeleports.Destination(scene,
                                    coordinate(tokens[0]), coordinate(tokens[1]), coordinate(tokens[2]))));
                        } else reply(player, teleports.named(player, args));
                    }
                    return;
                }
                case "记点", "保存地点", "删除地点" -> {
                    if (permitted(player, "player.teleport")) reply(player, teleports.remember(player, args, verb.equals("删除地点")));
                    return;
                }
                case "返回" -> { if (permitted(player, "player.teleport")) reply(player, teleports.back(player)); return; }
                case "找人" -> {
                    if (!permitted(player, "player.teleport")) return;
                    var friend = chat.getServer().getPlayers().get(integer(args, 1, Integer.MAX_VALUE, "UID"));
                    if (friend == null || !friend.isOnline()) throw new IllegalArgumentException("该玩家不在线。");
                    if (friend.getWorld() != player.getWorld()) throw new IllegalArgumentException("请先通过游戏联机进入对方世界，再使用“找人”。");
                    reply(player, teleports.transfer(player, AssistantTeleports.Destination.current(friend)));
                    return;
                }
                case "打造" -> {
                    if (permitted(player, "player.give")) {
                        var item = AssistantArtifacts.create(args);
                        deliver(player, ItemCatalog.entry(item.getItemData()).name(), List.of(item), 1);
                    }
                    return;
                }
                case "树脂" -> {
                    if (args.isEmpty()) reply(player, "当前树脂：" + player.getProperty(PlayerProperty.PROP_PLAYER_RESIN));
                    else if (permitted(player, "player.setprop")) {
                        int value = integer(args, 0, 2000, "树脂");
                        player.setProperty(PlayerProperty.PROP_PLAYER_RESIN, value);
                        player.sendPacket(new PacketResinChangeNotify(player));
                        player.save();
                        reply(player, "树脂已设为 " + value + "。");
                    }
                    return;
                }
                case "时间", "时间锁定" -> { time(player, verb, args); return; }
                case "神瞳", "收集神瞳" -> {
                    var preview = OculusCollector.preview(player);
                    if (!Boolean.TRUE.equals(preview.get("available"))) throw new IllegalArgumentException(String.valueOf(preview.get("reason")));
                    if (verb.equals("收集神瞳")) {
                        if (permitted(player, "player.give")) reply(player, OculusCollector.collect(player, (Integer) preview.get("cityId")));
                    } else reply(player, preview.get("country") + " · " + preview.get("oculus") + "：已索引 " + preview.get("total")
                            + "，已收 " + preview.get("collected") + "，剩余 " + preview.get("remaining") + "。发送“收集神瞳”领取剩余点位。");
                    return;
                }
                case "命座", "天赋", "好感", "拉满" -> { avatarAction(player, verb, args); return; }
                case "我的角色" -> {
                    var owned = ItemCatalog.avatars().stream().filter(e -> player.getAvatars().getAvatarById(e.id()) != null)
                            .map(e -> e.name() + "（" + player.getAvatars().getAvatarById(e.id()).getLevel() + "级）").toList();
                    reply(player, String.join(" / ", owned));
                    return;
                }
                case "属性", "属性恢复" -> { stats(player, args, verb.equals("属性恢复")); return; }
                case "角色", "领取角色" -> {
                    if (args.isEmpty()) reply(player, MENUS.get("角色"));
                    else {
                        var request = Request.parse(args);
                        // Characters take a level, not a stack quantity.
                        if (request.level() != null || request.refinement() != null) throw new IllegalArgumentException("格式：角色 名称 [等级]。");
                        search(player, session, new Request(request.query(), 1, request.amount(), null), "avatar", false);
                    }
                    return;
                }
                case "刷怪" -> { search(player, session, Request.parse(args), "monster", false); return; }
                case "搜索", "查找" -> { search(player, session, Request.parse(args), "", true); return; }
                case "领取", "刷", "给我", "获取" -> { text = args; }
                case "命令" -> { run(player, args.isEmpty() ? "help" : args); return; }
                case "帮助" -> { run(player, "help " + args); return; }
                default -> {
                    String shortcut = shortcut(verb, args);
                    if (shortcut != null) { run(player, shortcut); return; }
                    if (CommandMap.getInstance().getHandler(verb.toLowerCase(Locale.ROOT)) != null) { run(player, text); return; }
                }
            }
            boolean explicitId = text.toLowerCase(Locale.ROOT).startsWith("id ");
            var request = Request.parse(explicitId ? text.substring(3) : text);
            if (!explicitId && !session.choices.isEmpty() && request.query().matches("\\d+")) {
                int index = integer(request.query(), 1, Integer.MAX_VALUE, "序号");
                if (index <= session.choices.size()) {
                    var choice = session.choices.get(index - 1);
                    grant(player, choice.entry(), new Request(choice.request().query(),
                            request.amount() == null ? choice.request().amount() : request.amount(),
                            request.level() == null ? choice.request().level() : request.level(),
                            request.refinement() == null ? choice.request().refinement() : request.refinement()));
                    return;
                }
            }
            search(player, session, request, "", false);
        } catch (IllegalArgumentException error) {
            reply(player, error.getMessage());
        } catch (IOException error) {
            Grasscutter.getLogger().error("Cannot access assistant locations for UID {}", player.getUid(), error);
            reply(player, "地点文件读写失败，请查看服务端日志。");
        }
    }

    static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[\\s·'’\\-]", "");
    }

    static int integer(String text, int min, int max, String label) {
        try {
            int value = Integer.parseInt(text);
            if (value >= min && value <= max) return value;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException(label + "须为 " + min + "–" + max + " 的整数。");
    }

    private static float coordinate(String text) {
        try {
            float value = Float.parseFloat(text);
            if (Float.isFinite(value)) return value;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("坐标须为有效数字。");
    }

    static boolean isCoordinateInput(String[] tokens) {
        if (tokens.length != 3 && tokens.length != 4) return false;
        for (int i = 0; i < 3; i++) {
            try { Float.parseFloat(tokens[i]); }
            catch (NumberFormatException error) { return false; }
        }
        return true;
    }

    private boolean permitted(Player player, String node) {
        if (player.getAccount() == null || !Grasscutter.getPermissionHandler().checkPermission(player, player, node, node + ".others")) {
            reply(player, "当前账号没有此功能权限：" + node);
            return false;
        }
        return true;
    }

    private static void run(Player player, String command) { CommandMap.getInstance().invoke(player, player, command); }

    static String shortcut(String verb, String args) {
        String toggle = switch (args.toLowerCase(Locale.ROOT)) {
            case "开", "开启", "on", "true", "1" -> "on";
            case "关", "关闭", "off", "false", "0" -> "off";
            case "", "切换" -> "toggle";
            default -> null;
        };
        String boolProp = switch (verb) {
            case "无敌" -> "godmode";
            case "无限体力" -> "unlimitedstamina";
            case "无限能量" -> "unlimitedenergy";
            case "开放滑翔" -> "canfly";
            case "开放潜水" -> "candive";
            default -> null;
        };
        if (boolProp != null) {
            if (toggle == null) throw new IllegalArgumentException("请填写“开”或“关”。");
            // Native fly/dive properties take on/off, not the boolean toggle pseudo-value.
            if (boolProp.equals("canfly") || boolProp.equals("candive")) toggle = args.isEmpty() ? "on" : toggle;
            return "prop " + boolProp + " " + toggle;
        }
        return switch (verb) {
            case "回血", "复活" -> "restore hp --all";
            case "回能" -> "restore energy";
            case "恢复" -> "restore all";
            case "解除边界" -> "barrier off";
            case "自由探索" -> {
                if (toggle == null) throw new IllegalArgumentException("自由探索 开/关。");
                yield "barrier " + (toggle.equals("off") ? "on" : "off");
            }
            case "恢复边界" -> "barrier on";
            case "解锁地图" -> "unlock map";
            case "解锁传送点" -> "waypoints";
            case "清怪" -> "kill all";
            case "传送点" -> "teleport " + integerArgs(args, 1, 2);
            case "神像" -> "statue level " + integerArgs(args, 2, 2);
            case "世界等级" -> "prop worldlevel" + (args.isEmpty() ? "" : " " + integer(args, 0, 9, "世界等级"));
            case "冒险等级" -> "prop player_level" + (args.isEmpty() ? "" : " " + integer(args, 1, 60, "冒险等级"));
            case "纪行等级" -> "prop bplevel " + integer(args, 0, 50, "纪行等级");
            case "深渊层数" -> "prop abyss " + integer(args, 0, 100, "层数");
            case "天气" -> "weather " + switch (args) {
                case "晴", "晴天" -> "sunny";
                case "阴", "阴天" -> "cloudy";
                case "雨", "雨天" -> "rain";
                case "雷雨" -> "thunderstorm";
                case "雪", "雪天" -> "snow";
                case "雾", "雾天" -> "mist";
                default -> args.isBlank() ? "" : String.valueOf(integer(args, 0, Integer.MAX_VALUE, "天气ID"));
            };
            default -> null;
        };
    }

    private static String integerArgs(String text, int min, int max) {
        String[] tokens = text.strip().split("\\s+");
        if (text.isBlank() || tokens.length < min || tokens.length > max) throw new IllegalArgumentException("参数数量不正确，请发送“菜单”查看用法。");
        return Arrays.stream(tokens).map(s -> String.valueOf(integer(s, 0, Integer.MAX_VALUE, "参数"))).reduce((a, b) -> a + " " + b).orElseThrow();
    }

    static List<Entry> matches(List<Entry> catalog, String text) {
        String query = normalize(switch (text) {
            case "木偶" -> "桑多涅";
            case "粉球" -> "纠缠之缘";
            case "蓝球" -> "相遇之缘";
            case "大经验书", "紫书" -> "大英雄的经验";
            default -> text;
        });
        var found = catalog.stream().filter(e -> String.valueOf(e.id()).equals(query)
                || normalize(e.name()).contains(query) || normalize(e.en()).contains(query)).toList();
        var exact = found.stream().filter(e -> String.valueOf(e.id()).equals(query)
                || normalize(e.name()).equals(query) || normalize(e.en()).equals(query)).toList();
        return (exact.isEmpty() ? found : exact).stream()
                .sorted(Comparator.comparingInt(Entry::rank).reversed().thenComparingInt(Entry::id)).toList();
    }

    private void search(Player player, Session session, Request request, String category, boolean lookupOnly) {
        List<Entry> catalog;
        if (category.equals("monster")) catalog = MonsterCatalog.catalog().stream()
                .map(e -> new Entry(e.id(), e.name(), e.en() + " " + e.internalName(), "monster", 0, e.named())).toList();
        else if (category.equals("avatar")) catalog = ItemCatalog.avatars();
        else {
            catalog = new ArrayList<>(ItemCatalog.catalog());
            catalog.addAll(ItemCatalog.avatars());
        }
        var found = matches(catalog, request.query());
        if (found.isEmpty()) throw new IllegalArgumentException("没有找到“" + request.query() + "”。请换关键词或输入原生 ID。");
        session.choices = found.stream().map(e -> new Choice(e, request)).toList();
        session.page = 0;
        if (found.size() == 1 && !lookupOnly) grant(player, found.getFirst(), request);
        else showChoices(player, session);
    }

    private void common(Player player, Session session) {
        var choices = new ArrayList<Choice>();
        for (int[] item : new int[][] {{201, 10000}, {202, 1000000}, {223, 100}, {224, 100}, {104003, 1000}, {104013, 1000}, {106, 10}}) {
            var data = GameData.getItemDataMap().get(item[0]);
            if (data != null) choices.add(new Choice(ItemCatalog.entry(data), new Request(String.valueOf(item[0]), item[1], null, null)));
        }
        session.choices = choices;
        session.page = 0;
        showChoices(player, session);
    }

    private void showChoices(Player player, Session session) {
        int start = session.page * PAGE_SIZE, end = Math.min(start + PAGE_SIZE, session.choices.size());
        boolean monsters = session.choices.getFirst().entry().category().equals("monster");
        var message = new StringBuilder(monsters ? "回复序号生成，也可发“序号 数量 [等级]”：\n"
                : "回复序号领取，也可发“序号 数量 [等级] [精炼]”：\n");
        for (int i = start; i < end; i++) {
            var choice = session.choices.get(i);
            message.append(i + 1).append(". ").append(choice.entry().name()).append(" [").append(choice.entry().id()).append(']');
            if (choice.entry().rank() > 0) message.append(' ').append(choice.entry().rank()).append('★');
            if (choice.request().amount() != null) message.append(" ×").append(choice.request().amount());
            message.append('\n');
        }
        message.append("第 ").append(session.page + 1).append('/').append((session.choices.size() + PAGE_SIZE - 1) / PAGE_SIZE)
                .append(" 页；上一页 / 下一页 / 菜单。")
                .append(monsters ? "按ID生成可发“刷怪 原生ID 数量 等级”。" : "直接按ID领取可发“ID 数字 数量”。");
        reply(player, message.toString());
    }

    private void grant(Player player, Entry entry, Request request) {
        int amount = request.amount() == null ? 1 : request.amount();
        if (entry.category().equals("monster")) {
            if (request.refinement() != null) throw new IllegalArgumentException("格式：刷怪 名称 数量 等级。");
            if (permitted(player, "server.spawn")) {
                var receipt = MonsterCatalog.spawn(player, entry.id(), amount, request.level() == null ? 1 : request.level(), 5);
                reply(player, (receipt.spawned() == 0 ? "未生成怪物：" : "已生成 ") + entry.name() + " ×" + receipt.spawned()
                        + "（请求 " + receipt.requested() + "）。"
                        + (receipt.spawned() != receipt.requested() ? "\n" + String.join("\n", receipt.output()) : "位置在你附近。"));
            }
            return;
        }
        if (!permitted(player, "player.give")) return;
        if (entry.category().equals("avatar")) {
            if (amount != 1 || request.refinement() != null) throw new IllegalArgumentException("角色只能领取一次，不能设置精炼；培养请发“角色”。");
            if (player.getAvatars().getAvatarById(entry.id()) != null) throw new IllegalArgumentException("已拥有“" + entry.name() + "”。用“命座 / 天赋 / 拉满”培养。");
            var avatar = new Avatar(GameData.getAvatarDataMap().get(entry.id()));
            int level = request.level() == null ? 1 : integer(String.valueOf(request.level()), 1, 100, "角色等级");
            avatar.setLevel(level);
            avatar.setPromoteLevel(Avatar.getMinPromoteLevel(level));
            avatar.recalcStats(true);
            player.addAvatar(avatar);
            if (player.getAvatars().getAvatarById(entry.id()) == null) throw new IllegalArgumentException("服务端未接收这个角色。");
            reply(player, "已领取角色：" + entry.name() + "（" + level + "级）。");
            return;
        }
        var definition = GameData.getItemDataMap().get(entry.id());
        deliver(player, entry.name(), prepareItems(definition, request), amount);
    }

    static List<GameItem> prepareItems(emu.grasscutter.data.excels.ItemData data, Request request) {
        if (data == null || !ItemCatalog.supported(data.getItemType())) throw new IllegalArgumentException("当前服务端不支持这个物品。");
        boolean weapon = data.getItemType() == ItemType.ITEM_WEAPON, relic = data.getItemType() == ItemType.ITEM_RELIQUARY;
        if (GameConstants.ILLEGAL_ITEMS.contains(data.getId()) || (weapon && GameConstants.ILLEGAL_WEAPONS.contains(data.getId()))
                || (relic && GameConstants.ILLEGAL_RELICS.contains(data.getId()))) throw new IllegalArgumentException("此 ID 是不可正常领取的内部条目。");
        if (!weapon && request.refinement() != null) throw new IllegalArgumentException("只有武器可以设置精炼。");
        if (!weapon && !relic && request.level() != null) throw new IllegalArgumentException("只有武器和圣遗物可以设置等级。");
        int level = request.level() == null ? (relic ? 0 : 1) : request.level();
        integer(String.valueOf(level), relic ? 0 : 1, relic ? data.getMaxLevel() - 1 : 100, "等级");
        int internalLevel = relic ? level + 1 : level;
        var items = InventoryGrantBuilder.create(data, request.amount() == null ? 1 : request.amount(), internalLevel);
        int totalExp = 0;
        for (int i = 1; i < internalLevel; i++) {
            if (weapon) totalExp += GameData.getWeaponExpRequired(data.getRankLevel(), i);
            else if (relic) totalExp += GameData.getRelicExpRequired(data.getRankLevel(), i);
        }
        for (var item : items) {
            if (weapon || relic) item.setTotalExp(totalExp);
            if (weapon) item.setRefinement(request.refinement() == null ? 0 : request.refinement() - 1);
            if (relic) item.addAppendProps(level / 4);
        }
        return items;
    }

    private void deliver(Player player, String name, List<GameItem> items, int requested) {
        var result = player.getInventory().addItems(items, ActionReason.SubfieldDrop, InventoryAddPolicy.ALL_OR_NOTHING);
        if (result.allAccepted()) reply(player, "已领取：" + name + " ×" + requested + "。");
        else {
            int added = result.entries().stream().mapToInt(InventoryAddResult.Entry::added).sum();
            String reason = result.entries().stream().filter(e -> !e.accepted()).map(e -> switch (e.status()) {
                case CAPACITY_FULL -> "背包空位不足";
                case STACK_LIMIT -> "超过持有上限";
                case ALREADY_OWNED -> "已经拥有";
                default -> e.status().name();
            }).distinct().reduce((a, b) -> a + "、" + b).orElse("发放未完成");
            reply(player, "未完整领取：" + name + "；实际到账 " + added + " / " + requested + "。" + reason + "。");
        }
    }

    private void time(Player player, String verb, String args) {
        if (!permitted(player, "player.setprop")) return;
        if (verb.equals("时间锁定")) {
            boolean enabled = switch (args.toLowerCase(Locale.ROOT)) {
                case "开", "开启", "on", "1" -> true;
                case "关", "关闭", "off", "0" -> false;
                case "" -> !player.getWorld().isTimeLocked();
                default -> throw new IllegalArgumentException("时间锁定 开/关。");
            };
            player.getWorld().lockTime(enabled);
            reply(player, "时间锁定：" + (enabled ? "开" : "关") + "。");
        } else {
            if (player.getWorld().isTimeLocked()) throw new IllegalArgumentException("先发送“时间锁定 关”再调时。");
            int minutes;
            if (args.contains(":")) {
                var parts = args.split(":", -1);
                if (parts.length != 2) throw new IllegalArgumentException("时间格式：12:00。");
                minutes = integer(parts[0], 0, 23, "小时") * 60 + integer(parts[1], 0, 59, "分钟");
            } else minutes = integer(args, 0, 1439, "当天分钟");
            player.getWorld().changeTime(minutes, 0);
            player.getWorld().updateTime();
            reply(player, "世界时间已更新，同一世界内共同生效。");
        }
        player.save();
    }

    private void avatarAction(Player player, String verb, String args) {
        String target = args;
        String value = "";
        if (!verb.equals("拉满")) {
            var parts = args.strip().split("\\s+");
            int max = verb.equals("命座") ? 6 : 10;
            value = String.valueOf(integer(parts[parts.length - 1], verb.equals("命座") ? 0 : 1, max, verb));
            target = String.join(" ", Arrays.copyOf(parts, parts.length - 1));
        }
        String selection = "";
        if (target.equals("全部")) selection = " --all";
        else if (!target.isBlank()) {
            var matches = matches(ItemCatalog.avatars().stream().filter(e -> player.getAvatars().getAvatarById(e.id()) != null).toList(), target);
            if (matches.size() != 1) throw new IllegalArgumentException("请填写唯一的已拥有角色名；发“我的角色”查看。");
            selection = " --avatar " + matches.getFirst().id();
        }
        String action = switch (verb) {
            case "命座" -> "constellation set " + value;
            case "天赋" -> "talent all " + value;
            case "好感" -> "friendship set " + value;
            default -> "max";
        };
        run(player, "avatar " + action + selection);
    }

    private void stats(Player player, String args, boolean reset) {
        if (!permitted(player, "player.setstats")) return;
        var entity = player.getTeamManager().getCurrentAvatarEntity();
        if (entity == null) throw new IllegalArgumentException("当前没有出战角色。");
        var avatar = entity.getAvatar();
        if (reset && (args.isBlank() || args.equals("全部"))) {
            avatar.getFightPropOverrides().clear();
            avatar.getGmFightPropOverrides().clear();
        } else {
            var parts = args.split("\\s+");
            if ((!reset && parts.length != 2) || (reset && parts.length != 1)) throw new IllegalArgumentException("属性 攻击力 10000 / 属性 暴击率 100 / 属性恢复 [属性名]。");
            var prop = switch (parts[0]) {
                case "生命", "生命上限", "生命值", "maxhp" -> FightProperty.FIGHT_PROP_MAX_HP;
                case "攻击", "攻击力", "atk" -> FightProperty.FIGHT_PROP_CUR_ATTACK;
                case "防御", "防御力", "def" -> FightProperty.FIGHT_PROP_CUR_DEFENSE;
                case "伤害加成" -> FightProperty.FIGHT_PROP_ADD_HURT;
                case "冷却缩减" -> FightProperty.FIGHT_PROP_SKILL_CD_MINUS_RATIO;
                case "护盾强效" -> FightProperty.FIGHT_PROP_SHIELD_COST_MINUS_RATIO;
                default -> AssistantArtifacts.property(parts[0]);
            };
            if (!Set.of(2000, 2001, 2002, 20, 22, 23, 24, 26, 28, 30, 40, 41, 42, 43, 44, 45, 46, 80, 81).contains(prop.getId()))
                throw new IllegalArgumentException("不支持的战斗属性。");
            if (reset) {
                avatar.getFightPropOverrides().remove(prop.getId());
                avatar.getGmFightPropOverrides().remove(prop.getId());
            } else {
                double value;
                try { value = Double.parseDouble(parts[1].replace("%", "")); }
                catch (NumberFormatException error) { throw new IllegalArgumentException("属性值须为数字；百分比直接填写百分数。"); }
                double max = prop.getId() == 2000 ? 10_000_000 : prop.getId() == 2001 || prop.getId() == 2002 ? 1_000_000
                        : prop.getId() == 28 ? 100_000 : prop.getId() == 80 ? 100 : prop.getId() == 20 || prop.getId() == 26 || prop.getId() == 81 ? 1000 : 10_000;
                if (!Double.isFinite(value) || value < (prop.getId() == 2000 ? 1 : 0) || value > max)
                    throw new IllegalArgumentException("此属性值超出可设置范围，上限为 " + max + "。");
                avatar.getGmFightPropOverrides().put(prop.getId(), statValue(prop, value));
            }
        }
        avatar.recalcStats();
        avatar.save();
        reply(player, reset ? "已恢复当前角色的指定属性。" : "已保存当前角色属性，发送“属性恢复”可恢复。");
    }

    static float statValue(FightProperty property, double value) {
        // The legacy flatProps enum table omits elemental mastery; it is an absolute value.
        return (float) (property == FightProperty.FIGHT_PROP_ELEMENT_MASTERY
                || !FightProperty.isPercentage(property) ? value : value / 100);
    }
}
