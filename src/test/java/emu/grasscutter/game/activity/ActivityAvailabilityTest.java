package emu.grasscutter.game.activity;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.activity.ActivityData;
import emu.grasscutter.data.excels.dungeon.DungeonData;
import emu.grasscutter.game.dungeons.DungeonSystem;
import emu.grasscutter.net.proto.ActivityScheduleInfoNotifyOuterClass.ActivityScheduleInfoNotify;
import emu.grasscutter.server.packet.send.PacketActivityScheduleInfoNotify;
import emu.grasscutter.utils.JsonUtils;
import java.util.*;
import org.junit.jupiter.api.*;

class ActivityAvailabilityTest {
    private Map<Integer, ActivityData> previousActivities;
    private DungeonData previousDungeon;

    @BeforeEach void configure() {
        previousActivities = new HashMap<>(GameData.getActivityDataMap());
        previousDungeon = GameData.getDungeonDataMap().get(10954);
        GameData.getActivityDataMap().clear();
        activity(5072, "NEW_ACTIVITY_MUSIC_GAME", List.of());
        activity(5344, "NEW_ACTIVITY_SNOW_BALL_FIGHT", List.of(10954));
        activity(5269, "NEW_ACTIVITY_LEY_LINE_CHALLENGE", List.of(20000, 20001, 20002));
        activity(5244, "NEW_ACTIVITY_MUSIC_GAME_BOOK_BANNER", List.of());
        activity(6010, "NEW_ACTIVITY_PERMANENT_BANNER", List.of());
    }

    @AfterEach void restore() {
        GameData.getActivityDataMap().clear();
        GameData.getActivityDataMap().putAll(previousActivities);
        if (previousDungeon == null) GameData.getDungeonDataMap().remove(10954);
        else GameData.getDungeonDataMap().put(10954, previousDungeon);
    }

    private static void activity(int id, String type, List<Integer> dungeons) {
        var json = new com.google.gson.JsonObject();
        json.addProperty("activityId", id);
        json.addProperty("activityType", type);
        json.add("dungeonIdList", JsonUtils.toJson(dungeons));
        var data = JsonUtils.decode(json, ActivityData.class);
        data.onLoad();
        GameData.getActivityDataMap().put(id, data);
    }

    private static ActivityConfigItem config(int id) {
        var config = new ActivityConfigItem();
        config.setActivityId(id);
        config.setScheduleId(id * 1000 + 1);
        config.setBeginTime(new Date(1_000));
        config.setEndTime(new Date(4_000_000_000_000L));
        config.onLoad();
        return config;
    }

    @Test void extendedDatesDoNotPutRemovedEventsBackOnTheWire() throws Exception {
        var packet = new PacketActivityScheduleInfoNotify(
                List.of(config(5072), config(5344), config(5269), config(5244), config(6010)));
        var response = ActivityScheduleInfoNotify.parseFrom(packet.getData());
        assertEquals(List.of(5269, 5244, 6010), response.getActivityScheduleListList().stream()
                .map(item -> item.getActivityId()).toList());
    }

    @Test void removedActivityCannotBeRegisteredByArchiveApi() {
        assertThrows(IllegalArgumentException.class,
                () -> ActivityManager.applyArchiveConfig(5072, config(5072)));
    }

    @Test void removedDungeonCannotBeEnteredOrHandedOff() {
        var system = new DungeonSystem(null);
        assertFalse(system.enterDungeon(null, 0, 10954, true));
        assertFalse(system.handoffDungeon(null, 10954, List.of()));
        assertFalse(ActivityAvailability.isRemovedEventDungeon(20000));
        assertFalse(ActivityAvailability.isRemovedEventDungeon(20003));
        assertFalse(ActivityAvailability.isRemovedEventDungeon(3310));
    }

    @Test void aPermanentReferenceOrCoreStoryDungeonIsNeverBlocked() {
        activity(6010, "NEW_ACTIVITY_PERMANENT_BANNER", List.of(10954));
        assertFalse(ActivityAvailability.isRemovedEventDungeon(10954));
        activity(6010, "NEW_ACTIVITY_PERMANENT_BANNER", List.of());
        var story = JsonUtils.decode("{\"id\":10954,\"type\":\"DUNGEON_PLOT\"}", DungeonData.class);
        GameData.getDungeonDataMap().put(10954, story);
        assertFalse(ActivityAvailability.isRemovedEventDungeon(10954));
    }

    @Test void existingLimitedProgressCannotAdvanceOrPayRewards() {
        var watcher = PlayerActivityData.WatcherInfo.of()
                .watcherId(15072001).curProgress(0).totalProgress(1).build();
        var progress = PlayerActivityData.of().activityId(5072)
                .watcherInfoMap(Map.of(15072001, watcher)).build();
        progress.addWatcherProgress(15072001);
        progress.takeWatcherReward(15072001);
        assertEquals(0, watcher.getCurProgress());
        assertFalse(watcher.isTakenReward());
    }
}
