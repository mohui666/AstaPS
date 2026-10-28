package emu.grasscutter.game.quest;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.game.entity.EntityTeam;
import emu.grasscutter.game.player.TeamManager;
import emu.grasscutter.game.world.TestWorld;
import emu.grasscutter.game.world.TestWorld.TestPlayer;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.packet.PacketOpcodes;
import emu.grasscutter.net.proto.TeamMoonPhaseChangeNotify._TeamMoonPhaseChangeNotify;
import emu.grasscutter.utils.JsonUtils;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(ServerResourceFixture.class)
final class QuestFreeMoonLifecycleTest {
    private static long at(int hour, int minute) {
        return LocalDate.of(2026, 10, 28).atTime(hour, minute).atZone(ZoneId.systemDefault())
                .toEpochSecond();
    }

    @Test
    void aRealActivationAppliesNativeFixedLevelToActualTeamSgv() throws Exception {
        var player = new MoonPlayer();
        TestWorld.create(player);
        player.team.setEntity(new EntityTeam(player));
        assertTrue(QuestFreeMoonPhase.activate(player, 7600601, at(3, 50)));
        assertEquals(new QuestFreeMoonPhase.Phase(20000, 2), QuestFreeMoonPhase.current(player, at(3, 51)));
        assertEquals(20000f, player.team.getEntity().getGlobalAbilityValues().get("SGV_MoonPhaseType"));
        assertEquals(2f, player.team.getEntity().getGlobalAbilityValues().get("SGV_MoonPhaseLevel"));
        var notice = _TeamMoonPhaseChangeNotify.parseFrom(player.notifications().getFirst().getData());
        assertEquals(20000, notice.getMoonPhaseType());
        assertEquals(2, notice.getLIAGEGINEPH());
        assertTrue(notice.getIsActivateDamseletteFreeMoonPhase());
        assertEquals(7600601, player.state.getQuestId());
        assertEquals(1, player.saves);
    }

    @Test
    void savedActivationSurvivesRelogWithoutReplayingActivationOrExtendingIt() throws Exception {
        var before = new MoonPlayer();
        assertTrue(QuestFreeMoonPhase.activate(before, 7600601, at(3, 50)));
        var after = new MoonPlayer();
        after.state = JsonUtils.decode(JsonUtils.encode(before.state), QuestFreeMoonPhaseState.class);
        QuestFreeMoonPhase.sendState(after, false, at(3, 55));
        assertEquals(at(3, 50), after.state.getActivatedAt());
        assertEquals(0, after.saves);
        var notice = _TeamMoonPhaseChangeNotify.parseFrom(after.notifications().getFirst().getData());
        assertEquals(20000, notice.getMoonPhaseType());
        assertEquals(2, notice.getLIAGEGINEPH());
        assertFalse(notice.getIsActivateDamseletteFreeMoonPhase());
    }

    @Test
    void actualFourOClockBoundaryExpiresAndRefreshDoesNotRegrant() throws Exception {
        var player = new MoonPlayer();
        QuestFreeMoonPhase.activate(player, 7600601, at(3, 50));
        QuestFreeMoonPhase.expire(player, at(3, 59));
        assertEquals(1, player.saves);
        player.sent.clear();
        QuestFreeMoonPhase.expire(player, at(4, 0));
        assertEquals(0, player.state.getActivatedAt());
        assertEquals(2, player.saves);
        var notice = _TeamMoonPhaseChangeNotify.parseFrom(player.notifications().getFirst().getData());
        assertEquals(10000, notice.getMoonPhaseType());
        assertEquals(0, notice.getLIAGEGINEPH());
        assertFalse(notice.getIsActivateDamseletteFreeMoonPhase());
        QuestFreeMoonPhase.expire(player, at(4, 1));
        assertEquals(2, player.saves);
        assertEquals(1, player.notifications().size());
    }

    @Test
    void offlinePassageAndFutureCorruptTimeCannotKeepTheBuff() {
        var offline = new MoonPlayer();
        QuestFreeMoonPhase.activate(offline, 7600601, at(3, 50));
        var restored = new MoonPlayer();
        restored.state = JsonUtils.decode(JsonUtils.encode(offline.state), QuestFreeMoonPhaseState.class);
        assertEquals(10000, QuestFreeMoonPhase.current(restored, at(4, 1)).type());
        QuestFreeMoonPhase.expire(restored, at(4, 1));
        assertEquals(0, restored.state.getActivatedAt());
        var future = new MoonPlayer();
        future.state.activate(at(5, 0), 7600601);
        assertEquals(10000, QuestFreeMoonPhase.current(future, at(4, 1)).type());
        QuestFreeMoonPhase.expire(future, at(4, 1));
        assertEquals(0, future.state.getActivatedAt());
    }

    @Test
    void legacyPlayerWithoutActivationTimeGetsNoNewGrantOrSave() {
        var player = new MoonPlayer();
        assertEquals(new QuestFreeMoonPhase.Phase(10000, 0), QuestFreeMoonPhase.current(player, at(3, 50)));
        QuestFreeMoonPhase.expire(player, at(3, 50));
        assertFalse(QuestFreeMoonPhase.activate(player, 0, at(3, 50)));
        assertFalse(QuestFreeMoonPhase.activate(player, 7600601, 0));
        assertEquals(0, player.saves);
        assertTrue(player.sent.isEmpty());
    }

    private static final class MoonPlayer extends TestPlayer {
        private int saves;
        private QuestFreeMoonPhaseState state = new QuestFreeMoonPhaseState();
        private final List<BasePacket> sent = new ArrayList<>();
        private final TeamManager team = new TeamManager(this);
        @Override public QuestFreeMoonPhaseState getQuestFreeMoonPhase() { return state; }
        @Override public TeamManager getTeamManager() { return team; }
        @Override public void save() { saves++; }
        @Override public void sendPacket(BasePacket packet) { sent.add(packet); }
        List<BasePacket> notifications() {
            return sent.stream().filter(packet -> packet.getOpcode() == PacketOpcodes._TeamMoonPhaseChangeNotify).toList();
        }
    }
}
