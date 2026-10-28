package emu.grasscutter.game.activity;

import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.activity.ActivityData;
import emu.grasscutter.game.dungeons.enums.DungeonType;
import java.util.Set;

/** The operator removed limited events; native permanent content remains available. */
public final class ActivityAvailability {
    // This resource version has no isPermanent column. Use explicit native permanent types,
    // never the operator's extended archive dates, to distinguish permanent content.
    private static final Set<String> PERMANENT_TYPES = Set.of(
            "NEW_ACTIVITY_PERMANENT_BANNER",
            "NEW_ACTIVITY_NEWBIE_PERMANENT_BANNER",
            "NEW_ACTIVITY_MUSIC_GAME_BOOK_BANNER",
            "NEW_ACTIVITY_TRIBAL_PERSONAL_LINE_BANNER",
            "NEW_ACTIVITY_LEY_LINE_CHALLENGE");
    private static final Set<DungeonType> CORE_DUNGEONS = Set.of(
            DungeonType.DUNGEON_PLOT, DungeonType.DUNGEON_FIGHT,
            DungeonType.DUNGEON_DAILY_FIGHT, DungeonType.DUNGEON_WEEKLY_FIGHT,
            DungeonType.DUNGEON_TOWER, DungeonType.DUNGEON_BOSS,
            DungeonType.DUNGEON_ELEMENT_CHALLENGE,
            DungeonType.DUNGEON_REPUTATION_SNEZ_TPS,
            DungeonType.DUNGEON_TPS_SHOOTING_RANGE);

    private ActivityAvailability() {}

    public static boolean isRetained(int activityId) {
        return isRetained(GameData.getActivityDataMap().get(activityId));
    }

    public static boolean isRetained(ActivityData activity) {
        return activity != null && activity.getActivityType() != null
                && PERMANENT_TYPES.contains(activity.getActivityType());
    }

    public static void requireRetained(int activityId) {
        if (!isRetained(activityId)) {
            throw new IllegalArgumentException("限时活动已移除，不能重新开启或参加。");
        }
    }

    /** Only an explicit limited-event reference can close a dungeon; core/shared content wins. */
    public static boolean isRemovedEventDungeon(int dungeonId) {
        var dungeon = GameData.getDungeonDataMap().get(dungeonId);
        if (dungeon != null && CORE_DUNGEONS.contains(dungeon.getType())) return false;
        boolean limitedReference = false;
        for (var activity : GameData.getActivityDataMap().values()) {
            if (!activity.getDungeonIdList().contains(dungeonId)) continue;
            if (isRetained(activity)) return false;
            limitedReference = true;
        }
        return limitedReference;
    }
}
