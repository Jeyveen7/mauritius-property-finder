"""Builds the gazetteer and coastline seed files from OpenStreetMap (free, ODbL:
"© OpenStreetMap contributors" must be credited wherever they are shown).

`python -m mpf.cli osm-import` queries the public Overpass API once and rewrites
seed/localities.csv and seed/coastline.json; the app never calls Overpass at runtime.
"""

import csv
import json
import time
from pathlib import Path

import httpx

from mpf.domain.text import norm

OVERPASS = ["https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter"]
# The main island only (Rodrigues and the outer islands are separate markets).
BBOX = "-20.56,57.28,-19.96,57.82"
SEED_DIR = Path(__file__).resolve().parent.parent / "seed"
ATTRIBUTION = "© OpenStreetMap contributors, ODbL 1.0 (https://www.openstreetmap.org/copyright)"

DISTRICT_NAMES = {"Riviere du Rempart": "Rivière du Rempart"}
REGION_OF_DISTRICT = {
    "Rivière du Rempart": "North", "Pamplemousses": "North",
    "Port Louis": "West", "Black River": "West",
    "Plaines Wilhems": "Centre", "Moka": "Centre",
    "Flacq": "East",
    "Grand Port": "South", "Savanne": "South",
}
PLACE_RANK = {"city": 0, "town": 1, "village": 2, "suburb": 3, "hamlet": 4, "locality": 5}


def overpass(query: str, cache: Path | None = None, name: str = "") -> dict:
    """Runs an Overpass query. With `cache`, a saved response <cache>/<name>.json is
    used instead (the public servers are slow; save once with curl and re-run)."""
    if cache and (cache / f"{name}.json").exists():
        return json.loads((cache / f"{name}.json").read_text())
    last = None
    for attempt in range(3):
        for url in OVERPASS:
            try:
                r = httpx.post(url, data={"data": query}, timeout=180,
                               headers={"User-Agent": "mpf-gazetteer/0.1 (open-source property finder)"})
                if r.status_code == 200:
                    return r.json()
                last = f"{url}: HTTP {r.status_code}"
            except httpx.HTTPError as e:
                last = f"{url}: {e}"
        time.sleep(10 * (attempt + 1))
    raise RuntimeError(f"Overpass unavailable ({last})")


def _rings(members: list[dict]) -> list[list[tuple[float, float]]]:
    """Joins a relation's outer ways into closed rings of (lat, lon)."""
    pieces = [[(p["lat"], p["lon"]) for p in m["geometry"]]
              for m in members if m.get("type") == "way" and m.get("role") in ("outer", "") and m.get("geometry")]
    rings = []
    while pieces:
        ring = pieces.pop(0)
        changed = True
        while ring[0] != ring[-1] and changed:
            changed = False
            for i, p in enumerate(pieces):
                if p[0] == ring[-1]:
                    ring += p[1:]
                elif p[-1] == ring[-1]:
                    ring += list(reversed(p))[1:]
                elif p[-1] == ring[0]:
                    ring = p[:-1] + ring
                elif p[0] == ring[0]:
                    ring = list(reversed(p))[:-1] + ring
                else:
                    continue
                pieces.pop(i)
                changed = True
                break
        rings.append(ring)
    return rings


def _inside(lat: float, lon: float, ring: list[tuple[float, float]]) -> bool:
    inside = False
    for (y1, x1), (y2, x2) in zip(ring, ring[1:] + ring[:1]):
        if (y1 > lat) != (y2 > lat) and lon < (x2 - x1) * (lat - y1) / (y2 - y1) + x1:
            inside = not inside
    return inside


def districts(cache: Path | None = None) -> dict[str, list]:
    data = overpass(f'[out:json][timeout:400];rel["boundary"="administrative"]["admin_level"="4"]({BBOX});out geom;',
                    cache, "districts")
    out = {}
    for rel in data["elements"]:
        name = DISTRICT_NAMES.get(rel["tags"]["name"], rel["tags"]["name"])
        if name in REGION_OF_DISTRICT:
            out[name] = _rings(rel["members"])
    return out


def places(district_rings: dict[str, list], cache: Path | None = None) -> list[dict]:
    data = overpass(f'[out:json][timeout:200];node["place"~"^(city|town|village|suburb|hamlet)$"]["name"]({BBOX});out;',
                    cache, "places")
    rows = []
    for n in data["elements"]:
        district = next((d for d, rings in district_rings.items()
                         if any(_inside(n["lat"], n["lon"], r) for r in rings)), None)
        if district is None:
            continue  # offshore islets
        t = n["tags"]
        aliases = {t[k] for k in ("name:en", "name:fr", "alt_name", "old_name", "official_name") if t.get(k)}
        aliases |= {a for k in ("alt_name",) for a in t.get(k, "").split(";") if a}
        aliases.discard(t["name"])
        rows.append({"name": t["name"], "district": district, "region": REGION_OF_DISTRICT[district],
                     "lat": round(n["lat"], 5), "lon": round(n["lon"], 5),
                     "rank": PLACE_RANK.get(t["place"], 9), "aliases": sorted(aliases)})
    return rows


def coastline(cache: Path | None = None) -> list[list[list[float]]]:
    data = overpass(f'[out:json][timeout:400];way["natural"="coastline"]({BBOX});out geom;', cache, "coastline")
    return [[[round(p["lat"], 5), round(p["lon"], 5)] for p in w["geometry"]] for w in data["elements"]]


def write_seeds(cache: Path | None = None) -> dict:
    """Rewrites seed/localities.csv and seed/coastline.json. Hand-written entries in
    seed/localities_manual.csv (aliases, places OSM lacks) are merged in."""
    rings = districts(cache)
    rows = sorted(places(rings, cache), key=lambda r: (r["rank"], r["name"]))

    by_key: dict[str, dict] = {}
    for r in rows:
        key = norm(r["name"])
        if key in by_key:
            # Same name in two districts: keep both, told apart by district.
            if by_key[key]["district"] != r["district"]:
                r["name"] = f'{r["name"]} ({r["district"]})'
                by_key[norm(r["name"])] = r
            continue
        by_key[key] = r

    manual = SEED_DIR / "localities_manual.csv"
    if manual.exists():
        with open(manual, newline="") as f:
            for m in csv.DictReader(f):
                aliases = [a for a in m["aliases"].split("|") if a]
                hit = by_key.get(norm(m["name"])) or next(
                    (r for r in by_key.values() if norm(m["name"]) in {norm(a) for a in r["aliases"]}), None)
                if hit:
                    hit["aliases"] = sorted(set(hit["aliases"]) | set(aliases) | ({m["name"]} - {hit["name"]}))
                else:
                    by_key[norm(m["name"])] = {"name": m["name"], "district": m["district"], "region": m["region"],
                                               "lat": float(m["lat"]), "lon": float(m["lon"]), "rank": 9,
                                               "aliases": aliases}

    with open(SEED_DIR / "localities.csv", "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["name", "district", "region", "lat", "lon", "aliases"])
        for r in sorted(by_key.values(), key=lambda r: r["name"]):
            w.writerow([r["name"], r["district"], r["region"], r["lat"], r["lon"], "|".join(r["aliases"])])

    lines = coastline(cache)
    (SEED_DIR / "coastline.json").write_text(json.dumps({"attribution": ATTRIBUTION, "lines": lines}))
    return {"localities": len(by_key), "coastline_ways": len(lines),
            "coastline_points": sum(len(l) for l in lines)}
