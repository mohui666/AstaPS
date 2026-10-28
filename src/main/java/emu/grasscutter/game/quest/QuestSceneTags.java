package emu.grasscutter.game.quest;

import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.scene.LevelTagData;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.scripts.constants.EventType;
import emu.grasscutter.scripts.data.ScriptArgs;
import emu.grasscutter.server.packet.send.PacketPlayerWorldSceneInfoListNotify;
import emu.grasscutter.game.quest.enums.*;
import java.util.*;

/** Level tags change one configured mutually exclusive environment, including its scene groups. */
public final class QuestSceneTags {
    private QuestSceneTags() {}

    public static List<Integer> current(Player player, int groupId) {
        var group = GameData.getLevelTagGroupDataMap().get(groupId);
        if (group == null) return List.of();
        return group.getLevelTagGroupList().stream().flatMap(list -> list.getLevelTagIdList().stream())
                .filter(id -> active(player, id)).toList();
    }

    public static boolean active(Player player, int tagId) {
        var tag = GameData.getLevelTagDataMap().get(tagId);
        if (tag == null) return false;
        var selected = player.getQuestSceneLevelTags();
        if (selected.contains(tagId)) return true;
        var peers = peers(tagId);
        if (peers.stream().anyMatch(selected::contains)) return false;
        var sceneTags = emu.grasscutter.game.quest.world.QuestTemporaryResourceRuntime.effectiveSceneTags(player)
                .getOrDefault(tag.getSceneId(), Set.of());
        // Existing saves already hold scene tags. Do not invent a default for tags without evidence.
        return (!tag.getAddSceneTagIdList().isEmpty() || !tag.getRemoveSceneTagIdList().isEmpty())
                && sceneTags.containsAll(tag.getAddSceneTagIdList())
                && Collections.disjoint(sceneTags, tag.getRemoveSceneTagIdList());
    }

    private static Set<Integer> peers(int tagId) {
        var result = new HashSet<Integer>();
        result.add(tagId);
        for (var group : GameData.getLevelTagGroupDataMap().values())
            for (var list : group.getLevelTagGroupList())
                if (list.getLevelTagIdList().contains(tagId)) result.addAll(list.getLevelTagIdList());
        return result;
    }

    public static boolean change(Player player, int tagId) {
        if (player.getWorld() == null || player.getWorld().getHost() != player) return false;
        var target = GameData.getLevelTagDataMap().get(tagId);
        if (target == null) return false;
        var peers = peers(tagId);
        var previous = peers.stream().filter(id -> active(player, id)).toList();
        var sceneTags = player.getSceneTags().computeIfAbsent(target.getSceneId(), ignored -> new HashSet<>());
        var selected = player.getQuestSceneLevelTags();
        boolean changed = selected.removeIf(id -> id != tagId && peers.contains(id));
        changed |= selected.add(tagId);
        changed |= sceneTags.removeAll(target.getRemoveSceneTagIdList());
        changed |= sceneTags.addAll(target.getAddSceneTagIdList());
        if (!changed) return true;
        player.save();
        player.getWorld().getPlayers().forEach(member ->
                member.sendPacket(new PacketPlayerWorldSceneInfoListNotify(player)));
        var scene = player.getScene();
        if (scene != null && scene.getId() == target.getSceneId()) {
            scene.getScriptManager().whenInitialized(ready -> {
                if (!ready || !active(player, tagId)) return;
                var retained = new HashSet<>(target.getLoadDynamicGroupList());
                for (int old : previous) {
                    var data = GameData.getLevelTagDataMap().get(old);
                    for (int groupId : data.getLoadDynamicGroupList())
                        if (!retained.contains(groupId)
                                && scene.getScriptManager().getGroupInstanceById(groupId) != null)
                            scene.unregisterDynamicGroup(groupId);
                }
                for (int groupId : retained) scene.loadDynamicGroup(groupId);
                scene.getScriptManager().callEvent(new ScriptArgs(0, EventType.EVENT_LEVEL_TAG_CHANGE).setParam2(tagId));
            });
        }
        player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_SCENE_LEVEL_TAG_EQ, tagId);
        player.getQuestManager().queueEvent(QuestCond.QUEST_COND_SCENE_LEVEL_TAG_EQ, tagId);
        return true;
    }

    public static boolean matchesCurrent(GameQuest quest, int tagId) {
        var player = quest.getOwner();
        var tag = GameData.getLevelTagDataMap().get(tagId);
        return tag != null && player.getScene() != null && player.getSceneId() == tag.getSceneId()
                && player.getScene().getId() == player.getSceneId()
                && player.getSceneLoadState() == Player.SceneLoadState.LOADED
                && active(player.getScene().getWorld().getHost(), tagId);
    }

    public static void onSceneLoaded(Player player) {
        if (player.getWorld().getHost() != player) return;
        var scene = player.getScene();
        for (var tag : GameData.getLevelTagDataMap().values()) {
            if (tag.getSceneId() != scene.getId() || !active(player, tag.getId())) continue;
            scene.getScriptManager().whenInitialized(ready -> {
                if (ready && active(player, tag.getId()))
                    tag.getLoadDynamicGroupList().forEach(scene::loadDynamicGroup);
            });
        }
    }
}
