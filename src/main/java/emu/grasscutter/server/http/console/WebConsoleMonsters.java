package emu.grasscutter.server.http.console;

import com.google.gson.JsonParser;
import emu.grasscutter.game.entity.MonsterCatalog;

/** Typed request parsing; authorization stays in the console router. */
final class WebConsoleMonsters {
    record Request(int uid, int monsterId, int amount, int level, double distance) {
        static Request parse(String body) {
            var json = JsonParser.parseString(body).getAsJsonObject();
            int uid = WebConsoleItems.Grant.integer(json, "uid", null);
            int monsterId = WebConsoleItems.Grant.integer(json, "monsterId", null);
            int amount = WebConsoleItems.Grant.integer(json, "amount", 1);
            int level = WebConsoleItems.Grant.integer(json, "level", 1);
            double distance = json.has("distance") ? WebConsolePlayerTools.number(json, "distance", 0, 100) : 5;
            if (uid < 1 || monsterId < 1) throw new IllegalArgumentException("请选择有效的玩家和怪物。");
            if (amount < 1 || level < 1) throw new IllegalArgumentException("数量与等级必须为正整数。");
            return new Request(uid, monsterId, amount, level, distance);
        }
    }

    private WebConsoleMonsters() {}

    static MonsterCatalog.Receipt spawn(Request request) {
        return MonsterCatalog.spawn(WebConsolePlayerTools.player(request.uid()), request.monsterId(),
                request.amount(), request.level(), request.distance());
    }
}
