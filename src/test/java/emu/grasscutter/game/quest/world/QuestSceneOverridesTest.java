package emu.grasscutter.game.quest.world;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.ScenePointEntry;
import emu.grasscutter.data.common.PointData;
import emu.grasscutter.data.excels.avatar.AvatarCostumeData;
import emu.grasscutter.data.excels.dungeon.DungeonData;
import emu.grasscutter.game.avatar.Avatar;
import emu.grasscutter.game.player.TeamManager;
import emu.grasscutter.game.world.TestWorld.TestPlayer;
import emu.grasscutter.net.proto.GetScenePointRspOuterClass.GetScenePointRsp;
import emu.grasscutter.net.proto.ScenePointUnlockNotifyOuterClass.ScenePointUnlockNotify;
import emu.grasscutter.server.packet.send.PacketQuestScenePointVisibilityNotify;
import emu.grasscutter.utils.JsonUtils;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

@ExtendWith(ServerResourceFixture.class)
@ResourceLock("GameData")
final class QuestSceneOverridesTest {
    private final Map<Integer, ScenePointEntry> points = new HashMap<>(GameData.getScenePointEntryMap());
    private final Map<Integer, DungeonData> dungeons = new HashMap<>(GameData.getDungeonDataMap());
    private final Map<Integer, AvatarCostumeData> costumes = new HashMap<>(GameData.getAvatarCostumeDataMap());

    @AfterEach
    void restore() {
        GameData.getScenePointEntryMap().clear();
        GameData.getScenePointEntryMap().putAll(points);
        GameData.getDungeonDataMap().clear();
        GameData.getDungeonDataMap().putAll(dungeons);
        GameData.getAvatarCostumeDataMap().clear();
        GameData.getAvatarCostumeDataMap().putAll(costumes);
    }

    @Test
    void pointVisibilityPersistsSeparatelyFromUnlockAndBelongsToOnePlayer() {
        var owner = new StoryPlayer();
        var other = new StoryPlayer();
        point(3, 831);
        assertTrue(QuestSceneOverrideRuntime.setPointVisibility(owner, 3, List.of(831), true));
        assertEquals(Set.of(831), owner.getQuestSceneOverrides().revealedPoints(3));
        assertTrue(owner.getUnlockedScenePoints(3).isEmpty());
        assertTrue(other.getQuestSceneOverrides().revealedPoints(3).isEmpty());
        assertTrue(QuestSceneOverrideRuntime.setPointVisibility(owner, 3, List.of(831), false));
        assertTrue(owner.getQuestSceneOverrides().revealedPoints(3).isEmpty());
        assertEquals(Set.of(831), owner.getQuestSceneOverrides().hiddenPoints(3));
    }

    @Test
    void invalidPointInBatchDoesNotPartiallyRevealOtherPoints() {
        var owner = new StoryPlayer();
        point(10, 4);
        assertFalse(QuestSceneOverrideRuntime.setPointVisibility(owner, 10, List.of(4, 9), true));
        assertTrue(owner.getQuestSceneOverrides().revealedPoints(10).isEmpty());
        assertEquals(List.of(4, 9, 20), QuestSceneOverrideRuntime.parsePointIds("4,9,20,4"));
        assertThrows(IllegalArgumentException.class, () -> QuestSceneOverrideRuntime.parsePointIds("4,0"));
    }

    @Test
    void unhideNotificationUsesNativeWireEightWithoutUnlockingOrLocking() throws Exception {
        var packet = new PacketQuestScenePointVisibilityNotify(3, List.of(831), true);
        var proto = ScenePointUnlockNotify.parseFrom(packet.getData());
        assertEquals(List.of(831), proto.getHBKHEKLMEKNList());
        assertTrue(proto.getPointListList().isEmpty());
        assertTrue(proto.getUnhidePointListList().isEmpty());
        assertTrue(proto.getHidePointListList().isEmpty());
        assertEquals(8, ScenePointUnlockNotify.HBKHEKLMEKN_FIELD_NUMBER);
    }

    @Test
    void reconnectSnapshotUsesNativeRevealFieldWithoutUnlocking() {
        var owner = new StoryPlayer();
        owner.getQuestSceneOverrides().setPointVisibility(3, List.of(831), true);
        owner.getQuestSceneOverrides().setPointVisibility(3, List.of(832), false);
        var proto = GetScenePointRsp.newBuilder();
        QuestSceneOverrideRuntime.addPointVisibilitySnapshot(owner, 3, proto);
        assertEquals(List.of(831), proto.getHBKHEKLMEKNList());
        assertEquals(List.of(832), proto.getHidePointListList());
        assertTrue(proto.getUnlockedPointListList().isEmpty());
        assertTrue(proto.getUnhidePointListList().isEmpty());
        assertEquals(14, GetScenePointRsp.HBKHEKLMEKN_FIELD_NUMBER);
    }

    @Test
    void dungeonExitOverrideCanBeSetBeforeEntryWithoutChangingOtherPlayers() {
        var owner = new StoryPlayer();
        var other = new StoryPlayer();
        GameData.getDungeonDataMap().put(1314, JsonUtils.decode("{\"id\":1314}", DungeonData.class));
        var nativePoint = point(3, 2061);
        assertTrue(QuestSceneOverrideRuntime.setDungeonExit(owner, 1314, 3, 2061));
        assertSame(nativePoint, QuestSceneOverrideRuntime.resolveDungeonExit(owner, 1314));
        assertNull(QuestSceneOverrideRuntime.resolveDungeonExit(other, 1314));
        assertFalse(QuestSceneOverrideRuntime.setDungeonExit(owner, 1314, 3, 999999));
        assertSame(nativePoint, QuestSceneOverrideRuntime.resolveDungeonExit(owner, 1314));
        assertEquals(1, nativePoint.getPointData().getTranPos().getX());
    }

    @Test
    void storyCostumeChecksCharacterAndDoesNotAwardWardrobeOwnership() {
        var owner = new StoryPlayer();
        var avatar = new StoryAvatar(10000005);
        owner.getAvatars().getAvatars().put(avatar.getAvatarId(), avatar);
        GameData.getAvatarCostumeDataMap().put(200501, JsonUtils.decode(
                "{\"skinId\":200501,\"characterId\":10000005,\"itemId\":340023}", AvatarCostumeData.class));
        GameData.getAvatarCostumeDataMap().put(200701, JsonUtils.decode(
                "{\"skinId\":200701,\"characterId\":10000007,\"itemId\":340024}", AvatarCostumeData.class));
        assertFalse(QuestSceneOverrideRuntime.changeCostume(owner, 10000005, 200701));
        assertEquals(0, avatar.getCostume());
        assertTrue(QuestSceneOverrideRuntime.changeCostume(owner, 10000005, 200501));
        assertEquals(200501, avatar.getCostume());
        assertEquals(1, avatar.saves);
        assertFalse(owner.getCostumeList().contains(200501));
        assertTrue(QuestSceneOverrideRuntime.changeCostume(owner, 10000007, 200701));
        assertEquals(200501, avatar.getCostume());
        assertTrue(QuestSceneOverrideRuntime.changeCostume(owner, 10000005, 0));
        assertEquals(0, avatar.getCostume());
    }

    @Test
    void mapLayerQuestLocksOverrideDefaultUnlockAllAndCanBeReopened() {
        var state = new QuestSceneOverrides();
        assertEquals(List.of(1901, 1902, 1903), state.unlockedMapLayers(List.of(1901, 1902, 1903)));
        state.setMapLayerUnlocks(List.of(1902), false);
        assertEquals(List.of(1901, 1903), state.unlockedMapLayers(List.of(1901, 1902, 1903)));
        state.setMapLayerUnlocks(List.of(1902), true);
        assertEquals(Set.of(1901, 1902, 1903), Set.copyOf(state.unlockedMapLayers(List.of(1901, 1902, 1903))));
        assertEquals(List.of(3360102, 3360103, 3360104, 3360701, 3360702, 3360703),
                QuestSceneOverrideRuntime.parseMapLayerIds("3360102;3360103;3360104;3360701;3360702;3360703"));
        assertTrue(new QuestSceneOverrides().unlockedMapLayers(List.of()).isEmpty());
    }

    private static ScenePointEntry point(int scene, int id) {
        var data = JsonUtils.decode("{\"id\":" + id + ",\"tranPos\":{\"x\":1,\"y\":2,\"z\":3}}", PointData.class);
        var point = new ScenePointEntry(scene, data);
        GameData.getScenePointEntryMap().put((scene << 16) + id, point);
        return point;
    }

    private static final class StoryPlayer extends TestPlayer {
        private final TeamManager team = new TeamManager(this);
        private final QuestSceneOverrides overrides = new QuestSceneOverrides();
        @Override public TeamManager getTeamManager() { return team; }
        @Override public QuestSceneOverrides getQuestSceneOverrides() { return overrides; }
        @Override public int getMainCharacterId() { return 10000005; }
        @Override public void save() {}
    }

    private static final class StoryAvatar extends Avatar {
        private final int avatarId;
        private int saves;
        private StoryAvatar(int id) { this.avatarId = id; }
        @Override public int getAvatarId() { return avatarId; }
        @Override public void save() { saves++; }
    }
}
