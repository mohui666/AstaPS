package emu.grasscutter.game.quest.rename;

import com.google.gson.annotations.SerializedName;
import emu.grasscutter.utils.*;
import java.io.IOException;
import java.util.List;

public final class QuestMiscRenameCatalog {
    public static final class Entry {
        @SerializedName("renameID") private int id;
        @SerializedName("renameQuestID") private int quest;
        @SerializedName("renamePreQuestID") private int itemPreQuest;
        @SerializedName("renameItemID") private int item;
        private String tmpValueKey;
        public int id() { return id; }
        public int quest() { return quest; }
        public int itemPreQuest() { return itemPreQuest; }
        public int item() { return item; }
        public String tmpValueKey() { return tmpValueKey; }
    }
    private static final class Installed {
        static final List<Entry> ENTRIES = load();
        private static List<Entry> load() {
            try { return List.copyOf(JsonUtils.loadToList(FileUtils.getExcelPath("MiscRenameExcelConfigData.json"),Entry.class)); }
            catch (IOException error) { throw new IllegalStateException("Cannot read original companion rename data",error); }
        }
    }
    private QuestMiscRenameCatalog() {}
    public static Entry byId(int id) { return Installed.ENTRIES.stream().filter(entry -> entry.id == id).findFirst().orElse(null); }
    public static Entry byItem(int item) { return Installed.ENTRIES.stream().filter(entry -> entry.item == item).findFirst().orElse(null); }
}
