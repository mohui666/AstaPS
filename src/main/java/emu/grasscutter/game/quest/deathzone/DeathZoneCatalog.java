package emu.grasscutter.game.quest.deathzone;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Historical server definitions, restricted to groups with Lua in the installed resource pack. */
public final class DeathZoneCatalog {
    public record Zone(int id, int sceneId, int level, boolean defaultOpen,
                       List<Integer> groups, List<Integer> polygons) {
        public Zone {
            if (id <= 0 || sceneId <= 0 || level < 0 || groups.isEmpty())
                throw new IllegalArgumentException("Invalid death zone definition");
            groups = List.copyOf(groups);
            polygons = List.copyOf(polygons);
        }
    }

    private final Map<Integer, Zone> zones;
    private final Map<Integer, Zone> groups;

    DeathZoneCatalog(List<Zone> definitions) {
        var byZone = new LinkedHashMap<Integer, Zone>();
        var byGroup = new LinkedHashMap<Integer, Zone>();
        for (var zone : definitions) {
            if (byZone.put(zone.id(), zone) != null)
                throw new IllegalArgumentException("Duplicate death zone " + zone.id());
            for (int group : zone.groups()) {
                if (group <= 0 || byGroup.put(group, zone) != null)
                    throw new IllegalArgumentException("Invalid or duplicate death zone group " + group);
            }
        }
        zones = Map.copyOf(byZone);
        groups = Map.copyOf(byGroup);
    }

    private static final class Installed {
        private static final DeathZoneCatalog VALUE = load();
    }

    public static DeathZoneCatalog installed() { return Installed.VALUE; }

    private static DeathZoneCatalog load() {
        try (var stream = DeathZoneCatalog.class.getResourceAsStream("/quest/DeathZones.json")) {
            if (stream == null) throw new IllegalStateException("Missing death zone definitions");
            var json = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            var definitions = new java.util.ArrayList<Zone>();
            for (var value : json.getAsJsonArray("zones")) {
                var row = value.getAsJsonObject();
                var groups = new java.util.ArrayList<Integer>();
                var polygons = new java.util.ArrayList<Integer>();
                for (var group : row.getAsJsonArray("groupList")) groups.add(group.getAsInt());
                for (var polygon : row.getAsJsonArray("polygonList")) polygons.add(polygon.getAsInt());
                definitions.add(new Zone(row.get("id").getAsInt(), row.get("sceneId").getAsInt(),
                        row.get("level").getAsInt(), row.get("isDefaultOpen").getAsBoolean(), groups, polygons));
            }
            return new DeathZoneCatalog(definitions);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read death zone definitions", error);
        }
    }

    /** Zero means the current group's definition, never a global/default death zone. */
    public Zone resolve(int sceneId, int currentGroupId, int requestedZoneId) {
        if (sceneId <= 0 || currentGroupId <= 0 || requestedZoneId < 0) return null;
        var zone = requestedZoneId == 0 ? groups.get(currentGroupId) : zones.get(requestedZoneId);
        return zone != null && zone.sceneId() == sceneId ? zone : null;
    }

    public Map<Integer, Zone> zones() { return zones; }
}
