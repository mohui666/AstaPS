package emu.grasscutter.game.tower;

import static emu.grasscutter.config.Configuration.GAME_OPTIONS;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.ResourceLoader;
import emu.grasscutter.data.excels.tower.TowerFloorData;
import emu.grasscutter.data.excels.tower.TowerLevelData;
import emu.grasscutter.data.excels.tower.TowerScheduleData;
import emu.grasscutter.utils.FileUtils;
import emu.grasscutter.utils.JsonUtils;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

@ExtendWith(ServerResourceFixture.class)
@ResourceLock("GameData")
final class TowerMonsterPreviewTest {
    @Test
    void currentCnResourcesResolveAllTwentyFourMoonHalvesAndKeepMissingDataExplicit() throws Exception {
        var schedulesPath = FileUtils.getExcelPath("TowerScheduleExcelConfigData.json");
        assumeTrue(Files.isRegularFile(schedulesPath), "Requires the local official 7.1 resource pack");
        var originalSchedules = new HashMap<>(GameData.getTowerScheduleDataMap());
        var originalFloors = new HashMap<>(GameData.getTowerFloorDataMap());
        var originalLevels = new HashMap<>(GameData.getTowerLevelDataMap());
        var originalPreviews = new HashMap<>(GameData.getMonsterPreviewDataMap());
        int originalSchedule = GAME_OPTIONS.tower.scheduleId;
        try {
            GameData.getTowerScheduleDataMap().clear();
            for (var schedule : JsonUtils.loadToList(schedulesPath, TowerScheduleData.class)) {
                schedule.onLoad();
                GameData.getTowerScheduleDataMap().put(schedule.getId(), schedule);
            }
            GameData.getTowerFloorDataMap().clear();
            for (var floor : JsonUtils.loadToList(FileUtils.getExcelPath("TowerFloorExcelConfigData.json"), TowerFloorData.class)) {
                GameData.getTowerFloorDataMap().put(floor.getId(), floor);
            }
            GameData.getTowerLevelDataMap().clear();
            for (var level : JsonUtils.loadToList(FileUtils.getExcelPath("TowerLevelExcelConfigData.json"), TowerLevelData.class)) {
                GameData.getTowerLevelDataMap().put(level.getId(), level);
            }
            ResourceLoader.loadMonsterPreviews();
            GAME_OPTIONS.tower.scheduleId = 124;
            var tower = new TowerSystem(null);
            assertEquals(List.of(1134, 1135, 1140, 1141), tower.getScheduleFloors());
            int halves = 0;
            for (int floor : tower.getScheduleFloors()) {
                for (int chamber = 1; chamber <= 3; chamber++) {
                    for (boolean upper : List.of(true, false)) {
                        var preview = tower.getMonsterPreview(floor, chamber, upper).orElseThrow();
                        assertFalse(preview.getMonsterPreviewInfo().isEmpty());
                        assertTrue(preview.getMonsterPreviewInfo().stream()
                                .allMatch(enemy -> enemy.getMonsterPreviewDescribeId() > 0
                                        && enemy.getReviseLevel() == preview.getMonsterPreviewLevel()));
                        halves++;
                    }
                }
            }
            assertEquals(24, halves);
            assertEquals(63105, tower.getMonsterPreview(1141, 3, true).orElseThrow()
                    .getMonsterPreviewInfo().getFirst().getMonsterPreviewDescribeId());
            assertEquals(40905, tower.getMonsterPreview(1141, 3, false).orElseThrow()
                    .getMonsterPreviewInfo().getFirst().getMonsterPreviewDescribeId());
            assertTrue(tower.getMonsterPreview(1001, 1, true).isEmpty());
            assertTrue(tower.getMonsterPreview(1008, 3, false).isEmpty());
            GameData.getMonsterPreviewDataMap().remove("PT18");
            assertTrue(tower.getMonsterPreview(1140, 1, true).isEmpty());
        } finally {
            GameData.getTowerScheduleDataMap().clear();
            GameData.getTowerScheduleDataMap().putAll(originalSchedules);
            GameData.getTowerFloorDataMap().clear();
            GameData.getTowerFloorDataMap().putAll(originalFloors);
            GameData.getTowerLevelDataMap().clear();
            GameData.getTowerLevelDataMap().putAll(originalLevels);
            GameData.getMonsterPreviewDataMap().clear();
            GameData.getMonsterPreviewDataMap().putAll(originalPreviews);
            GAME_OPTIONS.tower.scheduleId = originalSchedule;
        }
    }
}
