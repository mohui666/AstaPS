package emu.grasscutter.game.tps;

import emu.grasscutter.game.player.Player;
import emu.grasscutter.net.proto.RetcodeOuterClass.Retcode;
import emu.grasscutter.net.proto.UpdateTpsGlassesChatReqOuterClass.UpdateTpsGlassesChatReq;
import emu.grasscutter.server.packet.send.PacketTpsGlassesChatDataNotify;
import java.util.List;

/** The quest-owned glasses chat, separate from multiplayer/private chat. */
public final class TpsGlassesChatSystem {
    public static final int UPDATE_REQ = 20783;
    public static final int UPDATE_RSP = 21937;
    public static final int DATA_NOTIFY = 21361;

    private TpsGlassesChatSystem() {}

    public static boolean setProgress(Player player, int topicId, int progress) {
        var catalog = TpsGlassesChatCatalog.installed();
        if (progress < 0 || !catalog.hasTopic(topicId)) return false;
        var state = player.getTpsGlassesChat();
        synchronized (state) {
            var previous = state.topic(topicId);
            state.setProgress(topicId, progress, previous == null ? List.of()
                    : catalog.choicesBefore(topicId, progress, previous.chosenOptions()));
            player.save();
            player.sendPacket(new PacketTpsGlassesChatDataNotify(state));
        }
        return true;
    }

    public static int update(Player player, UpdateTpsGlassesChatReq request) {
        var state = player.getTpsGlassesChat();
        synchronized (state) {
            var topic = state.topic(request.getTopicId());
            // The native sender writes its current progress to both of these fields. Neither
            // field can unlock a topic that has not been granted by its server quest action.
            if (topic == null || topic.progress() <= 0
                    || request.getCPGANAPPFDM() != request.getProgress()
                    || !TpsGlassesChatCatalog.installed().canAdvance(topic.id(), topic.progress(),
                            topic.chosenOptions(), request.getProgress(), request.getChosenOptionListList()))
                return Retcode.RET_FAIL_VALUE;
            state.recordRead(topic.id(), request.getProgress(), request.getChosenOptionListList(),
                    request.getCPGANAPPFDM());
            player.save();
            player.sendPacket(new PacketTpsGlassesChatDataNotify(state));
            return 0;
        }
    }

    public static void onLogin(Player player) {
        // Cmd 21361 is a complete replacement, including after a topic has been disabled.
        player.sendPacket(new PacketTpsGlassesChatDataNotify(player.getTpsGlassesChat()));
    }
}
