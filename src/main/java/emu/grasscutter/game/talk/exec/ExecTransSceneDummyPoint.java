package emu.grasscutter.game.talk.exec;

import emu.grasscutter.data.excels.TalkConfigData;
import emu.grasscutter.data.excels.TalkConfigData.TalkExecParam;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.QuestSceneConditions;
import emu.grasscutter.game.talk.*;

@TalkValueExec(TalkExec.TALK_EXEC_TRANS_SCENE_DUMMY_POINT)
public final class ExecTransSceneDummyPoint extends TalkExecHandler {
    @Override
    public void execute(Player player, TalkConfigData talkData, TalkExecParam execParam) {
        // param[0] == sceneid, param[1] == position
        if (execParam.getParam().length < 2) return;

        int sceneId = Integer.parseInt(execParam.getParam()[0]);
        var position = QuestSceneConditions.dummyPoint(sceneId, execParam.getParam()[1]);
        if (position == null) return;

        player
                .getWorld()
                .transferPlayerToScene(
                        player,
                        sceneId, position);
    }
}
