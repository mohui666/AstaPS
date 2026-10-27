package emu.grasscutter.server.http.console;

import com.google.gson.*;
import emu.grasscutter.game.activity.ActivityArchive;
import emu.grasscutter.utils.JsonUtils;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Local original-file event packages. Filesystem I/O is kept off the game logic thread. */
final class WebConsoleActivityAssets {
    private static final Path ROOT = Path.of("local/activity-assets/7.1.0").toAbsolutePath().normalize();
    private static final Map<Integer, String> RESTORES = new java.util.concurrent.ConcurrentHashMap<>();

    static Path inside(Path root, String relative) {
        var path = root.resolve(relative).normalize();
        if (relative.isBlank() || Path.of(relative).isAbsolute() || !path.startsWith(root) || path.equals(root))
            throw new IllegalArgumentException("活动包包含越界路径。");
        return path;
    }

    private static Path packagePath(int id) {
        if (id < 1) throw new IllegalArgumentException("活动 ID 无效。");
        return ROOT.resolve("packages").resolve(String.valueOf(id)).resolve("package.json");
    }

    private static JsonObject read(int id) throws IOException {
        var path = packagePath(id);
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("此活动尚未保存资源包。");
        var pack = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        if (pack.get("formatVersion").getAsInt() != 1 || pack.get("activityId").getAsInt() != id
                || !pack.get("resourceVersion").getAsString().equals("7.1.0"))
            throw new IllegalArgumentException("活动包版本或 ID 不匹配。");
        return pack;
    }

    static Map<String, Object> status(int id) throws IOException {
        if (!Files.isRegularFile(packagePath(id))) return Map.of("available", false);
        var pack = read(id);
        long bytes = 0;
        int missingFiles = 0;
        for (var element : pack.getAsJsonArray("files")) {
            var row = element.getAsJsonObject();
            String kind = row.get("kind").getAsString();
            if (!Set.of("client", "server").contains(kind)) throw new IllegalArgumentException("资源类型无效。");
            var stored = inside(ROOT.resolve("files").resolve(kind), row.get("path").getAsString());
            long length = row.get("length").getAsLong();
            bytes += length;
            if (!Files.isRegularFile(stored) || Files.size(stored) != length) missingFiles++;
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("available", true);
        result.put("fileCount", pack.getAsJsonArray("files").size());
        result.put("logicalBytes", bytes);
        result.put("missingFiles", missingFiles);
        result.put("coverage", pack.get("coverage").getAsString());
        result.put("unresolvedDependencies", pack.getAsJsonArray("unresolvedDependencies").size());
        result.put("unmappedRoots", pack.getAsJsonArray("unmappedRoots").size());
        result.put("matchedAssetCount", pack.get("matchedAssetCount").getAsInt());
        result.put("directory", packagePath(id).getParent().toString());
        result.put("resourceVersion", "7.1.0");
        var job = RESTORES.get(id);
        if (job != null) {
            var path = ROOT.resolve("restore-" + job + ".json");
            result.put("restore", Files.exists(path) ? JsonParser.parseString(Files.readString(path))
                    : Map.of("status", "pending", "message", "正在等待 Windows 管理员确认或复制资源；点击刷新读取结果。"));
        }
        return result;
    }

    static Map<String, Object> save(int id, JsonObject snapshot) throws IOException {
        JsonObject pack;
        if (Files.exists(packagePath(id))) pack = read(id);
        else {
            pack = new JsonObject();
            pack.addProperty("formatVersion", 1); pack.addProperty("activityId", id); pack.addProperty("resourceVersion", "7.1.0");
            pack.addProperty("name", snapshot.get("name").getAsString()); pack.addProperty("coverage", "metadata-only");
            pack.addProperty("matchedAssetCount", 0);
            for (var key : List.of("files", "unresolvedDependencies", "unmappedRoots")) pack.add(key, new JsonArray());
        }
        var environment = JsonParser.parseString(Files.readString(Path.of("local/environment.json"))).getAsJsonObject();
        var game = Path.of(environment.get("gameDirectory").getAsString()).toAbsolutePath().normalize();
        if (!Files.readString(game.resolve("config.ini")).matches("(?s).*game_version\\s*=\\s*7\\.1\\.0(?:\\s|$).*"))
            throw new IllegalArgumentException("保存活动包需要相同的 7.1.0 客户端。");
        for (var element : pack.getAsJsonArray("files")) {
            var row = element.getAsJsonObject();
            String kind = row.get("kind").getAsString();
            if (!Set.of("client", "server").contains(kind)) throw new IllegalArgumentException("资源类型无效。");
            var base = kind.equals("client") ? game : Path.of("resources").toAbsolutePath().normalize();
            var source = inside(base, row.get("path").getAsString());
            var target = inside(ROOT.resolve("files").resolve(kind), row.get("path").getAsString());
            if (!Files.isRegularFile(source)) throw new IllegalArgumentException("缺少原始资源：" + source);
            Files.createDirectories(target.getParent());
            if (!Files.isRegularFile(target) || Files.size(target) != Files.size(source)
                    || !Files.getLastModifiedTime(target).equals(Files.getLastModifiedTime(source)))
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            row.addProperty("length", Files.size(target));
        }
        pack.add("settings", snapshot.get("saved"));
        pack.add("definition", snapshot);
        Files.createDirectories(packagePath(id).getParent());
        Files.writeString(packagePath(id), JsonUtils.encode(pack));
        return status(id);
    }

    /** Called on the game logic thread: load only the saved schedule/settings, not a filesystem scan. */
    static void loadSettings(int id) throws IOException {
        var pack = read(id);
        var input = new JsonObject();
        input.addProperty("formatVersion", 1); input.addProperty("resourceVersion", "7.1.0");
        var rows = new JsonArray(); rows.add(pack.get("settings")); input.add("activities", rows);
        ActivityArchive.importPack(input.toString());
    }

    static synchronized Map<String, Object> restore(int id) throws IOException {
        var state = status(id);
        if (!Boolean.TRUE.equals(state.get("available")) || ((Number) state.get("fileCount")).intValue() == 0)
            throw new IllegalArgumentException("此活动包只有配置资料，尚无可恢复的原始资源。");
        if (((Number) state.get("missingFiles")).intValue() != 0) throw new IllegalArgumentException("存档资源有缺失，请先补齐或重新保存。");
        String previous = RESTORES.get(id);
        if (previous != null && !Files.exists(ROOT.resolve("restore-" + previous + ".json")))
            throw new IllegalArgumentException("此活动已有资源恢复任务，请先处理 Windows 授权窗口并刷新结果。");
        String job = UUID.randomUUID().toString();
        var script = Path.of("tools/console/Restore-ActivityPackage.ps1").toAbsolutePath();
        new ProcessBuilder("powershell.exe", "-NoProfile", "-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass",
                "-File", script.toString(), "-ActivityId", String.valueOf(id), "-ResultName", job).start();
        RESTORES.put(id, job);
        return status(id);
    }
}
