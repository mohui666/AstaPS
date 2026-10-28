package emu.grasscutter.game.quest.world;

import com.google.gson.*;
import emu.grasscutter.data.GameData;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.SceneType;
import emu.grasscutter.game.quest.enums.QuestState;
import emu.grasscutter.net.proto.LimitedRegionInfo._LimitedRegionInfo;
import emu.grasscutter.server.packet.send.*;
import emu.grasscutter.utils.FileUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

/** The original creator-owned scene effects used by the three reviewed story steps. */
public final class QuestSceneMechanicsRuntime {
    private static final int REGION=31001001;
    private static final String REGION_NAME="BigWorld_GraffitiIsland_OpenArea_001";
    private static final String UNLOCKABLE_MP="SCENE_MP_DEFAULT_SINGLE_CAN_UNLOCK_MP";
    private QuestSceneMechanicsRuntime() {}

    private static Player creator(Player player) {
        return player.getWorld()!=null&&player.getWorld().getHost()!=null?player.getWorld().getHost():player;
    }

    private static boolean finished(Player player,int id) {
        var quest=player.getQuestManager().getQuestById(id);
        return quest!=null&&quest.getState()==QuestState.QUEST_STATE_FINISHED;
    }

    private static Set<Integer> unlockedMps(Player player) {
        var result=new TreeSet<>(player.getQuestSceneMechanics().unlockedSceneMps());
        // This actual completed final step already contains UNLOCK_SCENE_MP(103).
        if (finished(player,7609623)) result.add(103);
        return result;
    }

    public static boolean unlockMultiplayer(Player player,int sceneId) {
        var data=GameData.getSceneDataMap().get(sceneId);
        if (data==null||data.getSceneType()!=SceneType.SCENE_WORLD||!UNLOCKABLE_MP.equals(data.getMpType())) return false;
        if (player.getQuestSceneMechanics().unlockMp(sceneId)) player.save();
        player.sendPacket(new PacketSceneMpUnlockNotify(unlockedMps(player)));
        return true;
    }

    /** This resource's default-single rule is independent of ordinary world multiplayer admission. */
    public static boolean isMultiplayerBlocked(Player player) {
        var data=GameData.getSceneDataMap().get(player.getSceneId());
        return data!=null&&UNLOCKABLE_MP.equals(data.getMpType())
                &&!unlockedMps(creator(player)).contains(data.getId());
    }

    public static boolean setSceneVariable(Player player,int sceneId,int hash,float value) {
        var scene=GameData.getSceneDataMap().get(sceneId);
        if (scene==null||scene.getSceneType()!=SceneType.SCENE_WORLD||hash==0||!Float.isFinite(value)) return false;
        player.getQuestSceneMechanics().setVariable(sceneId,hash,value);
        player.save();
        if (creator(player)==player&&player.getWorld()!=null)
            player.getWorld().getPlayers().stream().filter(p->p.getSceneId()==sceneId)
                    .forEach(p->p.sendPacket(new PacketSceneVarNotify(sceneId,Map.of(hash,value),false)));
        return true;
    }

    private static final class RegionData {
        static final JsonObject VALUE=load();
        private static JsonObject load() {
            try {
                var rows=JsonParser.parseString(Files.readString(FileUtils.getResourcePath(
                        "ExcelBinOutput/LimitRegionExcelConfigData.json"))).getAsJsonArray();
                for (var value:rows) {
                    var row=value.getAsJsonObject();
                    if (row.get("id").getAsInt()==REGION) return row;
                }
                throw new IllegalStateException("Missing native limit region "+REGION);
            } catch (IOException error) {
                throw new IllegalStateException("Cannot load native limit region",error);
            }
        }
    }

    private static boolean supportedRegion(int id) {
        if (id!=REGION) return false;
        var row=RegionData.VALUE;
        return row.get("sceneId").getAsInt()==3&&REGION_NAME.equals(row.get("name").getAsString())
                &&"LIMIT_REGION_TYPE_BIGWORLD".equals(row.get("type").getAsString())
                &&"LIMIT_REGION_SHARE_TYPE_CREATOR".equals(row.get("DLIDHHPNEON").getAsString())
                &&Files.isRegularFile(FileUtils.getResourcePath("BinOutput/LevelDesign/SimplePolygons/"+REGION_NAME+".json"));
    }

    public static boolean setLimitRegion(Player player,int id,int enabled) {
        if (enabled<0||enabled>1||!supportedRegion(id)) return false;
        player.getQuestSceneMechanics().setRegionEnabled(id,enabled==1);
        player.save();
        if (creator(player)==player&&player.getWorld()!=null) {
            for (var member:player.getWorld().getPlayers()) {
                if (member.getSceneId()==3) member.sendPacket(new PacketSceneDataNotify(member));
            }
        }
        return true;
    }

    /** Native PDNHECBDBOB list11 contains enabled LimitRegionExcel IDs, not world-area numbers. */
    public static _LimitedRegionInfo limitRegions(Player player,int sceneId) {
        var result=_LimitedRegionInfo.newBuilder();
        if (sceneId!=3||!supportedRegion(REGION)) return result.build();
        var owner=creator(player);
        boolean initial=RegionData.VALUE.has("JEAMJBPPGBA")&&RegionData.VALUE.get("JEAMJBPPGBA").getAsBoolean();
        boolean enabled=owner.getQuestSceneMechanics().regionEnabled(REGION,initial&&!finished(owner,1504301));
        if (enabled) result.addLimitedRegionList(REGION);
        return result.build();
    }

    /** Scene init occurs after the world creator is known; full snapshots survive a new client session. */
    public static void notifySceneEntry(Player player) {
        var owner=creator(player);
        player.sendPacket(new PacketSceneMpUnlockNotify(unlockedMps(owner)));
        player.sendPacket(new PacketSceneVarNotify(player.getSceneId(),owner.getQuestSceneMechanics()
                .variables(player.getSceneId()),true));
    }
}
