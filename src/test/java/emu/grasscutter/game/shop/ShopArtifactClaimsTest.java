package emu.grasscutter.game.shop;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.command.DefaultPermissionHandler;
import emu.grasscutter.command.PermissionHandler;
import emu.grasscutter.data.*;
import emu.grasscutter.data.excels.ItemData;
import emu.grasscutter.data.excels.reliquary.*;
import emu.grasscutter.game.Account;
import emu.grasscutter.game.inventory.*;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.server.game.GameServer;
import emu.grasscutter.server.http.console.ShopArtifactClaims;
import io.javalin.Javalin;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;

class ShopArtifactClaimsTest {
    private static final Gson GSON = new Gson();
    private static final int PIECE = 9000001, PURPLE = 9000002, MAIN = 9100001, WRONG_MAIN = 9100002;
    private static final int CRIT = 9200001, HP = 9200002, WRONG_RANK = 9200003;
    private final List<Runnable> restore = new ArrayList<>();
    private final HttpClient client = HttpClient.newHttpClient();
    private PermissionHandler previousPermissions;
    private Javalin app;
    private Visitor owner, other;
    private String token;

    @BeforeEach @SuppressWarnings("unchecked") void setup() throws Exception {
        previousPermissions = Grasscutter.getPermissionHandler();
        Grasscutter.setPermissionHandler(new DefaultPermissionHandler());
        for (int rank : new int[] {4, 5}) {
            var item = GSON.fromJson("{\"id\":" + (rank == 5 ? PIECE : PURPLE)
                    + ",\"setId\":15020,\"itemType\":\"ITEM_RELIQUARY\",\"equipType\":\"EQUIP_BRACER\",\"rankLevel\":"
                    + rank + ",\"maxLevel\":" + (rank == 5 ? 21 : 17)
                    + ",\"mainPropDepotId\":4000,\"appendPropDepotId\":" + (rank * 100 + 1) + ",\"appendPropNum\":4}", ItemData.class);
            put(GameData.getItemDataMap(), item.getId(), item);
            for (int level : new int[] {1, rank == 5 ? 21 : 17}) {
                var row = GSON.fromJson("{\"rank\":" + rank + ",\"level\":" + level
                        + ",\"exp\":100,\"addProps\":[{\"propType\":\"FIGHT_PROP_HP\",\"value\":"
                        + (level == 1 ? 717 : rank == 5 ? 4780 : 3571) + "}]}", ReliquaryLevelData.class);
                row.onLoad();
                put((Map<Integer, ReliquaryLevelData>) GameData.getMapByResourceDef(ReliquaryLevelData.class), row.getId(), row);
            }
        }
        for (int id : new int[] {MAIN, WRONG_MAIN}) {
            var main = GSON.fromJson("{\"id\":" + id + ",\"propDepotId\":" + (id == MAIN ? 4000 : 3000)
                    + ",\"propType\":\"FIGHT_PROP_HP\",\"weight\":100}", ReliquaryMainPropData.class);
            put(GameData.getReliquaryMainPropDataMap(), id, main);
        }
        affix(CRIT, 501, "FIGHT_PROP_CRITICAL_HURT", .0777);
        affix(HP, 501, "FIGHT_PROP_HP", 299);
        affix(WRONG_RANK, 401, "FIGHT_PROP_CRITICAL_HURT", .0622);
        var mains = GameDepot.class.getDeclaredField("relicMainPropDepot"); mains.setAccessible(true);
        put((Map<Integer, List<ReliquaryMainPropData>>) mains.get(null), 4000, List.of(GameData.getReliquaryMainPropDataMap().get(MAIN)));
        var subs = GameDepot.class.getDeclaredField("relicAffixDepot"); subs.setAccessible(true);
        for (int depot : new int[] {401, 501}) put((Map<Integer, List<ReliquaryAffixData>>) subs.get(null), depot,
                GameData.getReliquaryAffixDataMap().values().stream().filter(row -> row.getDepotId() == depot).toList());

        owner = new Visitor(99893); other = new Visitor(99894);
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); unsafeField.setAccessible(true);
        var server = (GameServer) ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(GameServer.class);
        var players = GameServer.class.getDeclaredField("players"); players.setAccessible(true);
        players.set(server, new HashMap<>(Map.of(owner.getUid(), owner, other.getUid(), other)));
        var serverField = Grasscutter.class.getDeclaredField("gameServer"); serverField.setAccessible(true);
        Object previousServer = serverField.get(null); serverField.set(null, server);
        restore.add(() -> { try { serverField.set(null, previousServer); } catch (IllegalAccessException e) { throw new AssertionError(e); } });
        token = ShopSearch.issue(owner);
        app = Javalin.create();
        new ShopSearch().applyRoutes(app);
        // A second installation on the same handle must only keep the existing routes.
        new ShopSearch().applyRoutes(app);
        app.start("127.0.0.1", 0);
    }

    @AfterEach void cleanup() {
        if (app != null) app.stop();
        ShopSearch.revoke(99893); ShopSearch.revoke(99894);
        Collections.reverse(restore); restore.forEach(Runnable::run);
        Grasscutter.setPermissionHandler(previousPermissions);
    }

    private <T> void put(Map<Integer, T> table, int id, T value) {
        T old = table.put(id, value);
        restore.add(() -> { if (old == null) table.remove(id); else table.put(id, old); });
    }

    private void affix(int id, int depot, String property, double value) {
        var row = GSON.fromJson("{\"id\":" + id + ",\"depotId\":" + depot + ",\"propType\":\"" + property
                + "\",\"propValue\":" + value + ",\"weight\":100,\"upgradeWeight\":1000}", ReliquaryAffixData.class);
        put(GameData.getReliquaryAffixDataMap(), id, row);
    }

    private String recipe(int piece, int main, int affix, String rolls) {
        return "{\"itemId\":" + piece + ",\"mainPropId\":" + main + ",\"substats\":[{\"affixId\":" + affix + ",\"rolls\":" + rolls + "}]}";
    }

    private HttpResponse<String> request(String path, String body, String session) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/shop-search/api/artifacts" + path));
        if (session != null) request.header("X-Shop-Token", session);
        if (body == null) request.GET();
        else request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void routesRequireTheExistingPersonalTokenAndNeverAcceptARecipientUid() throws Exception {
        assertEquals(401, request("", null, null).statusCode());
        assertEquals(401, request("/preview", recipe(PIECE, MAIN, CRIT, "6"), "x".repeat(43)).statusCode());
        assertEquals(200, request("", null, token).statusCode());
        assertEquals(400, request("?uid=" + other.getUid(), null, token).statusCode());
        String foreign = recipe(PIECE, MAIN, CRIT, "6").replaceFirst("\\{", "{\"uid\":" + other.getUid() + ",");
        assertEquals(400, request("/claim", foreign, token).statusCode());
        assertEquals(400, request("/preview", foreign, token).statusCode());
        assertEquals(0, owner.inventory.calls); assertEquals(0, other.inventory.calls);
        owner.account.clearPermission();
        assertEquals(403, request("/claim", recipe(PIECE, MAIN, CRIT, "6"), token).statusCode());
        assertEquals(0, owner.inventory.calls);
        var pageField = ShopArtifactClaims.class.getDeclaredField("pageOverride"); pageField.setAccessible(true);
        Object previousPage = pageField.get(null);
        try {
            byte[] page = "<html>updated-diy</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ShopArtifactClaims.installPage(page);
            page[0] = 'X';
            var updated = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port()
                    + "/shop-search")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, updated.statusCode());
            assertEquals("<html>updated-diy</html>", updated.body());
        } finally {
            pageField.set(null, previousPage);
        }
    }

    @Test void previewUsesNativeValuesAndDefaultMaxLevelLockWithTheSameStrictValidation() throws Exception {
        for (int piece : new int[] {PIECE, PURPLE}) {
            var response = request("/preview", recipe(piece, MAIN, piece == PIECE ? CRIT : WRONG_RANK, "6"), token);
            assertEquals(200, response.statusCode(), response.body());
            var preview = JsonParser.parseString(response.body()).getAsJsonObject();
            assertEquals(piece == PIECE ? 20 : 16, preview.get("level").getAsInt());
            assertTrue(preview.get("locked").getAsBoolean());
            assertEquals(piece == PIECE ? 4780 : 3571, preview.getAsJsonObject("main").get("value").getAsInt());
        }
        for (String invalid : List.of(recipe(PIECE, WRONG_MAIN, CRIT, "6"), recipe(PIECE, MAIN, WRONG_RANK, "6"),
                recipe(PIECE, MAIN, HP, "1"), recipe(PIECE, MAIN, CRIT, "201"), recipe(PIECE, MAIN, CRIT, "1.5"),
                recipe(PIECE, MAIN, CRIT, "6").replaceFirst("\\{", "{\"level\":21,"))) {
            assertEquals(400, request("/preview", invalid, token).statusCode(), invalid);
        }
        assertEquals(0, owner.inventory.calls);
    }

    @Test void claimGivesOneExactItemOnlyToTheBoundPlayerAndNeverReportsRejectedInventoryAsSuccess() throws Exception {
        String recipe = recipe(PIECE, MAIN, CRIT, "6");
        var response = request("/claim", recipe, token);
        assertEquals(200, response.statusCode(), response.body());
        var receipt = JsonParser.parseString(response.body()).getAsJsonObject();
        assertTrue(receipt.get("accepted").getAsBoolean());
        assertEquals(1, owner.inventory.items.size());
        var item = owner.inventory.items.getFirst();
        assertEquals(owner.getUid(), item.getOwnerId());
        assertEquals(String.valueOf(item.getGuid()), receipt.get("guid").getAsString());
        assertEquals(21, item.getLevel()); assertTrue(item.isLocked());
        assertEquals(List.of(CRIT, CRIT, CRIT, CRIT, CRIT, CRIT), item.getAppendPropIdList());
        assertEquals(.0777 * 6, receipt.getAsJsonObject("preview").getAsJsonArray("substats")
                .get(0).getAsJsonObject().get("value").getAsDouble(), .000001);
        assertEquals(0, other.inventory.calls);
        owner.inventory.reject = true;
        assertEquals(400, request("/claim", recipe, token).statusCode());
        owner.inventory.full = true;
        int calls = owner.inventory.calls;
        var full = request("/claim", recipe, token);
        assertEquals(400, full.statusCode()); assertTrue(full.body().contains("背包已满"));
        assertEquals(calls, owner.inventory.calls);
    }

    private static final class Visitor extends Player {
        final Account account = new Account();
        final RecordingInventory inventory = new RecordingInventory(this);
        Visitor(int uid) { setUid(uid); account.addPermission("player.give"); }
        @Override public boolean isOnline() { return true; }
        @Override public Account getAccount() { return account; }
        @Override public Inventory getInventory() { return inventory; }
        @Override public void dropMessage(Object message) {}
    }

    private static final class RecordingInventory extends Inventory {
        int calls; boolean reject, full; List<GameItem> items = List.of();
        RecordingInventory(Player player) { super(player); }
        @Override public InventoryTab getInventoryTab(ItemType type) { return new EquipInventoryTab(full ? 0 : 5); }
        @Override public InventoryAddResult addItems(Collection<GameItem> granted) {
            calls++; items = List.copyOf(granted);
            if (reject) return InventoryAddResult.rejected(granted, InventoryAddResult.Status.CAPACITY_FULL);
            items.forEach(item -> item.setOwner(getPlayer()));
            return new InventoryAddResult(items.stream().map(item -> new InventoryAddResult.Entry(item.getItemId(),
                    item.getCount(), item.getCount(), InventoryAddResult.Status.ADDED)).toList());
        }
    }
}
