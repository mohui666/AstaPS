package emu.grasscutter.server.http.console;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.shop.ShopSearchClaims;
import emu.grasscutter.server.game.GameSessionManager;
import emu.grasscutter.server.http.Router;
import emu.grasscutter.utils.FileUtils;
import emu.grasscutter.utils.JsonUtils;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

/** Personal claims reuse the console's native artifact validation, preview and inventory result. */
public final class ShopArtifactClaims implements Router {
    private static Javalin installed;
    private static volatile byte[] pageOverride;

    /** The online installer supplies the page from its own new runtime JAR. */
    public static void installPage(byte[] html) {
        pageOverride = html.clone();
    }

    public static InputStream openPage() {
        byte[] html = pageOverride;
        return html == null ? FileUtils.readResourceAsStream("/html/shop-search.html") : new ByteArrayInputStream(html);
    }

    public static synchronized void install(Javalin javalin) {
        if (installed == javalin) return;
        new ShopArtifactClaims().applyRoutes(javalin);
        installed = javalin;
    }

    @Override
    public void applyRoutes(Javalin javalin) {
        javalin.get("/shop-search/api/artifacts", ctx -> api(ctx, player -> WebConsoleArtifacts.catalog()));
        javalin.post("/shop-search/api/artifacts/preview", ctx -> {
            String body = ctx.body();
            api(ctx, player -> WebConsoleArtifacts.preview(request(player, body)));
        });
        javalin.post("/shop-search/api/artifacts/claim", ctx -> {
            String body = ctx.body();
            api(ctx, player -> {
                var receipt = WebConsoleArtifacts.give(request(player, body));
                return Map.of("accepted", true, "guid", receipt.guid(), "preview", receipt.preview(),
                        "message", "已领取1件圣遗物，请在游戏内背包查看。");
            });
        });
    }

    static WebConsoleArtifacts.Request request(Player player, String body) {
        final JsonObject json;
        try { json = JsonParser.parseString(body).getAsJsonObject(); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("圣遗物配置格式错误。"); }
        if (json.has("uid")) throw new IllegalArgumentException("领取目标由游戏入口绑定，不能指定UID。");
        if (json.has("quantity") && WebConsoleItems.Grant.integer(json, "quantity", null) != 1)
            throw new IllegalArgumentException("圣遗物DIY每次领取1件。");
        int itemId = WebConsoleItems.Grant.integer(json, "itemId", null);
        var definition = emu.grasscutter.data.GameData.getItemDataMap().get(itemId);
        if (!WebConsoleArtifacts.supported(definition)) throw new IllegalArgumentException("请选择列表中的圣遗物。");
        if (!json.has("level")) json.addProperty("level", definition.getMaxLevel() - 1);
        if (!json.has("locked")) json.addProperty("locked", true);
        json.addProperty("uid", player.getUid());
        return WebConsoleArtifacts.Request.parse(json.toString());
    }

    private static void json(Context ctx, int status, Object body) {
        ctx.header("Cache-Control", "no-store");
        ctx.status(status).contentType("application/json; charset=UTF-8").result(JsonUtils.encode(body));
    }

    private static void api(Context ctx, Function<Player, Object> operation) {
        String token = ctx.header("X-Shop-Token");
        boolean suppliedUid = ctx.queryParam("uid") != null;
        try {
            Object result = GameSessionManager.getLogicThread().submit(() -> {
                var player = ShopSearchClaims.authorize(token);
                if (suppliedUid) throw new IllegalArgumentException("领取目标由游戏入口绑定，不能指定UID。");
                return operation.apply(player);
            }).get();
            json(ctx, 200, result);
        } catch (ExecutionException failure) {
            var cause = failure.getCause();
            if (cause instanceof HttpResponseException response) json(ctx, response.getStatus(), Map.of("error", response.getMessage()));
            else if (cause instanceof IllegalArgumentException) json(ctx, 400, Map.of("error", cause.getMessage()));
            else {
                Grasscutter.getLogger().error("Shop artifact request failed", cause);
                json(ctx, 500, Map.of("error", "操作失败，请先检查游戏内圣遗物背包的实际结果。"));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            json(ctx, 503, Map.of("error", "请求已中断，结果未知，请先检查游戏内背包。"));
        }
    }
}
