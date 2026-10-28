package emu.grasscutter.game.quest.world;

import static emu.grasscutter.game.quest.world.QuestSceneOverrides.*;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.quest.QuestData.QuestExecParam;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.ClimateType;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;
import emu.grasscutter.server.packet.send.PacketPlayerWorldSceneInfoListNotify;
import emu.grasscutter.server.packet.send.PacketScenePointUnlockNotify;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit temporary overrides never enter the player's permanent unlock/tag collections. */
public final class QuestTemporaryResourceRuntime {
    private QuestTemporaryResourceRuntime() {}
    private record Resource(int scene, int kind, int id, boolean enabled) {}

    public static boolean execute(GameQuest quest, QuestExecParam condition, QuestExecHandler handler, String... params) {
        try {
            var operation = QuestResourceOperation.parse(condition.getResourceOpType());
            var resource = binding(condition.getType(), params);
            if (operation != QuestResourceOperation.PERMANENT && resource == null) return false;
            if (operation == QuestResourceOperation.TEMPORARY) {
                if (resource == null || !valid(resource)) return false;
                return applyTemporary(quest.getOwner(), quest.getMainQuestId(), resource);
            }
            int weatherBase = 0;
            var player = quest.getOwner();
            if (resource != null && resource.kind == TEMP_WEATHER_GADGET)
                weatherBase = player.getQuestSceneOverrides().weatherBeforeTemporary(resource.scene, player.getWeatherId());
            boolean result = handler.execute(quest, condition, params);
            if (!result || resource == null) return result;
            var state = player.getQuestSceneOverrides();
            if (resource.kind == TEMP_WEATHER_GADGET)
                state.updatePermanentWeather(resource.scene, resource.enabled ? resource.id
                        : weatherBase == resource.id ? 0 : weatherBase);
            if (operation == QuestResourceOperation.TEMPORARY_TO_PERMANENT) {
                state.clearTemporaryResources(resource.scene, resource.kind, resource.id, quest.getMainQuestId());
                player.save();
            }
            if (state.temporaryValue(resource.scene, resource.kind, resource.id) != null
                    || operation == QuestResourceOperation.TEMPORARY_TO_PERMANENT) notifyResource(player, resource);
            return result;
        } catch (IllegalArgumentException error) {
            Grasscutter.getLogger().error("Invalid temporary resource action for quest {}: {}",
                    quest.getSubQuestId(), error.getMessage());
            return false;
        }
    }

    private static Resource binding(QuestExec type, String[] params) {
        if (type != QuestExec.QUEST_EXEC_UNLOCK_POINT && type != QuestExec.QUEST_EXEC_LOCK_POINT
                && type != QuestExec.QUEST_EXEC_SET_WEATHER_GADGET
                && type != QuestExec.QUEST_EXEC_ADD_SCENE_TAG && type != QuestExec.QUEST_EXEC_DEL_SCENE_TAG) return null;
        if (params.length < 2) throw new IllegalArgumentException("Missing resource id or state");
        int first = Integer.parseInt(params[0]), second = Integer.parseInt(params[1]);
        return switch (type) {
            case QUEST_EXEC_UNLOCK_POINT, QUEST_EXEC_LOCK_POINT -> new Resource(first, TEMP_POINT, second,
                    type == QuestExec.QUEST_EXEC_UNLOCK_POINT);
            case QUEST_EXEC_ADD_SCENE_TAG, QUEST_EXEC_DEL_SCENE_TAG -> new Resource(first, TEMP_SCENE_TAG, second,
                    type == QuestExec.QUEST_EXEC_ADD_SCENE_TAG);
            default -> {
                var data = GameData.getWeatherDataMap().get(first);
                if (data == null || second < 0 || second > 1) throw new IllegalArgumentException("Invalid weather gadget");
                yield new Resource(data.getSceneID(), TEMP_WEATHER_GADGET, first, second == 1);
            }
        };
    }

    private static boolean valid(Resource resource) {
        if (resource.scene <= 0 || resource.id <= 0) return false;
        return switch (resource.kind) {
            case TEMP_POINT -> GameData.getScenePointEntryById(resource.scene, resource.id) != null;
            case TEMP_SCENE_TAG -> {
                var tag = GameData.getSceneTagDataMap().get(resource.id);
                yield tag != null && tag.getSceneId() == resource.scene;
            }
            case TEMP_WEATHER_GADGET -> {
                var weather = GameData.getWeatherDataMap().get(resource.id);
                yield weather != null && weather.getGadgetID() > 0 && weather.getSceneID() == resource.scene;
            }
            default -> false;
        };
    }

    private static boolean applyTemporary(Player player, int mainId, Resource resource) {
        var state = player.getQuestSceneOverrides();
        if (resource.kind == TEMP_WEATHER_GADGET) state.rememberWeatherBeforeTemporary(resource.scene,
                player.getSceneId() == resource.scene ? player.getWeatherId() : 0);
        state.putTemporaryResource(mainId, resource.scene, resource.kind, resource.id, resource.enabled);
        player.save();
        notifyResource(player, resource);
        return true;
    }

    public static boolean delete(Player player, int scene, int kind, int id) {
        if (scene <= 0 || id < 0 || kind != 0 && !supportedTemporaryKind(kind)) return false;
        var removed = player.getQuestSceneOverrides().clearTemporaryResources(scene, kind, id, 0);
        if (removed.isEmpty()) return true;
        var keys = new LinkedHashSet<Resource>();
        removed.forEach(value -> keys.add(new Resource(value.getSceneId(), value.getKind(), value.getResourceId(), false)));
        keys.forEach(resource -> notifyResource(player, resource));
        player.save();
        return true;
    }

    public static Set<Integer> effectiveUnlockedPoints(Player player, int scene) {
        var unlocked = new LinkedHashSet<>(player.getUnlockedScenePoints(scene));
        for (var value : player.getQuestSceneOverrides().temporaryResources()) {
            if (value.getSceneId() != scene || value.getKind() != TEMP_POINT) continue;
            if (value.isEnabled()) unlocked.add(value.getResourceId());
            else unlocked.remove(value.getResourceId());
        }
        return unlocked;
    }

    public static boolean isPointUnlocked(Player player, int scene, int point) {
        var value = player.getQuestSceneOverrides().temporaryValue(scene, TEMP_POINT, point);
        return value != null ? value : player.getUnlockedScenePoints(scene).contains(point);
    }

    public static boolean isPointLocked(Player player, int scene, int point) {
        var value = player.getQuestSceneOverrides().temporaryValue(scene, TEMP_POINT, point);
        return value != null ? !value : player.getForceLockedScenePoints(scene).contains(point);
    }

    public static Map<Integer, Set<Integer>> effectiveSceneTags(Player player) {
        Map<Integer, Set<Integer>> result = new HashMap<>();
        player.getSceneTags().forEach((scene, tags) -> result.put(scene, new HashSet<>(tags)));
        for (var value : player.getQuestSceneOverrides().temporaryResources()) {
            if (value.getKind() != TEMP_SCENE_TAG) continue;
            var tags = result.computeIfAbsent(value.getSceneId(), ignored -> new HashSet<>());
            if (value.isEnabled()) tags.add(value.getResourceId());
            else tags.remove(value.getResourceId());
        }
        return emu.grasscutter.game.quest.scenery.QuestSceneryRuntime.effectiveSceneTags(player,result);
    }

    public static int effectiveWeatherGadget(Player player, int scene, int permanentWeather) {
        var resources = player.getQuestSceneOverrides().temporaryResources();
        var seen = new HashSet<Integer>();
        for (int i = resources.size() - 1; i >= 0; i--) {
            var value = resources.get(i);
            if (value.getSceneId() != scene || value.getKind() != TEMP_WEATHER_GADGET
                    || !seen.add(value.getResourceId())) continue;
            if (value.isEnabled()) return value.getResourceId();
        }
        var override = player.getQuestSceneOverrides().temporaryValue(scene, TEMP_WEATHER_GADGET, permanentWeather);
        return Boolean.FALSE.equals(override) ? 0 : permanentWeather;
    }

    /** Called after entry as well so a persisted TEMP weather override resumes after relogin. */
    public static void refreshTemporaryWeather(Player player) {
        if (player.getScene() == null || !player.hasSentLoginPackets()) return;
        int scene = player.getScene().getId();
        var state = player.getQuestSceneOverrides();
        int base = state.weatherBeforeTemporary(scene, player.getWeatherId());
        int effective = effectiveWeatherGadget(player, scene, base);
        if (effective != player.getWeatherId()) player.setWeather(effective,
                effective == 0 ? ClimateType.CLIMATE_SUNNY : ClimateType.CLIMATE_NONE);
        state.retireWeatherBaseline(scene);
    }

    private static void notifyResource(Player player, Resource resource) {
        if (resource.kind == TEMP_POINT) {
            if (isPointUnlocked(player, resource.scene, resource.id) && !isPointLocked(player, resource.scene, resource.id))
                player.sendPacket(new PacketScenePointUnlockNotify(resource.scene, resource.id));
            else player.sendPacket(PacketScenePointUnlockNotify.lock(resource.scene, resource.id));
        } else if (resource.kind == TEMP_SCENE_TAG) {
            player.sendPacket(new PacketPlayerWorldSceneInfoListNotify(player));
        } else {
            refreshTemporaryWeather(player);
        }
    }
}
