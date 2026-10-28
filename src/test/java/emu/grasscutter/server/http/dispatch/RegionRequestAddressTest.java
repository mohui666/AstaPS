package emu.grasscutter.server.http.dispatch;

import static emu.grasscutter.config.Configuration.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.Grasscutter.ServerRunMode;
import emu.grasscutter.net.proto.QueryCurrRegionHttpRspOuterClass.QueryCurrRegionHttpRsp;
import emu.grasscutter.net.proto.QueryRegionListHttpRspOuterClass.QueryRegionListHttpRsp;
import emu.grasscutter.server.http.handlers.AnnouncementsHandler;
import emu.grasscutter.utils.Crypto;
import io.javalin.Javalin;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RegionRequestAddressTest {
    private static final String LAN = "192.168.31.79";
    private static final String PUBLIC_HOST = "203.0.113.16:18088";
    private static final String VERSION = "CNRELAndroid7.1.0";
    private final List<Runnable> restore = new ArrayList<>();
    private Javalin app;

    @BeforeEach
    void startHttpFixture() throws Exception {
        replace(HTTP_INFO, "bindAddress", LAN);
        replace(HTTP_INFO, "bindPort", 8088);
        replace(HTTP_INFO, "accessAddress", "");
        replace(HTTP_INFO, "accessPort", 0);
        replace(HTTP_ENCRYPTION, "useInRouting", false);
        replace(GAME_INFO, "bindAddress", LAN);
        replace(GAME_INFO, "bindPort", 22101);
        replace(GAME_INFO, "accessAddress", "");
        replace(GAME_INFO, "accessPort", 0);
        replace(GAME_INFO, "useXorEncryption", false);
        replace(DISPATCH_INFO, "regions", List.of());
        replaceStatic(Grasscutter.class, "runModeOverride", ServerRunMode.HYBRID);
        replaceStatic(Crypto.class, "DISPATCH_SEED", new byte[] {7, 1, 0});
        replaceStatic(Crypto.class, "DISPATCH_KEY", new byte[] {1, 7});
        isolateRegionMap("regions");
        isolateRegionMap("regionTitles");
        replaceStatic(RegionHandler.class, "regionConfigEncrypted", null);
        replaceStatic(RegionHandler.class, "regionConfigEncryptedCN", null);
        startApp();
    }

    private void startApp() {
        app = Javalin.create();
        new RegionHandler().applyRoutes(app);
        new AnnouncementsHandler().applyRoutes(app);
        app.start("127.0.0.1", 0);
    }

    @AfterEach
    void stopAndRestore() {
        try {
            if (app != null) app.stop();
        } finally {
            Collections.reverse(restore);
            restore.forEach(Runnable::run);
        }
    }

    @Test
    void publicHostKeepsForwardedHttpPortAndLocalGamePort() throws Exception {
        assertRouting(PUBLIC_HOST, "203.0.113.16", 22101, "http://203.0.113.16:18088");
    }

    @Test
    void lanHostKeepsLanAddresses() throws Exception {
        assertRouting(LAN + ":8088", LAN, 22101, "http://" + LAN + ":8088");
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1:8088", "localhost:8088"})
    void localHostRetainsTheConfiguredLanFallback(String host) throws Exception {
        assertRouting(host, LAN, 22101, "http://" + LAN + ":8088");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0.0.0.0"})
    void mappedPublicUdpPortKeepsDirectClientsOnTheBoundPort(String accessAddress) throws Exception {
        GAME_INFO.accessAddress = accessAddress;
        GAME_INFO.accessPort = 32101;
        assertRouting(PUBLIC_HOST, "203.0.113.16", 32101, "http://203.0.113.16:18088");
        for (var host : List.of(LAN + ":8088", "127.0.0.1:8088", "localhost:8088")) {
            assertRouting(host, LAN, 22101, "http://" + LAN + ":8088");
        }
    }

    @Test
    void explicitPublicConfigurationTakesPriorityOverTheRequest() throws Exception {
        app.stop();
        HTTP_INFO.accessAddress = "198.51.100.8";
        HTTP_INFO.accessPort = 28088;
        GAME_INFO.accessAddress = "198.51.100.9";
        GAME_INFO.accessPort = 32101;
        // Recreate the default region with the explicit settings of a restarted deployment.
        startApp();
        assertRouting(PUBLIC_HOST, "198.51.100.9", 32101, "http://198.51.100.8:28088");
        assertRouting(LAN + ":8088", "198.51.100.9", 32101, "http://198.51.100.8:28088");
        assertRouting("localhost:8088", "198.51.100.9", 32101, "http://198.51.100.8:28088");
    }

    @Test
    void explicitRegionsKeepTheirOwnPortsInsteadOfTheGlobalMappedPort() throws Exception {
        app.stop();
        GAME_INFO.accessPort = 32101;
        DISPATCH_INFO.regions =
                List.of(
                        new Region("custom_one", "First", "198.51.100.1", 23001),
                        new Region("custom_two", "Second", "198.51.100.2", 23002));
        isolateRegionMap("regions");
        isolateRegionMap("regionTitles");
        startApp();

        var hotfix = RegionVersionConfigLoader.load(VERSION).orElseThrow().regionInfo();
        for (var configured : DISPATCH_INFO.regions) {
            var response =
                    JsonParser.parseString(
                                    get("/query_cur_region/" + configured.Name + "?version=" + VERSION,
                                            PUBLIC_HOST))
                            .getAsJsonObject();
            var region =
                    QueryCurrRegionHttpRsp.parseFrom(
                            Base64.getDecoder().decode(response.get("content").getAsString()));
            assertEquals(
                    hotfix.toBuilder()
                            .setGateserverIp("203.0.113.16")
                            .setGateserverPort(configured.Port)
                            .build(),
                    region.getRegionInfo());
        }
        assertEquals("203.0.113.16:32101", get("/query_server_address", PUBLIC_HOST));
    }

    private void assertRouting(String host, String gameAddress, int gamePort, String dispatchDomain)
            throws Exception {
        assertEquals(gameAddress + ":" + gamePort, get("/query_server_address", host));

        var list =
                QueryRegionListHttpRsp.parseFrom(
                        Base64.getDecoder().decode(get("/query_region_list", host)));
        assertEquals(1, list.getRegionListCount());
        assertEquals(
                dispatchDomain + "/query_cur_region/os_usa", list.getRegionList(0).getDispatchUrl());

        var response =
                JsonParser.parseString(get("/query_cur_region/os_usa?version=" + VERSION, host))
                        .getAsJsonObject();
        var region =
                QueryCurrRegionHttpRsp.parseFrom(
                        Base64.getDecoder().decode(response.get("content").getAsString()));
        var hotfix = RegionVersionConfigLoader.load(VERSION).orElseThrow().regionInfo();
        assertEquals(
                hotfix.toBuilder().setGateserverIp(gameAddress).setGateserverPort(gamePort).build(),
                region.getRegionInfo(),
                "Only the gate endpoint may change; every resource/data hotfix field must survive");

        var content =
                JsonParser.parseString(
                                get("/common/hk4e_global/announcement/api/getAnnContent", host))
                        .getAsJsonObject();
        assertEquals(0, content.get("retcode").getAsInt());
        assertEquals(
                dispatchDomain + "/hk4e/announcement/assets/banner/1.jpg",
                content.getAsJsonObject("data")
                        .getAsJsonArray("list")
                        .get(0)
                        .getAsJsonObject()
                        .get("banner")
                        .getAsString());

        var announcements =
                JsonParser.parseString(get("/common/hk4e_global/announcement/api/getAnnList", host))
                        .getAsJsonObject();
        assertEquals(0, announcements.get("retcode").getAsInt());
        var item =
                announcements.getAsJsonObject("data")
                        .getAsJsonArray("list")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonArray("list")
                        .get(0)
                        .getAsJsonObject();
        assertEquals(
                dispatchDomain + "/hk4e/announcement/assets/banner/1.jpg",
                item.get("banner").getAsString());
        assertEquals(
                dispatchDomain + "/hk4e/announcement/assets/tag_icon.png",
                item.get("tag_icon").getAsString());
    }

    private String get(String path, String host) throws Exception {
        try (var socket = new Socket("127.0.0.1", app.port())) {
            socket.setSoTimeout(5000);
            // HTTP/1.0 closes the response body without chunk framing; Host is still parsed by Jetty.
            var request = "GET " + path + " HTTP/1.0\r\nHost: " + host + "\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            var response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(
                    response.startsWith("HTTP/1.1 200 ") || response.startsWith("HTTP/1.0 200 "),
                    response);
            int headerEnd = response.indexOf("\r\n\r\n");
            assertTrue(headerEnd >= 0, response);
            return response.substring(headerEnd + 4);
        }
    }

    private void replace(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getField(name);
        var previous = field.get(target);
        field.set(target, value);
        restore.add(
                () -> {
                    try {
                        field.set(target, previous);
                    } catch (IllegalAccessException e) {
                        throw new AssertionError(e);
                    }
                });
    }

    private void replaceStatic(Class<?> owner, String name, Object value) throws Exception {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        var previous = field.get(null);
        field.set(null, value);
        restore.add(
                () -> {
                    try {
                        field.set(null, previous);
                    } catch (IllegalAccessException e) {
                        throw new AssertionError(e);
                    }
                });
    }

    @SuppressWarnings("unchecked")
    private void isolateRegionMap(String name) throws Exception {
        var field = RegionHandler.class.getDeclaredField(name);
        field.setAccessible(true);
        var map = (Map<String, Object>) field.get(null);
        var previous = new HashMap<>(map);
        map.clear();
        restore.add(
                () -> {
                    map.clear();
                    map.putAll(previous);
                });
    }
}
