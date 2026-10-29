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
        var original = 5244001;
        ActivityArchive.update(5244, "close", 7, false, false, "keep progress", null, null);
        assertTrue(ActivityArchive.detail(5244).getAsJsonObject("saved").getAsJsonObject("config").get("disabled").getAsBoolean());
        var reopened = ActivityArchive.update(5244, "open", 7, true, true, null, null, null);
        assertFalse(reopened.config().isDisabled());
        assertEquals(original, reopened.config().getScheduleId());
        assertEquals(7 * 86_400_000L, reopened.config().getEndTime().getTime() - reopened.config().getBeginTime().getTime());
        assertEquals("keep progress", reopened.note());
        ActivityArchive.load(bundled, file);
        var restored = ActivityArchive.detail(5244).getAsJsonObject("saved");
        assertTrue(restored.getAsJsonObject("config").get("unlockConditions").getAsBoolean());
        assertEquals("keep progress", restored.get("note").getAsString());
    }

    @Test void stygianSavedAsNoneIsCorrectedWithoutLosingTheOperatorSchedule() throws Exception {
        var opened = ActivityArchive.update(5269, "open", 30, true, true, "native entry", null, null);
        assertEquals(5701, opened.config().getActivityType());
        long end = opened.config().getEndTime().getTime();
        var pack = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        for (var row : pack.getAsJsonArray("activities")) {
            var saved = row.getAsJsonObject();
            if (saved.get("activityId").getAsInt() == 5269)
                saved.getAsJsonObject("config").addProperty("activityType", 0);
        }
        Files.writeString(file, pack.toString());
        ActivityArchive.load(bundled, file);
        var restored = ActivityArchive.detail(5269).getAsJsonObject("saved").getAsJsonObject("config");
        assertEquals(5701, restored.get("activityType").getAsInt());
        assertFalse(restored.get("disabled").getAsBoolean());
        assertEquals(end, java.time.OffsetDateTime.parse(restored.get("endTime").getAsString()).toInstant().toEpochMilli());
    }

    @Test void limitedEventsCannotBeReopenedEvenWithEntryOnlySelected() throws Exception {
        var before = Files.readString(file);
        assertThrows(IllegalArgumentException.class, () -> ActivityArchive.update(5344, "open", 7, false, false, "", null, null));
        assertThrows(IllegalArgumentException.class, () -> ActivityArchive.update(5344, "open", 1, false, true, "", null, null));
        assertThrows(IllegalArgumentException.class, () -> ActivityArchive.update(5072, "open", 1, false, true, "", null, null));
        assertEquals(before, Files.readString(file));
    }

    @Test void invalidImportCannotPartiallyApplySchedules() throws Exception {
        ActivityArchive.update(5244, "close", 7, false, false, "", null, null);
        var pack = ActivityArchive.exportPack();
        var rows = pack.getAsJsonArray("activities");
        for (var row : rows) {
            var saved = row.getAsJsonObject();
            if (saved.get("activityId").getAsInt() == 5244) saved.getAsJsonObject("config").addProperty("scheduleId", 123);
        }
        var before = Files.readString(file);
        assertThrows(IllegalArgumentException.class, () -> ActivityArchive.importPack(pack.toString()));
        assertEquals(before, Files.readString(file));
    }

    @Test void exportedArchiveIncludesDefinitionsButNoPlayerProgress() {
        var pack = ActivityArchive.exportPack();
        var ids = ActivityArchive.catalog().stream().map(row -> (int) row.get("id")).toList();
        assertTrue(ids.containsAll(List.of(5269, 5244, 6010)));
        assertFalse(ids.contains(5072));
        assertFalse(ids.contains(5344));
        assertEquals(ids.size(), pack.getAsJsonArray("definitions").size());
        assertEquals(ids.size(), pack.getAsJsonArray("activities").size());
        assertFalse(pack.has("players"));
        assertFalse(pack.has("progress"));
    }

    @Test void reversedOrUnencodableDatesDoNotReplaceFile() throws Exception {
        var before = Files.readString(file);
        assertThrows(IllegalArgumentException.class, () -> ActivityArchive.update(5244, "schedule", 7, false, true, "", 2000L, 1000L));
        assertThrows(IllegalArgumentException.class, () -> ActivityArchive.update(5244, "schedule", 7, false, true, "", 1000L, Long.MAX_VALUE));
        assertEquals(before, Files.readString(file));
    }

    @Test void storedLimitedEventsAreRemovedAndCannotBeImportedBack() throws Exception {
        var legacy = """
                {"formatVersion":1,"resourceVersion":"7.1.0","activities":[
                  {"activityId":5072,"config":{
                    "activityId":5072,"activityType":2202,"scheduleId":5072001,
                    "meetCondList":[],"beginTime":"2022-05-01T00:00:00+08:00",
                    "endTime":"2030-05-01T00:00:00+08:00"},"note":"old event"}
                ]}
                """;
        Files.writeString(file, legacy);
        var configs = ActivityArchive.load(bundled, file);
        assertTrue(configs.stream().noneMatch(config -> config.getActivityId() == 5072));
        assertFalse(ActivityArchive.catalog().stream().anyMatch(row -> (int) row.get("id") == 5072));
        assertFalse(Files.readString(file).contains("old event"));
        var after = Files.readString(file);
        assertThrows(IllegalArgumentException.class, () -> ActivityArchive.importPack(legacy));
        assertEquals(after, Files.readString(file));
    }
}
