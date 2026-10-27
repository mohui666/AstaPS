package emu.grasscutter.server.http.console;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class WebConsolePlayerToolsTest {
    @Test void percentagesUseHumanInputWithoutChangingFlatStats() {
        var json = JsonParser.parseString("{\"value\":100}").getAsJsonObject();
        assertEquals(1d, WebConsolePlayerTools.validatedStatValue(new WebConsolePlayerTools.Stat(20,"","",true,0,1000),json));
        assertEquals(100d, WebConsolePlayerTools.validatedStatValue(new WebConsolePlayerTools.Stat(2001,"","",false,0,1000),json));
    }

    @Test void invalidNumbersAndFractionalIntegersAreRejected() {
        for (String body : new String[] {"{\"value\":1e999}","{\"value\":-1}","{\"value\":\"100\"}","{\"value\":null}"}) {
            assertThrows(IllegalArgumentException.class, () -> WebConsolePlayerTools.number(JsonParser.parseString(body).getAsJsonObject(),"value",0,1000));
        }
        assertThrows(IllegalArgumentException.class, () -> WebConsolePlayerTools.integer(JsonParser.parseString("{\"value\":1.5}").getAsJsonObject(),"value",0,9));
    }

    @Test void booleansCannotBeCommandStringsOrImplicitNumbers() {
        for (String value : new String[] {"\"on\"", "1", "null"})
            assertThrows(IllegalArgumentException.class, () -> WebConsolePlayerTools.bool(JsonParser.parseString("{\"enabled\":"+value+"}").getAsJsonObject(),"enabled"));
        assertTrue(WebConsolePlayerTools.bool(JsonParser.parseString("{\"enabled\":true}").getAsJsonObject(),"enabled"));
    }
}
