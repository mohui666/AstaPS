package emu.grasscutter.game.quest.exec;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.CodedInputStream;
import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.player.TeamManager;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.world.TestWorld.TestPlayer;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.packet.PacketOpcodes;
import emu.grasscutter.net.proto.TeamMoonPhaseChangeNotify._TeamMoonPhaseChangeNotify;
import emu.grasscutter.server.packet.send.PacketTeamMoonPhaseChangeNotify;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(ServerResourceFixture.class)
final class QuestFreeMoonPhaseTest {
    @Test
    void eachExecSendsOneActivationAndNormalTeamRefreshesDoNotReplayIt() throws Exception {
        var player = new MoonPlayer();
        var other = new MoonPlayer();
        var quest = new GameQuest() {
            @Override public Player getOwner() { return player; }
            @Override public int getSubQuestId() { return 7600601; }
        };
        var handler = new ExecActivateDamseletteFreeMoonPhase();
        assertTrue(handler.execute(quest, null));
        assertTrue(handler.execute(quest, null));
        var notifications = player.sent.stream()
                .filter(packet -> packet.getOpcode() == PacketOpcodes._TeamMoonPhaseChangeNotify).toList();
        assertEquals(2, notifications.size());
        assertTrue(other.sent.isEmpty());
        assertEquals(2, player.saves);
        var packet = notifications.getFirst();
        assertEquals(29844, packet.getOpcode());
        // KBHAJLIBJLP parser RVA 0x12BEE6B0: level4, activation3, type7.
        var wire = CodedInputStream.newInstance(packet.getData());
        assertEquals(24, wire.readTag());
        assertTrue(wire.readBool());
        assertEquals(32, wire.readTag());
        assertEquals(2, wire.readUInt32());
        assertEquals(56, wire.readTag());
        assertEquals(20000, wire.readUInt32());
        assertEquals(0, wire.readTag());

        var updated = _TeamMoonPhaseChangeNotify.parseFrom(new PacketTeamMoonPhaseChangeNotify(player).getData());
        assertEquals(2, updated.getLIAGEGINEPH());
        assertEquals(20000, updated.getMoonPhaseType());
        assertFalse(updated.getIsActivateDamseletteFreeMoonPhase());
    }

    @Test
    void normalMoonPhaseLevelUsesNativeFieldFour() throws Exception {
        var packet = new PacketTeamMoonPhaseChangeNotify(2);
        var wire = CodedInputStream.newInstance(packet.getData());
        assertEquals(32, wire.readTag());
        assertEquals(2, wire.readUInt32());
        assertEquals(56, wire.readTag());
        assertEquals(10000, wire.readUInt32());
        assertEquals(0, wire.readTag());
    }

    @Test
    void malformedExecDoesNotActivateTheFreeMoonPhase() {
        var player = new MoonPlayer();
        var quest = new GameQuest() { @Override public Player getOwner() { return player; } };
        assertFalse(new ExecActivateDamseletteFreeMoonPhase().execute(quest, null, "unexpected"));
        assertEquals(0, player.saves);
        assertTrue(player.sent.isEmpty());
    }

    private static final class MoonPlayer extends TestPlayer {
        private int saves;
        private final List<BasePacket> sent = new ArrayList<>();
        private final TeamManager team = new TeamManager(this);
        @Override public TeamManager getTeamManager() { return team; }
        @Override public void save() { saves++; }
        @Override public void sendPacket(BasePacket packet) { sent.add(packet); }
    }
}
