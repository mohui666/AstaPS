package emu.grasscutter.data.excels;

import emu.grasscutter.data.*;
import java.util.List;
import lombok.Getter;

@Getter
@ResourceType(name = "GalleryExcelConfigData.json")
public final class GalleryData extends GameResource {
    private int id;
    private int sceneId;
    private int controlGroupId;
    private int duration;
    private String type;
    private List<String> teamAbilityGroupList = List.of();
}
