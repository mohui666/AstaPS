package emu.grasscutter.command.commands;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.command.Command;
import emu.grasscutter.command.CommandHandler;
import emu.grasscutter.command.CommandMap;
import emu.grasscutter.command.CommandOutput;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.world.PvpDuels;
import emu.grasscutter.game.world.PvpTestBot;
import java.util.List;
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
        line.registerConverter(CommandMap.TargetSelector.class, value -> {
            try {
                return CommandMap.parseExplicitTargetSelector(value);
            } catch (IllegalArgumentException invalid) {
                throw new CommandLine.TypeConversionException(invalid.getMessage());
            }
        });
        return line;
    }

    @CommandLine.Command(name = "pvp", description = "UID 1–4 中任意两人的服务端真实命中对战。",
            customSynopsis = "pvp on [@对手UID | @UID1 @UID2]; pvp [off|status|bot|removebot]")
    private static final class Args implements Runnable {
        private final Player sender;
        @Parameters(index = "0", arity = "0..1", defaultValue = "status") private String action;
        @Parameters(index = "1..2", arity = "0..2", paramLabel = "<playerSelector>")
        private List<CommandMap.TargetSelector> participants = List.of();
        private Args(Player sender) { this.sender = sender; }

        @Override
        public void run() {
            try {
                if (!action.equalsIgnoreCase("on") && !participants.isEmpty())
                    throw new IllegalArgumentException("只有 pvp on 可以指定对战玩家。");
                String message = switch (action.toLowerCase(Locale.ROOT)) {
                    case "on" -> {
                        var server = Grasscutter.getGameServer();
                        if (participants.size() == 2)
                            yield PvpDuels.enable(CommandMap.findPlayer(participants.get(0)),
                                    CommandMap.findPlayer(participants.get(1)));
                        var owner = server.getPlayerByUid(1);
                        if (participants.size() == 1)
                            yield PvpDuels.enable(owner, CommandMap.findPlayer(participants.get(0)));
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
                    default -> throw new IllegalArgumentException("用法：pvp on [@对手UID | @UID1 @UID2]；pvp [off|status|bot|removebot]。仅授权 UID 1–4 中不同的两人或登记的机器人。");
                };
                CommandOutput.sendMessage(sender, message);
            } catch (IllegalArgumentException error) {
                CommandOutput.sendMessage(sender, error.getMessage());
            }
        }
    }
}
