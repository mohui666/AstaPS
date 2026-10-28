package emu.grasscutter.game.quest.rename;

import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.enums.QuestContent;
import emu.grasscutter.game.quest.enums.QuestState;
import emu.grasscutter.net.proto.RetcodeOuterClass.Retcode;
import emu.grasscutter.server.packet.send.PacketAvatarRenameInfoNotify;

public final class QuestAvatarRenameService {
    private QuestAvatarRenameService() {}

    public static int questRename(Player player, int renameId, String name, boolean checkOnly) {
        var data = QuestAvatarRenameCatalog.byId(renameId);
        if (data == null || !validName(name)) return Retcode.RET_FAIL_VALUE;
        var state = player.getQuestAvatarRenames();
        synchronized (state) {
            boolean activeQuest = data.questId() > 0
                    && questInState(player, data.questId(), QuestState.QUEST_STATE_UNFINISHED);
            if (checkOnly) {
                // The same validity request is also used before confirming a rename-item dialog.
                return activeQuest || canOpenRenameDialog(player, data.itemId())
                        ? 0 : Retcode.RET_RENAME_INVALID_QUEST_STATE_VALUE;
            }
            if (name.equals(state.getName(renameId))) return 0;
            if (!activeQuest) return Retcode.RET_RENAME_INVALID_QUEST_STATE_VALUE;
            if (state.hasName(renameId)) return Retcode.RET_RENAME_CONFLICTED_VALUE;
            apply(player, renameId, name);
            return 0;
        }
    }

    public static int itemRename(Player player, int renameId, String name) {
        var data = QuestAvatarRenameCatalog.byId(renameId);
        if (data == null || !validName(name)) return Retcode.RET_FAIL_VALUE;
        var inventory = player.getInventory();
        var state = player.getQuestAvatarRenames();
        synchronized (inventory) {
            synchronized (state) {
                if (name.equals(state.getName(renameId))) return 0;
                if (!canOpenRenameDialog(player, data.itemId())) return Retcode.RET_RENAME_INVALID_QUEST_STATE_VALUE;
                var voucher = inventory.getFirstItem(data.itemId());
                if (voucher == null || voucher.getCount() < 1) return Retcode.RET_ITEM_NOT_EXIST_VALUE;
                if (!inventory.removeItem(voucher, 1)) return Retcode.RET_ITEM_NOT_EXIST_VALUE;
                apply(player, renameId, name);
                return 0;
            }
        }
    }

    public static boolean canOpenRenameDialog(Player player, int itemId) {
        var data = QuestAvatarRenameCatalog.byItem(itemId);
        if (data == null || data.itemPreQuestId() <= 0
                || !questInState(player, data.itemPreQuestId(), QuestState.QUEST_STATE_FINISHED)) return false;
        var item = player.getInventory().getFirstItem(itemId);
        return item != null && item.getCount() > 0;
    }

    private static boolean questInState(Player player, int questId, QuestState expected) {
        var quest = player.getQuestManager().getQuestById(questId);
        return quest != null && quest.getState().getValue() == expected.getValue();
    }

    static boolean validName(String name) {
        return name != null && !name.isBlank()
                && name.codePoints().noneMatch(code -> Character.isISOControl(code) || code == '<' || code == '>');
    }

    private static void apply(Player player, int renameId, String name) {
        player.getQuestAvatarRenames().setName(renameId, name);
        player.save();
        onLogin(player);
        player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_AVATAR_RENAME_COMPLETE, renameId);
    }

    public static void onLogin(Player player) {
        player.sendPacket(new PacketAvatarRenameInfoNotify(player));
    }
}
