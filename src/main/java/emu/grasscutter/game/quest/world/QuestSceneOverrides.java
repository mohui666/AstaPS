package emu.grasscutter.game.quest.world;

import dev.morphia.annotations.Entity;
import java.util.Collection;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.Getter;

/** Player-owned quest overrides; native scene and dungeon definitions remain immutable. */
@Entity
public final class QuestSceneOverrides {
    private Map<Integer, DungeonExit> dungeonExits = new HashMap<>();
    private Map<Integer, Set<Integer>> hiddenPoints = new HashMap<>();
    private Map<Integer, Set<Integer>> revealedPoints = new HashMap<>();
    private Map<Integer, Boolean> mapLayerUnlocks = new HashMap<>();
    private Set<Integer> mapLayerShownGroups = new HashSet<>();
    private List<TemporaryResource> temporaryResources = new ArrayList<>();
    private Map<Integer, Integer> weatherBeforeTemporary = new HashMap<>();
    private Map<Integer, Map<Integer, Integer>> climateAreaTypes = new HashMap<>();

    public static final int TEMP_POINT = 2;
    public static final int TEMP_WEATHER_GADGET = 3;
    public static final int TEMP_SCENE_TAG = 5;

    @Entity
    @Getter
    public static final class TemporaryResource {
        private int ownerQuest;
        private int sceneId;
        private int kind;
        private int resourceId;
        private boolean enabled;

        public TemporaryResource() {}

        public TemporaryResource(int owner, int scene, int kind, int id, boolean enabled) {
            if (owner <= 0 || scene <= 0 || id <= 0 || !supportedTemporaryKind(kind))
                throw new IllegalArgumentException("Invalid temporary quest resource");
            this.ownerQuest = owner;
            this.sceneId = scene;
            this.kind = kind;
            this.resourceId = id;
            this.enabled = enabled;
        }
    }

    public static boolean supportedTemporaryKind(int kind) {
        return kind == TEMP_POINT || kind == TEMP_WEATHER_GADGET || kind == TEMP_SCENE_TAG;
    }

    public List<TemporaryResource> temporaryResources() { return List.copyOf(this.temporaryResources); }

    public void putTemporaryResource(int owner, int scene, int kind, int id, boolean enabled) {
        var resource = new TemporaryResource(owner, scene, kind, id, enabled);
        this.temporaryResources.removeIf(old -> old.ownerQuest == owner && old.sceneId == scene
                && old.kind == kind && old.resourceId == id);
        this.temporaryResources.add(resource);
    }

    public Boolean temporaryValue(int scene, int kind, int id) {
        for (int i = this.temporaryResources.size() - 1; i >= 0; i--) {
            var value = this.temporaryResources.get(i);
            if (value.sceneId == scene && value.kind == kind && value.resourceId == id) return value.enabled;
        }
        return null;
    }

    /** Native DEL accepts all kinds (0), all ids (0), and explicit cross-quest scene cleanup. */
    public List<TemporaryResource> clearTemporaryResources(int scene, int kind, int id, int onlyOwner) {
        if (scene <= 0 || id < 0 || kind != 0 && !supportedTemporaryKind(kind))
            throw new IllegalArgumentException("Invalid temporary resource cleanup");
        var removed = this.temporaryResources.stream().filter(value -> value.sceneId == scene
                && (kind == 0 || value.kind == kind) && (id == 0 || value.resourceId == id)
                && (onlyOwner == 0 || value.ownerQuest == onlyOwner)).toList();
        this.temporaryResources.removeAll(removed);
        return removed;
    }

    public void rememberWeatherBeforeTemporary(int scene, int weather) {
        this.weatherBeforeTemporary.putIfAbsent(scene, weather);
    }

    public int weatherBeforeTemporary(int scene, int fallback) {
        return this.weatherBeforeTemporary.getOrDefault(scene, fallback);
    }

    public void updatePermanentWeather(int scene, int weather) {
        if (this.weatherBeforeTemporary.containsKey(scene)) this.weatherBeforeTemporary.put(scene, weather);
    }

    public void retireWeatherBaseline(int scene) {
        if (this.temporaryResources.stream().noneMatch(value -> value.sceneId == scene
                && value.kind == TEMP_WEATHER_GADGET)) this.weatherBeforeTemporary.remove(scene);
    }

    /** Zero is an explicit closure, so it must survive serialization separately from no override. */
    public synchronized boolean setClimateAreaType(int scene, int area, int type) {
        if (scene <= 0 || area <= 0 || type < 0) throw new IllegalArgumentException("Invalid climate area");
        if (this.climateAreaTypes == null) this.climateAreaTypes = new HashMap<>();
        Integer previous = this.climateAreaTypes.computeIfAbsent(scene, ignored -> new HashMap<>()).put(area, type);
        return previous == null || previous != type;
    }

    public synchronized Map<Integer, Integer> climateAreaTypes(int scene) {
        if (this.climateAreaTypes == null) this.climateAreaTypes = new HashMap<>();
        return Map.copyOf(this.climateAreaTypes.getOrDefault(scene, Map.of()));
    }

    @Entity
    @Getter
    public static final class DungeonExit {
        private int sceneId;
        private int pointId;

        public DungeonExit() {}

        public DungeonExit(int sceneId, int pointId) {
            if (sceneId <= 0 || pointId <= 0) throw new IllegalArgumentException("Invalid dungeon exit");
            this.sceneId = sceneId;
            this.pointId = pointId;
        }
    }

    public DungeonExit getDungeonExit(int dungeonId) { return this.dungeonExits.get(dungeonId); }

    public void setDungeonExit(int dungeonId, int sceneId, int pointId) {
        if (dungeonId <= 0) throw new IllegalArgumentException("Invalid dungeon id");
        this.dungeonExits.put(dungeonId, new DungeonExit(sceneId, pointId));
    }

    public Set<Integer> hiddenPoints(int sceneId) {
        return Set.copyOf(this.hiddenPoints.getOrDefault(sceneId, Set.of()));
    }

    public Set<Integer> revealedPoints(int sceneId) {
        return Set.copyOf(this.revealedPoints.getOrDefault(sceneId, Set.of()));
    }

    public void setPointVisibility(int sceneId, Collection<Integer> pointIds, boolean visible) {
        if (sceneId <= 0 || pointIds.isEmpty() || pointIds.stream().anyMatch(id -> id <= 0))
            throw new IllegalArgumentException("Invalid scene point visibility");
        var target = visible ? this.revealedPoints : this.hiddenPoints;
        var opposite = visible ? this.hiddenPoints : this.revealedPoints;
        target.computeIfAbsent(sceneId, ignored -> new HashSet<>()).addAll(pointIds);
        var removed = opposite.get(sceneId);
        if (removed != null) {
            removed.removeAll(pointIds);
            if (removed.isEmpty()) opposite.remove(sceneId);
        }
    }

    /** Showing a group does not unlock any of its individual map layers. */
    public boolean showMapLayerGroup(int groupId) {
        if (groupId <= 0) throw new IllegalArgumentException("Invalid map layer group");
        if (this.mapLayerShownGroups == null) this.mapLayerShownGroups = new HashSet<>();
        return this.mapLayerShownGroups.add(groupId);
    }

    public List<Integer> shownMapLayerGroups(Collection<Integer> defaults) {
        var result = new LinkedHashSet<>(defaults);
        if (this.mapLayerShownGroups != null) result.addAll(this.mapLayerShownGroups);
        return List.copyOf(result);
    }

    public void setMapLayerUnlocks(Collection<Integer> layers, boolean unlocked) {
        if (layers.isEmpty() || layers.stream().anyMatch(id -> id <= 0))
            throw new IllegalArgumentException("Invalid map layers");
        layers.forEach(id -> this.mapLayerUnlocks.put(id, unlocked));
    }

    /** Apply only explicit quest overrides to the caller's existing map policy. */
    public List<Integer> unlockedMapLayers(Collection<Integer> defaults) {
        var result = new LinkedHashSet<>(defaults);
        this.mapLayerUnlocks.forEach((id, unlocked) -> {
            if (unlocked) result.add(id);
            else result.remove(id);
        });
        return List.copyOf(result);
    }
}
