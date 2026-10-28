package emu.grasscutter.scripts;

import com.google.protobuf.InvalidProtocolBufferException;
import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.world.Scene;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.SceneEntityAppearNotifyOuterClass.SceneEntityAppearNotify;
import emu.grasscutter.net.proto.SceneEntityUpdateNotifyOuterClass.SceneEntityUpdateNotify;
import emu.grasscutter.net.proto.GroupSuiteNotifyOuterClass.GroupSuiteNotify;
import emu.grasscutter.net.proto.VisionTypeOuterClass.VisionType;
import emu.grasscutter.server.packet.send.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.file.Files;
import emu.grasscutter.utils.FileUtils;
import javax.script.ScriptException;
import org.luaj.vm2.LuaTable;

/** Script and region vision are per player; hiding a group never unloads another player's entities. */
public final class ScriptVisionManager {
    public enum Operation { SET, ADD, REMOVE }
    private record Snapshot(Set<GameEntity> entities, Map<Integer,Integer> groups) {}
    private static final class State {
        Set<Integer> manual = Set.of(1);
        Set<Integer> regions = Set.of();
        Set<Integer> effective = Set.of(1);
        boolean regionActive = true;
    }
    private final Scene scene;
    private final Map<Integer, State> players = new HashMap<>();
    private static final Map<Integer,Map<Integer,Set<Integer>>> mutexByScene = new ConcurrentHashMap<>();

    public ScriptVisionManager(Scene scene) { this.scene = scene; }

    public int change(int[] uids, int[] types, Operation operation) {
        synchronized (scene) {
            if (uids.length == 0 || types.length == 0 || Arrays.stream(types).anyMatch(type -> type < 0)) return -1;
            var targets = new ArrayList<Player>();
            for (int uid : uids) {
                var player = scene.getPlayers().stream().filter(p -> p.getUid() == uid).findFirst().orElse(null);
                if (player == null) return -1;
                if (!targets.contains(player)) targets.add(player);
            }
            for (var player : targets) {
                var before = visibleSnapshot(player);
                var state = players.computeIfAbsent(player.getUid(), ignored -> new State());
                var next = new HashSet<>(state.manual);
                if (operation == Operation.SET) next.clear();
                for (int type : types) {
                    if (operation == Operation.REMOVE) next.remove(type);
                    else next.add(type);
                }
                state.manual = Set.copyOf(next);
                recalculate(state);
                publishChanges(player, before);
            }
            return 0;
        }
    }

    public int setRegionActive(int uid, boolean active) {
        synchronized (scene) {
            var player = scene.getPlayers().stream().filter(p -> p.getUid() == uid).findFirst().orElse(null);
            if (player == null) return -1;
            var before = visibleSnapshot(player);
            var state = players.computeIfAbsent(uid, ignored -> new State());
            state.regionActive = active;
            recalculate(state);
            publishChanges(player, before);
            return 0;
        }
    }

    public Set<Integer> manualTypes(int uid) {
        synchronized (scene) {
            var state = players.get(uid);
            return state == null ? Set.of(1) : state.manual;
        }
    }

    public void updateRegions(Collection<EntityRegion> regions) {
        synchronized (scene) {
            for (var player : scene.getPlayers()) {
                var types = new HashSet<Integer>();
                for (var region : regions) if (!region.getMetaRegion().vision_type_list.isEmpty()
                        && region.getMetaRegion().contains(player.getPosition()))
                    types.addAll(region.getMetaRegion().vision_type_list);
                var state = players.get(player.getUid());
                if (state == null && types.isEmpty()) continue;
                if (state != null && state.regions.equals(types)) continue;
                var before = visibleSnapshot(player);
                if (state == null) {
                    state = new State();
                    players.put(player.getUid(), state);
                }
                state.regions = Set.copyOf(types);
                recalculate(state);
                publishChanges(player, before);
            }
        }
    }

    private void recalculate(State state) {
        var effective = new HashSet<>(state.manual);
        if (state.regionActive) {
            var regions = new HashSet<>(state.regions);
            var mutex = mutexByScene.computeIfAbsent(scene.getId(),ScriptVisionManager::loadMutex);
            for (int type : state.regions) regions.removeAll(mutex.getOrDefault(type,Set.of()));
            effective.addAll(regions);
        }
        state.effective = Set.copyOf(effective);
    }

    private static Map<Integer,Set<Integer>> loadMutex(int sceneId) {
        String path = "Scene/"+sceneId+"/scene"+sceneId+"_vision_type_mutex.lua";
        if (!Files.exists(FileUtils.getScriptPath(path))) return Map.of();
        var bindings = ScriptLoader.getEngine().createBindings();
        try { ScriptLoader.eval(ScriptLoader.getScript(path),bindings); }
        catch (ScriptException error) { throw new IllegalStateException("Cannot load scene vision mutex "+sceneId,error); }
        var table = (LuaTable) bindings.get("mutex_table");
        var result = new HashMap<Integer,Set<Integer>>();
        for (var key : table.keys()) {
            var entry = table.get(key);
            var excluded = new HashSet<Integer>();
            var targets = entry.get("target").checktable();
            for (var target : targets.keys()) excluded.add(targets.get(target).checkint());
            result.put(entry.get("source").checkint(),Set.copyOf(excluded));
        }
        return Map.copyOf(result);
    }

    public boolean canSee(Player player, GameEntity entity) {
        synchronized (scene) {
            if (entity == null || entity.getScene() != scene) return false;
            if (entity.getGroupId() == 0) return true;
            var group = scene.getScriptManager().getGroupInstanceById(entity.getGroupId());
            int type = group == null ? 0 : group.getLuaGroup().vision_type;
            return canSeeType(player,type);
        }
    }

    public boolean canSeeType(Player player, int type) {
        synchronized (scene) {
            var state = players.get(player.getUid());
            return type == 0 || (state == null ? Set.of(1) : state.effective).contains(type);
        }
    }

    private Snapshot visibleSnapshot(Player player) {
        var result = new HashSet<GameEntity>();
        for (var entity : scene.getEntities().values()) if (canSee(player, entity)) result.add(entity);
        var groups = new HashMap<Integer,Integer>();
        for (var instance : scene.getScriptManager().getCachedGroupInstances().values())
            if (scene.getScriptManager().getGroupInstanceById(instance.getGroupId()) == instance
                    && instance.getActiveSuiteId() > 0 && canSeeType(player,instance.getLuaGroup().vision_type))
                groups.put(instance.getGroupId(),instance.getActiveSuiteId());
        return new Snapshot(result,groups);
    }

    private void publishChanges(Player player, Snapshot before) {
        var after = visibleSnapshot(player);
        var hiddenGroups = before.groups().keySet().stream().filter(group -> !after.groups().containsKey(group)).toList();
        if (!hiddenGroups.isEmpty()) player.sendPacket(new PacketGroupUnloadNotify(hiddenGroups));
        var appearedGroups = new HashMap<>(after.groups());
        appearedGroups.keySet().removeAll(before.groups().keySet());
        if (!appearedGroups.isEmpty()) {
            var packet = new BasePacket(PacketOpcodes.GroupSuiteNotify);
            packet.setData(GroupSuiteNotify.newBuilder().putAllGroupMap(appearedGroups));
            player.sendPacket(packet);
        }
        var removed = before.entities().stream().filter(entity -> !after.entities().contains(entity)).toList();
        var added = after.entities().stream().filter(entity -> !before.entities().contains(entity))
                .filter(entity -> !(entity instanceof Rebornable rebornable) || !rebornable.isInCD()).toList();
        if (!removed.isEmpty()) player.sendPacket(new PacketSceneEntityDisappearNotify(removed, VisionType.VisionType_VISION_MISS));
        if (!added.isEmpty()) player.sendPacket(new PacketSceneEntityAppearNotify(added, VisionType.VisionType_VISION_MEET));
    }

    public BasePacket filterPacket(Player player, BasePacket packet) {
        if (packet.getOpcode() != PacketOpcodes.SceneEntityAppearNotify
                && packet.getOpcode() != PacketOpcodes.SceneEntityUpdateNotify
                && packet.getOpcode() != PacketOpcodes.GroupSuiteNotify) return packet;
        synchronized (scene) {
            try {
                if (packet.getOpcode() == PacketOpcodes.SceneEntityAppearNotify) {
                    var original = SceneEntityAppearNotify.parseFrom(packet.getData());
                    var visible = original.getEntityListList().stream()
                            .filter(info -> isVisibleId(player, info.getEntityId())).toList();
                    if (visible.size() == original.getEntityListCount()) return packet;
                    if (visible.isEmpty()) return null;
                    var filtered = new BasePacket(packet.getOpcode(), packet.shouldBuildHeader());
                    filtered.setData(original.toBuilder().clearEntityList().addAllEntityList(visible));
                    return filtered;
                }
                if (packet.getOpcode() == PacketOpcodes.SceneEntityUpdateNotify) {
                    var original = SceneEntityUpdateNotify.parseFrom(packet.getData());
                    var visible = original.getEntityListList().stream()
                            .filter(info -> isVisibleId(player, info.getEntityId())).toList();
                    if (visible.size() == original.getEntityListCount()) return packet;
                    if (visible.isEmpty()) return null;
                    var filtered = new BasePacket(packet.getOpcode(), packet.shouldBuildHeader());
                    filtered.setData(original.toBuilder().clearEntityList().addAllEntityList(visible));
                    return filtered;
                }
                if (packet.getOpcode() == PacketOpcodes.GroupSuiteNotify) {
                    var original = GroupSuiteNotify.parseFrom(packet.getData());
                    var visible = new HashMap<>(original.getGroupMapMap());
                    visible.keySet().removeIf(groupId -> {
                        var instance = scene.getScriptManager().getGroupInstanceById(groupId);
                        return instance != null && !canSeeType(player,instance.getLuaGroup().vision_type);
                    });
                    if (visible.size() == original.getGroupMapCount()) return packet;
                    if (visible.isEmpty()) return null;
                    var filtered = new BasePacket(packet.getOpcode(),packet.shouldBuildHeader());
                    filtered.setData(original.toBuilder().clearGroupMap().putAllGroupMap(visible));
                    return filtered;
                }
                return packet;
            } catch (InvalidProtocolBufferException error) {
                throw new IllegalStateException("Invalid server entity notification", error);
            }
        }
    }

    private boolean isVisibleId(Player player, int entityId) {
        var entity = scene.getEntities().get(entityId);
        return entity == null || canSee(player, entity);
    }

    public void onPlayerLeave(Player player) {
        synchronized (scene) { players.remove(player.getUid()); }
    }
}
