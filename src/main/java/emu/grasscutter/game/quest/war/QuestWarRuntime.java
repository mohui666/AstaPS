package emu.grasscutter.game.quest.war;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.enums.QuestContent;
import emu.grasscutter.game.world.Scene;
import java.util.concurrent.ThreadLocalRandom;

/** Native war actions mutate only the owner of the relevant quest/world. */
public final class QuestWarRuntime {
    private QuestWarRuntime() {}

    public static boolean matchesCurrent(GameQuest quest, QuestData.QuestContentCondition condition) {
        var player = quest.getOwner();
        int[] params = condition.getParam();
        if (params == null || params.length < 2 || !inWarScene(player)
                || !ReconstructedWarRuntime.hasStateProof(quest,condition)) return false;
        var catalog = QuestWarCatalog.installed();
        return switch (condition.getType()) {
            case QUEST_CONTENT_ABYSS_WAR_LEVEL_STATE_EQUAL -> catalog.hasLevel(params[0])
                    && player.getQuestWar().getLevelState(params[0]) == params[1];
            case QUEST_CONTENT_ABYSS_WAR_LIMIT_REIGON_STATE_EQUAL -> catalog.hasRegion(params[0])
                    && player.getQuestWar().getRegionState(params[0]) == params[1];
            default -> false;
        };
    }

    public static boolean setLevels(Player player, int[] ids, int state) {
        if (!ownWorld(player) || !validState(state) || ids == null || ids.length == 0) return false;
        var catalog = QuestWarCatalog.installed();
        for (int id : ids) if (!catalog.hasLevel(id)) return false;
        if (player.getQuestWar().setLevels(ids, state)) player.save();
        for (int id : ids) player.getQuestManager().queueEvent(
                QuestContent.QUEST_CONTENT_ABYSS_WAR_LEVEL_STATE_EQUAL, id, state);
        return true;
    }

    public static boolean setRegions(Player player, int[] ids, int state) {
        if (!ownWorld(player) || !validState(state) || ids == null || ids.length == 0) return false;
        var catalog = QuestWarCatalog.installed();
        for (int id : ids) if (!catalog.hasRegion(id)) return false;
        if (player.getQuestWar().setRegions(ids, state)) player.save();
        for (int id : ids) player.getQuestManager().queueEvent(
                QuestContent.QUEST_CONTENT_ABYSS_WAR_LIMIT_REIGON_STATE_EQUAL, id, state);
        return true;
    }

    public static boolean addAccount(GameQuest quest, int accountId, String range) {
        var player = quest.getOwner();
        if (!ownWorld(player) || !QuestWarCatalog.installed().hasAccount(accountId)) return false;
        int[] bounds = parseRange(range);
        int delta = (int) ThreadLocalRandom.current().nextLong(bounds[0], (long) bounds[1] + 1);
        if (player.getQuestWar().addAccountOnce(quest.getSubQuestId(), accountId, delta)) player.save();
        return true;
    }

    public static int[] parseRange(String range) {
        String[] values = range.split(",", -1);
        if (values.length != 2) throw new IllegalArgumentException("Expected native war account min,max");
        int min = Integer.parseInt(values[0].trim()), max = Integer.parseInt(values[1].trim());
        if (min > max) throw new IllegalArgumentException("Inverted native war account range");
        return new int[] {min,max};
    }

    public static boolean finishPerformance(GameQuest quest) {
        if (!ownWorld(quest.getOwner())) return false;
        if (quest.getOwner().getQuestWar().finishPerformance()) quest.getOwner().save();
        return true;
    }

    /** Called only by an actual server scene script; no client completion flag is trusted here. */
    public static int setScriptLevelState(Scene scene, int id, int state) {
        var host = scene.getWorld().getHost();
        return host != null && host.getScene() == scene && inWarScene(host)
                && setLevels(host, new int[] {id}, state) ? 0 : -1;
    }

    public static int setScriptRegionState(Scene scene, int id, int state) {
        var host = scene.getWorld().getHost();
        return host != null && host.getScene() == scene && inWarScene(host)
                && setRegions(host, new int[] {id}, state) ? 0 : -1;
    }

    private static boolean validState(int state) { return state >= 0 && state <= 4; }
    private static boolean ownWorld(Player player) {
        return player.getWorld() != null && player.getWorld().getHost() == player;
    }
    private static boolean inWarScene(Player player) {
        return ownWorld(player) && player.getSceneId() == QuestWarCatalog.installed().sceneId()
                && player.getScene() != null && player.getScene().getId() == player.getSceneId()
                && player.getSceneLoadState() == Player.SceneLoadState.LOADED;
    }
}
