package emu.grasscutter.game.world;

import emu.grasscutter.game.player.Player;
import emu.grasscutter.server.game.GameSession;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Training state kept separately so the live single-duel classes retain their JVM schema. */
public final class PvpDuelRoster {
    private record Member(Player bot, Object duel, GameSession session) {}
    private static final Map<Player, Member> members = new IdentityHashMap<>();
    private static Player owner;
    private static World world;
    private static Scene scene;
    private static GameSession ownerSession;

    private PvpDuelRoster() {}

    /** Returns whether this adds to the same authenticated owner's existing training group. */
    public static synchronized boolean register(Player first, Player second, Object duel) {
        var entry = PvpTestBotRoster.entry(second);
        if (entry == null || entry.owner() != first) entry = PvpTestBotRoster.entry(first);
        if (entry == null || !PvpTestBotRoster.isOwnedPair(first, second)) {
            clear();
            return false;
        }
        boolean continuing = owner == entry.owner() && world == entry.world() && scene == entry.scene()
                && ownerSession == entry.ownerSession();
        if (!continuing) clear();
        owner = entry.owner();
        world = entry.world();
        scene = entry.scene();
        ownerSession = entry.ownerSession();
        members.put(entry.bot(), new Member(entry.bot(), duel, entry.bot().getSession()));
        return continuing;
    }

    public static synchronized boolean training() { return owner != null; }

    public static synchronized boolean owns(Player player) { return owner == player; }

    public static synchronized boolean validOwner() {
        return owner != null && ownerSession != null && ownerSession.isActive()
                && ownerSession.getPlayer() == owner && owner.getSession() == ownerSession
                && owner.getWorld() == world && owner.getScene() == scene && scene.getWorld() == world
                && world.getPlayers().contains(owner) && scene.getPlayers().contains(owner);
    }

    private static boolean valid(Member member) {
        var bot = member.bot();
        return validOwner() && PvpTestBotRoster.isOwnedPair(owner, bot)
                && bot.getSession() == member.session() && member.session() != null
                && member.session().isActive() && member.session().getPlayer() == bot
                && bot.getWorld() == world && bot.getScene() == scene
                && world.getPlayers().contains(bot) && scene.getPlayers().contains(bot);
    }

    public static synchronized Object find(Player first, Player second) {
        var member = first == owner ? members.get(second) : second == owner ? members.get(first) : null;
        return member != null && valid(member) ? member.duel() : null;
    }

    public static synchronized Object any() {
        for (var member : members.values()) if (valid(member)) return member.duel();
        return null;
    }

    public static synchronized List<Player> bots(Player player) {
        var result = new ArrayList<Player>();
        if (owner == player) for (var member : members.values()) if (valid(member)) result.add(member.bot());
        return result;
    }

    public static synchronized boolean contains(Player player) { return player == owner || members.containsKey(player); }

    /** Called before scene membership changes; removing one target leaves the other pairs intact. */
    public static synchronized void leave(Player player) {
        if (player == owner) clear();
        else members.remove(player);
    }

    public static synchronized void clear() {
        members.clear();
        owner = null;
        world = null;
        scene = null;
        ownerSession = null;
    }
}
