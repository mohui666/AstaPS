package emu.grasscutter.data.excels.leylinechallenge;

import emu.grasscutter.data.GameResource;
import emu.grasscutter.data.ResourceType;
import lombok.Getter;

@Getter
@ResourceType(name = "LeyLineDifficultyExcelConfigData.json")
public final class LeyLineDifficultyData extends GameResource {
    @Getter(onMethod_ = @Override) private int id;
    private int leyLineDungeonId;
    private int requiredDifficultyCompletionToUnlock;
}
