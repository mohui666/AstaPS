package emu.grasscutter.command.commands;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.command.Command;
import emu.grasscutter.command.CommandHandler;
import emu.grasscutter.command.CommandMap;
import emu.grasscutter.command.CommandOutput;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.world.PvpDuels;
import emu.grasscutter.game.world.PvpTestBot;
import java.util.Locale;
import picocli.CommandLine;
import picocli.CommandLine.Parameters;

@Command(label = "pvp", permission = "server.pvp", targetRequirement = Command.TargetRequirement.NONE, inlineTarget = false)
public final class PvpCommand implements CommandHandler {
    /** Register after loading this command into an already running server. */
    public static void register() { CommandMap.getInstance().registerCommand("pvp", new PvpCommand()); }

    @Override
    public CommandLine createCommandLine(Player sender, Player targetPlayer) {
        var line = new CommandLine(new Args(sender));
        line.setExpandAtFiles(false);
        return line;
    }

    @CommandLine.Command(name = "pvp", description = "UID 1/UID 2 的服务端真实命中对战；不会改变客户端队友筛选。",
            customSynopsis = "pvp [on|off|status|bot|removebot]")
    private static final class Args implements Runnable {
        private final Player sender;
        @Parameters(index = "0", arity = "0..1", defaultValue = "status") private String action;
        private Args(Player sender) { this.sender = sender; }

        @Override
        public void run() {
            try {
                String message = switch (action.toLowerCase(Locale.ROOT)) {
                    case "on" -> {
                        var server = Grasscutter.getGameServer();
                        var owner = server.getPlayerByUid(1);
                        var bot = server.getPlayers().get(PvpTestBot.UID);
                        yield PvpDuels.enable(owner, PvpTestBot.isOwnedPair(owner, bot)
                                ? bot : server.getPlayerByUid(2));
                    }
                    case "off" -> PvpDuels.disable();
                    case "status" -> PvpDuels.status();
                    case "bot" -> {
                        var bot = PvpTestBot.spawn(Grasscutter.getGameServer().getPlayerByUid(1));
                        yield "临时玩家机器人 UID " + bot.getUid() + " 已加入你的当前场景并启用配对；不自动攻击，只测试真人对远端Avatar的实际命中。";
                    }
                    case "removebot" -> PvpTestBot.remove(Grasscutter.getGameServer().getPlayerByUid(1));
                    default -> throw new IllegalArgumentException("用法：pvp [on|off|status|bot|removebot]；仅授权 UID 1/2 或主人与登记的测试机器人。");
                };
                CommandOutput.sendMessage(sender, message);
            } catch (IllegalArgumentException error) {
                CommandOutput.sendMessage(sender, error.getMessage());
            }
        }
    }
}
