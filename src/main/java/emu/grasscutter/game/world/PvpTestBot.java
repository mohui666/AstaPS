package emu.grasscutter.game.world;

import emu.grasscutter.data.GameData;
import emu.grasscutter.game.Account;
import emu.grasscutter.game.avatar.Avatar;
import emu.grasscutter.game.entity.EntityAvatar;
import emu.grasscutter.game.inventory.GameItem;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.ElementType;
import emu.grasscutter.game.props.FightProperty;
import emu.grasscutter.game.props.LifeState;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.MotionStateOuterClass.MotionState;
import emu.grasscutter.net.proto.PlayerDieTypeOuterClass.PlayerDieType;
import emu.grasscutter.net.proto.VisionTypeOuterClass.VisionType;
import emu.grasscutter.server.game.GameServer;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.game.GameSessionManager;
import emu.grasscutter.server.packet.send.PacketLifeStateChangeNotify;
import emu.grasscutter.server.packet.send.PacketSceneEntityDisappearNotify;
import emu.grasscutter.server.packet.send.PacketScenePlayerInfoNotify;
import emu.grasscutter.server.packet.send.PacketSceneTeamUpdateNotify;
import emu.grasscutter.server.packet.send.PacketWorldPlayerInfoNotify;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Temporary remote-player target. It has no client, attacks, account writes or automatic revival. */
public final class PvpTestBot {
    public static final int UID = 98;
    private static final int AVATAR_ID = 10000015;
    private record Registration(Player owner, BotPlayer bot, World world, Scene scene, GameSession ownerSession) {}
    private static final AtomicReference<Registration> active = new AtomicReference<>();

    private PvpTestBot() {}

    /** Joins the host's existing instance without transferring the host or changing multiplayer mode. */
    public static Player spawn(Player host) {
        var loop = GameSessionManager.getLogicThread();
        if (loop.inEventLoop()) return spawnNow(host);
        var result = loop.submit(() -> spawnNow(host));
        result.syncUninterruptibly();
        return result.getNow();
    }

    private static Player spawnNow(Player host) {
        if (host == null || host.getUid() != 1 || !host.isOnline() || host.getScene() == null
                || host.getWorld() == null || host.getWorld().getHost() != host)
            throw new IllegalArgumentException("测试机器人仅能加入 UID 1 自己的当前世界。");
        var legacy = active.get();
        if (legacy != null && PvpTestBotRoster.entry(legacy.bot()) == null
                && !PvpTestBotRoster.adopt(legacy.owner(), legacy.bot(), legacy.world(), legacy.scene(), legacy.ownerSession()))
            active.compareAndSet(legacy, null);
        var world = host.getWorld();
        if (world.getPlayerCount() >= emu.grasscutter.config.Configuration.GAME_OPTIONS.avatarLimits.multiplayerTeam)
            throw new IllegalArgumentException("当前世界人数已满，测试机器人也遵守世界人数上限。");
        if (world.getPlayers().stream().anyMatch(player -> player != host && !PvpTestBotRoster.isOwnedPair(host, player)))
            throw new IllegalArgumentException("当前世界有其他玩家；不会移动或修改他们，请在自己的单人世界测试。");
        var server = host.getServer();
        verifyUidAvailable(server.getPlayers());
        var bot = new BotPlayer(server);
        bot.setSceneId(host.getSceneId());
        bot.getPosition().set(inFrontOf(host.getPosition(), host.getRotation().getY()));
        bot.getRotation().set(0, host.getRotation().getY() + 180, 0);
        var registration = new Registration(host, bot, world, host.getScene(), host.getSession());
        active.compareAndSet(null, registration);
        if (server.getPlayers().putIfAbsent(bot.getUid(), bot) != null) {
            active.compareAndSet(registration, null);
            throw new IllegalArgumentException("临时机器人 UID 已上线，未创建机器人。");
        }
        try {
            // Native membership/peer/team setup, preserving this exact dungeon and its progress.
            world.addPlayer(bot, host.getSceneId());
            var scene = bot.getScene();
            var team = bot.getTeamManager().getActiveTeam();
            var previous = new ArrayList<>(team);
            team.clear();
            var entity = new BotEntity(scene, bot.getAvatars().getAvatarById(AVATAR_ID));
            team.add(entity);
            bot.getTeamManager().setCurrentCharacterIndex(0);
            for (var old : previous)
                world.broadcastPacket(new PacketSceneEntityDisappearNotify(old, VisionType.VisionType_VISION_REMOVE));
            world.broadcastPacket(new PacketWorldPlayerInfoNotify(world));
            world.broadcastPacket(new PacketScenePlayerInfoNotify(world));
            world.broadcastPacket(new PacketSceneTeamUpdateNotify(host));
            scene.spawnPlayer(bot);
            PvpTestBotRoster.register(host, bot);
            PvpDuels.enable(host, bot);
            return bot;
        } catch (RuntimeException | Error failure) {
            active.compareAndSet(registration, null);
            PvpTestBotRoster.discard(bot, world);
            throw failure;
        }
    }

    public static String remove(Player host) {
        var loop = GameSessionManager.getLogicThread();
        if (loop.inEventLoop()) return removeNow(host);
        var result = loop.submit(() -> removeNow(host));
        result.syncUninterruptibly();
        return result.getNow();
    }

    private static String removeNow(Player host) {
        var registration = active.get();
        if (registration != null && PvpTestBotRoster.entry(registration.bot()) == null
                && !PvpTestBotRoster.adopt(registration.owner(), registration.bot(), registration.world(), registration.scene(), registration.ownerSession()))
            active.compareAndSet(registration, null);
        var direct = PvpTestBotRoster.entry(host);
        var targets = direct == null ? PvpTestBotRoster.bots(host) : java.util.List.of(host);
        if (targets.isEmpty()) {
            if (registration != null && host != registration.owner() && host != registration.bot())
                throw new IllegalArgumentException("这不是你的测试机器人。");
            return "没有临时测试机器人。";
        }
        int removed = 0;
        for (var target : targets) {
            var entry = PvpTestBotRoster.remove(target);
            if (entry == null) continue;
            var previous = active.get();
            if (previous != null && previous.bot() == target) active.compareAndSet(previous, null);
            // Clear identities first: native world.removePlayer re-enters onPlayerLeave.
            var bot = (BotPlayer) entry.bot();
            PvpDuels.onPlayerLeave(bot);
            if (bot.getWorld() == entry.world()
                    && entry.world().getPlayers().stream().anyMatch(player -> player == bot))
                entry.world().removePlayer(bot);
            bot.getServer().getPlayers().computeIfPresent(bot.getUid(), (id, player) -> player == bot ? null : player);
            bot.getSession().setState(GameSession.SessionState.INACTIVE);
            removed++;
        }
        return "已移除 " + removed + " 个临时测试机器人；你的场景和位置未改变。";
    }

    public static boolean isOwnedPair(Player first, Player second) {
        var registration = active.get();
        if (registration != null && PvpTestBotRoster.entry(registration.bot()) == null
                && !PvpTestBotRoster.adopt(registration.owner(), registration.bot(), registration.world(), registration.scene(), registration.ownerSession()))
            active.compareAndSet(registration, null);
        return PvpTestBotRoster.isOwnedPair(first, second);
    }

    public static void onPlayerLeave(Player player) {
        var registration = active.get();
        if (registration != null && (player == registration.owner() || player == registration.bot())
                && PvpTestBotRoster.entry(registration.bot()) == null
                && !PvpTestBotRoster.adopt(registration.owner(), registration.bot(), registration.world(), registration.scene(), registration.ownerSession()))
            active.compareAndSet(registration, null);
        if (PvpTestBotRoster.entry(player) != null || !PvpTestBotRoster.bots(player).isEmpty()) removeNow(player);
    }

    static void verifyUidAvailable(Map<Integer, Player> online) {
        PvpTestBotRoster.nextAvailableUid(online);
    }

    static Position inFrontOf(Position origin, float yawDegrees) {
        // The host is already standing here; an unchecked offset can put the target inside a wall.
        return origin.clone();
    }

    static final class BotPlayer extends Player {
        BotPlayer(GameServer server) {
            super(new BotSession(server));
            setUid(PvpTestBotRoster.nextAvailableUid(server.getPlayers()));
            getSession().getAccount().setId("temporary-pvp-bot-" + getUid());
            getSession().getAccount().setUsername("temporary-pvp-bot-" + getUid());
            getSession().setPlayer(this);
            getSession().setState(GameSession.SessionState.ACTIVE);
            setSceneLoadState(SceneLoadState.LOADED);
            var data = GameData.getAvatarDataMap().get(AVATAR_ID);
            if (data == null) throw new IllegalArgumentException("当前资源缺少凯亚角色数据。");
            var avatar = new BotAvatar(data);
            avatar.setLevel(90);
            avatar.setPromoteLevel(6);
            getAvatars().addAvatar(avatar);
            var weaponData = GameData.getItemDataMap().get(data.getInitialWeapon());
            if (weaponData == null) throw new IllegalArgumentException("当前资源缺少凯亚初始武器。");
            var weapon = new BotItem(weaponData);
            weapon.setOwner(this);
            avatar.equipItem(weapon, true);
            avatar.setCurrentHp(avatar.getFightProperty(FightProperty.FIGHT_PROP_MAX_HP));
            avatar.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP, avatar.getCurrentHp());
            getTeamManager().getCurrentSinglePlayerTeamInfo().addAvatar(avatar);
        }

        @Override public String getNickname() { return "PvP 测试机器人 " + getUid(); }
        @Override public String getSignature() { return "临时玩家靶：不攻击、不自动回血或复活。"; }
        @Override public int getHeadImage() { return AVATAR_ID; }
        @Override public void save() {}
        @Override public void sendPacket(BasePacket packet) {}
        @Override public void onLogout() { PvpTestBot.onPlayerLeave(this); }
        @Override public synchronized void onTick() {
            var legacy = active.get();
            if (legacy != null && legacy.bot() == this && PvpTestBotRoster.entry(this) == null
                    && !PvpTestBotRoster.adopt(legacy.owner(), this, legacy.world(), legacy.scene(), legacy.ownerSession()))
                active.compareAndSet(legacy, null);
            var registration = PvpTestBotRoster.entry(this);
            if (registration == null) return;
            if (!registration.owner().isOnline() || registration.owner().getWorld() != registration.world()
                    || registration.owner().getScene() != registration.scene()
                    || registration.owner().getSession() != registration.ownerSession()
                    || getServer().getPlayers().get(getUid()) != this) {
                removeNow(this);
                return;
            }
            var entity = getTeamManager().getCurrentAvatarEntity();
            if (entity instanceof BotEntity target) target.finishDeath(0);
        }
    }

    private static final class BotSession extends GameSession {
        BotSession(GameServer server) {
            super(server);
            var account = new Account();
            account.setId("temporary-pvp-bot-98");
            account.setUsername("temporary-pvp-bot-98");
            setAccount(account);
        }
        @Override public void send(BasePacket packet) {}
        @Override public void close() { setState(SessionState.INACTIVE); PvpTestBot.onPlayerLeave(getPlayer()); }
        @Override public boolean isConnected() { return isActive(); }
    }

    static final class BotAvatar extends Avatar {
        BotAvatar(emu.grasscutter.data.excels.avatar.AvatarData data) { super(data); }
        @Override public void save() {}
    }

    private static final class BotItem extends GameItem {
        BotItem(emu.grasscutter.data.excels.ItemData data) { super(data); }
        @Override public void save() {}
    }

    static final class BotEntity extends EntityAvatar {
        private boolean dying;
        BotEntity(Scene scene, Avatar avatar) { super(scene, avatar); setMotionState(MotionState.MotionState_MOTION_STANDBY); }

        @Override public void checkIfDead() {
            super.checkIfDead();
            if (isDead()) { dying = true; setDead(false); }
        }
        @Override public void damage(float amount, int killerId, ElementType type) {
            super.damage(amount, killerId, type);
            finishDeath(killerId);
        }
        void finishDeath(int killerId) {
            if (!dying) return;
            dying = false;
            setDead(true);
            getScene().broadcastPacket(new PacketLifeStateChangeNotify(killerId, this, LifeState.LIFE_DEAD));
            getScene().removeEntity(this, VisionType.VisionType_VISION_DIE);
        }
        // A target dummy's team wipe must not fire dungeon/quest callbacks for the real host.
        @Override public void onDeath(int killerId) { setDead(true); }
        @Override public void onDeath(PlayerDieType type, int killerId) { setDead(true); }
    }
}
