"""Archive named event assets and their CAB dependencies as original, restorable files.

Inputs are the installed 7.1 client's AnimeStudio AssetMap + CABMap, not exported textures.
Run without --copy to inspect the exact storage cost first.
"""
import argparse
import collections
import json
import re
import shutil
import struct
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ARCHIVE = ROOT / "local/activity-assets/7.1.0"
RULES = {
    5072: ("MusicGame", ["MusicGame"]),
    5343: ("GamblingChess", ["GamblingChess"]),
    5344: ("SnowBallFight", ["ActivitySnowBallFight"]),
    5345: ("__unmapped_skin_banner__", []),
    5346: ("__unmapped_archon_banner__", ["ActivityArchonBanner"]),
    5347: ("VesnaQuest", []),
    5348: ("VodyanitsaQuest", ["QuestVodyanitsa"]),
    5349: ("BeyondAnniversary", ["BeyondActivityAnniversary"]),
    5351: ("Anecdote", ["Anecdote"]),
}


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def read_cab_map(path):
    with path.open("rb") as f:
        def unpack(fmt):
            return struct.unpack(fmt, f.read(struct.calcsize(fmt)))[0]

        def string():
            length = 0
            for shift in range(0, 35, 7):
                value = unpack("<B")
                length |= (value & 127) << shift
                if not value & 128:
                    return f.read(length).decode("utf-8")
            raise ValueError("Invalid CABMap string length")

        if f.read(7) != b"\xffCABMAP" or unpack("<H") != 3:
            raise ValueError("This archive builder expects AnimeStudio CABMap V3")
        base = Path(string())
        cabs = {}
        for _ in range(unpack("<i")):
            name = string().lower()
            locations = []
            for _ in range(unpack("<i")):
                relative, offset = string(), unpack("<q")
                dependencies = [string().lower() for _ in range(unpack("<i"))]
                locations.append({"source": base / relative, "offset": offset, "dependencies": dependencies})
            cabs[name] = locations
        if f.read(1):
            raise ValueError("Unexpected CABMap trailing bytes")
        return cabs


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--copy", action="store_true")
    args = parser.parse_args()
    game = Path(read_json(ROOT / "local/environment.json")["gameDirectory"])
    if not re.search(r"game_version\s*=\s*7\.1\.0(?:\s|$)", (game / "config.ini").read_text(encoding="utf-8-sig")):
        raise ValueError("Only the installed 7.1.0 client can be archived by this index")
    entries = read_json(ARCHIVE / "index/activity_candidates.json")["AssetEntries"]
    cabs = read_cab_map(ARCHIVE / "index/Maps/activity_dependencies.bin")
    by_source = collections.defaultdict(list)
    for name, locations in cabs.items():
        for location in locations:
            by_source[(str(location["source"]).lower(), location["offset"])].append(name)
    definitions = {row["id"]: row for row in read_json(ROOT / "src/main/resources/activities/catalog.json")["activities"]}
    state_path = ROOT / "data/activities/archives.json"
    configs = read_json(state_path)["activities"] if state_path.exists() else [
        {"activityId": c["activityId"], "config": c, "note": ""}
        for c in read_json(ROOT / "src/main/resources/defaults/data/ActivityConfig.json")]
    configs = {row["activityId"]: row for row in configs}
    packages, all_files = [], {}
    for activity_id, (pattern, prefixes) in RULES.items():
        roots = [e for e in entries if re.search(pattern, e["Name"] + " " + e["Container"], re.I)]
        queue, visited, missing, unmatched = collections.deque(), set(), set(), []
        for entry in roots:
            names = by_source.get((entry["Source"].lower(), entry["Offset"]), [])
            if not names:
                unmatched.append({"name": entry["Name"], "source": entry["Source"], "offset": entry["Offset"]})
            queue.extend(names)
        sources = {Path(e["Source"]) for e in roots}
        while queue:
            name = queue.popleft()
            if name in visited:
                continue
            visited.add(name)
            if name not in cabs:
                missing.add(name)
                continue
            for location in cabs[name]:
                sources.add(location["source"])
                queue.extend(location["dependencies"])
        # Unity's built-in shared resources live outside the numbered block map.
        if "unity default resources" in missing:
            builtin = game / "YuanShen_Data/Resources/unity default resources"
            if builtin.is_file():
                sources.add(builtin)
                missing.remove("unity default resources")
        files = []
        for source in sorted(sources):
            relative = source.relative_to(game).as_posix()
            files.append({"kind": "client", "path": relative, "length": source.stat().st_size})
        if activity_id == 5072:
            for source in sorted((game / "YuanShen_Data/StreamingAssets/AudioAssets/MusicGame").glob("*.pck")):
                files.append({"kind": "client", "path": source.relative_to(game).as_posix(), "length": source.stat().st_size})
        for source in (ROOT / "resources/ExcelBinOutput").glob("*.json"):
            if any(source.name.startswith(prefix) for prefix in prefixes):
                files.append({"kind": "server", "path": source.relative_to(ROOT / "resources").as_posix(), "length": source.stat().st_size})
        if activity_id == 5344:
            # These dedicated dungeon scenes are referenced by 7.1's snowball activity levels.
            for scene_id in (51335, 51337, 51338):
                for source in (ROOT / f"resources/Scripts/Scene/{scene_id}").rglob("*"):
                    if source.is_file():
                        files.append({"kind": "server", "path": source.relative_to(ROOT / "resources").as_posix(), "length": source.stat().st_size})
        saved = configs.get(activity_id, {"activityId": activity_id, "config": None, "note": ""})
        if saved["config"]:
            config = saved["config"]
            if config["endTime"].startswith("2999-"):
                config["endTime"] = "2099-12-31T23:59:59Z"
            config.setdefault("openTime", config["beginTime"])
            config.setdefault("closeTime", config["endTime"])
        package = {
            "formatVersion": 1, "activityId": activity_id, "resourceVersion": "7.1.0",
            "name": definitions[activity_id]["name"], "settings": saved,
            "definition": definitions[activity_id], "files": files,
            "matchedAssetCount": len(roots), "cabCount": len(visited),
            "unresolvedDependencies": sorted(missing), "unmappedRoots": unmatched,
            "coverage": "named-assets-with-cab-dependencies" if roots else "metadata-only",
            "requirements": ["相同的 7.1.0 客户端和当前 AstaPS 资源基础；恢复原始包不会补出服务端未实现的玩法。",
                "此包按已识别的活动资源名称及 CAB 引用构建；未命名的业务依赖和旧版本已删资源仍需游戏内验证。"],
        }
        packages.append(package)
        for file in files:
            all_files[(file["kind"], file["path"])] = file
    total = sum(row["length"] for row in all_files.values())
    print(json.dumps({"packages": [{"id": p["activityId"], "assets": p["matchedAssetCount"], "files": len(p["files"]),
        "missingCABs": p["unresolvedDependencies"], "unmappedRoots": len(p["unmappedRoots"])} for p in packages],
        "uniqueFiles": len(all_files), "totalGiB": round(total / 2**30, 3)}, ensure_ascii=False))
    if not args.copy:
        return
    pending = []
    for (kind, relative), file in all_files.items():
        source = (game if kind == "client" else ROOT / "resources") / relative
        target = ARCHIVE / "files" / kind / relative
        if not target.is_file() or target.stat().st_size != source.stat().st_size or target.stat().st_mtime_ns != source.stat().st_mtime_ns:
            pending.append(((kind, relative), file))
    if shutil.disk_usage(ARCHIVE).free < sum(file["length"] for _, file in pending) + 1024**3:
        raise OSError("Not enough free space to preserve the selected activity dependencies")
    store = ARCHIVE / "files"
    copied = 0
    for (kind, relative), file in pending:
        source = (game if kind == "client" else ROOT / "resources") / relative
        target = store / kind / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        if target.stat().st_size != file["length"]:
            raise OSError(f"Incomplete copy: {target}")
        copied += 1
        if copied % 100 == 0:
            print(f"Copied {copied}/{len(pending)} activity resource files", flush=True)
    for package in packages:
        target = ARCHIVE / "packages" / str(package["activityId"])
        target.mkdir(parents=True, exist_ok=True)
        (target / "package.json").write_text(json.dumps(package, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Saved {len(packages)} event packages; {len(all_files)} original files, {total} bytes")


if __name__ == "__main__":
    main()
