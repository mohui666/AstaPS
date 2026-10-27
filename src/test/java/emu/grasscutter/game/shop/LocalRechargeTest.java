package emu.grasscutter.game.shop;

import static emu.grasscutter.config.Configuration.GAME_OPTIONS;
import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.utils.FileUtils;
import java.nio.file.Files;
import org.junit.jupiter.api.*;

class LocalRechargeTest {
    private boolean previous;

    @BeforeEach void enableLocalCheckout() {
        Assumptions.assumeTrue(Files.exists(FileUtils.getResourcePath("ExcelBinOutput/ProductMcoinDetailConfigData.json")));
        previous = GAME_OPTIONS.localRecharge;
        GAME_OPTIONS.localRecharge = true;
    }

    @AfterEach void restoreSetting() { GAME_OPTIONS.localRecharge = previous; }

    static final class Buyer extends Player {
        private int crystals;
        @Override public boolean isOnline() { return true; }
        @Override public int getUid() { return 1; }
        @Override public int getCrystals() { return crystals; }
        @Override public boolean setCrystals(int value) { crystals = value; return true; }
        @Override public void save() {}
    }

    @Test void duplicateOrdersCannotGrantAgainOrBeChangedToAnotherProduct() {
        var player = new Buyer();
        var first = LocalRecharge.purchase(player,"1","test-order-first");
        assertEquals(120,first.get("crystalsAdded").getAsInt());
        LocalRecharge.purchase(player,"1","test-order-second");
        assertEquals(180,player.getCrystals());
        var duplicate = LocalRecharge.purchase(player,"1","test-order-first");
        assertTrue(duplicate.get("duplicate").getAsBoolean());
        assertEquals(180,duplicate.get("crystals").getAsInt());
        assertEquals(2,player.getLocalRechargeCounts().get("1"));
        assertThrows(IllegalArgumentException.class,() -> LocalRecharge.purchase(player,"2","test-order-first"));
        assertEquals(180,player.getCrystals());
    }

    @Test void overflowAndUnknownProductsDoNotCreateReceiptsOrChangeTheBalance() {
        var player = new Buyer();
        player.setCrystals(Integer.MAX_VALUE);
        assertThrows(ArithmeticException.class,() -> LocalRecharge.purchase(player,"1","test-order-overflow"));
        assertThrows(IllegalArgumentException.class,() -> LocalRecharge.purchase(player,"not-a-product","test-order-invalid"));
        assertEquals(Integer.MAX_VALUE,player.getCrystals());
        assertTrue(player.getLocalRechargeCounts().isEmpty());
        assertTrue(player.getLocalRechargeReceipts().isEmpty());
    }

    @Test void nativeRequestsRequireOneExactSkuInsteadOfAnAmbiguousTier() throws Exception {
        assertEquals(1,LocalRecharge.product("ys_chn_primogem60_tier1").configId());
        assertEquals(101,LocalRecharge.product("cloudys_chn_blessofmoon_tier5").configId());
        var sku = UnknownFieldSet.Field.newBuilder().addLengthDelimited(ByteString.copyFromUtf8("1")).build();
        var request = UnknownFieldSet.newBuilder().addField(3,sku);
        assertEquals("1",LocalRecharge.productFromPayload(request.build().toByteArray()));
        request.addField(8,UnknownFieldSet.Field.newBuilder().addLengthDelimited(ByteString.copyFromUtf8("2")).build());
        assertThrows(IllegalArgumentException.class,() -> LocalRecharge.productFromPayload(request.build().toByteArray()));
        assertThrows(IllegalArgumentException.class,() -> LocalRecharge.productFromPayload(new byte[0]));
    }

    @Test void capturedSevenOneMonthlyCardRequestReadsTheNestedSku() throws Exception {
        var payload = java.util.HexFormat.of().parseHex(
            "622b0a1879735f63686e5f626c6573736f666d6f6f6e5f74696572351206546965725f3518ac02205a281e3801");
        assertEquals("ys_chn_blessofmoon_tier5",LocalRecharge.productFromPayload(payload));
        var ambiguous = UnknownFieldSet.parseFrom(payload).toBuilder().addField(3,
                UnknownFieldSet.Field.newBuilder().addLengthDelimited(ByteString.copyFromUtf8("1")).build()).build();
        assertThrows(IllegalArgumentException.class,() -> LocalRecharge.productFromPayload(ambiguous.toByteArray()));
        assertEquals(204,LocalRecharge.product("204").configId());
        assertEquals(205,LocalRecharge.product("205").configId());
    }
}
