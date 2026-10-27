package emu.grasscutter.game.world;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.config.Configuration;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.server.game.GameServer;
import emu.grasscutter.server.game.GameSession;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

final class PvpTestBotRosterTest {
    private GameServer server;
    private RecordingWorld world;
    private Scene scene;
    private RecordingPlayer owner;
    private final List<Player> created = new ArrayList<>();

    @BeforeEach
    void hostAndNativeBotIdentities() throws Exception {
        server = allocate(GameServer.class);
        set(server, GameServer.class, "players", new HashMap<Integer, Player>());
        world = allocate(RecordingWorld.class);
        world.players = new ArrayList<>();
        scene = allocate(Scene.class);
        owner = allocate(RecordingPlayer.class);
        owner.setUid(1);
        owner.world = world;
        owner.scene = scene;
        owner.session = session(owner);
        world.host = owner;
        world.players.add(owner);
        server.getPlayers().put(1, owner);
    }

    @AfterEach
    void clearOnlyTestRegistrations() {
        for (var bot : created) PvpTestBotRoster.remove(bot);
    }

    @Test
    void uidPoolSkipsOnlineAndRealAccountsAndHasABoundedEnd() {
        var online = new HashMap<Integer, Player>();
        online.put(98, owner);
        online.put(100, owner);
        var realAccounts = Set.of(99, 102, 105);
        assertEquals(101, PvpTestBotRoster.nextAvailableUid(online, realAccounts::contains));
        online.put(101, owner);
        assertEquals(103, PvpTestBotRoster.nextAvailableUid(online, realAccounts::contains));
        online.put(103, owner);
        online.put(104, owner);
        assertThrows(IllegalArgumentException.class,
                () -> PvpTestBotRoster.nextAvailableUid(online, realAccounts::contains));
    }

    @Test
    void multipleBotsKeepSeparateIdentityEntriesAndRemovalLeavesTheOthers() throws Exception {
        var first = bot(98);
        var second = bot(99);
        var third = bot(100);
        for (var bot : List.of(third, first, second)) PvpTestBotRoster.register(owner, bot);
        PvpTestBotRoster.register(owner, first);
        assertEquals(List.of(first, second, third), PvpTestBotRoster.bots(owner));
        assertTrue(PvpTestBotRoster.isOwnedPair(owner, second));
        assertTrue(PvpTestBotRoster.isOwnedPair(third, owner));
        assertFalse(PvpTestBotRoster.isOwnedPair(first, second));
        assertSame(owner.getSession(), PvpTestBotRoster.entry(first).ownerSession());
        assertSame(world, PvpTestBotRoster.remove(second).world());
        assertFalse(PvpTestBotRoster.isOwnedPair(owner, second));
        assertEquals(List.of(first, third), PvpTestBotRoster.bots(owner));
        assertTrue(PvpTestBotRoster.isOwnedPair(owner, first));
        assertThrows(UnsupportedOperationException.class, () -> PvpTestBotRoster.bots(owner).clear());
    }

    @Test
    void aRealPlayerOrAnotherObjectWithTheSameUidCannotBecomeAnOwnedBot() throws Exception {
        var nativeBot = bot(98);
        PvpTestBotRoster.register(owner, nativeBot);
        var realPlayer = allocate(RecordingPlayer.class);
        realPlayer.setUid(98);
        realPlayer.world = world;
        realPlayer.scene = scene;
        realPlayer.session = session(realPlayer);
        server.getPlayers().put(98, realPlayer);
        assertFalse(PvpTestBotRoster.isOwnedPair(owner, nativeBot));
        assertFalse(PvpTestBotRoster.isOwnedPair(owner, realPlayer));
        assertNull(PvpTestBotRoster.remove(realPlayer));
        assertNotNull(PvpTestBotRoster.entry(nativeBot));
        assertThrows(IllegalArgumentException.class, () -> PvpTestBotRoster.register(owner, realPlayer));
        assertThrows(IllegalArgumentException.class, () -> PvpTestBotRoster.register(owner, nativeBot));
    }

    @Test
    void adoptingTheOldRegistrationRetainsItsOriginalWorldSceneAndSession() throws Exception {
        var oldBot = bot(98);
        var oldSession = owner.getSession();
        owner.world = allocate(RecordingWorld.class);
        owner.scene = allocate(Scene.class);
        owner.session = session(owner);
        assertTrue(PvpTestBotRoster.adopt(owner, oldBot, world, scene, oldSession));
        var entry = PvpTestBotRoster.entry(oldBot);
        assertSame(world, entry.world());
        assertSame(scene, entry.scene());
        assertSame(oldSession, entry.ownerSession());
        assertEquals(List.of(oldBot), PvpTestBotRoster.bots(owner));
        server.getPlayers().remove(98);
        PvpTestBotRoster.remove(oldBot);
        assertFalse(PvpTestBotRoster.adopt(owner, oldBot, world, scene, oldSession));
        assertNull(PvpTestBotRoster.entry(oldBot));
    }

    @Test
    void aFullWorldRejectsSpawningBeforeAllocatingAnotherUidOrChangingMembers() throws Exception {
        int previousLimit = Configuration.GAME_OPTIONS.avatarLimits.multiplayerTeam;
        Configuration.GAME_OPTIONS.avatarLimits.multiplayerTeam = 4;
        try {
            bot(98);
            bot(99);
            bot(100);
            assertEquals(4, world.getPlayerCount());
            var before = List.copyOf(world.getPlayers());
            var spawn = PvpTestBot.class.getDeclaredMethod("spawnNow", Player.class);
            spawn.setAccessible(true);
            var failure = assertThrows(InvocationTargetException.class, () -> spawn.invoke(null, owner));
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("人数已满"));
            assertEquals(before, world.getPlayers());
            assertEquals(4, server.getPlayers().size());
            assertFalse(server.getPlayers().containsKey(101));
        } finally {
            Configuration.GAME_OPTIONS.avatarLimits.multiplayerTeam = previousLimit;
        }
    }

    private Player bot(int uid) throws Exception {
        var bot = allocate(PvpTestBot.BotPlayer.class);
        bot.setUid(uid);
        bot.setSession(session(bot));
        bot.setWorld(world);
        set(bot, Player.class, "scene", scene);
        server.getPlayers().put(uid, bot);
        world.players.add(bot);
        created.add(bot);
        return bot;
    }

    private RecordingSession session(Player player) throws Exception {
        var session = allocate(RecordingSession.class);
        session.server = server;
        session.player = player;
        return session;
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        var field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
    }

    private static void set(Object target, Class<?> declaring, String name, Object value) throws Exception {
        var field = declaring.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class RecordingWorld extends World {
        private List<Player> players;
        private Player host;
        private RecordingWorld() { super((GameServer) null, null); }
        @Override public List<Player> getPlayers() { return players; }
        @Override public Player getHost() { return host; }
        @Override public int getPlayerCount() { return players.size(); }
    }

    private static final class RecordingSession extends GameSession {
        private GameServer server;
        private Player player;
        private RecordingSession() { super(null); }
        @Override public boolean isActive() { return true; }
        @Override public GameServer getServer() { return server; }
        @Override public Player getPlayer() { return player; }
    }

    private static final class RecordingPlayer extends Player {
        private World world;
        private Scene scene;
        private GameSession session;
        private RecordingPlayer() { super((GameSession) null); }
        @Override public World getWorld() { return world; }
        @Override public Scene getScene() { return scene; }
        @Override public GameSession getSession() { return session; }
    }
}
