"""Keep the installed resource version's event definitions with the GM console."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
EXCEL = ROOT / "resources/ExcelBinOutput"


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def main():
    native = read(EXCEL / "NewActivityExcelConfigData.json")
    watchers = {row["id"]: row for row in read(EXCEL / "NewActivityWatcherConfigData.json")}
    conditions = read(EXCEL / "NewActivityCondExcelConfigData.json")
    texts = {lang: read(ROOT / f"resources/TextMap/TextMap{lang}.json") for lang in ("CHS", "EN")}
    rows = []
    for row in native:
        activity_id = row["activityId"]
        name_hash = str(row.get("nameTextMapHash", 0))
        rows.append({
            "id": activity_id,
            "name": texts["CHS"].get(name_hash, f"活动 {activity_id}"),
            "en": texts["EN"].get(name_hash, f"Activity {activity_id}"),
            "type": row["activityType"],
            "definition": row,
            "watchers": [watchers[w] for w in row.get("watcherId", []) if w in watchers],
            "conditions": [c for c in conditions if c["condId"] // (100 if activity_id == 5001 else 1000) == activity_id],
        })
    target = ROOT / "src/main/resources/activities/catalog.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps({"resourceVersion": "7.1.0", "activities": rows}, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(f"Archived {len(rows)} definitions into {target} ({target.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
