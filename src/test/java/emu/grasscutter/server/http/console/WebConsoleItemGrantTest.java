package emu.grasscutter.server.http.console;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WebConsoleItemGrantTest {
    @Test
    void readsNativeIdsAndEquipmentOptionsWithoutCommandText() {
        var request = WebConsoleItems.Grant.parse("""
                {"uid":10001,"itemId":11509,"amount":2,"level":90,"refinement":5}
                """);
        assertEquals(new WebConsoleItems.Grant(10001, 11509, 2, 90, 5), request);
        var material = WebConsoleItems.Grant.parse("{\"uid\":10001,\"itemId\":201}");
        assertEquals(1, material.amount());
        assertNull(material.level());
    }

    @Test
    void rejectsFractionsOverflowAndCommandInjectionRatherThanTruncating() {
        for (var amount : new String[] {"0", "-1", "1.5", "2147483648", "true", "\"1\"", "null"}) {
            String json = "{\"uid\":10001,\"itemId\":201,\"amount\":" + amount + "}";
            assertThrows(IllegalArgumentException.class, () -> WebConsoleItems.Grant.parse(json), amount);
        }
        assertThrows(IllegalArgumentException.class,
                () -> WebConsoleItems.Grant.parse("{\"uid\":\"10001; give all\",\"itemId\":201}"));
    }

    @Test
    void requiresAnExplicitPositiveRecipientAndItem() {
        for (var json : new String[] {
                "{\"itemId\":201}", "{\"uid\":10001}",
                "{\"uid\":0,\"itemId\":201}", "{\"uid\":10001,\"itemId\":-1}",
                "{\"uid\":10001,\"itemId\":201,\"refinement\":6}"}) {
            assertThrows(IllegalArgumentException.class, () -> WebConsoleItems.Grant.parse(json));
        }
    }
}
