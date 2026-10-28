package emu.grasscutter.game.quest.coop;

import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.dungeon.DungeonData;
import emu.grasscutter.game.dungeons.enums.DungeonInvolveType;
import emu.grasscutter.game.dungeons.enums.DungeonType;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.SceneType;
import emu.grasscutter.game.quest.GameMainQuest;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.enums.QuestState;
import emu.grasscutter.game.quest.persistent.QuestPersistentDungeonRuntime;
import emu.grasscutter.game.world.Scene;
import emu.grasscutter.game.world.Position;

/** The two real Bennett plot instances, entered from the original Kaeya temple point 3/1. */
public final class CoopDungeonRuntime {
    private CoopDungeonRuntime() {}

    public static boolean isDungeon(DungeonData data) {
        return data != null && data.getType() == DungeonType.DUNGEON_PLOT
                && data.getInvolveType() == DungeonInvolveType.INVOLVE_ONLY_SINGLE
                && (data.getId() == 1111 && data.getSceneId() == 20035
                        || data.getId() == 1112 && data.getSceneId() == 20036);
    }

    /** Does not require a World: the login scene decision runs before World construction. */
    public static boolean ownsRun(Player player, DungeonData data) {
        if (!isDungeon(data) || player.getHangouts() == null) return false;
        var run = player.getHangouts().getRun();
        if (run == null || run.getChapterId() != 103201 || run.getCurrent() == null
                || run.getCurrent().getStatus() != 1) return false;
        int mainId = data.getId() == 1111 ? 19044 : 19045;
        int entryId = data.getId() == 1111 ? 1904403 : 1904501;
        var main = player.getQuestManager().getMainQuestById(mainId);
        var entry = main == null ? null : main.getChildQuestById(entryId);
        return main != null && !main.isFinished() && entry != null
                && (entry.getState() == QuestState.QUEST_STATE_UNFINISHED
                        || entry.getState() == QuestState.QUEST_STATE_FINISHED);
    }

    public static boolean validOrigin(DungeonData data, int sceneId, int pointId) {
        if (!isDungeon(data) || sceneId != 3 || pointId != 1) return false;
        var world = GameData.getSceneDataMap().get(3);
        var entry = GameData.getScenePointEntryById(3, 1);
        if (world == null || world.getSceneType() != SceneType.SCENE_WORLD || entry == null
                || entry.getSceneId() != 3 || entry.getPointData() == null) return false;
        var point = entry.getPointData();
        return point.getId() == 1 && "DungeonEntry".equals(point.getType())
                && finite(point.getPos()) && finite(point.getTranPos());
    }

    private static boolean finite(Position position) {
        return position != null && Float.isFinite(position.getX()) && Float.isFinite(position.getY())
                && Float.isFinite(position.getZ());
    }

    public static void onDungeonStarted(Scene scene) {
        if (scene == null || scene.getDungeonManager() == null || scene.getWorld() == null) return;
        var owner = scene.getWorld().getHost();
        if (owner == null || owner.getScene() != scene || owner.getWorld() != scene.getWorld()
                || scene.getWorld().getPlayers().size() != 1
                || !ownsRun(owner, scene.getDungeonManager().getDungeonData())
                || !validOrigin(scene.getDungeonManager().getDungeonData(), scene.getPrevScene(), scene.getPrevScenePoint())
                || !scene.getDungeonManager().capturePersistentProgress().isStarted()) return;
        owner.getPersistentDungeons().activate(scene.getId());
        scene.setDontDestroyWhenEmpty(true);
        QuestPersistentDungeonRuntime.capture(scene);
    }

    /** Arm suite capture before loading the scene; only a real started manager can restore. */
    public static void onQuestStarted(GameQuest quest) {
        int dungeonId = quest.getSubQuestId() == 1904403 ? 1111 : quest.getSubQuestId() == 1904501 ? 1112 : 0;
        var data = GameData.getDungeonDataMap().get(dungeonId);
        if (data != null && ownsRun(quest.getOwner(), data)
                && quest.getOwner().getPersistentDungeons().activate(data.getSceneId())) quest.getOwner().save();
    }

    public static boolean hasRestoredInstance(GameMainQuest main) {
        var player = main.getOwner();
        var scene = player.getScene();
        if (scene == null || scene.getWorld() != player.getWorld() || scene.getWorld().getHost() != player
                || scene.getDungeonManager() == null || !scene.getDungeonManager().isPersistentProgressRestored()
                || !ownsRun(player, scene.getDungeonManager().getDungeonData())
                || !QuestPersistentDungeonRuntime.canRestoreLogin(player)) return false;
        var progress = player.getPersistentDungeons().getRun(scene.getId()).getProgress();
        return progress.getClaimRunToken().equals(scene.getDungeonManager().capturePersistentProgress().getClaimRunToken());
    }

    /** A missing/invalid private instance restarts its real entry step, never its internal puzzle. */
    public static boolean recoverOnLogin(GameMainQuest main) {
        if (main.getParentQuestId() != 19044 && main.getParentQuestId() != 19045) return false;
        var player = main.getOwner();
        var data = GameData.getDungeonDataMap().get(main.getParentQuestId() == 19044 ? 1111 : 1112);
        if (!ownsRun(player, data)) return false;
        if (hasRestoredInstance(main)) return true;
        var entry = main.getChildQuestById(main.getParentQuestId() == 19044 ? 1904403 : 1904501);
        if (entry == null || entry.getState() == QuestState.QUEST_STATE_UNFINISHED) return false;
        var run = player.getHangouts().getRun();
        var point = player.getHangouts().getPoints().get(run.getPointId());
        if (point == null || point.getStart() == null) return false;
        run.setCurrent(point.getStart().copy());
        run.getQuestStarts().clear();
        main.rewindTo(entry, true);
        player.save();
        return true;
    }

    /** Starting another route cannot leave the prior route's private scene/receipt active. */
    public static void clear(Player player, int chapter) {
        if (chapter != 103201) return;
        for (int sceneId : new int[] {20035, 20036}) {
            player.getPersistentDungeons().clear(sceneId);
            if (player.getWorld() == null) continue;
            var scene = player.getWorld().getScenes().get(sceneId);
            if (scene != null) scene.setDontDestroyWhenEmpty(false);
        }
        var scene = player.getScene();
        if (scene != null && scene.getDungeonManager() != null
                && isDungeon(scene.getDungeonManager().getDungeonData()))
            player.getServer().getDungeonSystem().exitDungeon(player);
    }
}
