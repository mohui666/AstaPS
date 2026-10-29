package emu.grasscutter.server.http.console;

import static org.junit.jupiter.api.Assertions.*;
import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.NpcData;
import emu.grasscutter.data.excels.GadgetData;
import emu.grasscutter.utils.JsonUtils;
import emu.grasscutter.game.props.FightProperty;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(ServerResourceFixture.class)
class WebConsoleEntitiesTest {
    @Test void chineseEntityNamesReadOnlyRequestedHashesIncludingUnsignedIds() throws Exception {
        var names = WebConsoleEntities.readChineseNames(new java.io.StringReader(
                "{\"12\":\"冒险家\",\"13\":\"无关文本\",\"4294967295\":\"机关\"}"), Set.of(12, -1, 99));
        assertEquals(Map.of(12, "冒险家", -1, "机关"), names);
    }

    @Test void missingTextHashesKeepNpcAndGadgetCatalogsReadable() {
        int npc = 1900000011, gadget = 1900000012;
        var previousNpc = GameData.getNpcDataMap().put(npc, JsonUtils.decode("{\"id\":"+npc+"}", NpcData.class));
        var previousGadget = GameData.getGadgetDataMap().put(gadget, JsonUtils.decode("{\"id\":"+gadget+",\"type\":\"Gadget\"}", GadgetData.class));
        try {
            var catalog = WebConsoleEntities.catalog();
            assertTrue(catalog.stream().anyMatch(e -> e.id() == npc && e.name().contains("未命名角色模型")));
            assertTrue(catalog.stream().anyMatch(e -> e.id() == gadget && e.name().contains("未命名装置")));
        } finally {
            if (previousNpc == null) GameData.getNpcDataMap().remove(npc); else GameData.getNpcDataMap().put(npc, previousNpc);
            if (previousGadget == null) GameData.getGadgetDataMap().remove(gadget); else GameData.getGadgetDataMap().put(gadget, previousGadget);
        }
    }

    @Test void everyRealFightPropertyHasAChineseLabel() {
        var ids = new HashSet<Integer>();
        for (var prop : WebConsoleEntities.PROPERTIES) {
            assertTrue(ids.add(prop.id()));
            assertTrue(prop.name().matches(".*[\\p{IsHan}].*"));
        }
        for (var prop : FightProperty.values())
            if (prop != FightProperty.FIGHT_PROP_NONE) assertTrue(ids.contains(prop.getId()), prop.name());
    }

    @Test void percentagesAndLargeHpStayTypedAndDoNotBecomeCommandText() {
        var request = WebConsoleEntities.Request.parse("""
                {"uid":1,"kind":"monster","id":25133801,"level":95,"amount":2,
                 "properties":{"2000":5000000000,"20":50,"50":-20},"ai":0,
                 "position":{"x":1.5,"y":2,"z":3},"invincible":false}
                """);
        assertEquals(5_000_000_000f, request.properties().get(FightProperty.FIGHT_PROP_MAX_HP));
        assertEquals(.5f, request.properties().get(FightProperty.FIGHT_PROP_CRITICAL));
        assertEquals(-.2f, request.properties().get(FightProperty.FIGHT_PROP_FIRE_SUB_HURT));
        assertEquals(1.5f, request.position().getX());
        assertFalse(request.invincible());
    }

    @Test void rejectsInvalidNumbersUnknownPropertiesAndOptionsForTheWrongKind() {
        for (var extra : List.of("\"amount\":1.5", "\"amount\":2147483648", "\"distance\":101",
                "\"properties\":{\"99999\":5}", "\"properties\":{\"2000\":0}",
                "\"properties\":{\"20\":1e999}", "\"properties\":{\"20\":\"50\"}",
                "\"properties\":{\"20\":1e-999}", "\"ai\":\"stop\"", "\"ai\":-1",
                "\"position\":{\"x\":0,\"y\":0}", "\"state\":1")) {
            String body = "{\"uid\":1,\"kind\":\"monster\",\"id\":25133801," + extra + "}";
            assertThrows(RuntimeException.class, () -> WebConsoleEntities.Request.parse(body), extra);
        }
        assertThrows(IllegalArgumentException.class, () -> WebConsoleEntities.Request.parse(
                "{\"uid\":1,\"kind\":\"npc\",\"id\":1,\"properties\":{\"20\":50}}"));
        assertThrows(IllegalArgumentException.class, () -> WebConsoleEntities.Request.parse(
                "{\"uid\":1,\"kind\":\"avatar\",\"id\":1}"));
    }

    @Test void sameNumericIdKeepsItsExplicitEntityKind() {
        for (String kind : WebConsoleEntities.KINDS) {
            var request = WebConsoleEntities.Request.parse("{\"uid\":1,\"id\":1,\"kind\":\""+kind+"\"}");
            assertEquals(kind, request.kind());
            assertTrue(request.properties().isEmpty());
        }
    }
}
