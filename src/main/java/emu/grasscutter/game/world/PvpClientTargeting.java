package emu.grasscutter.game.world;

import com.google.protobuf.InvalidProtocolBufferException;
import emu.grasscutter.game.entity.EntityAvatar;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.packet.PacketOpcodes;
import emu.grasscutter.net.proto.SceneEntityAppearNotifyOuterClass.SceneEntityAppearNotify;
import emu.grasscutter.net.proto.SceneTeamUpdateNotifyOuterClass.SceneTeamUpdateNotify;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.game.GameSessionManager;
import emu.grasscutter.server.packet.send.PacketServerGlobalValueChangeNotify;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Server-issued targeting permission for the pair authenticated by PvpDuels, never a damage source. */
public final class PvpClientTargeting {
    public static final String KEY = "SGV_AstaPvpPair";
    public static final int KEY_HASH = 237768612;
    private static volatile Pair active;

    private static final class Pair {
        final Player first, second;
        final World world;
        final Scene scene;
        final GameSession firstSession, secondSession;
        final float value;
        final Map<EntityAvatar, Float> marked = new IdentityHashMap<>();

        Pair(Player first, Player second) {
            this.first = first;
            this.second = second;
            world = first.getWorld();
            scene = first.getScene();
            firstSession = first.getSession();
            secondSession = second.getSession();
            value = ThreadLocalRandom.current().nextInt(1, 1 << 24);
        }
    }

    private PvpClientTargeting() {}

    /** Called after PvpDuels has authenticated the two players and their current world. */
    public static synchronized void grant(Player first, Player second) {
        if (first == null || second == null) throw new IllegalArgumentException("Missing duel players");
        var pair = new Pair(first, second);
        if (!valid(pair)) throw new IllegalArgumentException("Duel targeting requires the same live sessions, world and scene");
        clear();
        active = pair;
        refresh(pair, true);
    }

    public static synchronized void clear() {
        var pair = active;
        active = null;
        if (pair != null) clearMarked(pair);
    }

    public static synchronized void onPlayerLeave(Player player) {
        var pair = active;
        if (pair != null && PvpDuelRoster.owns(pair.first)) {
            if (player == pair.first) { clear(); return; }
            boolean targetLeft = player == pair.second;
            for (var marked : pair.marked.keySet()) if (marked.getPlayer() == player) targetLeft = true;
            if (!targetLeft) return;
            var bots = PvpDuelRoster.bots(pair.first);
            if (!bots.isEmpty()) { grant(pair.first, bots.getFirst()); return; }
        }
        if (pair != null && (player == pair.first || player == pair.second)) clear();
    }

    /** GameSession.send calls this only after the original packet was written to its KCP tunnel. */
    public static void onPacketSent(GameSession session, BasePacket packet) {
        int opcode = packet.getOpcode();
        // In particular, our own ServerGlobalValueChangeNotify must never recurse here.
        if (opcode != PacketOpcodes.SceneEntityAppearNotify && opcode != PacketOpcodes.SceneTeamUpdateNotify) return;
        var pair = active;
        if (pair == null || !recipient(pair, session)) return;
        byte[] data = packet.getData();
        if (data == null) return;
        var loop = GameSessionManager.getLogicThread();
        if (loop.inEventLoop()) afterPacket(pair, session, opcode, data);
        else loop.execute(() -> afterPacket(pair, session, opcode, data));
    }

    private static synchronized void afterPacket(Pair pair, GameSession session, int opcode, byte[] data) {
        if (active != pair || !recipient(pair, session)) return;
        if (!valid(pair)) { clear(); return; }
        var first = pair.first.getTeamManager().getCurrentAvatarEntity();
        var second = pair.second.getTeamManager().getCurrentAvatarEntity();
        boolean containsAvatar;
        try {
            if (opcode == PacketOpcodes.SceneEntityAppearNotify) {
                containsAvatar = SceneEntityAppearNotify.parseFrom(data).getEntityListList().stream()
                        .anyMatch(entity -> matches(entity.getEntityId(), first, second));
            } else {
                containsAvatar = SceneTeamUpdateNotify.parseFrom(data).getSceneTeamAvatarListList().stream()
                        .anyMatch(entity -> matches(entity.getEntityId(), first, second));
            }
        } catch (InvalidProtocolBufferException malformedNativePacket) {
            // A malformed/unrelated outgoing packet cannot grant targeting permission.
            return;
        }
        if (containsAvatar) refresh(pair, true);
    }

    private static boolean matches(int entityId, EntityAvatar first, EntityAvatar second) {
        var pair = active;
        if (pair != null && PvpDuelRoster.owns(pair.first)) {
            for (var bot : PvpDuelRoster.bots(pair.first)) {
                var avatar = bot.getTeamManager().getCurrentAvatarEntity();
                if (avatar != null && avatar.getId() == entityId) return true;
            }
        }
        return (first != null && entityId == first.getId()) || (second != null && entityId == second.getId());
    }

    private static boolean recipient(Pair pair, GameSession session) {
        if (PvpDuelRoster.owns(pair.first))
            return session == pair.firstSession && session.getPlayer() == pair.first;
        return (session == pair.firstSession && session.getPlayer() == pair.first)
                || (session == pair.secondSession && session.getPlayer() == pair.second);
    }

    private static boolean valid(Pair pair) {
        if (PvpDuelRoster.owns(pair.first))
            return PvpDuelRoster.validOwner() && pair.first.getSession() == pair.firstSession
                    && pair.first.getWorld() == pair.world && pair.first.getScene() == pair.scene
                    && !PvpDuelRoster.bots(pair.first).isEmpty();
        return pair.first != pair.second && pair.world != null && pair.scene != null
                && pair.firstSession != null && pair.secondSession != null
                && pair.firstSession.isActive() && pair.secondSession.isActive()
                && pair.firstSession.getPlayer() == pair.first && pair.secondSession.getPlayer() == pair.second
                && pair.first.isOnline() && pair.second.isOnline()
                && pair.first.getSession() == pair.firstSession && pair.second.getSession() == pair.secondSession
                && pair.first.getWorld() == pair.world && pair.second.getWorld() == pair.world
                && pair.scene.getWorld() == pair.world
                && pair.first.getScene() == pair.scene && pair.second.getScene() == pair.scene
                && pair.world.getPlayers().stream().anyMatch(player -> player == pair.first)
                && pair.world.getPlayers().stream().anyMatch(player -> player == pair.second)
                && pair.scene.getPlayers().stream().anyMatch(player -> player == pair.first)
                && pair.scene.getPlayers().stream().anyMatch(player -> player == pair.second);
    }

    private static void refresh(Pair pair, boolean reannounce) {
        var first = pair.first.getTeamManager().getCurrentAvatarEntity();
        var second = pair.second.getTeamManager().getCurrentAvatarEntity();
        if (PvpDuelRoster.owns(pair.first)) {
            var targets = new ArrayList<EntityAvatar>();
            for (var bot : PvpDuelRoster.bots(pair.first)) {
                var avatar = bot.getTeamManager().getCurrentAvatarEntity();
                if (present(pair, avatar) && avatar.isAlive()) targets.add(avatar);
            }
            if (!present(pair, first) || !first.isAlive() || targets.isEmpty()) { clearMarked(pair); return; }
            for (var old : new ArrayList<>(pair.marked.keySet())) {
                if (old != first && !targets.contains(old)) {
                    write(pair, old, 0);
                    pair.marked.remove(old);
                }
            }
            mark(pair, first, pair.value, reannounce);
            for (var target : targets) mark(pair, target, -pair.value, reannounce);
            return;
        }
        if (!present(pair, first) || !present(pair, second)) { clearMarked(pair); return; }
        for (var old : new ArrayList<>(pair.marked.keySet())) {
            if (old != first && old != second) {
                write(pair, old, 0);
                pair.marked.remove(old);
            }
        }
        mark(pair, first, pair.value, reannounce);
        mark(pair, second, -pair.value, reannounce);
    }

    private static boolean present(Pair pair, EntityAvatar avatar) {
        return avatar != null && avatar.getScene() == pair.scene
                && pair.scene.getEntityById(avatar.getId()) == avatar;
    }

    private static void mark(Pair pair, EntityAvatar avatar, float value, boolean reannounce) {
        Float previous = pair.marked.put(avatar, value);
        if (reannounce || previous == null || previous != value) write(pair, avatar, value);
    }

    private static void clearMarked(Pair pair) {
        for (var avatar : new ArrayList<>(pair.marked.keySet())) write(pair, avatar, 0);
        pair.marked.clear();
    }

    private static void write(Pair pair, EntityAvatar avatar, float value) {
        if (value == 0) avatar.getGlobalAbilityValues().remove(KEY);
        else avatar.getGlobalAbilityValues().put(KEY, value);
        pair.scene.broadcastPacket(new PacketServerGlobalValueChangeNotify(avatar, KEY, value));
    }
}
