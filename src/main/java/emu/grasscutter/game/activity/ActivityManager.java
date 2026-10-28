package emu.grasscutter.game.activity;

import com.esotericsoftware.reflectasm.ConstructorAccess;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.*;
import emu.grasscutter.game.activity.condition.*;
import emu.grasscutter.game.player.*;
import emu.grasscutter.game.props.*;
import emu.grasscutter.net.proto.ActivityInfoOuterClass;
import emu.grasscutter.server.packet.send.PacketActivityInfoNotify;
import emu.grasscutter.server.packet.send.PacketActivityScheduleInfoNotify;
import java.util.*;
import java.util.concurrent.*;
import lombok.Getter;

@SuppressWarnings("unchecked")
@Getter
public class ActivityManager extends BasePlayerManager {
    private static final Map<Integer, ActivityConfigItem> activityConfigItemMap;
    @Getter private static final Map<Integer, ActivityConfigItem> scheduleActivityConfigMap;
    private final Map<Integer, PlayerActivityData> playerActivityDataMap;
    private ActivityConditionExecutor conditionExecutor;
    private final Map<Integer, Boolean> lastOpenStates = new HashMap<>();
    private static final Map<ActivityType, ConstructorAccess<?>> handlerTypes = new HashMap<>();
    private static final Map<WatcherTriggerType, ConstructorAccess<?>> watcherTypes = new HashMap<>();

    static {
        activityConfigItemMap = new ConcurrentHashMap<>();
        scheduleActivityConfigMap = new ConcurrentHashMap<>();
    }

    public static void loadActivityConfigData() {
        activityConfigItemMap.values().removeIf(item -> !ActivityAvailability.isRetained(item.getActivityId()));
        scheduleActivityConfigMap.values().removeIf(item -> !ActivityAvailability.isRetained(item.getActivityId()));
        // scan activity type handler and watcher type
        var activityHandlerTypeMap = handlerTypes;
        var activityWatcherTypeMap = watcherTypes;
        Grasscutter.reflector
                .getSubTypesOf(ActivityHandler.class)
                .forEach(
                        item -> {
                            var typeName = item.getAnnotation(GameActivity.class);
                            activityHandlerTypeMap.put(typeName.value(), ConstructorAccess.get(item));
                        });
        Grasscutter.reflector
                .getSubTypesOf(ActivityWatcher.class)
                .forEach(
                        item -> {
                            var typeName = item.getAnnotation(ActivityWatcherType.class);
                            activityWatcherTypeMap.put(typeName.value(), ConstructorAccess.get(item));
                        });

        try {
            ActivityArchive.load(DataLoader.loadBundledList("ActivityConfig.json", ActivityConfigItem.class))
                    .stream().filter(item -> ActivityAvailability.isRetained(item.getActivityId()))
                    .forEach(
                            item -> {
                                item.onLoad();
                                var activityData = GameData.getActivityDataMap().get(item.getActivityId());
                                if (activityData == null) {
                                    Grasscutter.getLogger().warn("activity {} not exist.", item.getActivityId());
                                    return;
                                }
                                var activityHandlerType =
                                        activityHandlerTypeMap.get(
                                                ActivityType.getTypeByName(activityData.getActivityType()));
                                ActivityHandler activityHandler;

                                if (activityHandlerType != null) {
                                    activityHandler = (ActivityHandler) activityHandlerType.newInstance();
                                } else {
                                    activityHandler = new DefaultActivityHandler();
                                }
                                activityHandler.setActivityConfigItem(item);
                                activityHandler.initWatchers(activityWatcherTypeMap);
                                item.setActivityHandler(activityHandler);

                                activityConfigItemMap.putIfAbsent(item.getActivityId(), item);
                                scheduleActivityConfigMap.putIfAbsent(item.getScheduleId(), item);
                            });

            Grasscutter.getLogger().debug("Enable {} activities.", activityConfigItemMap.size());
        } catch (Exception e) {
            Grasscutter.getLogger().warn("Unable to load activities config.", e);
        }
    }

    /** Runs on the game logic thread, including when an archive is imported. */
    public static void applyArchiveConfig(int id, ActivityConfigItem config) {
        ActivityAvailability.requireRetained(id);
        var previous = activityConfigItemMap.get(id);
        if (config == null) {
            if (previous == null) return;
            previous.setDisabled(true);
        } else {
            config.onLoad();
            var type = ActivityType.getTypeByName(GameData.getActivityDataMap().get(id).getActivityType());
            var constructor = handlerTypes.get(type);
            var handler = constructor == null ? new DefaultActivityHandler() : (ActivityHandler) constructor.newInstance();
            handler.setActivityConfigItem(config);
            handler.initWatchers(watcherTypes);
            config.setActivityHandler(handler);
            activityConfigItemMap.put(id, config);
            scheduleActivityConfigMap.put(config.getScheduleId(), config);
        }
        var server = Grasscutter.getGameServer();
        if (server != null) server.getPlayers().values().forEach(p -> p.getActivityManager().refreshArchiveActivity(id));
        if (config == null) {
            activityConfigItemMap.remove(id);
            scheduleActivityConfigMap.remove(previous.getScheduleId());
        }
    }

    private void refreshArchiveActivity(int id) {
        var config = activityConfigItemMap.get(id);
        var data = playerActivityDataMap.get(id);
        if (data == null) {
            data = PlayerActivityData.getByPlayer(player, id);
            if (data == null) {
                data = config.getActivityHandler().initPlayerActivityData(player);
                data.save();
            }
            playerActivityDataMap.put(id, data);
        }
        data.setPlayer(player);
        data.setActivityHandler(config.getActivityHandler());
        rebuildConditions();
        player.sendPacket(new PacketActivityScheduleInfoNotify(activityConfigItemMap.values()));
        player.sendPacket(new PacketActivityInfoNotify(getInfoProtoByActivityId(id)));
        lastOpenStates.put(id, isActivityActive(id) && isActivityOpen(id));
    }

    public PlayerActivityData resetArchiveProgress(int id) {
        ActivityAvailability.requireRetained(id);
        var config = activityConfigItemMap.get(id);
        if (config == null) throw new IllegalArgumentException("请先配置此活动。");
        var previous = playerActivityDataMap.get(id);
        var data = config.getActivityHandler().initPlayerActivityData(player);
        if (previous != null) data.setId(previous.getId());
        data.setPlayer(player);
        data.setActivityHandler(config.getActivityHandler());
        data.save();
        playerActivityDataMap.put(id, data);
        refreshArchiveActivity(id);
        return data;
    }

    private void rebuildConditions() {
        conditionExecutor = new BasicActivityConditionExecutor(activityConfigItemMap,
                GameData.getActivityCondExcelConfigDataMap(),
                PlayerActivityDataMappingBuilder.buildPlayerActivityDataByActivityCondId(playerActivityDataMap),
                AllActivityConditionBuilder.buildActivityConditions());
    }

    public void onTick() {
        boolean scheduleChanged = false;
        for (int id : activityConfigItemMap.keySet()) {
            boolean open = isActivityActive(id) && isActivityOpen(id);
            Boolean previous = lastOpenStates.put(id, open);
            if (previous != null && previous != open) {
                scheduleChanged = true;
                player.sendPacket(new PacketActivityInfoNotify(getInfoProtoByActivityId(id)));
            }
        }
        if (scheduleChanged) player.sendPacket(new PacketActivityScheduleInfoNotify(activityConfigItemMap.values()));
    }

    public ActivityManager(Player player) {
        super(player);

        playerActivityDataMap = new ConcurrentHashMap<>();
        // load data for player
        activityConfigItemMap
                .values()
                .forEach(
                        item -> {
                            var data = PlayerActivityData.getByPlayer(player, item.getActivityId());
                            if (data == null) {
                                data = item.getActivityHandler().initPlayerActivityData(player);
                                data.save();
                            }
                            data.setPlayer(player);
                            data.setActivityHandler(item.getActivityHandler());
                            playerActivityDataMap.put(item.getActivityId(), data);
                        });

        conditionExecutor =
                new BasicActivityConditionExecutor(
                        activityConfigItemMap,
                        GameData.getActivityCondExcelConfigDataMap(),
                        PlayerActivityDataMappingBuilder.buildPlayerActivityDataByActivityCondId(
                                playerActivityDataMap),
                        AllActivityConditionBuilder.buildActivityConditions());

        player.sendPacket(new PacketActivityScheduleInfoNotify(activityConfigItemMap.values()));

        // The schedule alone does not put an activity on screen: the client also needs its
        // ActivityInfo, and it does not reliably ask for it before deciding whether to show the
        // activity entry. Push it for every activity running now.
        // One broken activity must not take the login down with it.
        activityConfigItemMap.keySet().stream()
                .filter(this::isActivityActive)
                .forEach(
                        activityId -> {
                            try {
                                player.sendPacket(
                                        new PacketActivityInfoNotify(getInfoProtoByActivityId(activityId)));
                            } catch (RuntimeException e) {
                                Grasscutter.getLogger()
                                        .warn("Unable to send activity {} at login.", activityId, e);
                            }
                        });
    }

    /** trigger activity watcher */
    public void triggerWatcher(WatcherTriggerType watcherTriggerType, String... params) {
        var watchers =
                activityConfigItemMap.values().stream()
                        .filter(c -> isActivityActive(c.getActivityId()) && isActivityOpen(c.getActivityId()))
                        .map(ActivityConfigItem::getActivityHandler)
                        .filter(Objects::nonNull)
                        .map(ActivityHandler::getWatchersMap)
                        .map(map -> map.get(watcherTriggerType))
                        .filter(Objects::nonNull)
                        .flatMap(Collection::stream)
                        .toList();

        watchers.forEach(
                watcher ->
                        watcher.trigger(
                                playerActivityDataMap.get(
                                        watcher.getActivityHandler().getActivityConfigItem().getActivityId()),
                                params));
    }

    public boolean isActivityActive(int activityId) {
        if (!ActivityAvailability.isRetained(activityId)) return false;
        var activityConfig = activityConfigItemMap.get(activityId);
        if (activityConfig == null || activityConfig.isDisabled()) {
            return false;
        }

        var now = new Date();
        return now.after(activityConfig.getBeginTime()) && now.before(activityConfig.getEndTime());
    }

    public boolean hasActivityEnded(int activityId) {
        var activityConfig = activityConfigItemMap.get(activityId);
        if (activityConfig == null || activityConfig.isDisabled()) {
            return true;
        }

        return new Date().after(activityConfig.getEndTime());
    }

    public boolean isActivityOpen(int activityId) {
        if (!ActivityAvailability.isRetained(activityId)) return false;
        var activityConfig = activityConfigItemMap.get(activityId);
        if (activityConfig == null || activityConfig.isDisabled()) {
            return false;
        }

        var now = new Date();
        return now.after(activityConfig.getOpenTime()) && now.before(activityConfig.getCloseTime());
    }

    public int getOpenDay(int activityId) {
        var activityConfig = activityConfigItemMap.get(activityId);
        if (activityConfig == null || activityConfig.isDisabled()) {
            return 0;
        }

        var now = new Date();
        return (int)
                        TimeUnit.DAYS.convert(
                                now.getTime() - activityConfig.getOpenTime().getTime(), TimeUnit.MILLISECONDS)
                + 1;
    }

    public boolean isActivityClosed(int activityId) {
        var activityConfig = activityConfigItemMap.get(activityId);
        if (activityConfig == null || activityConfig.isDisabled()) {
            return true;
        }

        var now = new Date();
        return now.after(activityConfig.getCloseTime());
    }

    public boolean meetsCondition(int activityCondId) {
        return conditionExecutor.meetsCondition(activityCondId);
    }

    public void triggerActivityConditions() {
        activityConfigItemMap.forEach((k, v) -> v.getActivityHandler().triggerCondEvents(player));
    }

    /** Returns null for an activity this server has not configured. */
    public ActivityInfoOuterClass.ActivityInfo getInfoProtoByActivityId(int activityId) {
        if (!ActivityAvailability.isRetained(activityId)) return null;
        var activityConfig = activityConfigItemMap.get(activityId);
        if (activityConfig == null) {
            return null;
        }

        var activityHandler = activityConfig.getActivityHandler();
        var activityData = playerActivityDataMap.get(activityId);

        return activityHandler.toProto(activityData, conditionExecutor);
    }

    public Optional<ActivityHandler> getActivityHandler(ActivityType type) {
        return activityConfigItemMap.values().stream()
                .filter(x -> isActivityOpen(x.getActivityId()))
                .map(ActivityConfigItem::getActivityHandler)
                .filter(x -> type == x.getClass().getAnnotation(GameActivity.class).value())
                .findFirst();
    }

    public <T extends ActivityHandler> Optional<T> getActivityHandlerAs(
            ActivityType type, Class<T> clazz) {
        return getActivityHandler(type).map(x -> (T) x);
    }

    public Optional<Integer> getActivityIdByActivityType(ActivityType type) {
        return getActivityHandler(type)
                .map(ActivityHandler::getActivityConfigItem)
                .map(ActivityConfigItem::getActivityId);
    }

    public Optional<PlayerActivityData> getPlayerActivityDataByActivityType(ActivityType type) {
        return getActivityIdByActivityType(type).map(playerActivityDataMap::get);
    }

    public Optional<ActivityInfoOuterClass.ActivityInfo> getInfoProtoByActivityType(
            ActivityType type) {
        return getActivityIdByActivityType(type).map(this::getInfoProtoByActivityId);
    }
}
