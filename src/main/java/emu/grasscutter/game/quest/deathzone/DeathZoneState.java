package emu.grasscutter.game.quest.deathzone;

import dev.morphia.annotations.Entity;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Saved on the world owner, including disabled zones and explicitly reopened quest zones. */
@Entity(useDiscriminator = false)
public final class DeathZoneState {
    private Map<Integer, Boolean> openOverrides = new ConcurrentHashMap<>();

    public boolean isOpen(DeathZoneCatalog.Zone zone) {
        return openOverrides.getOrDefault(zone.id(), zone.defaultOpen());
    }

    public synchronized boolean setOpen(DeathZoneCatalog.Zone zone, boolean open) {
        if (isOpen(zone) == open) return false;
        if (open == zone.defaultOpen()) openOverrides.remove(zone.id());
        else openOverrides.put(zone.id(), open);
        return true;
    }

    public Map<Integer, Boolean> getOpenOverrides() { return Map.copyOf(openOverrides); }
}
