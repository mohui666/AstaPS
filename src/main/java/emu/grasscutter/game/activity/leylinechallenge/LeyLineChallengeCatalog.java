package emu.grasscutter.game.activity.leylinechallenge;

import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.leylinechallenge.LeyLineChallengeData;
import emu.grasscutter.data.excels.leylinechallenge.LeyLineLevelData;
import java.time.Instant;
import java.util.Comparator;

/** Uses the native schedule rather than opening an archived season under an arbitrary new date. */
public final class LeyLineChallengeCatalog {
    public static final int ACTIVITY_ID = 5269;

    private LeyLineChallengeCatalog() {}

    public static LeyLineChallengeData currentSchedule() {
        return currentSchedule(Instant.now());
    }

    public static LeyLineChallengeData currentSchedule(Instant now) {
        return GameData.getLeyLineChallengeDataMap().values().stream()
                .filter(schedule -> schedule.contains(now))
                .max(Comparator.comparing(LeyLineChallengeData::startTime))
                .orElse(null);
    }

    public static LeyLineLevelData levelForDifficulty(int difficulty) {
        return levelForDifficulty(currentSchedule(), difficulty);
    }

    public static LeyLineLevelData levelForDifficulty(LeyLineChallengeData schedule, int difficulty) {
        if (schedule == null || difficulty < 1 || difficulty > 6) return null;
        return schedule.getLevelIdList().stream()
                .map(id -> GameData.getLeyLineLevelDataMap().get(id.intValue()))
                .filter(level -> level != null && level.getDifficulty() == difficulty)
                .findFirst()
                .orElse(null);
    }

}
