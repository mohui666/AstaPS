package emu.grasscutter.command.commands;

import emu.grasscutter.command.Command;
import emu.grasscutter.command.CommandHandler;
import emu.grasscutter.command.CommandOutput;
import emu.grasscutter.game.activity.leylinechallenge.LeyLineChallengeEnterFlow;
import emu.grasscutter.game.player.Player;
import picocli.CommandLine;
import picocli.CommandLine.Parameters;

@Command(label = "stygian", aliases = {"youjing", "幽境"}, permission = "player.enterdungeon",
        permissionTargeted = "player.enterdungeon.others")
public final class StygianCommand implements CommandHandler {
    @Override
    public CommandLine createCommandLine(Player sender, Player targetPlayer) {
        return new CommandLine(new Args(sender, targetPlayer));
    }

    @CommandLine.Command(name = "stygian", description = "进入 MGC 幽境独立战场：难度 1–6，战场 1–3。")
    private static final class Args implements Runnable {
        private final Player sender;
        private final Player target;
        @Parameters(index = "0", paramLabel = "<difficulty>") private int difficulty;
        @Parameters(index = "1", paramLabel = "<battle>") private int battle;
        Args(Player sender, Player target) { this.sender = sender; this.target = target; }
        @Override public void run() {
            try { LeyLineChallengeEnterFlow.enterBattle(target, difficulty, battle); }
            catch (IllegalArgumentException error) { CommandOutput.sendMessage(sender, error.getMessage()); }
        }
    }
}
