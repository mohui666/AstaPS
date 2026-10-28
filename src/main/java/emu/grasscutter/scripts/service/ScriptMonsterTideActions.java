package emu.grasscutter.scripts.service;

import emu.grasscutter.game.entity.GameEntity;
import emu.grasscutter.net.proto.VisionTypeOuterClass.VisionType;
import emu.grasscutter.scripts.SceneScriptManager;
import java.util.ArrayList;

public final class ScriptMonsterTideActions {
    private ScriptMonsterTideActions() {}

    public static int kill(SceneScriptManager scripts, int groupId, int tideIndex) {
        if (scripts == null || groupId <= 0 || tideIndex < 0) return -1;
        var scene = scripts.getScene();
        synchronized (scene) {
            synchronized (scripts) {
                var tides = scripts.getMonsterTides(groupId).stream()
                        .filter(tide -> Integer.toString(tideIndex).equals(tide.getSource())).toList();
                if (tides.isEmpty()) return -1;
                var monsters = new ArrayList<GameEntity>();
                for (var tide : tides) {
                    if (!tide.resolveRestoredMembers(null)) return -1;
                    monsters.addAll(tide.getLiveMonsters());
                }
                // Cancel listeners and pending spawns before removing the tide's actual members.
                if (!scripts.unloadMonsterTide(groupId, Integer.toString(tideIndex))) return -1;
                scene.removeEntities(monsters, VisionType.VisionType_VISION_MISS);
                return 0;
            }
        }
    }
}
