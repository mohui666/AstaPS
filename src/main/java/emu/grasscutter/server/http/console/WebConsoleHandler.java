package emu.grasscutter.server.http.console;

import static emu.grasscutter.config.Configuration.HTTP_INFO;

import com.google.gson.JsonParser;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.GameConstants;
import emu.grasscutter.data.GameData;
import emu.grasscutter.game.gacha.HistoricalGachaSelection;
import emu.grasscutter.game.activity.ActivityArchive;
import emu.grasscutter.game.entity.MonsterCatalog;
import emu.grasscutter.command.CommandOutputCapture;
import emu.grasscutter.config.ConfigContainer.WebConsole;
import emu.grasscutter.server.http.Router;
import emu.grasscutter.server.game.GameSessionManager;
import emu.grasscutter.utils.FileUtils;
import emu.grasscutter.utils.JsonUtils;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * The browser console at /console: live log, online players and console commands. Requires
 * explicit opt-in and a non-blank {@code server.http.webConsole.password}.
 *
 * <p>Server metrics come from the existing public /api/status endpoint; everything here needs a
 * session token.
 */
public final class WebConsoleHandler implements Router {
    private static final String TOKEN_HEADER = "X-Console-Token";

    private final WebConsole options = HTTP_INFO.webConsole;
    private final WebConsoleAuth auth = new WebConsoleAuth(options);

    @Override
    public void applyRoutes(Javalin javalin) {
        if (!options.enabled) return;

        if (options.effectivePassword() == null) {
            Grasscutter.getLogger()
                    .error(
                            "Web console disabled: server.http.webConsole.enabled is true, but"
                                    + " server.http.webConsole.password is missing or blank.");
            return;
        }

        WebConsoleLog.install();

        if (options.localOnly) {
            javalin.before("/console", WebConsoleHandler::requireLoopback);
            javalin.before("/console/*", WebConsoleHandler::requireLoopback);
        }

        javalin.get("/console", this::page);
        javalin.get("/console/assets/gacha-placeholder.png", ctx -> {
            try (var stream = FileUtils.readResourceAsStream("/html/gacha-placeholder.png")) {
                ctx.contentType("image/png");
                ctx.result(stream.readAllBytes());
            }
        });
        javalin.post("/console/api/login", this::login);
        javalin.post("/console/api/logout", this::logout);
        javalin.get("/console/api/logs", this::logs);
        javalin.get("/console/api/players", this::players);
        javalin.get("/console/api/items", this::items);
        javalin.post("/console/api/items/give", this::giveItem);
        javalin.get("/console/api/monsters", this::monsters);
        javalin.post("/console/api/monsters/spawn", this::spawnMonster);
        javalin.get("/console/api/artifacts", this::artifacts);
        javalin.post("/console/api/artifacts/preview", ctx -> artifactAction(ctx, false));
        javalin.post("/console/api/artifacts/give", ctx -> artifactAction(ctx, true));
        javalin.get("/console/api/gacha", this::gacha);
        javalin.post("/console/api/gacha/select", this::selectGacha);
        javalin.get("/console/api/player-tools", this::playerTools);
        javalin.post("/console/api/player-tools/action", this::playerAction);
        javalin.get("/console/api/recharge", ctx -> {
            if (!authorized(ctx)) return;
            json(ctx,200,Map.of("enabled",emu.grasscutter.config.Configuration.GAME_OPTIONS.localRecharge,
                    "products",emu.grasscutter.game.shop.LocalRecharge.products()));
        });
        javalin.post("/console/api/recharge", ctx -> {
            if (!authorized(ctx)) return;
            final com.google.gson.JsonObject request;
            try { request = JsonParser.parseString(ctx.body()).getAsJsonObject(); }
            catch (RuntimeException e) { json(ctx,400,Map.of("error","充值参数格式错误。")); return; }
            logicAction(ctx,"本地充值",() -> {
                int uid = WebConsoleItems.Grant.integer(request,"uid",null);
                var product = WebConsolePlayerTools.string(request,"productId");
                var order = WebConsolePlayerTools.string(request,"orderId");
                return emu.grasscutter.game.shop.LocalRecharge.purchase(WebConsolePlayerTools.player(uid),product,order);
            });
        });
        javalin.get("/console/api/activities", this::activities);
        javalin.get("/console/api/activities/detail", this::activityDetail);
        javalin.get("/console/api/activities/export", this::exportActivities);
        javalin.post("/console/api/activities/import", this::importActivities);
        javalin.post("/console/api/activities/action", this::activityAction);
        javalin.get("/console/api/activities/assets", this::activityAssets);
        javalin.post("/console/api/activities/assets/action", this::activityAssetAction);
        javalin.post("/console/api/command", this::command);
    }

    private static void requireLoopback(Context ctx) throws java.net.UnknownHostException {
        // Use the peer socket, never a forwarded address supplied by the caller.
        if (!InetAddress.getByName(ctx.req().getRemoteAddr()).isLoopbackAddress()) {
            throw new ForbiddenResponse("The operator console is only available on this computer.");
        }
    }

    private void page(Context ctx) {
        try (var stream = FileUtils.readResourceAsStream("/html/console.html")) {
            ctx.contentType("text/html; charset=UTF-8");
            ctx.result(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            ctx.status(500).result("Web console page is missing from the jar.");
        }
    }

    private static void json(Context ctx, int status, Object body) {
        ctx.status(status);
        ctx.contentType("application/json; charset=UTF-8");
        ctx.result(JsonUtils.encode(body));
    }

    /** One string field of the JSON request body, or null. */
    private static String field(Context ctx, String name) {
        try {
            var value = JsonParser.parseString(ctx.body()).getAsJsonObject().get(name);
            return value == null || value.isJsonNull() ? null : value.getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    /** ctx.ip() is the socket address; X-Forwarded-For is not trusted for the lockout. */
    private void login(Context ctx) {
        var address = ctx.ip();
        if (auth.isLockedOut(address)) {
            json(ctx, 429, Map.of("error", "Too many wrong passwords. Try again in 10 minutes."));
            return;
        }

        var token = auth.login(address, field(ctx, "password"));
        if (token == null) {
            Grasscutter.getLogger().warn("Web console: wrong password from {}", address);
            json(ctx, 401, Map.of("error", "Wrong password."));
            return;
        }

        Grasscutter.getLogger().info("Web console: login from {}", address);
        json(ctx, 200, Map.of("token", token));
    }

    private boolean authorized(Context ctx) {
        if (auth.isValid(ctx.header(TOKEN_HEADER))) return true;
        json(ctx, 401, Map.of("error", "Not logged in."));
        return false;
    }

    private void logout(Context ctx) {
        auth.logout(ctx.header(TOKEN_HEADER));
        json(ctx, 200, Map.of());
    }

    private void logs(Context ctx) {
        if (!authorized(ctx)) return;

        long after;
        try {
            var param = ctx.queryParam("after");
            after = param == null ? 0 : Long.parseLong(param);
        } catch (NumberFormatException e) {
            after = 0;
        }
        json(ctx, 200, Map.of("lines", WebConsoleLog.since(after)));
    }

    private void players(Context ctx) {
        if (!authorized(ctx)) return;

        var gameServer = Grasscutter.getGameServer();
        List<Map<String, Object>> players =
                gameServer == null
                        ? List.of()
                        : gameServer.getPlayers().values().stream()
                                .sorted(Comparator.comparingInt(p -> p.getUid()))
                                .map(
                                        p -> {
                                            Map<String, Object> row = new LinkedHashMap<>();
                                            row.put("uid", p.getUid());
                                            row.put("nickname", p.getNickname());
                                            row.put("level", p.getLevel());
                                            row.put("worldLevel", p.getWorldLevel());
                                            row.put("sceneId", p.getSceneId());
                                            return row;
                                        })
                                .toList();
        json(ctx, 200, Map.of("players", players));
    }

    private void command(Context ctx) {
        if (!authorized(ctx)) return;

        var command = field(ctx, "command");
        if (command == null || command.isBlank()) {
            json(ctx, 400, Map.of("error", "No command given."));
            return;
        }
        command = command.trim();
        if (command.startsWith("/")) command = command.substring(1);

        Grasscutter.getLogger().info("Web console command from {}: {}", ctx.ip(), command);
        final var line = command;
        List<String> output;
        try {
            output =
                    CommandOutputCapture.capture(
                            () -> Grasscutter.getCommandMap().invoke(null, null, line));
        } catch (Exception e) {
            Grasscutter.getLogger().warn("Web console command failed: {}", line, e);
            json(ctx, 500, Map.of("error", String.valueOf(e)));
            return;
        }
        json(ctx, 200, Map.of("output", output));
    }

    private void items(Context ctx) {
        if (!authorized(ctx)) return;
        json(ctx, 200, Map.of("version", GameConstants.VERSION, "items", WebConsoleItems.catalog()));
    }

    private void monsters(Context ctx) {
        if (!authorized(ctx)) return;
        json(ctx, 200, Map.of("version", GameConstants.VERSION, "monsters", MonsterCatalog.catalog(),
                "levels", MonsterCatalog.levels(), "entityLimit", emu.grasscutter.config.Configuration.GAME_OPTIONS.sceneEntityLimit));
    }

    private void spawnMonster(Context ctx) {
        if (!authorized(ctx)) return;
        final WebConsoleMonsters.Request request;
        try { request = WebConsoleMonsters.Request.parse(ctx.body()); }
        catch (RuntimeException e) {
            json(ctx, 400, Map.of("error", e instanceof IllegalArgumentException ? e.getMessage() : "刷怪参数格式错误。"));
            return;
        }
        logicAction(ctx, "刷怪", () -> WebConsoleMonsters.spawn(request));
    }

    private static Map<String, Object> gachaPhase(HistoricalGachaSelection.Phase phase) {
        boolean dedicatedDisplay = Arrays.stream(phase.up5())
                .allMatch(id -> emu.grasscutter.game.gacha.GachaEpitomizedPrefabHelper.resolve(id) != null);
        return Map.of("key", phase.key(), "label", phase.label(),
                "nativeDisplayReady", true, "dedicatedDisplayReady", dedicatedDisplay,
                "placeholderDisplay", !dedicatedDisplay,
                "placeholderImage", dedicatedDisplay ? "" : "/console/assets/gacha-placeholder.png",
                "up5", Arrays.stream(phase.up5()).mapToObj(id -> WebConsoleItems.entry(GameData.getItemDataMap().get(id))).toList(),
                "up4", Arrays.stream(phase.up4()).mapToObj(id -> WebConsoleItems.entry(GameData.getItemDataMap().get(id))).toList());
    }

    private void gacha(Context ctx) throws Exception {
        if (!authorized(ctx)) return;
        var server = Grasscutter.getGameServer();
        if (server == null) {
            json(ctx, 503, Map.of("error", "游戏服务器尚未启动。"));
            return;
        }
        var system = server.getGachaSystem();
        var result = GameSessionManager.getLogicThread().submit(() -> {
            var current = system.getHistoricalGachaPhase();
            return current == null ? Map.of("enabled", false)
                    : Map.of("enabled", true, "current", gachaPhase(current),
                            "tabs", system.getGachaBanners().values().stream()
                                    .sorted(Comparator.comparingInt(emu.grasscutter.game.gacha.GachaBanner::getTabSortId).reversed())
                                    .map(banner -> Map.of("scheduleId",banner.getScheduleId(),"type",banner.getGachaType(),
                                            "up5",banner.getRateUpItems5(),"up4",banner.getRateUpItems4(),"prefab",banner.getPrefabPath(),
                                            "costItemId",banner.getCost(1).getId(),"placeholderDisplay",banner.isPlaceholderDisplay())).toList(),
                            "phases", system.getHistoricalGachaPhases().stream().map(WebConsoleHandler::gachaPhase).toList());
        }).get();
        json(ctx, 200, result);
    }

    private void selectGacha(Context ctx) throws Exception {
        if (!authorized(ctx)) return;
        var key = field(ctx, "phaseKey");
        if (key == null || key.isBlank()) {
            json(ctx, 400, Map.of("error", "请选择卡池。"));
            return;
        }
        var server = Grasscutter.getGameServer();
        if (server == null) {
            json(ctx, 503, Map.of("error", "游戏服务器尚未启动。"));
            return;
        }
        try {
            var selected = GameSessionManager.getLogicThread()
                    .submit(() -> server.getGachaSystem().selectHistoricalGacha(key)).get();
            Grasscutter.getLogger().info("Web console gacha selection from {}: {}", ctx.ip(), selected.key());
            json(ctx, 200, Map.of("current", gachaPhase(selected)));
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IllegalArgumentException invalid) {
                json(ctx, 400, Map.of("error", invalid.getMessage()));
            } else {
                Grasscutter.getLogger().error("Web console gacha selection failed", e.getCause());
                json(ctx, 500, Map.of("error", "切换卡池失败，请查看服务器日志。"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            json(ctx, 503, Map.of("error", "请求被中断，请刷新确认当前卡池。"));
        }
    }

    private void giveItem(Context ctx) throws Exception {
        if (!authorized(ctx)) return;
        final WebConsoleItems.Grant request;
        try {
            request = WebConsoleItems.Grant.parse(ctx.body());
        } catch (RuntimeException e) {
            json(ctx, 400, Map.of("error", e instanceof IllegalArgumentException ? e.getMessage() : "发放参数格式错误。"));
            return;
        }
        try {
            var output = GameSessionManager.getLogicThread().submit(() -> WebConsoleItems.give(request)).get();
            Grasscutter.getLogger().info("Web console item grant from {}: UID {}, item {}, amount {}", ctx.ip(), request.uid(), request.itemId(), request.amount());
            json(ctx, 200, Map.of("output", output));
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IllegalArgumentException invalid) {
                json(ctx, 400, Map.of("error", invalid.getMessage()));
            } else {
                Grasscutter.getLogger().error("Web console item grant failed", e.getCause());
                json(ctx, 500, Map.of("error", "发放执行失败，请查看服务器日志。"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            json(ctx, 503, Map.of("error", "请求被中断，结果未知，请先检查背包再操作。"));
        }
    }

    private void artifacts(Context ctx) {
        if (!authorized(ctx)) return;
        json(ctx, 200, WebConsoleArtifacts.catalog());
    }

    private void logicAction(Context ctx, String description, java.util.concurrent.Callable<Object> operation) {
        try {
            json(ctx, 200, GameSessionManager.getLogicThread().submit(operation).get());
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IllegalArgumentException invalid) json(ctx, 400, Map.of("error", invalid.getMessage()));
            else {
                Grasscutter.getLogger().error("Web console {} failed", description, e.getCause());
                json(ctx, 500, Map.of("error", description + "失败，请查看日志并刷新实际状态后再操作。"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            json(ctx, 503, Map.of("error", "请求中断，结果未知，请刷新实际状态后再操作。"));
        }
    }

    private void playerTools(Context ctx) {
        if (!authorized(ctx)) return;
        try {
            int uid = Integer.parseInt(ctx.queryParam("uid"));
            int avatarId = ctx.queryParam("avatarId") == null ? 0 : Integer.parseInt(ctx.queryParam("avatarId"));
            logicAction(ctx, "读取玩家状态", () -> WebConsolePlayerTools.state(uid, avatarId));
        } catch (NumberFormatException e) { json(ctx, 400, Map.of("error", "UID 或角色 ID 格式错误。")); }
    }

    private void playerAction(Context ctx) {
        if (!authorized(ctx)) return;
        final com.google.gson.JsonObject request;
        try { request = JsonParser.parseString(ctx.body()).getAsJsonObject(); }
        catch (RuntimeException e) { json(ctx, 400, Map.of("error", "玩家操作参数格式错误。")); return; }
        logicAction(ctx, "玩家操作", () -> {
            var result = WebConsolePlayerTools.execute(request);
            Grasscutter.getLogger().info("Web console player action from {}: UID {}, {}", ctx.ip(), request.get("uid"), request.get("action"));
            return result;
        });
    }

    private void activities(Context ctx) {
        if (!authorized(ctx)) return;
        logicAction(ctx, "读取活动档案", () -> Map.of("version", GameConstants.VERSION, "activities", ActivityArchive.catalog()));
    }

    private void activityDetail(Context ctx) {
        if (!authorized(ctx)) return;
        try {
            int id = Integer.parseInt(ctx.queryParam("id"));
            int uid = ctx.queryParam("uid") == null ? 0 : Integer.parseInt(ctx.queryParam("uid"));
            logicAction(ctx, "读取活动详情", () -> {
                var result = ActivityArchive.detail(id);
                if (uid > 0) {
                    var data = WebConsolePlayerTools.player(uid).getActivityManager().getPlayerActivityDataMap().get(id);
                    if (data != null) result.add("progress", JsonUtils.toJson(Map.of("uid", uid,
                            "watchers", data.getWatcherInfoMap(), "detail", data.getDetail() == null ? "" : data.getDetail())));
                }
                return result;
            });
        } catch (NumberFormatException e) { json(ctx, 400, Map.of("error", "活动 ID 或 UID 格式错误。")); }
    }

    private void exportActivities(Context ctx) {
        if (!authorized(ctx)) return;
        ctx.header("Content-Disposition", "attachment; filename=astaps-activities-7.1.json");
        logicAction(ctx, "导出活动档案", ActivityArchive::exportPack);
    }

    private void importActivities(Context ctx) {
        if (!authorized(ctx)) return;
        var body = ctx.body();
        logicAction(ctx, "导入活动档案", () -> {
            ActivityArchive.importPack(body);
            Grasscutter.getLogger().info("Web console activity archive imported from {}", ctx.ip());
            return Map.of("activities", ActivityArchive.catalog());
        });
    }

    private void activityAction(Context ctx) {
        if (!authorized(ctx)) return;
        final com.google.gson.JsonObject request;
        try { request = JsonParser.parseString(ctx.body()).getAsJsonObject(); }
        catch (RuntimeException e) { json(ctx, 400, Map.of("error", "活动参数格式错误。")); return; }
        logicAction(ctx, "活动操作", () -> {
            int id = WebConsoleItems.Grant.integer(request, "activityId", null);
            String action = WebConsolePlayerTools.string(request, "action");
            if (action.equals("resetProgress")) {
                if (!WebConsolePlayerTools.bool(request, "confirmed")) throw new IllegalArgumentException("请确认清空此玩家的活动进度及领奖记录。");
                var player = WebConsolePlayerTools.player(WebConsoleItems.Grant.integer(request, "uid", null));
                player.getActivityManager().resetArchiveProgress(id);
            } else {
                ActivityArchive.update(id, action, WebConsoleItems.Grant.integer(request, "days", 7),
                        WebConsolePlayerTools.bool(request, "unlockConditions"),
                        WebConsolePlayerTools.bool(request, "entryOnly"),
                        request.has("note") ? WebConsolePlayerTools.string(request, "note") : null,
                        request.has("begin") ? (long) WebConsolePlayerTools.number(request, "begin", 1000, 4_102_444_799_000d) : null,
                        request.has("end") ? (long) WebConsolePlayerTools.number(request, "end", 1000, 4_102_444_799_000d) : null);
            }
            Grasscutter.getLogger().info("Web console activity action from {}: {}, {}", ctx.ip(), id, action);
            return Map.of("activities", ActivityArchive.catalog());
        });
    }

    private void activityAssets(Context ctx) {
        if (!authorized(ctx)) return;
        try { json(ctx, 200, WebConsoleActivityAssets.status(Integer.parseInt(ctx.queryParam("id")))); }
        catch (IllegalArgumentException e) { json(ctx, 400, Map.of("error", e.getMessage())); }
        catch (java.io.IOException e) {
            Grasscutter.getLogger().error("Unable to read event package", e);
            json(ctx, 500, Map.of("error", "读取活动资源包失败，请检查日志。"));
        }
    }

    private void activityAssetAction(Context ctx) {
        if (!authorized(ctx)) return;
        try {
            var request = JsonParser.parseString(ctx.body()).getAsJsonObject();
            int id = WebConsoleItems.Grant.integer(request, "activityId", null);
            String action = WebConsolePlayerTools.string(request, "action");
            if (action.equals("load")) {
                logicAction(ctx, "载入已存活动设置", () -> {
                    WebConsoleActivityAssets.loadSettings(id);
                    return Map.of("activities", ActivityArchive.catalog(), "assets", WebConsoleActivityAssets.status(id));
                });
                return;
            }
            var snapshot = GameSessionManager.getLogicThread().submit(() -> ActivityArchive.detail(id)).get();
            var result = switch (action) {
                case "save" -> WebConsoleActivityAssets.save(id, snapshot);
                case "restore" -> WebConsoleActivityAssets.restore(id);
                default -> throw new IllegalArgumentException("未知资源包操作。");
            };
            Grasscutter.getLogger().info("Web console event package action from {}: {}, {}", ctx.ip(), id, action);
            json(ctx, 200, Map.of("assets", result));
        } catch (IllegalArgumentException e) { json(ctx, 400, Map.of("error", e.getMessage())); }
        catch (ExecutionException e) {
            if (e.getCause() instanceof IllegalArgumentException invalid) json(ctx, 400, Map.of("error", invalid.getMessage()));
            else { Grasscutter.getLogger().error("Event package snapshot failed", e.getCause()); json(ctx, 500, Map.of("error", "读取活动设置失败。")); }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); json(ctx, 503, Map.of("error", "操作中断，请刷新资源包状态。"));
        } catch (Exception e) {
            Grasscutter.getLogger().error("Event package operation failed", e);
            json(ctx, 500, Map.of("error", "资源包操作失败，请查看日志；已有的部分文件可能已复制。"));
        }
    }

    private void artifactAction(Context ctx, boolean give) {
        if (!authorized(ctx)) return;
        final WebConsoleArtifacts.Request request;
        try {
            request = WebConsoleArtifacts.Request.parse(ctx.body());
        } catch (RuntimeException e) {
            json(ctx, 400, Map.of("error", e instanceof IllegalArgumentException ? e.getMessage() : "圣遗物参数格式错误。"));
            return;
        }
        try {
            Object result = GameSessionManager.getLogicThread().submit(() -> give
                    ? WebConsoleArtifacts.give(request) : WebConsoleArtifacts.preview(request)).get();
            if (give) Grasscutter.getLogger().info("Web console DIY relic grant from {}: UID {}, item {}", ctx.ip(), request.uid(), request.itemId());
            json(ctx, 200, result);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IllegalArgumentException invalid) {
                json(ctx, 400, Map.of("error", invalid.getMessage()));
            } else {
                Grasscutter.getLogger().error("Web console DIY relic {} failed", give ? "grant" : "preview", e.getCause());
                json(ctx, 500, Map.of("error", give ? "发放执行异常，请先检查背包或日志再决定是否重发。" : "预览失败，请查看服务器日志。"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            json(ctx, 503, Map.of("error", "请求被中断，请先检查背包或刷新预览。"));
        }
    }
}
