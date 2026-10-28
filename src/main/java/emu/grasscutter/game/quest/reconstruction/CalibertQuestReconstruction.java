package emu.grasscutter.game.quest.reconstruction;

import emu.grasscutter.data.GameData;
import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.entity.gadget.GadgetWorktop;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.*;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.*;
import emu.grasscutter.game.world.*;
import emu.grasscutter.game.world.data.TeleportProperties;
import emu.grasscutter.net.proto.EnterTypeOuterClass.EnterType;
import emu.grasscutter.scripts.*;
import emu.grasscutter.server.event.player.PlayerTeleportEvent.TeleportType;
import emu.grasscutter.server.packet.send.PacketWorktopOptionNotify;
import java.util.*;

/** Authorized AstaPS reconstruction of missing Calibert levels; the original quest/text data stays intact. */
public final class CalibertQuestReconstruction {
    private static final int OPTION = 7;
    private static final Set<String> LUA_KEYS = Set.of("133302744", "22018000401", "22018000701",
            "22018000801", "22018000802", "801105", "801117", "801121", "801107", "801109", "801202");
    private static final Map<Integer,String> QUEST_KEYS = Map.of(
            801010,"22018000701", 801012,"22018000401", 801105,"801105", 801117,"801117",
            801121,"801121", 801107,"801107", 801109,"801109", 801202,"801202");
    private static final Map<Integer,Integer> DUNGEONS = Map.of(20180,1177,20178,1175,20181,1176);
    private record Journey(int scene, int group, int gadget, int region, int quest, String destination) {}
    private static final List<Journey> JOURNEYS = List.of(
            new Journey(20178,220178003,3001,3101,801105,"Q801116_N13072"),
            new Journey(20178,220178003,3002,3102,801117,"Q801120_N13072"),
            new Journey(20178,220178003,3003,3103,801121,"Q801106_N13072"),
            new Journey(20178,220178005,5001,5101,801109,"Q800109_guide"),
            new Journey(20181,220181001,1001,1101,801202,"Q801202_guide"));
    private record Battle(int scene, int group, int gadget, int quest, List<Integer> monsters) {}
    private static final List<Battle> BATTLES = List.of(
            new Battle(20180,220180004,4002,801012,List.of(4101,4102,4103)),
            new Battle(20178,220178004,4001,801107,List.of(4101)));
    private CalibertQuestReconstruction() {}

    public static boolean isServerOwnedLuaNotification(String key) { return key != null && LUA_KEYS.contains(key); }

    public static boolean isServerOwnedClientProgress(QuestContent type, int param) {
        return type == QuestContent.QUEST_CONTENT_LUA_NOTIFY
                        && isServerOwnedLuaNotification(Integer.toUnsignedString(param))
                || type == QuestContent.QUEST_CONTENT_ADD_QUEST_PROGRESS && param == 801032
                || type == QuestContent.QUEST_CONTENT_ENTER_DUNGEON && (param == 1175 || param == 1176);
    }

    /** The two original point requests are legitimate nested entries from the live outer instance. */
    public static boolean canUsePrivateEntry(Player player, int pointId, int dungeonId) {
        var scene = player == null ? null : player.getScene();
        return player != null && owner(scene) == player && scene.getId() == 20180
                && scene.getDungeonManager().capturePersistentProgress().isStarted()
                && emu.grasscutter.game.quest.persistent.QuestPersistentDungeonRuntime.isCalibertPrivateOrigin(
                        GameData.getDungeonDataMap().get(dungeonId), 20180, pointId)
                && (pointId == 2 && dungeonId == 1175 && (active(player,801114) || active(player,801103)
                                || unfinishedMain(player,8011) && emu.grasscutter.game.quest.persistent
                                        .QuestPersistentDungeonRuntime.canResumeCalibertDungeon(player,1175))
                        || pointId == 4 && dungeonId == 1176 && (active(player,801222) || active(player,801224)
                                || unfinishedMain(player,8012) && emu.grasscutter.game.quest.persistent
                                        .QuestPersistentDungeonRuntime.canResumeCalibertDungeon(player,1176)));
    }

    private static boolean unfinishedMain(Player player, int mainId) {
        return player.getQuestManager().getMainQuests().values().stream()
                .flatMap(main -> main.getActiveQuests().stream()).anyMatch(quest -> quest.getMainQuestId() == mainId);
    }

    /** Only a successfully restored native run can suppress this stage's destructive login rewind. */
    public static boolean shouldPreserveOnLogin(GameQuest quest) {
        if (quest == null || quest.getState() != QuestState.QUEST_STATE_UNFINISHED
                || quest.getMainQuestId() < 8010 || quest.getMainQuestId() > 8015) return false;
        var player = quest.getOwner();
        var scene = player.getScene();
        if (owner(scene) != player || !scene.getDungeonManager().isPersistentProgressRestored()
                || !emu.grasscutter.game.quest.persistent.QuestPersistentDungeonRuntime.canRestoreLogin(player)) return false;
        var saved=player.getPersistentDungeons().getRun(scene.getId()).getProgress();
        return saved.getClaimRunToken().equals(scene.getDungeonManager().capturePersistentProgress().getClaimRunToken());
    }

    private static Player owner(Scene scene) {
        if (scene == null || !DUNGEONS.containsKey(scene.getId())
                || scene.getSceneType() != SceneType.SCENE_DUNGEON || scene.getDungeonManager() == null
                || scene.getDungeonManager().getDungeonData() == null
                || !emu.grasscutter.game.quest.persistent.QuestPersistentDungeonRuntime.isCalibertDungeon(
                        scene.getDungeonManager().getDungeonData())
                || scene.getDungeonManager().getDungeonData().getId() != DUNGEONS.get(scene.getId())) return null;
        var player = scene.getWorld().getHost();
        return player != null && player.getWorld() == scene.getWorld() && player.getScene() == scene
                && player.getSceneId() == scene.getId() ? player : null;
    }

    private static Player worldOwner(Scene scene) {
        if (scene == null || scene.getId() != 3 || scene.getSceneType() != SceneType.SCENE_WORLD) return null;
        var player = scene.getWorld().getHost();
        return player != null && player.getWorld() == scene.getWorld() && player.getScene() == scene
                && player.getSceneId() == 3 ? player : null;
    }

    private static boolean storyActive(Player player) {
        return player.getQuestManager().getMainQuests().values().stream().flatMap(main -> main.getActiveQuests().stream())
                .anyMatch(quest -> quest.getMainQuestId() == 8010 || quest.getMainQuestId() == 8011 || quest.getMainQuestId() == 8012);
    }

    private static boolean hasRunningMain(Player player, int mainId) {
        return player.getQuestManager().getMainQuests().values().stream()
                .filter(main -> main.getState() != ParentQuestState.PARENT_QUEST_STATE_FINISHED)
                .flatMap(main -> main.getChildQuests().values().stream())
                .anyMatch(quest -> quest.getMainQuestId() == mainId && quest.getState() != QuestState.QUEST_STATE_UNSTARTED);
    }

    private static void startController(Player player, int questId) {
        var controller = player.getQuestManager().getQuestById(questId);
        if (controller == null || controller.getState() == QuestState.QUEST_STATE_UNSTARTED)
            player.getQuestManager().addQuest(questId);
    }

    /** Both the original DungeonEntry request and the custom pedestal reach this real lifecycle. */
    public static void onDungeonStarted(Scene scene) {
        var player = owner(scene);
        if (player == null
                || !scene.getDungeonManager().capturePersistentProgress().isStarted()) return;
        emu.grasscutter.game.quest.persistent.QuestPersistentDungeonRuntime.retainStartedCalibert(scene);
        if (scene.getId() == 20178) {
            if (hasRunningMain(player,8011)) startController(player,801401);
            return;
        }
        if (scene.getId() == 20181) {
            if (hasRunningMain(player,8012)) startController(player,801501);
            return;
        }
        if (scene.getId() != 20180 || finished(player,801211)) return;
        if (storyActive(player)) startController(player,801301);
        // DungeonManager queued ENTER1177 first. Its old event cannot fail a later 801302.
        if (active(player,801301)) complete(player,"133302744");
    }

    private static boolean active(Player player, int questId) {
        var quest = player.getQuestManager().getQuestById(questId);
        return quest != null && quest.getState() == QuestState.QUEST_STATE_UNFINISHED;
    }
    private static boolean finished(Player player, int questId) {
        var quest = player.getQuestManager().getQuestById(questId);
        return quest != null && quest.getState() == QuestState.QUEST_STATE_FINISHED;
    }
    private static int count(Player player, String key) {
        return Math.max(0,player.getPlayerProgress().getCurrentProgress(key));
    }
    private static String journeyKey(int quest) { return "asta.calibert.journey." + quest; }
    private static String battleKey(int quest, int config) { return "asta.calibert.battle." + quest + "." + config; }

    public static int progress(Scene scene, String key) {
        var player = owner(scene);
        return player == null || !isServerOwnedLuaNotification(key) ? -1 : count(player,key);
    }

    private static void emit(Player player, String key) {
        player.getQuestManager().queueEvent(QuestCond.QUEST_COND_LUA_NOTIFY,key,0);
        player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_LUA_NOTIFY,key,0);
    }
    private static boolean record(Player player, String key) {
        if (count(player,key) > 0) return false;
        player.getPlayerProgress().addToCurrentProgress(key,1);
        player.save();
        return true;
    }
    private static void complete(Player player, String key) {
        if (record(player,key)) emit(player,key);
    }

    private static void rearm(Player player, int group, int... configs) {
        var scene = player.getScene();
        if (owner(scene) != player) return;
        for (int config : configs) {
            var entity = scene.getEntityByConfigId(config,group);
            if (entity instanceof EntityGadget gadget && gadget.getContent() instanceof GadgetWorktop worktop
                    && !worktop.getWorktopOptions().contains(OPTION)) {
                worktop.addWorktopOptions(new int[]{OPTION});
                scene.broadcastPacket(new PacketWorktopOptionNotify(gadget));
            }
        }
    }

    /** Fresh accepts reset only this reconstruction's evidence; loaded active quests are not reset. */
    public static void onQuestStart(GameQuest quest) {
        int id = quest.getSubQuestId();
        var player = quest.getOwner();
        if (id == 801301) {
            player.getPlayerProgress().resetCurrentProgress("133302744");
            var scene = player.getScene();
            if (owner(scene) == player && scene.getId() == 20180
                    && scene.getDungeonManager().capturePersistentProgress().isStarted()) complete(player,"133302744");
            else player.save();
            return;
        }
        if (id == 801103 || id == 801224) {
            // The original table has two sequential acknowledgements of the same entry,
            // with a plot between them for 1176. Replay only the real started instance.
            var scene = player.getScene();
            int target = id == 801103 ? 20178 : 20181;
            if (owner(scene) == player && scene.getId() == target
                    && scene.getDungeonManager().capturePersistentProgress().isStarted())
                player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_ENTER_DUNGEON,DUNGEONS.get(target));
            return;
        }
        if (id == 801014 || id == 801031) {
            String key = id == 801014 ? "22018000801" : "22018000802";
            // These hidden steps are accepted after both actual mushrooms have been collected.
            if (count(player,key) > 0) emit(player,key);
            return;
        }
        if (id == 801032) {
            for (String key : List.of("801032","22018000801","22018000802"))
                player.getPlayerProgress().resetCurrentProgress(key);
            rearm(player,220180008,8001,8002);
            player.save();
        }
        String key = QUEST_KEYS.get(id);
        if (key == null) return;
        player.getPlayerProgress().resetCurrentProgress(key);
        player.getPlayerProgress().resetCurrentProgress(journeyKey(id));
        if (id == 801010) rearm(player,220180007,7001);
        for (var battle : BATTLES) if (battle.quest == id) {
            player.getPlayerProgress().resetCurrentProgress(battleKey(id,0));
            for (int config : battle.monsters) player.getPlayerProgress().resetCurrentProgress(battleKey(id,config));
            var scene = player.getScene();
            if (owner(scene) == player && scene.getId() == battle.scene)
                scene.getScriptManager().refreshGroupSuite(battle.group,1);
        }
        player.save();
    }

    /** Called only from this group's actual SELECT_OPTION callback. */
    public static int interact(SceneScriptManager scripts, int uid, int group, int config, int option) {
        var scene = scripts.getScene();
        synchronized (scene) {
            var player = scene.getId() == 3 ? worldOwner(scene) : owner(scene);
            var entity = scene.getEntityByConfigId(config,group);
            if (player == null || uid != player.getUid() || option != OPTION
                    || !(entity instanceof EntityGadget gadget)
                    || !(gadget.getContent() instanceof GadgetWorktop worktop)
                    || !worktop.getWorktopOptions().contains(option)
                    || player.getPosition().computeDistance(gadget.getPosition()) > 6) return -1;
            if (scene.getId() == 3 && group == 133302744 && config == 744001
                    && !finished(player,801211) && (storyActive(player) || active(player,801301))) {
                var entry = GameData.getScenePointEntryById(3,776);
                var data = GameData.getDungeonDataMap().get(1177);
                if (entry == null || entry.getPointData().getDungeonIds() == null
                        || Arrays.stream(entry.getPointData().getDungeonIds()).noneMatch(id -> id == 1177)
                        || data == null || data.getSceneId() != 20180) return -1;
                return player.getServer().getDungeonSystem().enterDungeon(player,776,1177,true) ? 0 : -1;
            }
            if (scene.getId() == 20180 && group == 220180004 && config == 4001) {
                if (!active(player,801005) && !active(player,801015)) return -1;
                if (player.getInventory().getItemCountById(120246) == 0
                        && !player.getInventory().addItem(120246,1,ActionReason.QuestItem)) return -1;
                // The second recipe step can legitimately already hold the first medicine.
                player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_OBTAIN_ITEM,120246,
                        player.getInventory().getItemCountById(120246));
                return 0;
            }
            if (scene.getId() == 20180 && group == 220180007 && config == 7001 && active(player,801010)) {
                // Giving801010 consumes the actual quest water after the following conversation.
                if (player.getInventory().getItemCountById(120247) == 0
                        && !player.getInventory().addItem(120247,1,ActionReason.QuestItem)) return -1;
                complete(player,"22018000701");
            } else if (scene.getId() == 20180 && group == 220180008 && (config == 8001 || config == 8002)
                    && active(player,801032)) {
                String key = config == 8001 ? "22018000801" : "22018000802";
                if (count(player,key) > 0 || !player.getInventory().addItem(120245,1,ActionReason.QuestItem)) return -1;
                complete(player,key);
                player.getProgressManager().addQuestProgress(801032,1);
            } else if (scene.getId() == 20180 && (group == 220180009 && config == 9001
                    || group == 220180010 && config == 10001)) {
                int dungeon = group == 220180009 ? 1175 : 1176;
                int pointId = group == 220180009 ? 2 : 4;
                if (!canUsePrivateEntry(player,pointId,dungeon)) return -1;
                var point = GameData.getScenePointEntryById(20180,pointId);
                var data = GameData.getDungeonDataMap().get(dungeon);
                if (point == null || point.getPointData().getDungeonIds() == null
                        || Arrays.stream(point.getPointData().getDungeonIds()).noneMatch(id -> id == dungeon)
                        || data == null || data.getSceneId() != (dungeon == 1175 ? 20178 : 20181)) return -1;
                return player.getServer().getDungeonSystem().enterDungeon(player,pointId,dungeon,true) ? 0 : -1;
            } else {
                var journey = JOURNEYS.stream().filter(j -> j.scene == scene.getId() && j.group == group
                        && j.gadget == config && active(player,j.quest)).findFirst().orElse(null);
                if (journey != null) {
                    var position = QuestSceneConditions.dummyPoint(scene.getId(),journey.destination);
                    var rotation = QuestSceneConditions.dummyRotation(scene.getId(),journey.destination);
                    if (position == null || !player.getWorld().transferPlayerToScene(player,TeleportProperties.builder()
                            .sceneId(scene.getId()).teleportType(TeleportType.SCRIPT).enterReason(EnterReason.Lua)
                            .enterType(EnterType.EnterType_ENTER_GOTO).teleportTo(position).teleportRot(rotation).build())) return -1;
                    record(player,journeyKey(journey.quest));
                    return 0; // Actual server region entry, after the move, emits the quest key.
                }
                var battle = BATTLES.stream().filter(b -> b.scene == scene.getId() && b.group == group
                        && b.gadget == config && active(player,b.quest)).findFirst().orElse(null);
                if (battle != null) {
                    if (battle.monsters.stream().anyMatch(id -> scene.getEntityByConfigId(id,group) instanceof EntityMonster)) return -1;
                    if (count(player,QUEST_KEYS.get(battle.quest)) > 0
                            && battle.monsters.stream().allMatch(id -> count(player,battleKey(battle.quest,id)) > 0)) {
                        emit(player,QUEST_KEYS.get(battle.quest));
                        return 0;
                    }
                    // A departed/unloaded partial fight is restarted as a complete real wave.
                    // Saved partial deaths cannot clear monsters that were never spawned in this attempt.
                    var instance = scripts.getGroupInstanceById(group);
                    if (instance == null) return -1;
                    if (instance.getActiveSuiteId() != 1 && SceneFlowSuites.refreshWorldQuest(scripts,group,1) != 0) return -1;
                    player.getPlayerProgress().resetCurrentProgress(battleKey(battle.quest,0));
                    for (int id : battle.monsters) player.getPlayerProgress().resetCurrentProgress(battleKey(battle.quest,id));
                    if (SceneFlowSuites.goTo(scripts,group,2) != 0) return -1;
                    if (!battle.monsters.stream().allMatch(id -> scene.getEntityByConfigId(id,group) instanceof EntityMonster)) return -1;
                    record(player,battleKey(battle.quest,0));
                    return 0;
                }
                boolean exit = scene.getId() == 20178 && group == 220178005 && config == 5002
                        && finished(player,801110) && count(player,"801107") > 0
                        || scene.getId() == 20181 && group == 220181001 && config == 1002
                        && finished(player,801203) && count(player,"801202") > 0;
                if (!exit) return -1;
                var data = GameData.getDungeonDataMap().get(1177);
                if (data == null || data.getSceneId() != 20180) return -1;
                scene.getDungeonManager().finishDungeon();
                if (!player.getServer().getDungeonSystem().enterDungeon(player,776,1177,false)) return -1;
                player.getScene().setPrevScene(3);
                player.getScene().setPrevScenePoint(776);
                return 0;
            }
            worktop.removeWorktopOption(option);
            scene.broadcastPacket(new PacketWorktopOptionNotify(gadget));
            return 0;
        }
    }

    /** A registered arrival volume must contain the actual owner's avatar after its manual gateway. */
    public static int entered(SceneScriptManager scripts, EntityAvatar avatar, int group, int config) {
        var scene = scripts.getScene();
        synchronized (scene) {
            var player = owner(scene);
            if (player == null || avatar == null || avatar.getPlayer() != player || avatar.getScene() != scene
                    || scene.getEntities().get(avatar.getId()) != avatar) return -1;
            var journey = JOURNEYS.stream().filter(j -> j.scene == scene.getId() && j.group == group
                    && j.region == config && active(player,j.quest)).findFirst().orElse(null);
            var region = scripts.getRegionByConfigId(group,config);
            if (journey == null || region == null || !region.getMetaRegion().contains(player.getPosition())
                    || count(player,journeyKey(journey.quest)) == 0) return -1;
            complete(player,Integer.toString(journey.quest));
            return 0;
        }
    }

    /** A real server death callback credits each member of the started wave once. */
    public static int monsterDied(SceneScriptManager scripts, int group, int config) {
        var scene = scripts.getScene();
        synchronized (scene) {
            var player = owner(scene);
            if (player == null || scene.getEntityByConfigId(config,group) != null) return -1;
            var battle = BATTLES.stream().filter(b -> b.scene == scene.getId() && b.group == group
                    && b.monsters.contains(config) && active(player,b.quest)).findFirst().orElse(null);
            if (battle == null || count(player,battleKey(battle.quest,0)) == 0) return -1;
            record(player,battleKey(battle.quest,config));
            if (battle.monsters.stream().allMatch(id -> count(player,battleKey(battle.quest,id)) > 0))
                complete(player,QUEST_KEYS.get(battle.quest));
            return 0;
        }
    }
}
