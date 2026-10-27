package emu.grasscutter.game.quest.exec;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;

@QuestValueExec(QuestExec.QUEST_EXEC_REGISTER_DYNAMIC_GROUP)
public class ExecRegisterDynamicGroup extends QuestExecHandler {

    @Override
    public boolean execute(GameQuest quest, QuestData.QuestExecParam condition, String... paramStr) {
        if (paramStr.length < 2) return false;
        var sceneId = Integer.parseInt(paramStr[0]);
        var groupId = Integer.parseInt(paramStr[1]);

        if (AmberFlightExamSupport.ownsGroup(quest, sceneId, groupId)) {
            AmberFlightExamSupport.ensure(quest);
            return true;
        }

        Grasscutter.getLogger().debug("Registering group {}", groupId);

        var world = quest.getOwner().getWorld();
        if (world == null) return false;
        var scene = world.getSceneById(sceneId);
        if (scene == null) return false;

        var scripts = scene.getScriptManager();
        var dispatchedState = quest.getState();
        if (!scripts.isInit()) {
            scripts.whenInitialized(ready -> {
                if (!ready) return;
                scene.getScheduler().scheduleDelayedTask(() -> {
                    if (quest.getState() == dispatchedState && quest.getOwner().getWorld() == world
                            && world.getScenes().get(sceneId) == scene && !scripts.isDestroyed())
                        register(quest, scene, groupId);
                }, 1);
            });
            return true;
        }
        return register(quest, scene, groupId);
    }

    private boolean register(GameQuest quest, emu.grasscutter.game.world.Scene scene, int groupId) {
        int suiteId = scene.loadDynamicGroup(groupId);
        if (suiteId == -1) return false;
        var suites = quest.getMainQuest().getQuestGroupSuites();
        suites.removeIf(saved -> saved.getScene() == scene.getId() && saved.getGroup() == groupId);
        suites.add(QuestGroupSuite.of().scene(scene.getId()).group(groupId).suite(suiteId).build());
        quest.save();

        Grasscutter.getLogger()
                .debug("Registered group {}, suite {} in scene {}", groupId, suiteId, scene.getId());

        return true;
    }
}
