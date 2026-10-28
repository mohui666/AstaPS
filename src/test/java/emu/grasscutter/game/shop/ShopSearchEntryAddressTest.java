package emu.grasscutter.game.shop;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.config.ConfigContainer;
import java.net.URI;
import java.net.URISyntaxException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ShopSearchEntryAddressTest {
    private static final String TOKEN = "Ab_9-" + "x".repeat(38);

    private static ConfigContainer.HTTP lan() {
        var http = new ConfigContainer.HTTP();
        http.bindAddress = "192.168.31.79";
        http.bindPort = 8088;
        http.accessAddress = "";
        return http;
    }

    @Test void publicMailUsesMappedAddressAndPortWithoutChangingLanConfiguration() throws Exception {
        var http = lan();
        var uri = URI.create(ShopSearchEntryAddress.url(TOKEN, http, false,
                "http://203.0.113.18:52286"));
        assertEquals("203.0.113.18", uri.getHost());
        assertEquals(52286, uri.getPort());
        assertEquals("/shop-search", uri.getPath());
        assertEquals("token=" + TOKEN, uri.getRawFragment());
        assertNull(uri.getRawQuery());
        assertEquals("", http.accessAddress);
        assertEquals("192.168.31.79", http.bindAddress);
        assertEquals(8088, http.bindPort);
    }

    @Test void unconfiguredOriginRetainsTheExistingBindAddressBehavior() throws Exception {
        for (String origin : new String[] {null, "", "  "}) {
            assertEquals("http://192.168.31.79:8088/shop-search#token=" + TOKEN,
                    ShopSearchEntryAddress.url(TOKEN, lan(), false, origin));
        }
    }

    @Test void existingExplicitAddressPortAndTlsRoutingRemainAvailable() throws Exception {
        var http = lan();
        http.accessAddress = "192.0.2.40";
        http.accessPort = 8443;
        assertEquals("https://192.0.2.40:8443/shop-search#token=" + TOKEN,
                ShopSearchEntryAddress.url(TOKEN, http, true, null));
    }

    @Test void publicOriginCanHaveARootSlashAndKeepsTheTokenOutOfTheRequestQuery() throws Exception {
        assertEquals("https://shop.example:8443/shop-search#token=" + TOKEN,
                ShopSearchEntryAddress.url(TOKEN, lan(), false, "https://shop.example:8443/"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "ftp://203.0.113.18:52286", "//203.0.113.18:52286",
        "http://user:secret@203.0.113.18:52286", "http://203.0.113.18:52286/private",
        "http://203.0.113.18:52286?token=other", "http://203.0.113.18:52286#other",
        "http://203.0.113.18:0", "http://203.0.113.18:65536"
    })
    void invalidOriginsCannotRedirectAnEntryToCredentialsOrAnotherPath(String origin) {
        assertThrows(URISyntaxException.class,
                () -> ShopSearchEntryAddress.url(TOKEN, lan(), false, origin));
    }
}
