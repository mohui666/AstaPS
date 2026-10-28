package emu.grasscutter.game.quest.rename;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.*;
import emu.grasscutter.net.proto.RetcodeOuterClass.Retcode;
import emu.grasscutter.server.packet.send.PacketMiscRenameDataNotify;

public final class QuestMiscRenameService {
    private QuestMiscRenameService() {}
    public static int questRename(Player player,int id,String name,boolean checkOnly) {
        var data = QuestMiscRenameCatalog.byId(id);
        if (data == null || !QuestAvatarRenameService.validName(name)) return Retcode.RET_FAIL_VALUE;
        var state = player.getQuestMiscRenames();
        synchronized (state) {
            boolean active = inState(player,data.quest(),QuestState.QUEST_STATE_UNFINISHED);
            if (checkOnly) return active || canOpenRenameDialog(player,data.item()) ? 0 : Retcode.RET_RENAME_INVALID_QUEST_STATE_VALUE;
            if (name.equals(state.getName(id))) return 0;
            if (!active) return Retcode.RET_RENAME_INVALID_QUEST_STATE_VALUE;
            if (state.hasName(id)) return Retcode.RET_RENAME_CONFLICTED_VALUE;
            apply(player,id,name); return 0;
        }
    }
    public static boolean canOpenRenameDialog(Player player,int item) {
        var data = QuestMiscRenameCatalog.byItem(item);
        return data != null && inState(player,data.itemPreQuest(),QuestState.QUEST_STATE_FINISHED)
                && player.getInventory().getItemCountById(item) > 0;
    }
    public static int itemRename(Player player,int id,String name) {
        var data = QuestMiscRenameCatalog.byId(id);
        if (data == null || !QuestAvatarRenameService.validName(name)) return Retcode.RET_FAIL_VALUE;
        synchronized (player.getInventory()) {
            synchronized (player.getQuestMiscRenames()) {
                if (name.equals(player.getQuestMiscRenames().getName(id))) return 0;
                if (!canOpenRenameDialog(player,data.item())) return Retcode.RET_RENAME_INVALID_QUEST_STATE_VALUE;
                var voucher = player.getInventory().getFirstItem(data.item());
                if (voucher == null || !player.getInventory().removeItem(voucher,1)) return Retcode.RET_ITEM_NOT_EXIST_VALUE;
                apply(player,id,name); return 0;
            }
        }
    }
    public static boolean matchesCurrent(GameQuest quest,QuestData.QuestContentCondition condition) {
        var params = condition.getParam(); if (params == null || params.length < 2 || params[1] != 0) return false;
        var data = QuestMiscRenameCatalog.byId(params[0]);
        return data != null && data.quest() == quest.getSubQuestId() && quest.getOwner().getQuestMiscRenames().hasName(data.id());
    }
    public static boolean preserveOnLogin(GameMainQuest main) {
        return main.getParentQuestId() == 75000 && !main.isFinished() && main.getActiveQuests().stream()
                .anyMatch(quest -> quest.getSubQuestId() == 7500007 && main.getOwner().getQuestMiscRenames().hasName(2));
    }
    private static boolean inState(Player player,int questId,QuestState state) {
        var quest = player.getQuestManager().getQuestById(questId); return quest != null && quest.getState() == state;
    }
    private static void apply(Player player,int id,String name) {
        player.getQuestMiscRenames().setName(id,name); player.save(); onLogin(player);
    }
    public static void onLogin(Player player) {
        player.sendPacket(new PacketMiscRenameDataNotify(player));
        player.getQuestMiscRenames().getNames().keySet().forEach(id ->
                player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_MISC_RENAME_COMPLETE,id));
    }
}
