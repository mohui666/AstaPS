package emu.grasscutter.game.shop;

import static emu.grasscutter.config.Configuration.HTTP_ENCRYPTION;
import static emu.grasscutter.config.Configuration.HTTP_INFO;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.ItemCatalog;
import emu.grasscutter.game.inventory.MaterialType;
import emu.grasscutter.game.mail.Mail;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.server.game.GameSessionManager;
import emu.grasscutter.server.http.Router;
import emu.grasscutter.utils.JsonUtils;
import io.javalin.Javalin;
import io.javalin.http.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.function.IntFunction;

/** Personal in-game browser entry for the same free goods as native mall 902. */
public final class ShopSearch implements Router {
    private static final String TOKEN_HEADER = "X-Shop-Token";
    static final int PAGE_SIZE = 30;
    private static final Set<String> CATEGORIES = Set.of(
            "all", "avatar", "currency", "weapon", "artifact", "material", "food", "furniture");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<Integer, Session> SESSIONS = new ConcurrentHashMap<>();
    private static Javalin installed;

    private record Session(Player player, String token) {}
    public record Product(int goodsId, int itemId, String name, String en, String category,
                          int rank, int unitCount, int avatarId) {}
    public record Results(String nickname, List<Product> items, int total, int page, int pageSize) {}

    /** May also be called on an already-running HTTP server. */
    public static void install() {
        var server = Grasscutter.getHttpServer();
        if (server == null || server.getHandle() == null) return;
        new ShopSearch().applyRoutes(server.getHandle());
    }

    @Override
    public void applyRoutes(Javalin javalin) {
        synchronized (ShopSearch.class) {
            emu.grasscutter.server.http.console.ShopArtifactClaims.install(javalin);
            if (installed == javalin) return;
            javalin.get("/shop-search", ctx -> {
                privateResponse(ctx);
                ctx.header("Referrer-Policy", "no-referrer");
                try (var stream = emu.grasscutter.server.http.console.ShopArtifactClaims.openPage()) {
                    ctx.contentType("text/html; charset=UTF-8");
                    ctx.result(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
                }
            });
            javalin.get("/shop-search/api/items", ctx -> {
                String query = ctx.queryParam("q"), category = ctx.queryParam("category"), page = ctx.queryParam("page");
                boolean suppliedUid = ctx.queryParam("uid") != null;
                api(ctx, player -> {
                    if (suppliedUid) throw new IllegalArgumentException("领取目标由游戏入口绑定，不能指定UID。");
                    return search(player, query, category, page);
                });
            });
            javalin.post("/shop-search/api/buy", ctx -> {
                String body = ctx.body();
                api(ctx, player -> {
                    var request = PurchaseRequest.parse(body);
                    var receipt = AssistantShop.buy(player, AssistantShop.SHOP_TYPE, request.goodsId(), request.quantity());
                    return Map.of("accepted", receipt.accepted(), "bagFull", receipt.bagFull(), "message", receipt.message());
                });
            });
            installed = javalin;
        }
    }

    /** Call from the game logic thread. Only this player's current entry remains valid. */
    public static void sendEntry(Player player) {
        var server = Grasscutter.getHttpServer();
        if (server == null || server.getHandle() == null)
            throw new IllegalArgumentException("当前未开启HTTP服务，暂时无法打开商城搜索。");
        String token = issue(player);
        final String url;
        try {
            url = ShopSearchEntryAddress.url(token, HTTP_INFO, HTTP_ENCRYPTION.useInRouting,
                    System.getProperty(ShopSearchEntryAddress.PUBLIC_ORIGIN_PROPERTY));
        } catch (java.net.URISyntaxException invalid) {
            revoke(player.getUid());
            throw new IllegalStateException("商城搜索的HTTP访问地址无效。", invalid);
        }
        var mail = new Mail();
        mail.mailContent = new Mail.MailContent("商城搜索",
                "搜索角色、武器、材料或物品ID，点击结果即可免费领取。\n\n"
                        + "<type=\"browser\" text=\"打开商城搜索\" href=\"" + url + "\"/>\n\n"
                        + "此入口仅绑定你本次登录的账号。退出游戏或重新获取入口后，旧入口失效。",
                "派蒙助手");
        player.sendMail(mail);
    }

    public static void revoke(int uid) {
        SESSIONS.remove(uid);
    }

    static String issue(Player player) {
        if (!AssistantShop.permitted(player)) throw new ForbiddenResponse("当前账号没有物品领取权限。");
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        SESSIONS.put(player.getUid(), new Session(player, token));
        return token;
    }

    /** Rechecks the online Player identity, so a relog cannot inherit an old browser entry. */
    static Player authorize(String token, IntFunction<Player> onlinePlayer) {
        if (token == null || token.length() != 43) throw new UnauthorizedResponse("请在游戏内向助手发送“商城搜索”，打开新的入口邮件。");
        var session = SESSIONS.values().stream().filter(value -> value.token().equals(token)).findFirst().orElse(null);
        if (session == null) throw new UnauthorizedResponse("搜索入口已失效，请在游戏内重新发送“商城搜索”。");
        var owner = session.player();
        if (onlinePlayer.apply(owner.getUid()) != owner || !owner.isOnline()) {
            SESSIONS.remove(owner.getUid(), session);
            throw new UnauthorizedResponse("角色已离线，请登录游戏后重新获取搜索入口。");
        }
        if (!AssistantShop.permitted(owner)) {
            SESSIONS.remove(owner.getUid(), session);
            throw new ForbiddenResponse("当前账号没有物品领取权限。");
        }
        return owner;
    }

    static Results search(Player player, String rawQuery, String rawCategory, String rawPage) {
        String query = rawQuery == null ? "" : rawQuery.strip().toLowerCase(Locale.ROOT);
        if (query.length() > 120) throw new IllegalArgumentException("搜索词请控制在120字以内。");
        String category = rawCategory == null || rawCategory.isBlank() ? "all" : rawCategory;
        if (!CATEGORIES.contains(category)) throw new IllegalArgumentException("物品分类无效。");
        final int requestedPage;
        try { requestedPage = rawPage == null ? 1 : Integer.parseInt(rawPage); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("页码必须是正整数。"); }
        if (requestedPage <= 0) throw new IllegalArgumentException("页码必须是正整数。");
        var goods = AssistantShop.goods(player, AssistantShop.SHOP_TYPE);
        if (goods == null) throw new ForbiddenResponse("当前账号没有物品领取权限。");
        String[] terms = query.isEmpty() ? new String[0] : query.split("\\s+");
        var matches = goods.stream().map(ShopSearch::product)
                .filter(row -> category.equals("all") || category.equals(row.category()))
                .filter(row -> matches(row, terms)).toList();
        int page = Math.min(requestedPage, Math.max(1, (matches.size() + PAGE_SIZE - 1) / PAGE_SIZE));
        int start = (page - 1) * PAGE_SIZE;
        return new Results(player.getNickname(), matches.subList(start, Math.min(start + PAGE_SIZE, matches.size())),
                matches.size(), page, PAGE_SIZE);
    }

    private static Product product(ShopInfo goods) {
        var definition = GameData.getItemDataMap().get(goods.getGoodsItem().getId());
        var name = ItemCatalog.entry(definition);
        boolean avatar = definition.getMaterialType() == MaterialType.MATERIAL_AVATAR;
        return new Product(goods.getGoodsId(), definition.getId(), name.name(), name.en(),
                avatar ? "avatar" : name.category(), name.rank(), goods.getGoodsItem().getCount(),
                avatar ? AssistantShop.avatarId(definition) : 0);
    }

    private static boolean matches(Product item, String[] terms) {
        String text = (item.name() + " " + item.en() + " " + item.itemId()
                + (item.avatarId() == 0 ? "" : " " + item.avatarId())).toLowerCase(Locale.ROOT);
        return Arrays.stream(terms).allMatch(text::contains);
    }

    record PurchaseRequest(int goodsId, int quantity) {
        static PurchaseRequest parse(String body) {
            final JsonObject request;
            try { request = JsonParser.parseString(body).getAsJsonObject(); }
            catch (RuntimeException invalid) { throw new IllegalArgumentException("领取参数格式错误。"); }
            if (request.has("uid")) throw new IllegalArgumentException("领取目标由游戏入口绑定，不能指定UID。");
            return new PurchaseRequest(positiveInteger(request, "goodsId"), positiveInteger(request, "quantity"));
        }

        private static int positiveInteger(JsonObject request, String key) {
            var value = request.get(key);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
                throw new IllegalArgumentException("商品和数量必须是正整数。");
            try {
                int number = value.getAsBigDecimal().intValueExact();
                if (number <= 0) throw new ArithmeticException();
                return number;
            } catch (ArithmeticException | NumberFormatException invalid) {
                throw new IllegalArgumentException("商品和数量必须是正整数。");
            }
        }
    }

    private static void privateResponse(Context ctx) {
        ctx.header("Cache-Control", "no-store");
    }

    private static void json(Context ctx, int status, Object body) {
        privateResponse(ctx);
        ctx.status(status).contentType("application/json; charset=UTF-8").result(JsonUtils.encode(body));
    }

    private static void api(Context ctx, Function<Player, Object> action) {
        String token = ctx.header(TOKEN_HEADER);
        try {
            Object result = GameSessionManager.getLogicThread().submit(() -> {
                var player = authorize(token, uid -> Grasscutter.getGameServer() == null
                        ? null : Grasscutter.getGameServer().getPlayerByUid(uid));
                return action.apply(player);
            }).get();
            json(ctx, 200, result);
        } catch (ExecutionException failure) {
            var cause = failure.getCause();
            if (cause instanceof HttpResponseException response) json(ctx, response.getStatus(), Map.of("error", response.getMessage()));
            else if (cause instanceof IllegalArgumentException) json(ctx, 400, Map.of("error", cause.getMessage()));
            else if (cause instanceof ArithmeticException) json(ctx, 400, Map.of("error", "数量过大，无法领取，请减小数量。"));
            else {
                Grasscutter.getLogger().error("Shop search request failed", cause);
                json(ctx, 500, Map.of("error", "操作失败，请先检查游戏内实际到账情况。"));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            json(ctx, 503, Map.of("error", "请求已中断，结果未知，请先检查实际到账情况。"));
        }
    }
}
