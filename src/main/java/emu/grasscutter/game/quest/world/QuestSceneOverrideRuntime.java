package emu.grasscutter.game.quest.world;

import emu.grasscutter.GameConstants;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.ScenePointEntry;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.world.Position;
import emu.grasscutter.net.proto.GetScenePointRspOuterClass.GetScenePointRsp;
import emu.grasscutter.server.packet.send.PacketAvatarChangeCostumeNotify;
import emu.grasscutter.server.packet.send.PacketQuestScenePointVisibilityNotify;
import emu.grasscutter.server.packet.send.PacketPlayerWorldSceneInfoListNotify;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

public final class QuestSceneOverrideRuntime {
    private QuestSceneOverrideRuntime() {}

    public static boolean setDungeonExit(Player player, int dungeonId, int sceneId, int pointId) {
        var point = GameData.getScenePointEntryById(sceneId, pointId);
        if (!GameData.getDungeonDataMap().containsKey(dungeonId) || point == null
                || point.getPointData().getTranPos() == null) return false;
        player.getQuestSceneOverrides().setDungeonExit(dungeonId, sceneId, pointId);
        applyDungeonExitOverride(player, dungeonId);
        player.save();
        return true;
    }

    /** Apply both when entering and immediately before exiting the matching dungeon. */
    public static boolean applyDungeonExitOverride(Player player, int dungeonId) {
        var scene = player.getScene();
        if (scene == null || scene.getDungeonManager() == null
                || scene.getDungeonManager().getDungeonData().getId() != dungeonId) return false;
        var point = resolveDungeonExit(player, dungeonId);
        if (point == null || point.getPointData().getTranPos() == null) return false;
        scene.setPrevScene(point.getSceneId());
        scene.setPrevScenePoint(point.getPointData().getId());
        player.setPrevSceneForDungeon(point.getSceneId());
        player.setPrevPosForDungeon(new Position(point.getPointData().getTranPos()));
        return true;
    }

    public static ScenePointEntry resolveDungeonExit(Player player, int dungeonId) {
        var exit = player.getQuestSceneOverrides().getDungeonExit(dungeonId);
        return exit == null ? null : GameData.getScenePointEntryById(exit.getSceneId(), exit.getPointId());
    }

    public static List<Integer> parsePointIds(String parameter) {
        var points = new LinkedHashSet<Integer>();
        for (String value : parameter.split(",", -1)) {
            int id = Integer.parseInt(value.trim());
            if (id <= 0) throw new IllegalArgumentException("Invalid scene point id");
            points.add(id);
        }
        return List.copyOf(points);
    }

    public static boolean setPointVisibility(Player player, int sceneId, List<Integer> pointIds, boolean visible) {
        if (sceneId <= 0 || pointIds.isEmpty() || pointIds.stream()
                .anyMatch(point -> GameData.getScenePointEntryById(sceneId, point) == null)) return false;
        player.getQuestSceneOverrides().setPointVisibility(sceneId, pointIds, visible);
        player.sendPacket(new PacketQuestScenePointVisibilityNotify(sceneId, pointIds, visible));
        player.save();
        return true;
    }

    public static void addPointVisibilitySnapshot(Player player, int sceneId, GetScenePointRsp.Builder proto) {
        // HBKHEKLMEKN is the same unhide field name as ScenePointUnlockNotify, wire field 14 here.
        proto.addAllHBKHEKLMEKN(player.getQuestSceneOverrides().revealedPoints(sceneId));
        proto.addAllHidePointList(player.getQuestSceneOverrides().hiddenPoints(sceneId));
    }

    public static List<Integer> parseMapLayerIds(String parameter) {
        var layers = new LinkedHashSet<Integer>();
        for (String value : parameter.split(";", -1)) {
            int id = Integer.parseInt(value.trim());
            if (id <= 0) throw new IllegalArgumentException("Invalid map layer id");
            layers.add(id);
        }
        return List.copyOf(layers);
    }

    public static boolean setMapLayerState(Player player, List<Integer> layers, int unlocked) {
        if (unlocked < 0 || unlocked > 1 || layers.isEmpty()
                || layers.stream().anyMatch(id -> !GameData.getMapLayerDataMap().containsKey(id))) return false;
        player.getQuestSceneOverrides().setMapLayerUnlocks(layers, unlocked == 1);
        player.save();
        player.sendPacket(new PacketPlayerWorldSceneInfoListNotify(player));
        return true;
    }

    public static List<Integer> unlockedMapLayers(Player player, Collection<Integer> defaults) {
        return player.getQuestSceneOverrides().unlockedMapLayers(defaults);
    }

    public static boolean showMapLayerGroup(Player player, int groupId) {
        if (groupId <= 0 || !GameData.getMapLayerGroupDataMap().containsKey(groupId)) return false;
        if (player.getQuestSceneOverrides().showMapLayerGroup(groupId)) player.save();
        player.sendPacket(new PacketPlayerWorldSceneInfoListNotify(player));
        return true;
    }

    public static List<Integer> shownMapLayerGroups(Player player, Collection<Integer> defaults) {
        return player.getQuestSceneOverrides().shownMapLayerGroups(defaults);
    }

    /** Internal quest authority can equip a story outfit without granting wardrobe ownership. */
    public static boolean changeCostume(Player player, int avatarId, int costumeId) {
        if ((avatarId == GameConstants.MAIN_CHARACTER_MALE || avatarId == GameConstants.MAIN_CHARACTER_FEMALE)
                && avatarId != player.getMainCharacterId()) return true;
        var costume = GameData.getAvatarCostumeDataMap().get(costumeId);
        if (costumeId != 0 && (costume == null || costume.getCharacterId() != avatarId)) return false;
        var avatar = player.getTeamManager().getActivePartyAvatar(avatarId);
        if (avatar == null) return false;
        if (avatar.getCostume() == costumeId) return true;
        avatar.setCostume(costumeId);
        avatar.save();
        var entity = avatar.getAsEntity();
        if (entity != null && player.getWorld() != null)
            player.getWorld().broadcastPacket(new PacketAvatarChangeCostumeNotify(entity));
        return true;
    }
}
