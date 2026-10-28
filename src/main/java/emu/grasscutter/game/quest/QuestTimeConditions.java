package emu.grasscutter.game.quest;

import com.google.gson.JsonParser;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.RefreshPolicyExcelConfigData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.RefreshType;
import emu.grasscutter.game.quest.enums.*;
import emu.grasscutter.utils.FileUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.time.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Native parent-quest timers use real elapsed time and the configured server day boundary. */
public final class QuestTimeConditions {
    private static final Set<Integer> missingPoliciesReported = ConcurrentHashMap.newKeySet();

    private QuestTimeConditions() {}

    private static final class DayBoundary {
        static final int OFFSET_SECONDS = readDayOffset();
        static final int GMT_OFFSET_SECONDS = ZoneId.systemDefault().getRules().getOffset(Instant.now()).getTotalSeconds();
    }

    private record ReconstructedPolicy(int policyId, int timerParent, int timerIndex, int days) {}

    private static final class ReconstructedPolicies {
        static final Map<Integer, ReconstructedPolicy> BY_QUEST = loadReconstructedPolicies();
    }

    private static Map<Integer, ReconstructedPolicy> loadReconstructedPolicies() {
        try {
            var config = JsonParser.parseString(Files.readString(FileUtils.getDataPath(
                    "QuestRefreshPolicyReconstruction.json"))).getAsJsonObject();
            if (!config.get("reconstructed").getAsBoolean())
                throw new IllegalStateException("Expected explicit quest refresh reconstruction");
            var result = new HashMap<Integer, ReconstructedPolicy>();
            for (var value : config.getAsJsonArray("quests")) {
                var row = value.getAsJsonObject();
                int questId = row.get("questId").getAsInt();
                var policy = new ReconstructedPolicy(row.get("policyId").getAsInt(),
                        row.get("timerParent").getAsInt(), row.get("timerIndex").getAsInt(),
                        row.get("serverDays").getAsInt());
                if (questId <= 0 || policy.policyId <= 0 || policy.timerParent <= 0
                        || policy.timerIndex < 0 || policy.timerIndex >= 10 || policy.days <= 0
                        || result.put(questId, policy) != null)
                    throw new IllegalStateException("Invalid explicit quest refresh reconstruction");
            }
            return Map.copyOf(result);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read explicit quest refresh reconstruction", error);
        }
    }

    private static int readDayOffset() {
        try {
            var rows = JsonParser.parseString(Files.readString(FileUtils.getResourcePath(
                    "ExcelBinOutput/ConstValueExcelConfigData.json"))).getAsJsonArray();
            for (var value : rows) {
                var row = value.getAsJsonObject();
                if (row.has("name") && row.get("name").getAsString().equals("CONST_VALUE_NEXT_DAY")) {
                    int hour = row.getAsJsonArray("value").get(0).getAsInt();
                    if (hour < 0 || hour >= 24) throw new IllegalStateException("Invalid native next-day clock");
                    return hour * 3600;
                }
            }
            throw new IllegalStateException("Missing native CONST_VALUE_NEXT_DAY for quest timers");
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read native day boundary for quest timers", error);
        }
    }

    static long dayIndex(long epochSeconds) {
        // TimeUtils::getTimeDay uses the server's gmtoff and its configured next_day_clock.
        return Math.floorDiv(epochSeconds + DayBoundary.GMT_OFFSET_SECONDS - DayBoundary.OFFSET_SECONDS, 86400);
    }

    public static boolean matchesCurrent(GameQuest quest, QuestData.QuestContentCondition condition) {
        var params = condition.getParam();
        if (params == null || params.length < 2 || params[0] <= 0 || params[1] < 0 || params[1] >= 10) return false;
        var parent = quest.getOwner().getQuestManager().getMainQuestById(params[0]);
        if (parent == null) return false;
        final long minimum;
        try { minimum = Long.parseLong(condition.getParamStr()); }
        catch (NumberFormatException error) { return false; }
        if (minimum < 0) return false;
        return switch (condition.getType()) {
            case QUEST_CONTENT_TIME_VAR_GT_EQ -> parent.getHoursSinceTimeVar(params[1]) >= minimum;
            case QUEST_CONTENT_TIME_VAR_PASS_DAY -> parent.getDaysSinceTimeVar(params[1]) >= minimum;
            case QUEST_CONTENT_TIME_VAR_PASS_REFRESH_POLICY -> matchesRefreshPolicy(quest, parent, params[1], minimum);
            default -> false;
        };
    }

    public static boolean matchesAccept(Player owner, QuestData.QuestAcceptCondition condition) {
        var params = condition.getParam();
        if (params == null || params.length < 3 || params[0] <= 0 || params[1] < 0 || params[1] >= 10 || params[2] < 0)
            return false;
        var parent = owner.getQuestManager().getMainQuestById(params[0]);
        if (parent == null) return false;
        return switch (condition.getType()) {
            case QUEST_COND_TIME_VAR_GT_EQ -> parent.getHoursSinceTimeVar(params[1]) >= params[2];
            case QUEST_COND_TIME_VAR_PASS_DAY -> parent.getDaysSinceTimeVar(params[1]) >= params[2];
            default -> false;
        };
    }

    static boolean preserveOnLogin(GameMainQuest main) {
        for (var quest : main.getActiveQuests()) {
            var data = quest.getQuestData();
            if (data == null) continue;
            for (var condition : java.util.stream.Stream.concat(data.getFinishCond().stream(), data.getFailCond().stream()).toList()) {
                if (condition.getType() != QuestContent.QUEST_CONTENT_TIME_VAR_GT_EQ
                        && condition.getType() != QuestContent.QUEST_CONTENT_TIME_VAR_PASS_DAY
                        && condition.getType() != QuestContent.QUEST_CONTENT_TIME_VAR_PASS_REFRESH_POLICY) continue;
                var params = condition.getParam();
                if (params == null || params.length < 2) continue;
                var timerOwner = main.getOwner().getQuestManager().getMainQuestById(params[0]);
                if (timerOwner != null && timerOwner.isTimeVarActive(params[1])) return true;
            }
            if (data.getBeginExec() == null) continue;
            for (var exec : data.getBeginExec()) {
                if (exec.getType() != QuestExec.QUEST_EXEC_INIT_TIME_VAR || exec.getParam() == null
                        || exec.getParam().length == 0) continue;
                try {
                    if (main.isTimeVarActive(Integer.parseInt(exec.getParam()[0]))) return true;
                } catch (NumberFormatException ignored) {
                    // The malformed native exec remains rejected by its ordinary handler.
                }
            }
        }
        return false;
    }

    private static boolean matchesRefreshPolicy(GameQuest quest, GameMainQuest parent, int index, long policyId) {
        if (policyId <= 0 || policyId > Integer.MAX_VALUE) return false;
        var policy = GameData.getRefreshPolicyExcelConfigDataMap().get((int) policyId);
        if (policy == null) {
            var reconstructed = ReconstructedPolicies.BY_QUEST.get(quest.getSubQuestId());
            if (reconstructed != null && reconstructed.policyId == policyId
                    && reconstructed.timerParent == parent.getParentQuestId() && reconstructed.timerIndex == index) {
                // This is an explicit local rule, not a recovered native calendar row. Only new
                // Unix timers can drive it: moving the in-game clock cannot unlock the next day.
                var timers = parent.getTimeVarUnixSeconds();
                var start = timers == null ? null : timers.get(index);
                long now = parent.timeVarNowSeconds();
                return start != null && start > 0 && now >= start
                        && dayIndex(now) - dayIndex(start) >= reconstructed.days;
            }
            if (missingPoliciesReported.add((int) policyId)) Grasscutter.getLogger().warn(
                    "Quest time-var refresh policy {} is missing from RefreshPolicyExcelConfigData; condition cannot pass", policyId);
            return false;
        }
        if (policy.getType() == RefreshType.REFRESH_INTERVAL) {
            long next = nextRefreshTime(policy, 1);
            return next > 1 && parent.getSecondsSinceTimeVar(index) >= next - 1;
        }
        var timers = parent.getTimeVarUnixSeconds();
        var start = timers == null ? null : timers.get(index);
        return start != null && hasRefreshed(policy, start, parent.timeVarNowSeconds());
    }

    /** Fixed native RefreshPolicyExcelConfigMgr::getNextRefreshTimeAndRefreshInterval semantics. */
    public static boolean hasRefreshed(RefreshPolicyExcelConfigData policy, long start, long now) {
        if (start <= 0 || now < start) return false;
        long next = nextRefreshTime(policy, start);
        return next > start && now >= next;
    }

    private static long nextRefreshTime(RefreshPolicyExcelConfigData policy, long start) {
        if (policy == null || policy.getType() == null || policy.getTime() == null || policy.getTime().isBlank()) return -1;
        final long[] points;
        try {
            var values = policy.getTime().split(";", -1);
            points = new long[values.length];
            for (int i = 0; i < values.length; i++) {
                points[i] = Long.parseLong(values[i]);
                if (points[i] < 0) return -1;
            }
        } catch (NumberFormatException error) { return -1; }
        long offset = DayBoundary.GMT_OFFSET_SECONDS;
        return switch (policy.getType()) {
            case REFRESH_INTERVAL -> points.length == 1 && points[0] > 0 && points[0] <= Integer.MAX_VALUE
                    ? start + points[0] : -1;
            case REFRESH_DAYBEGIN_INTERVAL -> points.length == 1 && points[0] > 0 && points[0] <= Integer.MAX_VALUE / 86400
                    ? start - Math.floorMod(start + offset, 86400) + points[0] * 86400 : -1;
            case REFRESH_DAILY -> nextCalendarPoint(start, offset, points, 86400, 0);
            case REFRESH_WEEKlY -> nextCalendarPoint(start, offset, points, 604800, 259200);
            default -> -1;
        };
    }

    private static long nextCalendarPoint(long start, long offset, long[] points, long period, long epochShift) {
        long begin = start - Math.floorMod(start + offset + epochShift, period);
        long next = Long.MAX_VALUE;
        for (long point : points) {
            if (point >= period) return -1;
            long candidate = begin + point;
            if (candidate <= start) candidate += period;
            next = Math.min(next, candidate);
        }
        return next;
    }
}
