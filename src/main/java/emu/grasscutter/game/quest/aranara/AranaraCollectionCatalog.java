package emu.grasscutter.game.quest.aranara;

import com.google.gson.JsonParser;
import emu.grasscutter.utils.FileUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

/** Current installed collection IDs; the mailbox and type are declared by the native Lua. */
public final class AranaraCollectionCatalog {
    public static final int CATALOG_V1 = 1;
    public static final int MAILBOX_SCENE = 3;
    public static final int MAILBOX_GROUP = 133301087;
    private final Set<Integer> pages;

    private AranaraCollectionCatalog(Set<Integer> pages) { this.pages = Set.copyOf(pages); }

    private static final class Installed {
        private static final AranaraCollectionCatalog VALUE = load();
    }

    public static AranaraCollectionCatalog installed() { return Installed.VALUE; }

    private static AranaraCollectionCatalog load() {
        try {
            var rows = JsonParser.parseString(Files.readString(FileUtils.getResourcePath(
                    "ExcelBinOutput/AranaraCollectionExcelConfigData.json"))).getAsJsonArray();
            var pages = new HashSet<Integer>();
            for (var value : rows) {
                var row = value.getAsJsonObject();
                if (!"ARANARA_COLLECTION_TYPE_CATALOG_V1".equals(row.get("PHHOGPAAAPH").getAsString()))
                    throw new IllegalStateException("Unsupported native Aranara collection type");
                int id = row.get("id").getAsInt();
                if (id <= 0 || !pages.add(id)) throw new IllegalStateException("Invalid Aranara collection ID");
            }
            if (pages.isEmpty()) throw new IllegalStateException("Missing native Aranara collection IDs");
            return new AranaraCollectionCatalog(pages);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read native Aranara collection table", error);
        }
    }

    public boolean hasType(int type) { return type == CATALOG_V1; }
    public boolean hasPage(int type, int pageId) { return hasType(type) && pages.contains(pageId); }
    public Set<Integer> pageIds() { return pages; }
    public static boolean validState(int state) { return state >= 0 && state <= 3; }
}
