package emu.grasscutter.game.quest.deathzone;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.utils.JsonUtils;
import org.junit.jupiter.api.Test;

final class DeathZoneStateTest {
    @Test
    void closedAndExplicitlyReopenedQuestZonesSurviveSavingWithoutChangingAnotherPlayer() {
        var catalog = DeathZoneCatalog.installed();
        var openZone = catalog.zones().get(330111);
        var closedZone = catalog.zones().get(330109);
        var state = new DeathZoneState();
        assertTrue(state.isOpen(openZone));
        assertFalse(state.isOpen(closedZone));
        assertTrue(state.setOpen(openZone, false));
        assertTrue(state.setOpen(closedZone, true));
        var restored = JsonUtils.decode(JsonUtils.encode(state), DeathZoneState.class);
        assertFalse(restored.isOpen(openZone));
        assertTrue(restored.isOpen(closedZone));
        assertFalse(restored.setOpen(openZone, false));
        assertTrue(new DeathZoneState().isOpen(openZone));
        assertTrue(restored.setOpen(openZone, true));
        assertFalse(restored.getOpenOverrides().containsKey(openZone.id()));
    }
}
