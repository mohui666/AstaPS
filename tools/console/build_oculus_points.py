"""Index actual scene gathers and the server's imported exploration markers."""
import json
import re
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ITEMS = {107001: 1, 107003: 2, 107014: 3, 107017: 4,
         107023: 5, 107028: 6, 107030: 7, 107035: 8}
KINDS = {"hydro_oculus": 107023, "pyro_oculus": 107028,
         "dendro_oculus": 107017, "moon_oculus": 107030, "ice_oculus": 107035}


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def build():
    gathers = read(ROOT / "resources/ExcelBinOutput/GatherExcelConfigData.json")
    by_type = {r["pointType"]: r["itemId"] for r in gathers}
    by_gadget = {r["gadgetId"]: r["itemId"] for r in gathers if r["itemId"] in ITEMS}
    points = {}
    keys = set()

    def add(item, pos, group, config, gadget, point_type, source, rounded_source=False):
        if item not in ITEMS:
            return
        if group <= 0 or config <= 0 or len(pos) != 3:
            raise ValueError(f"Invalid oculus in {source}: group={group}, config={config}")
        marker = (group, config)
        if marker in keys:
            return
        keys.add(marker)
        location = (item, *(round(float(n), 3) for n in pos))
        if rounded_source:
            # The older static table rounds coordinates to 2 decimals; retain
            # one pickup for the same native spawn while recording both IDs.
            for candidate, existing in points.items():
                if candidate[0] == item and max(abs(float(a)-float(b)) for a,b in zip(pos,[existing['x'],existing['y'],existing['z']])) <= 0.02:
                    location = candidate
                    break
        point = points.setdefault(location, {"sceneId": 3, "cityId": ITEMS[item], "itemId": item,
            "x": pos[0], "y": pos[1], "z": pos[2], "marks": []})
        point["marks"].append({"groupId": group, "configId": config,
                               "gadgetId": gadget, "pointType": point_type})

    scripts = read(ROOT / "resources/ScriptSceneData/flat.luas.scenes.full_globals.lua.json")["scriptObjectList"]
    for name, data in scripts.items():
        match = re.fullmatch(r"3/scene3_group(\d+)\.lua", name)
        if not match:
            continue
        gadgets = data.get("gadgets", [])
        if isinstance(gadgets, dict):
            expanded = {}
            for key, value in gadgets.items():
                prefix, separator, field = key.partition(".")
                if separator and prefix.isdigit():
                    if field in {"config_id", "gadget_id", "point_type", "pos"}:
                        expanded.setdefault(prefix, {})[field] = value
                elif isinstance(value, dict):
                    expanded[key] = value
            gadgets = expanded.values()
        for gadget in gadgets:
            if not isinstance(gadget, dict):
                raise ValueError(f"Unexpected gadget definition in {name}: {gadget!r}")
            item = by_type.get(gadget.get("point_type"))
            if item not in ITEMS:
                item = by_gadget.get(gadget.get("gadget_id"))
            if item in ITEMS:
                add(item, gadget["pos"], int(match[1]), gadget["config_id"],
                    gadget["gadget_id"], gadget.get("point_type", 0), name)

    for name in ["nodkrai_explore_points.json", "snezhnaya_explore_points.json", "region_explore_points.json"]:
        for point in read(ROOT / "data" / name):
            item = KINDS.get(point.get("kind"))
            if item is None:
                continue
            bases = [910700000, 910800000] if name.startswith("snezhnaya") else [910700000]
            for base in bases:
                add(item, [point["x"], point["y"], point["z"]], base + point["id"], point["id"],
                    point["gadgetId"], point.get("pointType", 0), name)

    for name in ["Spawns.json", "GadgetSpawns.json"]:
        for group in read(ROOT / "data" / name):
            if group.get("sceneId") != 3:
                continue
            for spawn in group["spawns"]:
                item = spawn.get("gatherItemId") or by_gadget.get(spawn.get("gadgetId"))
                if item not in ITEMS:
                    continue
                add(item,[spawn["pos"].get(axis,0) for axis in ["x","y","z"]],
                    group["groupId"],spawn["configId"],spawn["gadgetId"],0,name,rounded_source=True)

    result = list(points.values())
    result.sort(key=lambda p: (p["cityId"], p["marks"][0]["groupId"], p["marks"][0]["configId"]))
    target = ROOT / "data/oculus-points.json"
    target.write_text(json.dumps(result, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(json.dumps({"points": len(result), "spawnMarkers": len(keys),
                      "byItem": dict(sorted(Counter(p["itemId"] for p in result).items()))}))


if __name__ == "__main__":
    build()
