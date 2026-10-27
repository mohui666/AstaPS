package emu.grasscutter.server.packet.recv;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.OpenStateData;
import emu.grasscutter.data.excels.dungeon.DungeonEntryData;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.PlayerProperty;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.packet.PacketOpcodes;
import emu.grasscutter.net.proto.DungeonEntryBlockReasonOuterClass.DungeonEntryBlockReason;
import emu.grasscutter.net.proto.GetDungeonEntryExploreConditionReqOuterClass.GetDungeonEntryExploreConditionReq;
import emu.grasscutter.net.proto.GetDungeonEntryExploreConditionRspOuterClass.GetDungeonEntryExploreConditionRsp;
import emu.grasscutter.net.proto.OpenStateUpdateNotifyOuterClass.OpenStateUpdateNotify;
import emu.grasscutter.net.proto.SetOpenStateRspOuterClass.SetOpenStateRsp;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.packet.send.PacketOpenStateUpdateNotify;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@ExtendWith(ServerResourceFixture.class)
@ResourceLock("GameData")
final class AbyssEntranceAccessTest {
    private Map<Integer, DungeonEntryData> originalEntries;
    private OpenStateData originalTowerState;

    @BeforeEach
    void nativeEntrance() throws Exception {
        originalEntries = new HashMap<>(GameData.getDungeonEntryDataMap());
        originalTowerState = GameData.getOpenStateDataMap().remove(42);
        GameData.getDungeonEntryDataMap().clear();
        var rows = JsonParser.parseString(Files.readString(
                Path.of("resources/ExcelBinOutput/DungeonEntryExcelConfigData.json"))).getAsJsonArray();
        for (var row : rows) {
            if (row.getAsJsonObject().get("id").getAsInt() == 1001) {
                GameData.getDungeonEntryDataMap().put(1001, new Gson().fromJson(row, DungeonEntryData.class));
                break;
            }
        }
        var entry = GameData.getDungeonEntryDataMap().get(1001);
        assertNotNull(entry);
        assertEquals(20, entry.getLevelCondition(), "read the actual 7.1 entrance level field");
        assertEquals(0, entry.getQuestCondition());
    }

    @AfterEach
    void restoreResources() {
        GameData.getDungeonEntryDataMap().clear();
        GameData.getDungeonEntryDataMap().putAll(originalEntries);
        GameData.getOpenStateDataMap().remove(42);
        if (originalTowerState != null) GameData.getOpenStateDataMap().put(42, originalTowerState);
    }

    @ParameterizedTest
    @ValueSource(ints = {19, 20, 60})
    void nativePortalAlwaysAnswersAndOnlyReportsAnUnmetLevel(int level) throws Exception {
        var player = player(level);
        var req = request(3, 45, 1001);
        new HandlerGetDungeonEntryExploreConditionReq().handle(player.session, null, req.toByteArray());

        assertEquals(PacketOpcodes.GetDungeonEntryExploreConditionRsp, player.packets.getFirst().getOpcode());
        var response = GetDungeonEntryExploreConditionRsp.parseFrom(player.packets.getFirst().getData());
        assertEquals(0, response.getRetcode());
        if (level < 20) {
            assertEquals(DungeonEntryBlockReason.DungeonEntryBlockReason_DUNGEON_ENTRY_REASON_LEVEL,
                    response.getDungeonEntryCond().getCondReason());
            assertEquals(20, response.getDungeonEntryCond().getParam1());
            assertEquals(1, player.packets.size(), "a blocked entrance must not be announced as open");
        } else {
            assertEquals(DungeonEntryBlockReason.DungeonEntryBlockReason_DUNGEON_ENTRY_REASON_NONE,
                    response.getDungeonEntryCond().getCondReason());
            assertEquals(2, player.packets.size());
            assertEquals(PacketOpcodes.DungeonEntryToBeExploreNotify, player.packets.get(1).getOpcode());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {19, 20, 60})
    void missingTowerOpenStateIsDerivedFromNativeRequirementsAndSurvivesTheLoginPacket(int level)
            throws Exception {
        var player = player(level);
        player.getProgressManager().tryUnlockOpenStates(false);
        var response = OpenStateUpdateNotify.parseFrom(new PacketOpenStateUpdateNotify(player).getData());
        assertEquals(level >= 20 ? 1 : 0, response.getOpenStateMapMap().getOrDefault(42, 0));
        assertEquals(level >= 20 ? 1 : 0, player.getProgressManager().getOpenState(42));
        assertFalse(player.getOpenStates().containsKey(44), "first entry is still client-owned");
        assertTrue(player.getQuestManager().getMainQuests().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {19, 20, 60})
    void clientTowerOpenRequestUsesTheSameRankGate(int level) throws Exception {
        var player = player(level);
        player.getProgressManager().setOpenStateFromClient(42, 1);
        var last = player.packets.getLast();
        assertEquals(PacketOpcodes.SetOpenStateRsp, last.getOpcode());
        var response = SetOpenStateRsp.parseFrom(last.getData());
        assertEquals(level >= 20, response.getRetcode() == 0);
        assertEquals(level >= 20 ? 1 : 0, player.getProgressManager().getOpenState(42));
    }

    @Test
    void missingOrMismatchedEntrancesReturnFailureWithoutAnUnlockNotify() throws Exception {
        for (var req : List.of(request(3, 45, 999999), request(4, 45, 1001), request(3, 46, 1001))) {
            var player = player(60);
            new HandlerGetDungeonEntryExploreConditionReq().handle(player.session, null, req.toByteArray());
            assertEquals(1, player.packets.size());
            var response = GetDungeonEntryExploreConditionRsp.parseFrom(player.packets.getFirst().getData());
            assertNotEquals(0, response.getRetcode());
        }
        GameData.getDungeonEntryDataMap().clear();
        var player = player(60);
        player.getProgressManager().setOpenStateFromClient(42, 1);
        assertNotEquals(0, SetOpenStateRsp.parseFrom(player.packets.getLast().getData()).getRetcode());
        assertEquals(0, player.getProgressManager().getOpenState(42));
    }

    @Test
    void nativeOrAndAndConditionlessEntrancesKeepTheirOwnRules() {
        for (var combination : List.of("LOGIC_OR", "LOGIC_AND")) {
            var entry = new Gson().fromJson("""
                    {"condComb":"%s","NCPHMNJMPKB":[
                        {"type":"DUNGEON_ENTRY_CONDITION_LEVEL","param1":20},
                        {"type":"DUNGEON_ENTRY_CONDITION_QUEST","param1":35802},{}]}
                    """.formatted(combination), DungeonEntryData.class);
            if (combination.equals("LOGIC_OR")) {
                assertNull(entry.getUnmetCondition(60, quest -> false));
                assertNull(entry.getUnmetCondition(1, quest -> quest == 35802));
            } else {
                assertEquals(35802, entry.getUnmetCondition(60, quest -> false).getParam1());
                assertEquals(20, entry.getUnmetCondition(1, quest -> true).getParam1());
            }
            assertNotNull(entry.getUnmetCondition(1, quest -> false));
            assertNull(entry.getUnmetCondition(60, quest -> true));
        }
        var noConditions = new Gson().fromJson("{\"isDefaultOpen\":true}", DungeonEntryData.class);
        assertEquals(0, noConditions.getLevelCondition());
        assertEquals(0, noConditions.getQuestCondition());
        assertNull(noConditions.getUnmetCondition(1, quest -> false));
    }

    private static GetDungeonEntryExploreConditionReq request(int scene, int point, int config) {
        return GetDungeonEntryExploreConditionReq.newBuilder().setSceneId(scene)
                .setDungeonEntryScenePointId(point).setDungeonEntryConfigId(config).build();
    }

    private static RecordingPlayer player(int level) {
        var player = new RecordingPlayer();
        player.getProperties().put(PlayerProperty.PROP_PLAYER_LEVEL.getId(), level);
        return player;
    }

    private static final class RecordingPlayer extends Player {
        final List<BasePacket> packets = new ArrayList<>();
        final GameSession session = new GameSession(null) {
            @Override public Player getPlayer() { return RecordingPlayer.this; }
            @Override public void send(BasePacket packet) { packets.add(packet); }
        };
        @Override public GameSession getSession() { return session; }
        @Override public void sendPacket(BasePacket packet) { packets.add(packet); }
    }
}
