package emu.grasscutter.game.world;

import emu.grasscutter.GameConstants;
import emu.grasscutter.database.DatabaseHelper;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.server.game.GameSession;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

/** Identity-based registrations for native, memory-only player targets. */
public final class PvpTestBotRoster {
    public record Entry(Player owner, Player bot, World world, Scene scene, GameSession ownerSession) {}
    private static final Map<Player, Entry> entries = new IdentityHashMap<>();
    private static final int LAST_UID = 105;

    private PvpTestBotRoster() {}

    public static synchronized void register(Player owner, Player bot) {
        if (owner == null || !(bot instanceof PvpTestBot.BotPlayer) || owner == bot
                || bot.getUid() < PvpTestBot.UID || bot.getUid() > LAST_UID
                || owner.getWorld() == null || owner.getScene() == null || owner.getSession() == null
                || bot.getServer() != owner.getServer()
                || bot.getServer().getPlayers().get(bot.getUid()) != bot
                || bot.getWorld() != owner.getWorld() || bot.getScene() != owner.getScene())
            throw new IllegalArgumentException("只能登记已加入主人当前场景的临时机器人。");
        var existing = entries.get(bot);
        if (existing != null) {
            if (existing.owner() != owner) throw new IllegalArgumentException("机器人已属于其他玩家。");
            return;
        }
        entries.put(bot, new Entry(owner, bot, owner.getWorld(), owner.getScene(), owner.getSession()));
    }

    public static synchronized Entry entry(Player bot) { return entries.get(bot); }

    /** Adopts the original single-bot registration without replacing its session/scene snapshot. */
    static synchronized boolean adopt(Player owner, Player bot, World world, Scene scene, GameSession ownerSession) {
        if (!(bot instanceof PvpTestBot.BotPlayer) || owner == null || owner == bot
                || bot.getUid() < PvpTestBot.UID || bot.getUid() > LAST_UID
                || world == null || scene == null || ownerSession == null)
            throw new IllegalArgumentException("无效的旧测试机器人登记。");
        if (bot.getServer().getPlayers().get(bot.getUid()) != bot) return false;
        var existing = entries.get(bot);
        if (existing != null && existing.owner() != owner)
            throw new IllegalArgumentException("机器人已属于其他玩家。");
        if (existing == null) entries.put(bot, new Entry(owner, bot, world, scene, ownerSession));
        return true;
    }

    public static synchronized List<Player> bots(Player owner) {
        var result = new ArrayList<Player>();
        for (var entry : entries.values()) if (entry.owner() == owner) result.add(entry.bot());
        result.sort(Comparator.comparingInt(Player::getUid));
        return List.copyOf(result);
    }

    public static synchronized Entry remove(Player bot) { return entries.remove(bot); }

    public static synchronized boolean isOwnedPair(Player first, Player second) {
        if (first == null || second == null) return false;
        var entry = entries.get(second);
        if (entry != null && entry.owner() == first)
            return second.getServer().getPlayers().get(second.getUid()) == second;
        entry = entries.get(first);
        return entry != null && entry.owner() == second
                && first.getServer().getPlayers().get(first.getUid()) == first;
    }

    static int nextAvailableUid(Map<Integer, Player> online) {
        return nextAvailableUid(online, uid -> DatabaseHelper.getAccountByPlayerId(uid) != null);
    }

    static int nextAvailableUid(Map<Integer, Player> online, IntPredicate hasRealAccount) {
        for (int uid = PvpTestBot.UID; uid <= LAST_UID; uid++) {
            if (uid != GameConstants.SERVER_CONSOLE_UID && uid != GameConstants.SERVER_DPS_UID
                    && !online.containsKey(uid) && !hasRealAccount.test(uid)) return uid;
        }
        throw new IllegalArgumentException("临时机器人 UID 98～105 均已占用，最多可保留 8 个；不会占用真实账号。");
    }

    /** Removes only a newly constructed fake player when its native spawn fails. */
    static void discard(Player bot, World world) {
        if (!(bot instanceof PvpTestBot.BotPlayer))
            throw new IllegalArgumentException("不能将真实玩家作为测试机器人清理。");
        remove(bot);
        PvpDuels.onPlayerLeave(bot);
        if (bot.getWorld() == world && world.getPlayers().stream().anyMatch(player -> player == bot))
            world.removePlayer(bot);
        bot.getServer().getPlayers().remove(bot.getUid(), bot);
        bot.getSession().setState(GameSession.SessionState.INACTIVE);
    }
}
