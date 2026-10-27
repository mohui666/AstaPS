package emu.grasscutter.server.http.console;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WebConsoleMonstersTest {
    @Test
    void readsTypedRecipientAndSpawnOptions() {
        var request = WebConsoleMonsters.Request.parse("""
                {"uid":1,"monsterId":20010101,"amount":10,"level":90,"distance":5.5}
                """);
        assertEquals(new WebConsoleMonsters.Request(1, 20010101, 10, 90, 5.5), request);
        assertEquals(new WebConsoleMonsters.Request(2, 20010101, 1, 1, 5),
                WebConsoleMonsters.Request.parse("{\"uid\":2,\"monsterId\":20010101}"));
    }

    @Test
    void rejectsFractionsOverflowAndCommandTextInIntegerFields() {
        for (String value : new String[] {"0", "-1", "1.5", "2147483648", "true", "null", "\"1\"", "\"1; spawn all\""}) {
            for (String field : new String[] {"uid", "monsterId", "amount", "level"}) {
                String body = "{\"uid\":1,\"monsterId\":20010101,\"amount\":1,\"level\":90,\"" + field + "\":" + value + "}";
                assertThrows(IllegalArgumentException.class, () -> WebConsoleMonsters.Request.parse(body), field + "=" + value);
            }
        }
    }

    @Test
    void requiresExplicitRecipientAndMonsterAndBoundsTheDistance() {
        for (String body : new String[] {
                "{\"uid\":1}", "{\"monsterId\":20010101}",
                "{\"uid\":1,\"monsterId\":20010101,\"distance\":-1}",
                "{\"uid\":1,\"monsterId\":20010101,\"distance\":101}",
                "{\"uid\":1,\"monsterId\":20010101,\"distance\":\"NaN\"}"
        }) assertThrows(IllegalArgumentException.class, () -> WebConsoleMonsters.Request.parse(body), body);
    }
}
