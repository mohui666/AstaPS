package emu.grasscutter.scripts;

import emu.grasscutter.config.Configuration;
import emu.grasscutter.scripts.data.SceneGroup;
import emu.grasscutter.server.packet.send.PacketCutSceneEndNotify;
import emu.grasscutter.server.packet.send.PacketCutsceneBeginNotify;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.WeakHashMap;
import org.luaj.vm2.LuaTable;

public final class ScriptCutsceneActions {
    private static final class Waiting {
        final int cutsceneId;
        int taskId = -1;
        Waiting(int cutsceneId) { this.cutsceneId = cutsceneId; }
    }
    private static final Map<SceneScriptManager, Waiting> waiting = Collections.synchronizedMap(new WeakHashMap<>());
    private ScriptCutsceneActions() {}

    public static int play(SceneScriptManager scripts, SceneGroup group, int cutsceneId, int waitSeconds) {
        return play(scripts, group, cutsceneId, waitSeconds, List.of());
    }

    public static int playWithParameters(SceneScriptManager scripts, SceneGroup group,
            int cutsceneId, int waitSeconds, LuaTable parameters) {
        if (parameters == null || parameters.length() == 0) return -1;
        var rows = new ArrayList<List<Double>>();
        for (int i = 1; i <= parameters.length(); i++) {
            var value = parameters.get(i);
            if (!value.istable()) return -1;
            var row = new ArrayList<Double>();
            for (int j = 1; j <= value.length(); j++) {
                if (!value.get(j).isnumber() || !Double.isFinite(value.get(j).todouble())) return -1;
                row.add(value.get(j).todouble());
            }
            rows.add(List.copyOf(row));
        }
        return play(scripts, group, cutsceneId, waitSeconds, rows);
    }

    private static int play(SceneScriptManager scripts, SceneGroup group,
            int cutsceneId, int waitSeconds, List<List<Double>> rows) {
        if (scripts == null || group == null || cutsceneId <= 0 || waitSeconds < 0) return -1;
        double timeoutTicks = Math.ceil(waitSeconds * 1000d / Math.max(1, Configuration.GAME_INFO.tickRateMs));
        if (timeoutTicks > Integer.MAX_VALUE) return -1;
        var scene = scripts.getScene();
        synchronized (scene) {
            synchronized (scripts) {
                if (scripts.isDestroyed() || scripts.hasPendingCutscene()) return -1;
                if (waitSeconds > 0) scripts.registerCutscene(cutsceneId, group.id);
                if (Configuration.GAME_OPTIONS.disableCutscenes) {
                    if (waitSeconds > 0) scripts.finishCutscene(cutsceneId);
                    return 0;
                }
                if (waitSeconds > 0) {
                    var run = new Waiting(cutsceneId);
                    waiting.put(scripts, run);
                    run.taskId = scene.getScheduler().scheduleDelayedTask(() -> {
                        synchronized (scene) {
                            synchronized (scripts) {
                                if (waiting.get(scripts) != run || !scripts.hasPendingCutscene(cutsceneId)) return;
                                scene.broadcastPacket(new PacketCutSceneEndNotify(cutsceneId));
                                scripts.finishCutscene(cutsceneId);
                            }
                        }
                    }, (int) timeoutTicks);
                }
                scene.broadcastPacket(new PacketCutsceneBeginNotify(cutsceneId, waitSeconds > 0, rows));
                return 0;
            }
        }
    }

    /** Called by the existing genuine client-completion path, before its Lua END event. */
    public static void onFinished(SceneScriptManager scripts, int cutsceneId) {
        synchronized (scripts) {
            var run = waiting.get(scripts);
            if (run != null && run.cutsceneId == cutsceneId) clear(scripts);
        }
    }

    public static void clear(SceneScriptManager scripts) {
        synchronized (scripts) {
            var run = waiting.remove(scripts);
            if (run != null && run.taskId >= 0) scripts.getScene().getScheduler().cancelTask(run.taskId);
        }
    }
}
