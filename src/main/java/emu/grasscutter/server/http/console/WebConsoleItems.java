package emu.grasscutter.server.http.console;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.command.CommandOutputCapture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.ItemCatalog;
import emu.grasscutter.data.ItemCatalog.Entry;
import emu.grasscutter.data.excels.ItemData;
import emu.grasscutter.game.inventory.ItemType;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/** Console item names are joined by native ID, never by guesses about mismatched text hashes. */
final class WebConsoleItems {
    private WebConsoleItems() {}

    static List<Entry> catalog() { return ItemCatalog.catalog(); }

    static Entry entry(ItemData item) { return ItemCatalog.entry(item); }

    static String[] artifactSetNames(int setId) { return ItemCatalog.artifactSetNames(setId); }

    private static boolean supported(ItemType type) { return ItemCatalog.supported(type); }
    record Grant(int uid, int itemId, int amount, Integer level, int refinement) {
        static Grant parse(String body) {
            var json = JsonParser.parseString(body).getAsJsonObject();
            int uid = integer(json, "uid", null);
            int id = integer(json, "itemId", null);
            int amount = integer(json, "amount", 1);
            Integer level = json.has("level") ? integer(json, "level", null) : null;
            int refinement = integer(json, "refinement", 1);
            if (uid < 1 || id < 1) throw new IllegalArgumentException("请选择有效的玩家和物品。");
            if (amount < 1) throw new IllegalArgumentException("数量必须为正整数。");
            if (refinement < 1 || refinement > 5) throw new IllegalArgumentException("精炼等级须为 1–5。");
            return new Grant(uid, id, amount, level, refinement);
        }

        static int integer(JsonObject json, String key, Integer defaultValue) {
            if (!json.has(key) && defaultValue != null) return defaultValue;
            var value = json.get(key);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException(key + " 必须为整数。");
            }
            try {
                return value.getAsBigDecimal().intValueExact();
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException(key + " 必须为 32 位整数。");
            }
        }
    }

    /** Called on the game logic thread, which owns the online player's inventory. */
    static List<String> give(Grant request) {
        var server = Grasscutter.getGameServer();
        var player = server == null ? null : server.getPlayers().get(request.uid());
        if (player == null || !player.isOnline()) throw new IllegalArgumentException("该角色未在线，请进入游戏后刷新玩家列表。");
        var item = GameData.getItemDataMap().get(request.itemId());
        if (item == null || !supported(item.getItemType())) throw new IllegalArgumentException("当前服务端没有此物品 ID。");
        boolean weapon = item.getItemType() == ItemType.ITEM_WEAPON;
        boolean artifact = item.getItemType() == ItemType.ITEM_RELIQUARY;
        if (request.level() != null && ((!weapon && !artifact)
                || request.level() < (artifact ? 0 : 1) || request.level() > (artifact ? 20 : 100))) {
            throw new IllegalArgumentException("武器等级须为 1–100，圣遗物等级须为 0–20。");
        }
        if (item.isEquip()) {
            var tab = player.getInventory().getInventoryTab(item.getItemType());
            if (request.amount() > tab.getMaxCapacity() - tab.getSize()) {
                throw new IllegalArgumentException("背包空位不足，请减少数量或清理背包。");
            }
        }
        var arguments = new ArrayList<>(List.of(String.valueOf(request.itemId()), "--amount", String.valueOf(request.amount())));
        if (request.level() != null) arguments.addAll(List.of("--level", String.valueOf(request.level())));
        if (weapon) arguments.addAll(List.of("--refinement", String.valueOf(request.refinement())));
        // Invoke the registered give parser synchronously so its real output is returned,
        // rather than reporting an asynchronously queued command as a completed grant.
        return CommandOutputCapture.capture(() -> {
            var cli = Grasscutter.getCommandMap().getHandler("give").createCommandLine(null, player);
            var errors = new StringWriter();
            cli.setErr(new PrintWriter(errors));
            if (cli.execute(arguments.toArray(String[]::new)) != 0) {
                throw new IllegalStateException("发放失败：" + errors.toString().strip());
            }
        });
    }
}
