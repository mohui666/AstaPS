package emu.grasscutter.data.excels.leylinechallenge;

import emu.grasscutter.data.GameResource;
import emu.grasscutter.data.ResourceType;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.Getter;

@Getter
@ResourceType(name = "LeyLineChallengeExcelConfigData.json")
public final class LeyLineChallengeData extends GameResource {
    private int scheduleId;
    private String scheduleStartTime;
    private String scheduleEndTime;
    private List<Integer> levelIdList = List.of();

    @Override
    public int getId() {
        return scheduleId;
    }

    public Instant startTime() {
        return parseTime(scheduleStartTime);
    }

    /** The native end value is the last included second. */
    public Instant endTime() {
        return parseTime(scheduleEndTime).plusSeconds(1);
    }

    public boolean contains(Instant now) {
        return !now.isBefore(startTime()) && now.isBefore(endTime());
    }

    private static Instant parseTime(String value) {
        return LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                .toInstant(ZoneOffset.ofHours(8));
    }
}
