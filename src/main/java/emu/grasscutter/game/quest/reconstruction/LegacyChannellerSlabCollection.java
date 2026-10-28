package emu.grasscutter.game.quest.reconstruction;

import dev.morphia.annotations.Entity;
import emu.grasscutter.GameConstants;
import emu.grasscutter.data.GameData;
import emu.grasscutter.game.inventory.MaterialType;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.ItemUseOp;
import emu.grasscutter.game.quest.enums.QuestState;
import emu.grasscutter.server.packet.send.PacketPrivateChatNotify;
import java.util.HashSet;
import java.util.Set;

/**
 * Reconstructed collection for the removed activity 2004's quest 71652. Native addBuff stores
 * ownership, not an immediately active combat buff. The old activity UI/equipment is not recreated.
 */
@Entity(useDiscriminator = false)
public final class LegacyChannellerSlabCollection {
    private Set<Integer> ownedBuffs = new HashSet<>();

    public synchronized Set<Integer> ownedBuffs() { return Set.copyOf(ownedBuffs); }
    private synchronized boolean add(int buffId) { return ownedBuffs.add(buffId); }

    /** Only the retained native 1..37 material/use-param pairs belong to this old quest. */
    public static boolean collect(Player player, int materialId, int buffId, int count) {
        if (player == null || count != 1 || buffId < 1 || buffId > 37 || materialId != 200200 + buffId) return false;
        var material = GameData.getItemDataMap().get(materialId);
        if (material == null || !material.isUseOnGain()
                || material.getMaterialType() != MaterialType.MATERIAL_CHANNELLER_SLAB_BUFF
                || material.getItemUse() == null || material.getItemUse().stream().noneMatch(use ->
                        use.getUseOp() == ItemUseOp.ITEM_USE_ADD_CHANNELLER_SLAB_BUFF
                                && use.getUseParam() != null && use.getUseParam().length > 0
                                && Integer.toString(buffId).equals(use.getUseParam()[0]))) return false;
        var quest = player.getQuestManager().getQuestById(7165202);
        if (quest == null || quest.getState() != QuestState.QUEST_STATE_UNFINISHED) return false;
        synchronized (player) {
            var collection = player.getLegacyChannellerSlabCollection();
            if (collection == null) {
                collection = new LegacyChannellerSlabCollection();
                player.setLegacyChannellerSlabCollection(collection);
            }
            if (!collection.add(buffId)) return false;
            player.save();
        }
        player.sendPacket(new PacketPrivateChatNotify(GameConstants.SERVER_CONSOLE_UID, player.getUid(),
                "【重建剧情】已收录导能原盘碎片。"));
        return true;
    }
}
