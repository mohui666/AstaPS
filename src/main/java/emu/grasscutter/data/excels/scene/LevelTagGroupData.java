package emu.grasscutter.data.excels.scene;

import com.google.gson.annotations.SerializedName;
import emu.grasscutter.data.*;
import java.util.List;
import lombok.Getter;

@Getter
@ResourceType(name = "LevelTagGroupsExcelConfigData.json")
public final class LevelTagGroupData extends GameResource {
    @SerializedName("ID") private int id;
    private List<TagList> levelTagGroupList = List.of();

    @Getter
    public static final class TagList {
        private List<Integer> levelTagIdList = List.of();
    }
}
