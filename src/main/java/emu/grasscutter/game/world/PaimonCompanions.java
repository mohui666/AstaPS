package emu.grasscutter.game.world;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.GameData;
import emu.grasscutter.game.entity.EntityGadget;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.player.Player.SceneLoadState;
import emu.grasscutter.net.proto.EntityMoveInfoOuterClass.EntityMoveInfo;
import emu.grasscutter.net.proto.MotionInfoOuterClass.MotionInfo;
import emu.grasscutter.net.proto.MotionStateOuterClass.MotionState;
import emu.grasscutter.net.proto.VectorOuterClass.Vector;
import emu.grasscutter.net.proto.VisionTypeOuterClass.VisionType;
import emu.grasscutter.server.game.GameServer;
import emu.grasscutter.server.game.GameSessionManager;
import emu.grasscutter.server.packet.send.PacketSceneEntityMoveNotify;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Persistent, player-owned Paimon companions; scene NPCs and quest Paimon are never adopted. */
public final class PaimonCompanions {
    public static final int GADGET_ID = 90000003;
    public static final int MAX_COUNT = 20;
    private static final int PERIOD_MS = 200;
    private static PaimonCompanions installed;
    private static ScheduledFuture<?> ticker;

    private final Path directory;
    private final Map<Integer, Integer> desired = new HashMap<>();
    private final Map<Integer, ArrayList<Follower>> active = new HashMap<>();

    PaimonCompanions(Path directory) { this.directory = directory; }

    /** Also callable after loading this class through a local Java attach agent. */
    public static void install() {
        var server = Grasscutter.getGameServer();
        if (server == null) throw new IllegalStateException("游戏服务器尚未启动。");
        install(server);
    }

    public static synchronized void install(GameServer server) {
        if (ticker != null && !ticker.isCancelled()) return;
        installed = new PaimonCompanions(Path.of("data", "assistant-companions"));
        var service = installed;
        ticker = GameSessionManager.getLogicThread().scheduleAtFixedRate(() -> {
            try {
                service.tick(server.getPlayers());
            } catch (RuntimeException error) {
                Grasscutter.getLogger().error("Paimon companion update failed.", error);
            }
        }, 0, PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    /** Completes on the game logic thread before the caller reports success. */
    public static String configure(Player player, int count) {
        install();
        var service = installed;
        var loop = GameSessionManager.getLogicThread();
        if (loop.inEventLoop()) return service.configureNow(player, count);
        var result = loop.submit(() -> service.configureNow(player, count));
        result.syncUninterruptibly();
        return result.getNow();
    }

    private static void validateCount(int count) {
        if (count < 0 || count > MAX_COUNT) throw new IllegalArgumentException("跟随数量须为 0–" + MAX_COUNT + "，0 表示关闭。");
    }

    private int count(int uid) {
        return desired.computeIfAbsent(uid, ignored -> {
            var file = directory.resolve(uid + ".json");
            if (!Files.exists(file)) return 0;
            try (var reader = Files.newBufferedReader(file)) {
                var value = JsonParser.parseReader(reader).getAsJsonObject().get("count");
                if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
                    throw new IllegalArgumentException("派蒙跟随配置缺少整数 count。");
                int count = value.getAsBigDecimal().intValueExact();
                validateCount(count);
                return count;
            } catch (IOException error) {
                throw new UncheckedIOException("无法读取 UID " + uid + " 的派蒙跟随配置。", error);
            }
        });
    }

    // Calls below are serialized by the existing game logic event loop.
    String configureNow(Player player, int count) {
        validateCount(count);
        if (player == null || !player.isOnline()) throw new IllegalArgumentException("目标玩家尚未在线。");
        if (count > 0 && !GameData.getGadgetDataMap().containsKey(GADGET_ID))
            throw new IllegalArgumentException("当前资源没有派蒙模型 " + GADGET_ID + "。");
        var json = new JsonObject();
        json.addProperty("count", count);
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(player.getUid() + ".json"), json.toString());
        } catch (IOException error) {
            throw new UncheckedIOException("无法保存派蒙跟随配置。", error);
        }
        desired.put(player.getUid(), count);
        update(player, count);
        return count == 0 ? "已关闭 UID " + player.getUid() + " 的派蒙跟随。"
                : "已为 UID " + player.getUid() + " 设置 " + count + " 个派蒙持续跟随；传送、切换场景和重登后会继续跟随。";
    }

    void tick(Map<Integer, Player> players) {
        for (int uid : new ArrayList<>(active.keySet())) {
            var player = players.get(uid);
            if (player == null || !player.isOnline()) clear(uid);
        }
        for (var player : players.values()) {
            if (player != null && player.isOnline()) update(player, count(player.getUid()));
        }
    }

    private void clear(int uid) {
        var followers = active.remove(uid);
        if (followers == null) return;
        for (var follower : followers) remove(uid, follower);
    }

    private static void remove(int uid, Follower follower) {
        var scene = follower.getScene();
        if (follower.ownerUid == uid && scene.getEntityById(follower.getId()) == follower)
            scene.removeEntity(follower, VisionType.VisionType_VISION_REMOVE);
    }

    private void update(Player player, int count) {
        int uid = player.getUid();
        var scene = player.getScene();
        if (count == 0 || scene == null || player.getSceneLoadState() != SceneLoadState.LOADED) {
            clear(uid);
            return;
        }
        var followers = active.get(uid);
        if (followers != null && !followers.isEmpty() && followers.get(0).getScene() != scene) {
            clear(uid);
            followers = null;
        }
        if (followers == null) {
            followers = new ArrayList<>();
            active.put(uid, followers);
        }
        while (followers.size() > count) remove(uid, followers.remove(followers.size() - 1));
        for (int i = 0; i < count; i++) {
            var target = formation(player, i, count);
            Follower follower = i < followers.size() ? followers.get(i) : null;
            if (follower == null || scene.getEntityById(follower.getId()) != follower) {
                follower = new Follower(scene, uid, target, player.getRotation());
                scene.addEntity(follower);
                if (i < followers.size()) followers.set(i, follower);
                else followers.add(follower);
            } else move(follower, target, player.getRotation().getY());
        }
    }

    static Position formation(Player player, int slot, int count) {
        double angle = Math.toRadians(player.getRotation().getY() + (slot - (count - 1) / 2d) * 28);
        float radius = 2.1f + (slot / 7) * 0.7f;
        return player.getPosition().clone().addX((float) Math.sin(angle) * radius)
                .addY(1.1f + (slot % 2) * 0.12f).addZ((float) Math.cos(angle) * radius);
    }

    private static void move(Follower follower, Position target, float idleYaw) {
        var current = follower.getPosition();
        float x = target.getX() - current.getX(), y = target.getY() - current.getY(), z = target.getZ() - current.getZ();
        float distance = (float) Math.sqrt(x * x + y * y + z * z);
        if (distance < 0.05f && !follower.moving) return;
        float step = distance > 20 ? distance : Math.min(distance, (distance > 5 ? 10f : 6f) * PERIOD_MS / 1000f);
        boolean moving = distance > 0.05f && distance <= 20;
        float fraction = distance > 0 ? step / distance : 0;
        current.addX(x * fraction).addY(y * fraction).addZ(z * fraction);
        float yaw = moving ? (float) Math.toDegrees(Math.atan2(x, z)) : idleYaw;
        follower.getRotation().setY(yaw);
        follower.moving = moving;
        var speed = moving ? Vector.newBuilder().setX(x * fraction * 1000f / PERIOD_MS)
                .setY(y * fraction * 1000f / PERIOD_MS).setZ(z * fraction * 1000f / PERIOD_MS)
                : Vector.newBuilder();
        var motion = MotionInfo.newBuilder().setPos(current.toProto()).setRot(follower.getRotation().toProto())
                .setSpeed(speed).setState(moving ? MotionState.MotionState_MOTION_FLY : MotionState.MotionState_MOTION_STANDBY);
        var packet = new PacketSceneEntityMoveNotify(EntityMoveInfo.newBuilder().setEntityId(follower.getId())
                .setSceneTime(follower.getScene().getSceneTime()).setReliableSeq(++follower.moveSequence)
                .setMotionInfo(motion).build());
        follower.getScene().broadcastPacket(packet);
    }

    public static final class Follower extends EntityGadget {
        private final int ownerUid;
        private int moveSequence;
        private boolean moving;

        private Follower(Scene scene, int ownerUid, Position position, Position rotation) {
            super(scene, GADGET_ID, position, rotation, null);
            this.ownerUid = ownerUid;
            for (var block : scene.getLoadedBlocks()) {
                if (block.contains(position)) {
                    setBlockId(block.id);
                    break;
                }
            }
            buildContent();
        }

        public int getOwnerUid() { return ownerUid; }

    }
}
