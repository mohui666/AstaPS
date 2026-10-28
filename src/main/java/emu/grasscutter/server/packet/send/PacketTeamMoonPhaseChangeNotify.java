package emu.grasscutter.server.packet.send;

import emu.grasscutter.net.packet.*;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.QuestFreeMoonPhase;
import emu.grasscutter.net.proto.TeamMoonPhaseChangeNotify._TeamMoonPhaseChangeNotify;

public final class PacketTeamMoonPhaseChangeNotify extends BasePacket {

    /** MOON_PHASE_CONST_VALUE_MOON_PHASE_TEAM_ID: the party's own Moonsign, not an event's. */
    private static final int MOON_PHASE_TYPE = 10000;

    public PacketTeamMoonPhaseChangeNotify(int moonsignLevel) {
        this(MOON_PHASE_TYPE, moonsignLevel, false);
    }

    public PacketTeamMoonPhaseChangeNotify(Player player) {
        this(QuestFreeMoonPhase.current(player), false);
    }

    /** Field 3 is an activation event, so ordinary team refreshes must not replay it. */
    public static PacketTeamMoonPhaseChangeNotify freeActivation(Player player) {
        return new PacketTeamMoonPhaseChangeNotify(QuestFreeMoonPhase.current(player), true);
    }

    private PacketTeamMoonPhaseChangeNotify(QuestFreeMoonPhase.Phase phase, boolean activatedFreeMoonPhase) {
        this(phase.type(), phase.level(), activatedFreeMoonPhase);
    }

    public PacketTeamMoonPhaseChangeNotify(int moonPhaseType, int moonsignLevel, boolean activatedFreeMoonPhase) {
        super(PacketOpcodes._TeamMoonPhaseChangeNotify);

        this.setData(
                _TeamMoonPhaseChangeNotify.newBuilder()
                        .setMoonPhaseType(moonPhaseType)
                        // Native BONCOMALOOM.BCGFNPJMKPH at RVA 0x9477D00 copies field 4
                        // into currentLevel; the descriptor's field-1 name is incorrect.
                        .setLIAGEGINEPH(moonsignLevel)
                        .setIsActivateDamseletteFreeMoonPhase(activatedFreeMoonPhase));
    }
}
