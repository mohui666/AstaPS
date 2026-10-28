package emu.grasscutter.game.quest.rename;

import com.google.gson.annotations.SerializedName;
import emu.grasscutter.utils.FileUtils;
import emu.grasscutter.utils.JsonUtils;
import java.io.IOException;
import java.util.List;

public final class QuestAvatarRenameCatalog {
    private static volatile List<Entry> entries;

    private QuestAvatarRenameCatalog() {}

    public static List<Entry> entries() {
        var current = entries;
        if (current != null) return current;
        synchronized (QuestAvatarRenameCatalog.class) {
            if (entries == null) {
                try {
                    entries = List.copyOf(JsonUtils.loadToList(
                            FileUtils.getExcelPath("AvatarRenameExcelConfigData.json"), Entry.class));
                } catch (IOException exception) {
                    throw new IllegalStateException("Cannot load native avatar rename resources", exception);
                }
            }
            return entries;
        }
    }

    public static Entry byId(int renameId) {
        return entries().stream().filter(entry -> entry.renameId == renameId).findFirst().orElse(null);
    }

    public static Entry byItem(int itemId) {
        return entries().stream().filter(entry -> entry.itemId == itemId).findFirst().orElse(null);
    }

    public static final class Entry {
        @SerializedName("renameID") private int renameId;
        @SerializedName("renameQuestID") private int questId;
        @SerializedName("renamePreQuestID") private int itemPreQuestId;
        @SerializedName("renameItemID") private int itemId;
        @SerializedName("relatedAvatarID") private int avatarId;

        public int renameId() { return renameId; }
        public int questId() { return questId; }
        public int itemPreQuestId() { return itemPreQuestId; }
        public int itemId() { return itemId; }
        public int avatarId() { return avatarId; }
    }
}
