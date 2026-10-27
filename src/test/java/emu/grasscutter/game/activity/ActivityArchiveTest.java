package emu.grasscutter.game.activity;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import emu.grasscutter.data.*;
import emu.grasscutter.data.excels.activity.ActivityData;
import emu.grasscutter.utils.FileUtils;
import emu.grasscutter.utils.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ActivityArchiveTest {
    @TempDir Path directory;
    Path file;
    List<ActivityConfigItem> bundled;

    @BeforeEach void load() throws Exception {
        var stream = FileUtils.readResourceAsStream("/activities/catalog.json");
        var catalog = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        stream.close();
        for (var row : catalog.getAsJsonArray("activities")) {
            var activity = JsonUtils.decode(row.getAsJsonObject().get("definition"), ActivityData.class);
            activity.onLoad();
            GameData.getActivityDataMap().put(activity.getId(), activity);
        }
        bundled = DataLoader.loadBundledList("ActivityConfig.json", ActivityConfigItem.class);
        file = directory.resolve("archives.json");
        ActivityArchive.load(bundled, file);
    }

    @Test void closeReopenAndRestartPreserveStableSchedule() throws Exception {
        var original = ActivityArchive.detail(5072).getAsJsonObject("saved").getAsJsonObject("config").get("scheduleId").getAsInt();
        ActivityArchive.update(5072, "close", 7, false, false, "keep progress", null, null);
        assertTrue(ActivityArchive.detail(5072).getAsJsonObject("saved").getAsJsonObject("config").get("disabled").getAsBoolean());
        var reopened = ActivityArchive.update(5072, "open", 7, true, false, null, null, null);
        assertFalse(reopened.config().isDisabled());
        assertEquals(original, reopened.config().getScheduleId());
        assertEquals(7 * 86_400_000L, reopened.config().getEndTime().getTime() - reopened.config().getBeginTime().getTime());
        assertEquals("keep progress", reopened.note());
        ActivityArchive.load(bundled, file);
        var restored = ActivityArchive.detail(5072).getAsJsonObject("saved");
        assertTrue(restored.getAsJsonObject("config").get("unlockConditions").getAsBoolean());
        assertEquals("keep progress", restored.get("note").getAsString());
    }

    @Test void incompleteGameplayRequiresExplicitEntrySelection() throws Exception {
        var before = Files.readString(file);
        assertThrows(IllegalArgumentException.class, () -> ActivityArchive.update(5344, "open", 7, false, false, "", null, null));
        assertEquals(before, Files.readString(file));
        assertEquals("entry", ActivityArchive.support(5344));
        assertFalse(ActivityArchive.update(5344, "open", 1, false, true, "", null, null).config().isDisabled());
    }

    @Test void invalidImportCannotPartiallyApplySchedules() throws Exception {
        var pack = ActivityArchive.exportPack();
        var rows = pack.getAsJsonArray("activities");
        for (var row : rows) {
            var saved = row.getAsJsonObject();
            if (saved.get("activityId").getAsInt() == 5072) saved.getAsJsonObject("config").addProperty("scheduleId", 123);
        }
        var before = Files.readString(file);
        assertThrows(IllegalArgumentException.class, () -> ActivityArchive.importPack(pack.toString()));
        assertEquals(before, Files.readString(file));
    }

    @Test void exportedArchiveIncludesDefinitionsButNoPlayerProgress() {
        var pack = ActivityArchive.exportPack();
        assertEquals(401, pack.getAsJsonArray("definitions").size());
        assertEquals(401, pack.getAsJsonArray("activities").size());
        assertFalse(pack.has("players"));
        assertFalse(pack.has("progress"));
    }

    @Test void reversedOrUnencodableDatesDoNotReplaceFile() throws Exception {
        var before = Files.readString(file);
        assertThrows(IllegalArgumentException.class, () -> ActivityArchive.update(5072, "schedule", 7, false, false, "", 2000L, 1000L));
        assertThrows(IllegalArgumentException.class, () -> ActivityArchive.update(5072, "schedule", 7, false, false, "", 1000L, Long.MAX_VALUE));
        assertEquals(before, Files.readString(file));
    }
}
