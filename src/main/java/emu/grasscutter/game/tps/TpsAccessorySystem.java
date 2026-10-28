package emu.grasscutter.game.tps;

import emu.grasscutter.data.GameData;
import emu.grasscutter.game.avatar.Avatar;
import emu.grasscutter.game.inventory.GameItem;
import emu.grasscutter.game.inventory.ItemType;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.server.packet.send.PacketStoreItemChangeNotify;
import java.util.*;

/**
 * Reconstructed acquisition rule: owning a configured accessory material permanently unlocks
 * that accessory on its own TPS weapon. The material remains an owned collection item. All
 * combat properties and abilities come from the installed accessory's original equip affix.
 */
public final class TpsAccessorySystem {
    private TpsAccessorySystem() {}

    /** Call only after successful inventory admission, or once after inventory login loading. */
    public static int refreshOwnedAccessories(Player player) {
        synchronized (player.getInventory()) {
            var changed = new LinkedHashSet<GameItem>();
            var accessories = GameData.getTpsWeaponAccessoryDataMap().values().stream()
                    .sorted(Comparator.comparingInt(data -> data.getId())).toList();
            for (var data : accessories) {
                if (data.getUnlockMaterialId() <= 0) continue;
                var weapon = TpsWeaponSystem.findOwnedWeapon(player, data.getTpsWeaponId());
                if (weapon == null || weapon.getTpsAccessoryIds().contains(data.getId())) continue;
                boolean ownsMaterial = player.getInventory().getItems().values().stream()
                        .anyMatch(item -> item.getItemId() == data.getUnlockMaterialId()
                                && item.getItemType() == ItemType.ITEM_MATERIAL && item.getCount() > 0);
                if (!ownsMaterial) continue;
                weapon.getTpsAccessoryIds().add(data.getId());
                changed.add(weapon);
            }
            if (changed.isEmpty()) return 0;
            changed.forEach(GameItem::save);
            if (player.hasSentLoginPackets()) player.sendPacket(new PacketStoreItemChangeNotify(changed));

            // A formal character-page identity, the dungeon trial and a quest mirror may all
            // wear the same owned gun. Update each actor once without granting another weapon.
            Set<Avatar> actors = Collections.newSetFromMap(new IdentityHashMap<>());
            player.getAvatars().forEach(actors::add);
            actors.addAll(player.getTeamManager().getTrialAvatars().values());
            actors.addAll(player.getTeamManager().getQuestPartyAvatars());
            player.getTeamManager().getActiveTeam().forEach(entity -> actors.add(entity.getAvatar()));
            for (var actor : actors) {
                if (changed.stream().noneMatch(item -> actor.getTpsWeaponIds().contains(item.getItemId()))) continue;
                actor.recalcStats();
                TpsWeaponSystem.sendEquipChange(actor);
            }
            return changed.size();
        }
    }

    /** Avoid scanning accessory resources for ordinary inventory changes. */
    public static void onItemAdded(Player player, int itemId) {
        if (GameData.getTpsWeaponDataMap().containsKey(itemId)
                || GameData.getTpsWeaponAccessoryDataMap().values().stream()
                        .anyMatch(data -> data.getUnlockMaterialId() == itemId)) {
            refreshOwnedAccessories(player);
        }
    }
}
