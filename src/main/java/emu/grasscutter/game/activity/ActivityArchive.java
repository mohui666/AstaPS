package emu.grasscutter.game.activity;

import static emu.grasscutter.utils.FileUtils.getDataPath;

import com.google.gson.*;
import emu.grasscutter.GameConstants;
import emu.grasscutter.data.GameData;
import emu.grasscutter.game.props.ActivityType;
import emu.grasscutter.utils.FileUtils;
import emu.grasscutter.utils.JsonUtils;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Operator-owned event schedules, separate from native definitions and player progress. */
public final class ActivityArchive {
    private static final Path PATH = getDataPath("activities/archives.json");
    private static Path storagePath = PATH;
    private static final Map<Integer, JsonObject> DEFINITIONS = new TreeMap<>();
    private static final Map<Integer, ActivityConfigItem> ORIGINALS = new HashMap<>();
    private static final Map<Integer, Saved> SAVED = new TreeMap<>();
    private static final long LAST_DATE = Instant.parse("2099-12-31T23:59:59Z").toEpochMilli();
    private static String resourceVersion;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").create();

    private ActivityArchive() {}

    public record Saved(int activityId, ActivityConfigItem config, String note) {}
    public record Pack(int formatVersion, String resourceVersion, List<Saved> activities) {}

    static List<ActivityConfigItem> load(List<ActivityConfigItem> bundled) throws IOException {
        return load(bundled, PATH);
    }

    static List<ActivityConfigItem> load(List<ActivityConfigItem> bundled, Path statePath) throws IOException {
        storagePath = statePath;
        try (var stream = FileUtils.readResourceAsStream("/activities/catalog.json")) {
            var root = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            resourceVersion = root.get("resourceVersion").getAsString();
            for (var row : root.getAsJsonArray("activities")) {
                var object = row.getAsJsonObject();
                DEFINITIONS.put(object.get("id").getAsInt(), object);
            }
        }
        ORIGINALS.clear();
        for (var config : bundled) {
            if (!ActivityAvailability.isRetained(config.getActivityId())) continue;
            config.onLoad();
            // The client encodes timestamps as uint32; the old year 2999 wraps around.
            if (config.getEndTime().getTime() > LAST_DATE) config.setEndTime(new Date(LAST_DATE));
            if (config.getCloseTime().getTime() > LAST_DATE) config.setCloseTime(new Date(LAST_DATE));
            ORIGINALS.put(config.getActivityId(), copy(config));
        }
        Map<Integer, Saved> initial = new TreeMap<>();
        for (int id : DEFINITIONS.keySet()) {
            if (ActivityAvailability.isRetained(id)) initial.put(id, new Saved(id, ORIGINALS.get(id), ""));
        }
        if (Files.exists(storagePath)) {
            var pack = parse(Files.readString(storagePath), true);
            for (var saved : pack.activities()) initial.put(saved.activityId(), saved);
        }
        SAVED.clear();
        SAVED.putAll(initial);
        persist(SAVED);
        return SAVED.values().stream().map(Saved::config).filter(Objects::nonNull).map(ActivityArchive::copy).toList();
    }

    public static List<Map<String, Object>> catalog() {
        var rows = new ArrayList<Map<String, Object>>();
        for (var saved : SAVED.values()) {
            var definition = DEFINITIONS.get(saved.activityId());
            var row = new LinkedHashMap<String, Object>();
            row.put("id", saved.activityId());
            row.put("name", definition.get("name").getAsString());
            row.put("en", definition.get("en").getAsString());
            row.put("type", definition.get("type").getAsString());
            row.put("support", support(saved.activityId()));
            row.put("note", saved.note());
            row.put("config", saved.config() == null ? null : GSON.toJsonTree(saved.config()));
            row.put("watcherCount", definition.getAsJsonArray("watchers").size());
            row.put("conditionCount", definition.getAsJsonArray("conditions").size());
            var config = saved.config();
            long now = System.currentTimeMillis();
            row.put("state", config == null ? "archived" : config.isDisabled() ? "closed"
                    : now < config.getBeginTime().getTime() ? "scheduled"
                    : now >= config.getEndTime().getTime() ? "ended" : "open");
            rows.add(row);
        }
        return rows;
    }

    /** An implementation classification, never a promise that a client has the old assets. */
    public static String support(int id) {
        var type = DEFINITIONS.get(id).get("type").getAsString();
        return switch (type) {
            case "NEW_ACTIVITY_MUSIC_GAME" -> "music";
            case "NEW_ACTIVITY_TRIAL_AVATAR" -> "partial";
            default -> "entry";
        };
    }

    public static JsonObject detail(int id) {
        requireId(id);
        var result = DEFINITIONS.get(id).deepCopy();
        result.add("saved", GSON.toJsonTree(SAVED.get(id)));
        result.addProperty("support", support(id));
        return result;
    }

    public static JsonObject exportPack() {
        var root = GSON.toJsonTree(new Pack(1, resourceVersion, List.copyOf(SAVED.values()))).getAsJsonObject();
        root.add("definitions", JsonUtils.toJson(DEFINITIONS.entrySet().stream()
                .filter(entry -> ActivityAvailability.isRetained(entry.getKey()))
                .map(Map.Entry::getValue).toList()));
        root.add("resourceRequirements", JsonUtils.toJson(List.of(
                "保留当前 7.1.0 客户端及下载资源；JSON 无法替代已被客户端删除的音频、场景和玩法资源。",
                "保留 resources（当前 LunaGC-Resources 资源库）及本服务器 JAR；导入不会补出缺失的玩法实现。",
                "玩家活动进度单独保存在 MongoDB activities 集合，普通关闭和重开不清空进度。")));
        return root;
    }

    public static Pack parse(String body) {
        return parse(body, false);
    }

    private static Pack parse(String body, boolean loadingStoredArchive) {
        Pack pack;
        try {
            var json = JsonParser.parseString(body).getAsJsonObject();
            var rows = new ArrayList<Saved>();
            for (var element : json.getAsJsonArray("activities")) {
                var row = element.getAsJsonObject();
                int id = row.get("activityId").getAsBigDecimal().intValueExact();
                if (loadingStoredArchive && !ActivityAvailability.isRetained(id)) continue;
                var config = !row.has("config") || row.get("config").isJsonNull() ? null
                        : GSON.fromJson(row.get("config"), ActivityConfigItem.class);
                rows.add(new Saved(id, config,
                        row.has("note") && !row.get("note").isJsonNull() ? row.get("note").getAsString() : ""));
            }
            pack = new Pack(json.get("formatVersion").getAsBigDecimal().intValueExact(),
                    json.get("resourceVersion").getAsString(), rows);
        }
        catch (RuntimeException e) { throw new IllegalArgumentException("不是有效的活动档案 JSON。", e); }
        if (pack == null || pack.formatVersion() != 1 || !Objects.equals(pack.resourceVersion(), resourceVersion)
                || pack.activities() == null || pack.activities().size() > DEFINITIONS.size()) {
            throw new IllegalArgumentException("档案格式或资源版本不匹配；只能导入当前资源版本的档案。");
        }
        var ids = new HashSet<Integer>();
        for (var saved : pack.activities()) {
            if (saved == null || !ids.add(saved.activityId())) throw new IllegalArgumentException("活动档案包含空行或重复 ID。");
            validate(saved);
        }
        return pack;
    }

    public static void importPack(String body) throws IOException {
        var pack = parse(body); // Validate the complete file before changing any state.
        var next = new TreeMap<>(SAVED);
        pack.activities().forEach(saved -> next.put(saved.activityId(), saved));
        validateConflicts(next.values());
        persist(next);
        SAVED.clear();
        SAVED.putAll(next);
        for (var saved : pack.activities()) ActivityManager.applyArchiveConfig(saved.activityId(), saved.config());
    }

    public static Saved update(int id, String action, int days, boolean unlock, boolean entryOnly, String note,
            Long begin, Long end) throws IOException {
        requireId(id);
        if (!Set.of("open", "close", "schedule", "save").contains(action)) throw new IllegalArgumentException("未知活动操作。");
        var previous = SAVED.get(id);
        var config = previous.config() == null ? createConfig(id) : copy(previous.config());
        if (!action.equals("close") && !action.equals("save") && !support(id).equals("music") && !entryOnly) {
            throw new IllegalArgumentException("此活动缺少完整玩法实现，请确认仅尝试开启入口。");
        }
        if (action.equals("open")) {
            if (days < 0 || days > 3650) throw new IllegalArgumentException("开放天数须为 0（长期）或 1–3650。");
            long now = System.currentTimeMillis() - 1000;
            config.setBeginTime(new Date(now));
            config.setOpenTime(new Date(now));
            config.setEndTime(new Date(days == 0 ? LAST_DATE : now + days * 86_400_000L));
            config.setCloseTime(config.getEndTime());
            config.setDisabled(false);
        } else if (action.equals("close")) {
            config.setDisabled(true);
        } else if (action.equals("schedule")) {
            if (begin == null || end == null) throw new IllegalArgumentException("请设置开始和结束时间。");
            config.setBeginTime(new Date(begin));
            config.setOpenTime(new Date(begin));
            config.setEndTime(new Date(end));
            config.setCloseTime(new Date(end));
            config.setDisabled(false);
        }
        config.setUnlockConditions(unlock);
        var saved = new Saved(id, config, note == null ? previous.note() : note.strip());
        validate(saved);
        var next = new TreeMap<>(SAVED);
        next.put(id, saved);
        validateConflicts(next.values());
        persist(next); // A failed write must not silently change the running schedule.
        SAVED.put(id, saved);
        ActivityManager.applyArchiveConfig(id, copy(config));
        return saved;
    }

    private static ActivityConfigItem createConfig(int id) {
        var config = new ActivityConfigItem();
        config.setActivityId(id);
        config.setActivityType(ActivityType.getTypeByName(DEFINITIONS.get(id).get("type").getAsString()).getValue());
        config.setScheduleId(id * 1000 + 1);
        config.setMeetCondList(List.of());
        config.setBeginTime(new Date(1000));
        config.setOpenTime(new Date(1000));
        config.setCloseTime(new Date(2000));
        config.setEndTime(new Date(2000));
        config.setDisabled(true);
        return config;
    }

    private static void requireId(int id) {
        if (!DEFINITIONS.containsKey(id) || !GameData.getActivityDataMap().containsKey(id))
            throw new IllegalArgumentException("当前资源没有这个活动 ID。");
        ActivityAvailability.requireRetained(id);
    }

    private static void validate(Saved saved) {
        requireId(saved.activityId());
        if (saved.note() == null || saved.note().length() > 1000) throw new IllegalArgumentException("备注须在 1000 字以内。");
        var c = saved.config();
        if (c == null) return;
        var expected = ORIGINALS.getOrDefault(saved.activityId(), createConfig(saved.activityId()));
        // Earlier MGC builds saved this retained activity as NONE before its native enum was known.
        if (saved.activityId() == 5269 && c.getActivityType() == 0)
            c.setActivityType(ActivityType.NEW_ACTIVITY_LEY_LINE_CHALLENGE.getValue());
        if (c.getActivityId() != saved.activityId() || c.getScheduleId() != expected.getScheduleId()
                || c.getActivityType() != expected.getActivityType())
            throw new IllegalArgumentException("不能变更活动 ID、协议类型或排期 ID。");
        if (c.getBeginTime() == null || c.getEndTime() == null || c.getOpenTime() == null || c.getCloseTime() == null
                || c.getBeginTime().getTime() < 1000 || c.getEndTime().getTime() > LAST_DATE
                || !c.getBeginTime().before(c.getEndTime()) || c.getOpenTime().before(c.getBeginTime())
                || c.getCloseTime().after(c.getEndTime()) || !c.getOpenTime().before(c.getCloseTime()))
            throw new IllegalArgumentException("活动日期须有效且顺序为开始 ≤ 开放 < 关闭 ≤ 结束，最晚至 2099 年。");
        if (c.getMeetCondList() == null || !new HashSet<>(expected.getMeetCondList()).containsAll(c.getMeetCondList()))
            throw new IllegalArgumentException("条件 ID 不属于此活动的原始配置。");
    }

    private static void validateConflicts(Collection<Saved> state) {
        // Existing type-based packet routing can address only one active music event.
        var active = state.stream().filter(s -> s.config() != null && !s.config().isDisabled()
                && !support(s.activityId()).equals("entry")).toList();
        for (int i = 0; i < active.size(); i++) for (int j = i + 1; j < active.size(); j++) {
            var first = active.get(i); var second = active.get(j);
            if (!support(first.activityId()).equals(support(second.activityId()))) continue;
            var a = first.config(); var b = second.config();
            if (a.getBeginTime().before(b.getEndTime()) && b.getBeginTime().before(a.getEndTime()))
                throw new IllegalArgumentException("活动 " + first.activityId() + " 与 " + second.activityId()
                        + " 排期重叠；现有协议路由只支持同类型一个活动，请先关闭或调整排期。");
        }
    }

    private static void persist(Map<Integer, Saved> state) throws IOException {
        Files.createDirectories(storagePath.getParent());
        Files.writeString(storagePath, GSON.toJson(new Pack(1, resourceVersion, List.copyOf(state.values()))));
    }

    private static ActivityConfigItem copy(ActivityConfigItem config) {
        return GSON.fromJson(GSON.toJsonTree(config), ActivityConfigItem.class);
    }
}
