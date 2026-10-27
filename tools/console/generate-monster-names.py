"""Extract native-ID monster names from the repository's reference dictionary."""
import json
from pathlib import Path

root = Path(__file__).resolve().parents[2]
source = root / "docs/features/data"
rows = json.loads((source / "monsters.json").read_text(encoding="utf-8-sig"))
result = {
    "sourceVersion": json.loads((source / "version.json").read_text(encoding="utf-8-sig"))["version"],
    "source": "docs/features/data/monsters.json",
    "monsters": {key: [row["chsName"], row.get("enName", "")] for key, row in rows.items()},
}
output = root / "src/main/resources/html/monster-names.json"
output.write_text(json.dumps(result, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
print(f"Wrote {len(rows)} monster names to {output}")
