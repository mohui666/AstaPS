package emu.grasscutter.data.excels;

import emu.grasscutter.data.*;
import java.util.List;
import lombok.Getter;

@Getter
@ResourceType(name = "FeatureTagGroupExcelConfigData.json")
public final class FeatureTagGroupData extends GameResource {
    private int groupID;
    private List<Integer> tagIDs = List.of();
    @Override public int getId() { return groupID; }
}
