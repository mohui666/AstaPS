package emu.grasscutter.game.tps;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.exec.ExecSetTpsGlassesChatProgress;
import emu.grasscutter.game.world.TestWorld.TestPlayer;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.TpsGlassesChatDataNotifyOuterClass.TpsGlassesChatDataNotify;
import emu.grasscutter.net.proto.TpsGlassesChatTopicInfoOuterClass.TpsGlassesChatTopicInfo;
import emu.grasscutter.net.proto.UpdateTpsGlassesChatReqOuterClass.UpdateTpsGlassesChatReq;
import emu.grasscutter.net.proto.UpdateTpsGlassesChatRspOuterClass.UpdateTpsGlassesChatRsp;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.packet.recv.HandlerUpdateTpsGlassesChatReq;
import emu.grasscutter.server.packet.send.PacketTpsGlassesChatDataNotify;
import emu.grasscutter.utils.JsonUtils;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(ServerResourceFixture.class)
final class TpsGlassesChatTest {
    @Test
    void nativeExecRegistersAndPublishesIntegerProgress() throws Exception {
        assertEquals(134, QuestExec.QUEST_EXEC_SET_TPS_GLASSES_CHAT_PROGRESS.getValue());
        var player = new ChatPlayer();
        var data = JsonUtils.decode("{id:7710017}", QuestData.class);
        var quest = new GameQuest() {
            @Override public Player getOwner() { return player; }
            @Override public QuestData getQuestData() { return data; }
        };
        var action = JsonUtils.decode("{type:'QUEST_EXEC_SET_TPS_GLASSES_CHAT_PROGRESS',param:['17','1']}",
                QuestData.QuestExecParam.class);
        new QuestSystem(null).triggerExec(quest, action, action.getParam());
        QuestManager.eventExecutor.submit(() -> {}).get(3, TimeUnit.SECONDS);
        assertEquals(1, player.getTpsGlassesChat().topic(17).progress());
        assertEquals(1, player.saves);
        assertEquals(21361, player.sent.getLast().getOpcode());
    }

    @Test
    void allFifteenActualQuestActionsHaveRealTopicEntriesIncludingProgress32And34() throws Exception {
        int[][] actions = {{17,1},{10,1},{21,1},{16,1},{18,1},{5,1},{14,1},{11,1},
                {22,1},{5,1},{15,1},{12,1},{3,32},{4,34},{20,1}};
        var player = new ChatPlayer();
        for (var action : actions) assertTrue(TpsGlassesChatSystem.setProgress(player, action[0], action[1]));
        var wire = TpsGlassesChatDataNotify.parseFrom(player.sent.getLast().getData());
        assertEquals(14, wire.getChatData().getTopicListCount());
        assertEquals(32, player.getTpsGlassesChat().topic(3).progress());
        assertEquals(34, player.getTpsGlassesChat().topic(4).progress());
        assertFalse(TpsGlassesChatSystem.setProgress(player, 999999, 1));
        assertFalse(TpsGlassesChatSystem.setProgress(player, 17, -1));
        assertEquals(15, player.saves);
    }

    @Test
    void nativeMessageGraphAcceptsBatchedAutomaticMessagesAndTheChosenBranch() {
        var player = new ChatPlayer();
        assertTrue(TpsGlassesChatSystem.setProgress(player, 17, 1));
        assertEquals(0, TpsGlassesChatSystem.update(player,
                request(17, 39, 17002, 17007, 17024, 17032)));
        assertEquals(39, player.getTpsGlassesChat().topic(17).progress());
        assertEquals(List.of(17002, 17007, 17024, 17032),
                player.getTpsGlassesChat().topic(17).chosenOptions());
        assertEquals(0, TpsGlassesChatSystem.update(player,
                request(17, 40, 17002, 17007, 17024, 17032)));
    }

    @Test
    void lockedTopicsMissingChoicesForeignOptionsAndBranchReplacementCannotAdvance() {
        var player = new ChatPlayer();
        assertNotEquals(0, TpsGlassesChatSystem.update(player, request(17, 3, 17002)));
        TpsGlassesChatSystem.setProgress(player, 17, 1);
        int saves = player.saves;
        assertNotEquals(0, TpsGlassesChatSystem.update(player, request(17, 3)));
        assertNotEquals(0, TpsGlassesChatSystem.update(player, request(17, 3, 10002)));
        assertNotEquals(0, TpsGlassesChatSystem.update(player, request(17, 1, 17002)));
        assertNotEquals(0, TpsGlassesChatSystem.update(player, request(17, 82, 17002)));
        assertEquals(saves, player.saves);
        assertEquals(0, TpsGlassesChatSystem.update(player, request(17, 3, 17002)));
        assertNotEquals(0, TpsGlassesChatSystem.update(player, request(17, 44, 17039)));
        assertNotEquals(0, TpsGlassesChatSystem.update(player, request(17, 1)));
        assertEquals(List.of(17002), player.getTpsGlassesChat().topic(17).chosenOptions());
    }

    @Test
    void authoritativeQuestRewindResetsFutureChoicesAndZeroDisablesTheTopic() {
        var player = new ChatPlayer();
        TpsGlassesChatSystem.setProgress(player, 17, 1);
        assertEquals(0, TpsGlassesChatSystem.update(player, request(17, 3, 17002)));
        assertTrue(TpsGlassesChatSystem.setProgress(player, 17, 1));
        assertTrue(player.getTpsGlassesChat().topic(17).chosenOptions().isEmpty());
        assertEquals(0, TpsGlassesChatSystem.update(player, request(17, 44, 17039)));
        assertTrue(TpsGlassesChatSystem.setProgress(player, 17, 0));
        assertNotEquals(0, TpsGlassesChatSystem.update(player, request(17, 1)));
    }

    @Test
    void persistedSnapshotRestoresAllTopicsAndSelectionsAtLogin() throws Exception {
        var player = new ChatPlayer();
        TpsGlassesChatSystem.setProgress(player, 17, 1);
        TpsGlassesChatSystem.setProgress(player, 3, 32);
        assertEquals(0, TpsGlassesChatSystem.update(player, request(17, 3, 17002)));
        var restored = new ChatPlayer();
        restored.state = JsonUtils.decode(JsonUtils.encode(player.getTpsGlassesChat()), TpsGlassesChatState.class);
        TpsGlassesChatSystem.onLogin(restored);
        assertEquals(0, restored.saves);
        var wire = TpsGlassesChatDataNotify.parseFrom(restored.sent.getLast().getData());
        assertEquals(2, wire.getChatData().getTopicListCount());
        var topic = wire.getChatData().getTopicListList().stream()
                .filter(value -> value.getTopicId() == 17).findFirst().orElseThrow();
        assertEquals(3, topic.getProgress());
        assertEquals(3, topic.getCPGANAPPFDM());
        assertEquals(List.of(17002), topic.getChosenOptionListList());
    }

    @Test
    void nativeWireTagsPackedOptionIdsAndFixed32RemainExact() throws Exception {
        var topic = TpsGlassesChatTopicInfo.newBuilder().setTopicId(17).setProgress(1)
                .addChosenOptionList(17002).setCPGANAPPFDM(1).setKGBBAACLKLB(0x12345678).build();
        assertArrayEquals(HexFormat.of().parseHex("1203ea84011d78563412400150016011"), topic.toByteArray());
        var state = new TpsGlassesChatState();
        state.setProgress(17, 1, List.of());
        assertArrayEquals(HexFormat.of().parseHex("4a06420450016011"),
                new PacketTpsGlassesChatDataNotify(state).getData());
        assertArrayEquals(HexFormat.of().parseHex("0801200130116203ea8401"),
                request(17, 1, 17002).toByteArray());
        assertEquals(9, TpsGlassesChatDataNotify.getDescriptor().findFieldByName("chat_data").getNumber());
        assertEquals(7, UpdateTpsGlassesChatRsp.getDescriptor().findFieldByName("retcode").getNumber());
    }

    @Test
    void realRequestHandlerReturnsFailureForForgeryAndSuccessForAnAllowedConversation() throws Exception {
        var player = new ChatPlayer();
        var response = new ArrayList<BasePacket>();
        var session = new GameSession(null) {
            @Override public Player getPlayer() { return player; }
            @Override public void send(BasePacket packet) { response.add(packet); }
        };
        var handler = new HandlerUpdateTpsGlassesChatReq();
        handler.handle(session, new byte[0], request(17, 3, 17002).toByteArray());
        assertEquals(21937, response.getLast().getOpcode());
        assertNotEquals(0, UpdateTpsGlassesChatRsp.parseFrom(response.getLast().getData()).getRetcode());
        TpsGlassesChatSystem.setProgress(player, 17, 1);
        handler.handle(session, new byte[0], request(17, 3, 17002).toByteArray());
        assertEquals(0, UpdateTpsGlassesChatRsp.parseFrom(response.getLast().getData()).getRetcode());
    }

    private static UpdateTpsGlassesChatReq request(int topic, int progress, Integer... choices) {
        return UpdateTpsGlassesChatReq.newBuilder().setTopicId(topic).setProgress(progress)
                .setCPGANAPPFDM(progress).addAllChosenOptionList(List.of(choices)).build();
    }

    private static final class ChatPlayer extends TestPlayer {
        private TpsGlassesChatState state = new TpsGlassesChatState();
        final List<BasePacket> sent = new ArrayList<>();
        int saves;
        @Override public TpsGlassesChatState getTpsGlassesChat() { return state; }
        @Override public void save() { saves++; }
        @Override public void sendPacket(BasePacket packet) { sent.add(packet); }
    }
}
