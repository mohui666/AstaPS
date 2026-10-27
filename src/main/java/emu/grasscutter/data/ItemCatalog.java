package emu.grasscutter.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import emu.grasscutter.data.excels.ItemData;
import emu.grasscutter.game.inventory.ItemType;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;

/** The web console and chat assistant share the same native-ID name dictionary. */
public final class ItemCatalog {
    public record Entry(int id, String name, String en, String category, int rank, boolean named) {}

    private static final JsonObject NAMES = readNames();

    private ItemCatalog() {}

    private static JsonObject readNames() {
        var stream = ItemCatalog.class.getResourceAsStream("/html/item-names.json");
        if (stream == null) throw new IllegalStateException("Item dictionary is missing.");
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read item dictionary.", e);
        }
    }

    public static boolean supported(ItemType type) {
        return type == ItemType.ITEM_VIRTUAL || type == ItemType.ITEM_MATERIAL
                || type == ItemType.ITEM_WEAPON || type == ItemType.ITEM_RELIQUARY
                || type == ItemType.ITEM_FURNITURE;
    }

    public static List<Entry> catalog() {
        return GameData.getItemDataMap().values().stream()
                .filter(item -> supported(item.getItemType()))
                .map(ItemCatalog::entry)
                .sorted(Comparator.comparingInt(Entry::id)).toList();
    }

    public static List<Entry> avatars() {
        return NAMES.getAsJsonObject("avatars").entrySet().stream()
                .filter(row -> GameData.getAvatarDataMap().containsKey(Integer.parseInt(row.getKey())))
                .map(row -> new Entry(Integer.parseInt(row.getKey()), row.getValue().getAsJsonArray().get(0).getAsString(),
                        row.getValue().getAsJsonArray().get(1).getAsString(), "avatar", 0, true))
                .sorted(Comparator.comparingInt(Entry::id)).toList();
    }

    public static Entry entry(ItemData item) {
        var row = NAMES.getAsJsonObject("items").getAsJsonArray(String.valueOf(item.getId()));
        String name = row == null ? "" : row.get(0).getAsString();
        String english = row == null ? "" : row.get(1).getAsString();
        if (item.getItemType() == ItemType.ITEM_RELIQUARY) {
            var set = NAMES.getAsJsonObject("sets").getAsJsonArray(String.valueOf(item.getSetId()));
            if (set != null) {
                String slot = switch (item.getEquipType()) {
                    case EQUIP_BRACER -> "生之花";
                    case EQUIP_NECKLACE -> "死之羽";
                    case EQUIP_SHOES -> "时之沙";
                    case EQUIP_RING -> "空之杯";
                    case EQUIP_DRESS -> "理之冠";
                    default -> "圣遗物";
                };
                name = set.get(0).getAsString() + " · " + slot;
                english = set.get(1).getAsString() + " · " + item.getEquipType().name().substring(6);
            }
        }
        boolean named = !name.isBlank() || !english.isBlank();
        if (name.isBlank()) name = english.isBlank() ? "ID " + item.getId() : english;
        String category = switch (item.getItemType()) {
            case ITEM_WEAPON -> "weapon";
            case ITEM_RELIQUARY -> "artifact";
            case ITEM_FURNITURE -> "furniture";
            case ITEM_VIRTUAL -> "currency";
            default -> switch (item.getMaterialType()) {
                case MATERIAL_FOOD, MATERIAL_SPICE_FOOD -> "food";
                default -> "material";
            };
        };
        return new Entry(item.getId(), name, english, category, item.getRankLevel(), named);
    }

    public static String[] artifactSetNames(int setId) {
        var names = NAMES.getAsJsonObject("sets").getAsJsonArray(String.valueOf(setId));
        return names == null ? new String[] {"套装 " + setId, "Set " + setId}
                : new String[] {names.get(0).getAsString(), names.get(1).getAsString()};
    }
}
