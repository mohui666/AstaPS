package emu.grasscutter.command.commands;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.command.Command;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.OpenStateChangeNotifyOuterClass.OpenStateChangeNotify;
import emu.grasscutter.net.proto.OpenStateUpdateNotifyOuterClass.OpenStateUpdateNotify;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.packet.send.PacketOpenStateUpdateNotify;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

public final class BarrierCommandTest {
    @Test
    void requiresExplicitStateWithoutAnOnlinePlayerForParsing() {
        var command = new BarrierCommand();
        assertEquals(Command.TargetRequirement.NONE,
                BarrierCommand.class.getAnnotation(Command.class).targetRequirement());
        assertThrows(CommandLine.ParameterException.class,
                () -> command.createCommandLine(null, null).parseArgs());
        for (String state : new String[] {"on", "off", "1", "0", "ON", "OFF"}) {
            assertDoesNotThrow(() -> command.createCommandLine(null, null).parseArgs(state));
        }
        assertThrows(CommandLine.ParameterException.class,
                () -> command.createCommandLine(null, null).parseArgs("toggle"));
        assertThrows(CommandLine.ParameterException.class,
                () -> command.createCommandLine(null, null).parseArgs("on", "off"));
    }

    @Test
    void togglesStarterRegionRestrictionWithOpenState47() throws Exception {
        var player = new RecordingPlayer();
        player.getOpenStates().put(47, 0);
        player.setExplorationUnlocked(true);
        var off = OpenStateChangeNotify.parseFrom(BarrierCommand.barrierStatePacket(player).getData());
        player.setExplorationUnlocked(false);
        var on = OpenStateChangeNotify.parseFrom(BarrierCommand.barrierStatePacket(player).getData());

        assertEquals(Map.of(47, 1), off.getOpenStateMapMap());
        assertEquals(Map.of(47, 0), on.getOpenStateMapMap());
    }

    @Test
    void freeExplorationIsSavedAndIncludedInLoginWithoutForgingProgress() throws Exception {
        var player = new RecordingPlayer();
        player.getOpenStates().putAll(Map.of(45, 1, 47, 0, 48, 1));
        var originalStates = Map.copyOf(player.getOpenStates());

        assertEquals(0, new BarrierCommand().createCommandLine(null, player).execute("off"));

        assertTrue(player.isExplorationUnlocked());
        assertEquals(1, player.saves);
        assertTrue(player.savedExploration);
        assertEquals(originalStates, player.getOpenStates());
        assertTrue(player.getQuestManager().getMainQuests().isEmpty());
        assertEquals(Map.of(47, 1), change(player).getOpenStateMapMap());

        var login = OpenStateUpdateNotify.parseFrom(new PacketOpenStateUpdateNotify(player).getData());
        assertEquals(1, login.getOpenStateMapMap().get(47));
        assertEquals(originalStates, player.getOpenStates());

        var otherPlayer = new RecordingPlayer();
        assertFalse(otherPlayer.isExplorationUnlocked());
        assertEquals(0, otherPlayer.getProgressManager().getClientOpenState(47));
    }

    @Test
    void closingFreeExplorationRestoresNaturalStateInsteadOfForcingALock() throws Exception {
        for (int natural : new int[] {0, 1}) {
            var player = new RecordingPlayer();
            player.getOpenStates().put(47, natural);
            player.setExplorationUnlocked(true);

            assertEquals(0, new BarrierCommand().createCommandLine(null, player).execute("on"));

            assertFalse(player.isExplorationUnlocked());
            assertFalse(player.savedExploration);
            assertEquals(1, player.saves);
            assertEquals(natural, change(player).getOpenStateMapMap().get(47));
            assertEquals(natural, player.getProgressManager().getOpenState(47));
        }
    }

    @Test
    void laterQuestNotificationsKeepTheOverrideButRetainRealState() throws Exception {
        var player = new RecordingPlayer();
        player.getOpenStates().put(47, 1);
        player.setExplorationUnlocked(true);

        player.getProgressManager().forceSetOpenState(47, 0);

        assertEquals(0, player.getProgressManager().getOpenState(47));
        assertEquals(Map.of(47, 1), change(player).getOpenStateMapMap());

        player.getProgressManager().forceSetOpenState(48, 0);
        assertEquals(Map.of(48, 0), change(player).getOpenStateMapMap());
    }

    private static OpenStateChangeNotify change(RecordingPlayer player) throws Exception {
        return OpenStateChangeNotify.parseFrom(player.packets.get(player.packets.size() - 1).getData());
    }

    private static final class RecordingPlayer extends Player {
        final List<BasePacket> packets = new ArrayList<>();
        final GameSession session = new GameSession(null) {
            @Override
            public boolean isActive() { return true; }

            @Override
            public void send(BasePacket packet) { packets.add(packet); }
        };
        int saves;
        boolean savedExploration;

        @Override
        public GameSession getSession() { return session; }

        @Override
        public void sendPacket(BasePacket packet) { packets.add(packet); }

        @Override
        public void save() {
            saves++;
            savedExploration = isExplorationUnlocked();
        }
    }

    @Test
    void retainsPermissionsAndAliases() {
        var annotation = BarrierCommand.class.getAnnotation(Command.class);
        assertEquals("player.setprop", annotation.permission());
        assertEquals("player.setprop.others", annotation.permissionTargeted());
        assertArrayEquals(new String[] {"br"}, annotation.aliases());
    }
}
