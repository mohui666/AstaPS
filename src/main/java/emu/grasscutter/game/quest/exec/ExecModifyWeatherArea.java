package emu.grasscutter.game.quest.exec;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;
import java.util.Arrays;

@QuestValueExec(QuestExec.QUEST_EXEC_MODIFY_WEATHER_AREA)
public final class ExecModifyWeatherArea extends QuestExecHandler {
    @Override
    public boolean execute(GameQuest quest, QuestData.QuestExecParam condition, String... params) {
        if (params.length < 2 || params.length > 3) return false;
        int mode = params.length == 3 ? Integer.parseInt(params[2].trim()) : 0;
        if (mode < 0 || mode > 1) return false;
        int[] ids = Arrays.stream(params[0].split(";", -1)).map(String::trim).mapToInt(Integer::parseInt).toArray();
        int[] states = Arrays.stream(params[1].split(";", -1)).map(String::trim).mapToInt(Integer::parseInt).toArray();
        return mode == 1 ? QuestWeatherAreas.changeReconstructedMode1(quest.getOwner(), ids, states)
                : QuestWeatherAreas.change(quest.getOwner(), ids, states);
    }
}
