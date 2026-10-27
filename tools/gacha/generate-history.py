"""Generate native historical UP IDs from downloaded sources and the local resource tables.

Input URLs and licensing: src/main/resources/gacha/SOURCES.md.
Download banners.yaml and banner-data.json to local/gacha-sources/ before running.
"""
import json
import re
import unicodedata
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]


def read(path):
    return json.loads((ROOT / path).read_text(encoding="utf-8-sig"))


def normalized(name):
    ascii_name = unicodedata.normalize("NFKD", name).encode("ascii", "ignore").decode()
    return re.sub("[^a-z0-9]", "", ascii_name.lower())


materials = read("resources/ExcelBinOutput/MaterialExcelConfigData.json")
weapons = read("resources/ExcelBinOutput/WeaponExcelConfigData.json")
native_items = {row["id"]: row for row in materials + weapons}
avatar_cards = {}
for row in sorted(materials, key=lambda row: row["id"]):
    if row.get("materialType") != "MATERIAL_AVATAR" or 3000 <= row["id"] < 4000:
        continue
    for use in row.get("itemUse", []):
        if use.get("useOp") == "ITEM_USE_GAIN_AVATAR":
            avatar_cards.setdefault(int(use["useParam"][0]), row["id"])

names = {normalized(row["enName"]): avatar_cards[int(avatar_id)]
         for avatar_id, row in read("docs/features/data/characters.json").items()
         if avatar_id.isdigit() and int(avatar_id) in avatar_cards}
names.update({normalized(row["enName"]): int(item_id)
              for item_id, row in read("docs/features/data/weapons.json").items()})
source = yaml.safe_load((ROOT / "local/gacha-sources/samsara-banners.yaml").read_text(encoding="utf-8"))
phases = {}
for group, rows in source.items():
    tier = "up5" if group.startswith("five") else "up4"
    for row in rows:
        item_id = names[normalized(row["name"])]
        for key in row["versions"]:
            phases.setdefault(key, {"up5": set(), "up4": set()})[tier].add(item_id)

# 1.3's weapon banner changed between the Keqing and Hu Tao character banners.
characters_13_2 = {t: {i for i in phases["1.3.2"][t] if i < 10000} for t in ("up5", "up4")}
weapons_13_1 = {t: {i for i in phases["1.3.1"][t] if i >= 10000} for t in ("up5", "up4")}
weapons_13_2 = {t: {i for i in phases["1.3.2"][t] if i >= 10000} for t in ("up5", "up4")}
phases["1.3.2a"] = {t: characters_13_2[t] | weapons_13_1[t] for t in ("up5", "up4")}
phases["1.3.2b"] = phases.pop("1.3.2")
for tier in ("up5", "up4"):
    phases["1.3.3"][tier].update(weapons_13_2[tier])

# The announcement watcher uses full avatar IDs; verify both current 7.1 phases.
current = {}
for row in read("local/gacha-sources/UIGF-banner-data.json").values():
    key = f"{row['version_number']}.{row['order_number']}"
    pools = current.setdefault(key, {"up5": set(), "up4": set()})
    for field, tier in (("UpOrangeList", "up5"), ("UpPurpleList", "up4")):
        pools[tier].update(avatar_cards[i] if i >= 10000000 else i for i in row[field])
for key, pools in current.items():
    assert phases[key] == pools, f"Announcement/history disagreement: {key}"

LUNAS = {v: i for i, v in enumerate(("I", "II", "III", "IV", "V", "VI", "VII", "VIII"))}


def order(key):
    if key.startswith("Luna "):
        numeral, phase = key[5:].split(".")
        return 6, LUNAS[numeral], int(phase), ""
    major, minor, phase = key.split(".")
    return int(major), int(minor), int(phase.rstrip("ab")), phase[-1] if phase[-1] in "ab" else ""


def label(key):
    if key in ("1.3.2a", "1.3.2b"):
        return "1.3 中期 · " + ("武器更新前" if key.endswith("a") else "武器更新后")
    version, phase = key.rsplit(".", 1)
    return version + " · " + {"1": "上半", "2": "下半", "3": "第三期"}[phase]


result = []
for key in sorted(phases, key=order):
    pools = phases[key]
    for tier, rank in (("up5", 5), ("up4", 4)):
        assert pools[tier], (key, tier)
        for item_id in pools[tier]:
            assert native_items[item_id].get("rankLevel") == rank, (key, item_id, rank)
    result.append({"key": key, "label": label(key), **{t: sorted(ids) for t, ids in pools.items()}})
output = ROOT / "src/main/resources/gacha/history.json"
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps({"sources": [
    "https://github.com/benlei/samsara-web/blob/main/public/data/banners.yaml",
    "https://github.com/UIGF-org/CurrentBannerWatcher/blob/main/banner-data.json"
], "phases": result}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(f"Wrote {len(result)} ordered phases, {result[0]['key']} through {result[-1]['key']}.")
