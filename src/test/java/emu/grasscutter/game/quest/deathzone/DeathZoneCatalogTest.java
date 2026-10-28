package emu.grasscutter.game.quest.deathzone;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.utils.FileUtils;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

final class DeathZoneCatalogTest {
    @Test
    void zeroResolvesOnlyTheCurrentGroupAndAnExplicitZoneMustBelongToTheScene() {
        var catalog = DeathZoneCatalog.installed();
        var zone = catalog.zones().get(330111);
        assertSame(zone, catalog.resolve(3, zone.groups().getFirst(), 0));
        assertSame(catalog.zones().get(330112), catalog.resolve(3, zone.groups().getFirst(), 330112));
        assertNull(catalog.resolve(3, 133399999, 0));
        assertNull(catalog.resolve(1, zone.groups().getFirst(), 330111));
        assertNull(catalog.resolve(3, 0, 0));
    }

    @Test
    void everyRetainedHistoricalGroupExistsInTheInstalledLuaResources() {
        var zones = DeathZoneCatalog.installed().zones();
        assertEquals(45, zones.size());
        assertEquals(325, zones.values().stream().mapToInt(zone -> zone.groups().size()).sum());
        for (var zone : zones.values()) {
            for (int group : zone.groups()) {
                assertTrue(Files.isRegularFile(FileUtils.getResourcePath(
                        "Scripts/Scene/" + zone.sceneId() + "/scene" + zone.sceneId() + "_group" + group + ".lua")),
                        "Missing installed Lua for death zone group " + group);
            }
        }
        assertTrue(zones.get(330111).defaultOpen());
        assertFalse(zones.get(330109).defaultOpen());
    }
}
