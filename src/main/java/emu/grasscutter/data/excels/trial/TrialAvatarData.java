package emu.grasscutter.data.excels.trial;

import emu.grasscutter.data.*;
import com.google.gson.annotations.SerializedName;
import java.util.List;
import lombok.*;

@ResourceType(name = "TrialAvatarExcelConfigData.json")
@EqualsAndHashCode(callSuper = false)
@Data
public class TrialAvatarData extends GameResource {
    private int trialAvatarId;
    private List<Integer> trialAvatarParamList;
    private int trialSkillDepotId;
    @SerializedName("isDynamicLevel") private boolean dynamicLevel;

    @Override
    public int getId() {
        return trialAvatarId;
    }
}
