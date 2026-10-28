package emu.grasscutter.game.quest.exec;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.QuestValueExec;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;

/** Native action 71 resolves each group's authored personal scene and forces its WQ flow stage. */
@QuestValueExec(QuestExec.QUEST_EXEC_REFRESH_WORLD_QUEST_FLOW_GROUP_SUITE)
public final class ExecRefreshWorldQuestFlowGroupSuite extends QuestExecHandler {
    @Override
    public boolean execute(GameQuest quest, QuestData.QuestExecParam condition, String... params) {
        var owner = quest.getOwner();
        if (params.length != 1 || owner.getWorld() == null || owner.getWorld().getHost() != owner) return false;
        var scenes = new java.util.LinkedHashMap<Integer,java.util.List<String>>();
        try {
            for (String entry : params[0].split(";")) {
                String[] pair = entry.split(",",-1);
                if (pair.length != 2 || Integer.parseInt(pair[1].trim()) <= 0) return false;
                int group = Integer.parseInt(pair[0].trim());
                int scene = emu.grasscutter.scripts.data.SceneGroup.findSceneId(group);
                if (scene <= 0) return false;
                scenes.computeIfAbsent(scene,ignored -> new java.util.ArrayList<>()).add(entry);
            }
        } catch (NumberFormatException error) { return false; }
        if (scenes.isEmpty()) return false;
        boolean applied = true;
        for (var entry : scenes.entrySet()) applied &= new ExecRefreshGroupSuite().execute(quest,condition,
                String.valueOf(entry.getKey()),String.join(";",entry.getValue()));
        return applied;
    }
}
