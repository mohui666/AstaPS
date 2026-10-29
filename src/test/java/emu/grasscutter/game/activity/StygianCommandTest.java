package emu.grasscutter.game.activity;

import static org.junit.jupiter.api.Assertions.*;
import emu.grasscutter.command.commands.StygianCommand;
import org.junit.jupiter.api.Test;

class StygianCommandTest {
    @Test void entranceDoesNotRequireIndependentBattleArguments() {
        var command = new StygianCommand().createCommandLine(null, null);
        assertEquals("entrance", command.parseArgs("entrance").subcommand().commandSpec().name());
        assertNull(new StygianCommand().createCommandLine(null, null).parseArgs("3", "2").subcommand());
    }
}
