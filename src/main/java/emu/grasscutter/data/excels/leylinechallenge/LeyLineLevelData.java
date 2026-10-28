package emu.grasscutter.data.excels.leylinechallenge;

import com.google.gson.annotations.SerializedName;
import emu.grasscutter.data.GameResource;
import emu.grasscutter.data.ResourceType;
import java.util.List;
import lombok.Getter;

@Getter
@ResourceType(name = "LeyLineLevelExcelConfigData.json")
public final class LeyLineLevelData extends GameResource {
    @Getter(onMethod_ = @Override) private int id;
    private int reviseLevel;
    private List<Integer> levelConfigList = List.of();
    private List<Integer> watcherList = List.of();
    @SerializedName(value = "difficulty", alternate = {"MMKEJFCFMBN"})
    private int difficulty;
}
