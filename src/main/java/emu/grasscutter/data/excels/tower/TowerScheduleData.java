package emu.grasscutter.data.excels.tower;

import com.google.gson.annotations.SerializedName;
import emu.grasscutter.data.*;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;

@ResourceType(name = "TowerScheduleExcelConfigData.json")
public class TowerScheduleData extends GameResource {
    private int scheduleId;
    private List<Integer> entranceFloorId;
    // The 7.1 resources name this list LHOGNLPBILP; reading it as empty hides floors 9-12.
    @SerializedName(value = "schedules", alternate = {"LHOGNLPBILP"})
    private List<ScheduleDetail> schedules;
    private int monthlyLevelConfigId;
    private int rewardGroup;
    private String closeTime;

    private static final DateTimeFormatter SCHEDULE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** The supported CN client resource times use UTC+08:00, independently of the host zone. */
    public Date getScheduleStartTime() {
        if (schedules == null) return null;
        return schedules.stream()
                .map(ScheduleDetail::getOpenTime)
                .filter(time -> time != null && !time.isBlank())
                .map(TowerScheduleData::parseTime)
                .min(Date::compareTo)
                .orElse(null);
    }

    /** closeTime is the last active second, whereas the protocol advertises an exclusive end. */
    public Date getScheduleEndTime() {
        if (closeTime == null || closeTime.isBlank()) return null;
        return new Date(parseTime(closeTime).getTime() + 1_000L);
    }

    private static Date parseTime(String value) {
        return Date.from(LocalDateTime.parse(value, SCHEDULE_TIME).toInstant(ZoneOffset.ofHours(8)));
    }

    @Override
    public int getId() {
        return scheduleId;
    }

    @Override
    public void onLoad() {
        super.onLoad();

        // Throwing here costs the whole file, not the row: onLoad runs inside the resource
        // loader's per-file pass, so one schedule without a floor list left the server with no
        // tower schedules at all and every abyss lookup failing. Drop the unusable entries and
        // keep the rest.
        if (this.schedules == null) {
            this.schedules = List.of();
            return;
        }

        this.schedules =
                this.schedules.stream()
                        .filter(item -> item != null && item.getFloorList() != null)
                        .filter(item -> !item.getFloorList().isEmpty())
                        .toList();
    }

    public int getScheduleId() {
        return scheduleId;
    }

    public List<Integer> getEntranceFloorId() {
        return entranceFloorId;
    }

    public List<ScheduleDetail> getSchedules() {
        return schedules;
    }

    public int getMonthlyLevelConfigId() {
        return monthlyLevelConfigId;
    }

    public int getRewardGroup() {
        return rewardGroup;
    }

    public static class ScheduleDetail {
        private List<Integer> floorList;
        private String openTime;

        public String getOpenTime() {
            return openTime;
        }

        public List<Integer> getFloorList() {
            return floorList;
        }
    }
}
