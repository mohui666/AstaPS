package emu.grasscutter.game.quest.deathzone;

import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.world.Scene;
import java.util.Objects;

/** Server Lua changes an actual mapped zone; client requests cannot assert that it was cleared. */
public final class DeathZoneRuntime {
    private DeathZoneRuntime() {}

    @FunctionalInterface
    public interface StatusListener {
        /** Native event: param1=zone ID, param2=open (0/1), uid=world owner. */
        void changed(Scene scene, int groupId, int zoneId, int state, int ownerUid);
    }

    public static int getStatus(Scene scene, int currentGroupId, int requestedZoneId) {
        var owner = owner(scene);
        if (owner == null) return -1;
        var zone = DeathZoneCatalog.installed().resolve(scene.getId(), currentGroupId, requestedZoneId);
        if (zone == null) return -1;
        return owner.getDeathZones().isOpen(zone) ? 1 : 0;
    }

    /** Return the native success code for unchanged values, without duplicate saves or callbacks. */
    public static int change(Scene scene, int currentGroupId, int requestedZoneId,
                             boolean open, StatusListener listener) {
        var owner = owner(scene);
        if (owner == null) return -1;
        var zone = DeathZoneCatalog.installed().resolve(scene.getId(), currentGroupId, requestedZoneId);
        if (zone == null) return -1;
        Objects.requireNonNull(listener, "death zone status listener");
        synchronized (owner.getDeathZones()) {
            if (!owner.getDeathZones().setOpen(zone, open)) return 0;
            owner.save();
            for (int group : zone.groups()) {
                if (scene.getScriptManager().getGroupInstanceById(group) != null)
                    listener.changed(scene, group, zone.id(), open ? 1 : 0, owner.getUid());
            }
        }
        return 0;
    }

    private static Player owner(Scene scene) {
        if (scene == null || scene.getWorld() == null) return null;
        var owner = scene.getWorld().getHost();
        return owner != null && owner.getWorld() == scene.getWorld() && owner.getScene() == scene
                ? owner : null;
    }
}
