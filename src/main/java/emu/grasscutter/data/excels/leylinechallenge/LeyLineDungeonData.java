package emu.grasscutter.data.excels.leylinechallenge;

import emu.grasscutter.data.GameResource;
import emu.grasscutter.data.ResourceType;
import lombok.Getter;

@Getter
@ResourceType(name = "LeyLineDungeonExcelConfigData.json")
public final class LeyLineDungeonData extends GameResource {
    @Getter(onMethod_ = @Override) private int id;
    private int dungeonId;
}
