package emu.grasscutter.game.activity.leylinechallenge;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.leylinechallenge.*;
import emu.grasscutter.game.dungeons.enums.DungeonSubType;
import emu.grasscutter.game.dungeons.enums.DungeonType;
import emu.grasscutter.data.excels.dungeon.DungeonData;
import emu.grasscutter.utils.JsonUtils;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class LeyLineChallengeCatalogTest {
    private Map<Integer, LeyLineChallengeData> previousSchedules;
    private Map<Integer, LeyLineLevelData> previousLevels;

    @BeforeEach
    void isolateTables() {
        previousSchedules = new HashMap<>(GameData.getLeyLineChallengeDataMap());
        previousLevels = new HashMap<>(GameData.getLeyLineLevelDataMap());
        GameData.getLeyLineChallengeDataMap().clear();
        GameData.getLeyLineLevelDataMap().clear();
    }

    @AfterEach
    void restoreTables() {
        GameData.getLeyLineChallengeDataMap().clear();
        GameData.getLeyLineLevelDataMap().clear();
        GameData.getLeyLineChallengeDataMap().putAll(previousSchedules);
        GameData.getLeyLineLevelDataMap().putAll(previousLevels);
    }

    @Test
    void nativeSeasonEndsAtTheNextSecondAndDoesNotReopenOldSeasons() {
        var schedule = JsonUtils.decode("""
                {"scheduleId":5269012,"scheduleStartTime":"2026-09-30 10:00:00",
                 "scheduleEndTime":"2026-11-11 09:59:59","levelIdList":[101201,101206]}
                """, LeyLineChallengeData.class);
        GameData.getLeyLineChallengeDataMap().put(schedule.getId(), schedule);
        assertNull(LeyLineChallengeCatalog.currentSchedule(Instant.parse("2026-09-30T01:59:59Z")));
        assertSame(schedule, LeyLineChallengeCatalog.currentSchedule(Instant.parse("2026-09-30T02:00:00Z")));
        assertSame(schedule, LeyLineChallengeCatalog.currentSchedule(Instant.parse("2026-11-11T01:59:59Z")));
        assertNull(LeyLineChallengeCatalog.currentSchedule(Instant.parse("2026-11-11T02:00:00Z")));

        var level = JsonUtils.decode("{\"id\":101206,\"MMKEJFCFMBN\":6,\"reviseLevel\":109}", LeyLineLevelData.class);
        GameData.getLeyLineLevelDataMap().put(level.getId(), level);
        assertSame(level, LeyLineChallengeCatalog.levelForDifficulty(schedule, 6));
        assertNull(LeyLineChallengeCatalog.levelForDifficulty(schedule, 7));
        assertNull(LeyLineChallengeCatalog.levelForDifficulty(schedule, 1));
    }

    @Test
    void nativeDungeonTypesRemainRecognizable() {
        var dungeon = JsonUtils.decode("""
                {"id":20000,"type":"DUNGEON_LEY_LINE_CHALLENGE",
                 "subType":"DUNGEON_SUB_LEY_LINE_CHALLENGE"}
                """, DungeonData.class);
        assertEquals(DungeonType.DUNGEON_LEY_LINE_CHALLENGE, dungeon.getType());
        assertEquals(DungeonSubType.DUNGEON_SUB_LEY_LINE_CHALLENGE, dungeon.getSubType());
    }
}
