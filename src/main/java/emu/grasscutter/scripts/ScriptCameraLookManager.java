package emu.grasscutter.scripts;

import emu.grasscutter.config.Configuration;
import emu.grasscutter.game.entity.EntityGadget;
import emu.grasscutter.game.world.Position;
import emu.grasscutter.game.world.Scene;
import emu.grasscutter.scripts.data.SceneGroup;
import emu.grasscutter.server.packet.send.PacketBeginCameraSceneLookNotify;
import emu.grasscutter.server.packet.send.PacketBeginCameraSceneLookNotify.CameraSceneLookNotify;
import java.util.ArrayList;
import org.luaj.vm2.*;

/** Native script camera notices, including the scene's single delayed-camera timer. */
public final class ScriptCameraLookManager {
    private final Scene scene;
    private int delayedTask = -1;
    public ScriptCameraLookManager(Scene scene) { this.scene = scene; }

    public int begin(SceneGroup group, int uid, LuaTable values) {
        synchronized (scene) {
            if (scene.getScriptManager().isDestroyed() || values == null || !values.get("duration").isnumber()
                    || !values.get("is_force").isboolean() || !values.get("is_broadcast").isboolean()) return -1;
            try {
                var data = new CameraSceneLookNotify();
                int config = integer(values, "look_configid", 0);
                if (config > 0) {
                    var target = group == null ? null : scene.getEntityByConfigId(config, group.id);
                    if (!(target instanceof EntityGadget)) return -1;
                    data.setEntityId(target.getId());
                } else {
                    var point = point(values.get("look_pos"));
                    if (point == null) return -1;
                    data.setLookPos(point);
                }
                data.setDuration(number(values, "duration", 1));
                if (data.getDuration() < 0) return -1;
                data.setForce(values.get("is_force").checkboolean());
                boolean broadcast = values.get("is_broadcast").checkboolean();
                data.setAllowInput(values.get("is_allow_input").optboolean(false));
                data.setRecoverKeepCurrent(values.get("is_recover_keep_current").optboolean(true));
                data.setSetFollowPos(values.get("is_set_follow_pos").optboolean(false));
                if (!values.get("follow_pos").isnil()) {
                    var follow = point(values.get("follow_pos"));
                    if (follow == null) return -1;
                    data.setFollowPos(follow);
                }
                data.setForceWalk(values.get("is_force_walk").optboolean(false));
                data.setChangePlayMode(values.get("is_change_play_mode").optboolean(false));
                data.setScreenX(number(values, "screen_x", 0));
                data.setScreenY(number(values, "screen_y", 0));
                var screen = values.get("is_set_screenXY");
                if (screen.isnil()) screen = values.get("is_set_screen_XY");
                data.setScreenXY(screen.optboolean(false));
                data.setKeepRotType(integer(values, "keep_rot_type", 0));
                if (data.getKeepRotType() > 1) return -1;
                data.setCustomRadius(number(values, "custom_radius", 0));
                data.setDisableProtect(integer(values, "disable_protect", 0));
                data.setBlendType(integer(values, "blend_type", 0));
                data.setBlendDuration(number(values, "blend_duration", 0));
                data.setAbsFollowPos(values.get("is_abs_follow_pos").optboolean(false));
                var other = values.get("other_params");
                if (!other.isnil()) {
                    var strings = new ArrayList<String>();
                    var array = other.checktable();
                    for (int i = 1; i <= array.length(); i++) strings.add(array.get(i).checkjstring());
                    data.setOtherParams(strings);
                }
                int delay = integer(values, "delay", 0);
                if (delay > 0 && delayedTask >= 0) return -1;
                if (!broadcast && scene.getPlayers().stream().noneMatch(player -> player.getUid() == uid)) return -1;
                var packet = new PacketBeginCameraSceneLookNotify(data);
                Runnable publish = () -> {
                    if (scene.getScriptManager().isDestroyed()) return;
                    if (broadcast) scene.broadcastPacket(packet);
                    else scene.getPlayers().stream().filter(player -> player.getUid() == uid && player.getScene() == scene)
                            .findFirst().ifPresent(player -> player.sendPacket(packet));
                };
                if (delay == 0) publish.run();
                else {
                    double ticks = Math.ceil(delay * 1000d / Math.max(1, Configuration.GAME_INFO.tickRateMs));
                    if (ticks > Integer.MAX_VALUE) return -1;
                    delayedTask = scene.getScheduler().scheduleDelayedTask(() -> {
                        synchronized (scene) { delayedTask = -1; publish.run(); }
                    }, (int) ticks);
                }
                return 0;
            } catch (LuaError exception) {
                return -1;
            }
        }
    }

    public void clear() {
        synchronized (scene) {
            if (delayedTask >= 0) scene.getScheduler().cancelTask(delayedTask);
            delayedTask = -1;
        }
    }

    private static float number(LuaTable values, String key, float defaultValue) {
        float value = (float) values.get(key).optdouble(defaultValue);
        if (!Float.isFinite(value)) throw new LuaError("Invalid camera number " + key);
        return value;
    }

    private static int integer(LuaTable values, String key, int defaultValue) {
        double value = values.get(key).optdouble(defaultValue);
        if (value < 0 || value > Integer.MAX_VALUE || value != Math.rint(value))
            throw new LuaError("Invalid camera integer " + key);
        return (int) value;
    }

    private static Position point(LuaValue value) {
        if (!value.istable() || !value.get("x").isnumber() || !value.get("y").isnumber() || !value.get("z").isnumber()) return null;
        var table = value.checktable();
        return new Position(number(table, "x", 0), number(table, "y", 0), number(table, "z", 0));
    }
}
