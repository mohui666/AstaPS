package emu.grasscutter.data.binout;

import java.util.Collections;
import java.util.List;
import lombok.Getter;

/** Client enemy preview kinds and levels. These records contain no spawn counts or wave scripts. */
@Getter
public final class MonsterPreviewData {
    private int id;
    private int monsterPreviewLevel;
    private List<MonsterPreviewInfo> monsterPreviewInfo;

    public List<MonsterPreviewInfo> getMonsterPreviewInfo() {
        return monsterPreviewInfo == null ? null : Collections.unmodifiableList(monsterPreviewInfo);
    }

    @Getter
    public static final class MonsterPreviewInfo {
        private int monsterPreviewDescribeId;
        private int reviseLevel;
    }
}
