package emu.grasscutter.game.quest;

import emu.grasscutter.game.quest.enums.QuestState;
import emu.grasscutter.net.proto.ParentQuestOuterClass.ParentQuest;

/**
 * 7.1 compatibility for the active quest-marker group that can stall client scene entry.
 * Filters only client synchronization; quest progress, activation and rewards remain authoritative.
 */
public final class ClientQuestMarkers {
    private ClientQuestMarkers() {}

    public static boolean shouldSend(GameQuest quest) {
        if (quest.getState() != QuestState.QUEST_STATE_UNFINISHED) return true;
        if (quest.getSubQuestId() == 37001) return false;
        int pointId = switch (quest.getSubQuestId()) {
            case 30302 -> 3;
            case 30303 -> 4;
            default -> 0;
        };
        if (pointId == 0) return true;
        var parent = quest.getMainQuest();
        var owner = parent == null ? null : parent.getOwner();
        if (owner == null) return true;
        var points = owner.getUnlockedScenePoints().get(3);
        return points == null || !points.contains(pointId);
    }

    /** Retains every other parent/child field from the native serializer. */
    public static ParentQuest parentWithChildren(GameMainQuest quest) {
        var nativeParent = quest.toProto(true);
        var result = nativeParent.toBuilder().clearChildQuestList();
        for (var child : nativeParent.getChildQuestListList()) {
            var source = quest.getChildQuests().get(child.getQuestId());
            if (source == null || shouldSend(source)) result.addChildQuestList(child);
        }
        return result.build();
    }
}
