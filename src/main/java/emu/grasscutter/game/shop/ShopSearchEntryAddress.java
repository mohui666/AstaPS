package emu.grasscutter.game.shop;

import emu.grasscutter.config.ConfigContainer;
import java.net.URI;
import java.net.URISyntaxException;

/** Public origin for browser mail links, independent of LAN dispatch routing. */
final class ShopSearchEntryAddress {
    static final String PUBLIC_ORIGIN_PROPERTY = "astaps.shopSearchUrl";

    private ShopSearchEntryAddress() {}

    static String url(String token, ConfigContainer.HTTP http, boolean encrypted, String publicOrigin)
            throws URISyntaxException {
        String scheme = encrypted ? "https" : "http";
        String host = http.accessAddress == null || http.accessAddress.isBlank()
                ? http.bindAddress : http.accessAddress;
        int port = http.accessPort > 0 ? http.accessPort : http.bindPort;
        if (publicOrigin != null && !publicOrigin.isBlank()) {
            var origin = new URI(publicOrigin.strip());
            if (!("http".equalsIgnoreCase(origin.getScheme())
                            || "https".equalsIgnoreCase(origin.getScheme()))
                    || origin.getHost() == null || origin.getRawUserInfo() != null
                    || origin.getRawQuery() != null || origin.getRawFragment() != null
                    || !(origin.getRawPath().isEmpty() || origin.getRawPath().equals("/"))
                    || origin.getPort() == 0 || origin.getPort() > 65535) {
                throw new URISyntaxException("configured shop-search origin",
                        "Expected an HTTP(S) origin without credentials, path, query or fragment");
            }
            scheme = origin.getScheme();
            host = origin.getHost();
            port = origin.getPort();
        }
        return new URI(scheme, null, host, port, "/shop-search", null, "token=" + token)
                .toASCIIString();
    }
}
