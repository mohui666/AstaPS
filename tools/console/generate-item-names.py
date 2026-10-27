"""Extract the repository's ID dictionaries for the console; no network access needed."""
import json
from pathlib import Path

root = Path(__file__).resolve().parents[2]
source = root / "docs/features/data"


def read(name):
    return json.loads((source / f"{name}.json").read_text(encoding="utf-8-sig"))


items = {}
for dataset in ("materials", "weapons"):
    for key, row in read(dataset).items():
        items[key] = [row["chsName"], row.get("enName", "")]

# The furnishings reference contains English names. Keep them explicitly English.
for key, row in read("furnishings")["data"]["items"].items():
    items.setdefault(key, ["", row["name"]])

sets = {key: [row["chsName"], row.get("enName", "")] for key, row in read("artifacts").items()}
# Traveler entries are indexed by native ID plus an element suffix in the source dictionary.
avatars = {key.split("_", 1)[0]: [row["chsName"], row.get("enName", "")] for key, row in read("characters").items()}
result = {
    "sourceVersion": read("version")["version"],
    "sources": ["docs/features/data/" + name + ".json" for name in ("materials", "weapons", "artifacts", "furnishings", "characters")],
    "items": items,
    "sets": sets,
    "avatars": avatars,
}
output = root / "src/main/resources/html/item-names.json"
output.write_text(json.dumps(result, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
print(f"Wrote {len(items)} item names and {len(sets)} artifact sets to {output}")
