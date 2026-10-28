package emu.grasscutter.data.excels.leylinechallenge;

import emu.grasscutter.data.GameResource;
import emu.grasscutter.data.ResourceType;
import lombok.Getter;

@Getter
@ResourceType(name = "LeyLineLevelConfigExcelConfigData.json")
public final class LeyLineLevelConfigData extends GameResource {
    @Getter(onMethod_ = @Override) private int id;
    private String specialMonsterIcon;
}
