package emu.grasscutter.game.shop;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.command.DefaultPermissionHandler;
import emu.grasscutter.command.PermissionHandler;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.ItemData;
import emu.grasscutter.data.excels.avatar.AvatarData;
import emu.grasscutter.game.Account;
import emu.grasscutter.game.player.Player;
import io.javalin.http.ForbiddenResponse;
import io.javalin.http.UnauthorizedResponse;
import java.util.*;
import org.junit.jupiter.api.*;

class ShopSearchTest {
    private static final Gson GSON = new Gson();
    private final List<Runnable> restore = new ArrayList<>();
    private PermissionHandler previousPermissions;

    @BeforeEach void tables() {
        previousPermissions = Grasscutter.getPermissionHandler();
        Grasscutter.setPermissionHandler(new DefaultPermissionHandler());
        item("{\"id\":202,\"itemType\":\"ITEM_VIRTUAL\",\"stackLimit\":2147483647}");
        item("{\"id\":11509,\"itemType\":\"ITEM_WEAPON\",\"rankLevel\":5}");
        item("{\"id\":4133,\"itemType\":\"ITEM_MATERIAL\",\"materialType\":\"MATERIAL_AVATAR\",\"useOnGain\":true,\"itemUse\":[{\"useOp\":\"ITEM_USE_GAIN_AVATAR\",\"useParam\":[\"10000133\"]}]}");
        put(GameData.getAvatarDataMap(), 10000133,
                GSON.fromJson("{\"id\":10000133}", AvatarData.class));
    }

    @AfterEach void cleanup() {
        ShopSearch.revoke(99891);
        ShopSearch.revoke(99892);
        Collections.reverse(restore);
        restore.forEach(Runnable::run);
        Grasscutter.setPermissionHandler(previousPermissions);
    }

    private void item(String json) {
        var item = GSON.fromJson(json, ItemData.class);
        item.onLoad();
        put(GameData.getItemDataMap(), item.getId(), item);
    }

    private <T> void put(Map<Integer, T> table, int id, T row) {
        T old = table.put(id, row);
        restore.add(() -> { if (old == null) table.remove(id); else table.put(id, old); });
    }

    @Test void gameOnlyStartupSkipsRoutesAndExplicitEntryExplainsMissingHttp() throws Exception {
        var field = Grasscutter.class.getDeclaredField("httpServer");
        field.setAccessible(true);
        Object previous = field.get(null);
        try {
            field.set(null, null);
            assertDoesNotThrow(ShopSearch::install);
            var error = assertThrows(IllegalArgumentException.class, () -> ShopSearch.sendEntry(new Visitor(99891)));
            assertTrue(error.getMessage().contains("HTTP"));
        } finally {
            field.set(null, previous);
        }
    }

    @Test void entriesAreRandomPersonalRevocableAndBoundToTheCurrentPlayerObject() {
        var first = new Visitor(99891);
        var second = new Visitor(99892);
        String token = ShopSearch.issue(first);
        String otherToken = ShopSearch.issue(second);
        assertEquals(43, token.length());
        assertNotEquals(token, otherToken);
        assertSame(first, ShopSearch.authorize(token, uid -> uid == first.getUid() ? first : second));
        assertSame(second, ShopSearch.authorize(otherToken, uid -> uid == second.getUid() ? second : first));
        String replacement = ShopSearch.issue(first);
        assertNotEquals(token, replacement);
        assertThrows(UnauthorizedResponse.class, () -> ShopSearch.authorize(token, uid -> first));
        assertSame(first, ShopSearch.authorize(replacement, uid -> first));
        var relogged = new Visitor(first.getUid());
        assertThrows(UnauthorizedResponse.class, () -> ShopSearch.authorize(replacement, uid -> relogged));
        assertSame(second, ShopSearch.authorize(otherToken, uid -> second));
        ShopSearch.revoke(second.getUid());
        assertThrows(UnauthorizedResponse.class, () -> ShopSearch.authorize(otherToken, uid -> second));
    }

    @Test void offlinePlayersAndLostGivePermissionCannotReuseAnEntry() {
        var player = new Visitor(99891);
        String token = ShopSearch.issue(player);
        player.online = false;
        assertThrows(UnauthorizedResponse.class, () -> ShopSearch.authorize(token, uid -> player));
        player.online = true;
        assertThrows(UnauthorizedResponse.class, () -> ShopSearch.authorize(token, uid -> player));
        String permittedToken = ShopSearch.issue(player);
        player.account.clearPermission();
        assertThrows(ForbiddenResponse.class, () -> ShopSearch.authorize(permittedToken, uid -> player));
        assertThrows(ForbiddenResponse.class, () -> ShopSearch.issue(player));
        assertThrows(UnauthorizedResponse.class, () -> ShopSearch.authorize(null, uid -> player));
    }

    @Test void searchesNativeCardNamesAndIdsWithoutReplacingThemWithAvatarIds() {
        var player = new Visitor(99891);
        for (String query : List.of("桑多涅", " sAnDrOnE ", "4133", "10000133")) {
            var result = ShopSearch.search(player, query, "avatar", "1");
            assertEquals(1, result.total());
            var card = result.items().getFirst();
            assertEquals("桑多涅", card.name());
            assertEquals(4133, card.itemId());
            assertEquals(300004133, card.goodsId());
            assertEquals(10000133, card.avatarId());
        }
        assertEquals(1, ShopSearch.search(player, "雾切", "weapon", null).total());
        assertEquals(1, ShopSearch.search(player, "Mistsplitter Reforged", "weapon", null).total());
        assertEquals(0, ShopSearch.search(player, "桑多涅", "weapon", null).total());
        assertEquals(1_000_000, ShopSearch.search(player, "202", "currency", null).items().getFirst().unitCount());
    }

    @Test void pagesCoverAllMatchingGoodsAndInvalidCategoriesOrPagesAreRejected() {
        var player = new Visitor(99891);
        for (int i = 0; i < 41; i++) item("{\"id\":" + (9700000 + i)
                + ",\"itemType\":\"ITEM_MATERIAL\",\"stackLimit\":999}");
        var first = ShopSearch.search(player, "97000", "material", "1");
        var last = ShopSearch.search(player, "97000", "material", "2147483647");
        assertEquals(41, first.total());
        assertEquals(ShopSearch.PAGE_SIZE, first.items().size());
        assertEquals(2, last.page());
        assertEquals(11, last.items().size());
        var ids = new HashSet<Integer>();
        first.items().forEach(item -> assertTrue(ids.add(item.itemId())));
        last.items().forEach(item -> assertTrue(ids.add(item.itemId())));
        assertEquals(41, ids.size());
        assertThrows(IllegalArgumentException.class, () -> ShopSearch.search(player, "", "unknown", "1"));
        assertThrows(IllegalArgumentException.class, () -> ShopSearch.search(player, "", "all", "0"));
        assertThrows(IllegalArgumentException.class, () -> ShopSearch.search(player, "", "all", "1.5"));
    }

    @Test void purchaseInputCannotSelectAnotherUidOrTruncateFractionalAndOverflowQuantities() {
        var valid = ShopSearch.PurchaseRequest.parse("{\"goodsId\":300004133,\"quantity\":2}");
        assertEquals(300004133, valid.goodsId());
        assertEquals(2, valid.quantity());
        for (String invalid : List.of(
                "{\"goodsId\":300004133,\"quantity\":1,\"uid\":2}",
                "{\"goodsId\":300004133,\"quantity\":1.5}",
                "{\"goodsId\":300004133,\"quantity\":2147483648}",
                "{\"goodsId\":300004133,\"quantity\":-1}",
                "{\"goodsId\":300004133,\"quantity\":\"2\"}", "[]", "{}")) {
            assertThrows(IllegalArgumentException.class, () -> ShopSearch.PurchaseRequest.parse(invalid));
        }
    }

    private static final class Visitor extends Player {
        final Account account = new Account();
        boolean online = true;
        Visitor(int uid) { setUid(uid); account.addPermission("player.give"); }
        @Override public boolean isOnline() { return online; }
        @Override public Account getAccount() { return account; }
        @Override public String getNickname() { return "商城搜索测试"; }
        @Override public void dropMessage(Object message) {}
    }
}
