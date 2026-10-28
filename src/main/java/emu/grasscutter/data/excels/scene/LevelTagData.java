package emu.grasscutter.data.excels.scene;

import com.google.gson.annotations.SerializedName;
import emu.grasscutter.data.*;
import java.util.List;
import lombok.Getter;

@Getter
@ResourceType(name = "LevelTagExcelConfigData.json")
public final class LevelTagData extends GameResource {
    @SerializedName("ID") private int id;
    private int sceneId;
    @SerializedName(value = "levelTagName", alternate = "isInvertedlevelTagName")
    private String levelTagName = "";
    private List<Integer> addSceneTagIdList = List.of();
    private List<Integer> removeSceneTagIdList = List.of();
    private List<Integer> loadDynamicGroupList = List.of();
    private boolean levelTagCanFixTime;
    private float levelTagFixedEnviroTime;
}
