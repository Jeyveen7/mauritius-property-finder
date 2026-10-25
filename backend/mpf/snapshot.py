"""Exports everything the apps need as static files, so no server has to run:

    <out>/meta.json             version, generated time, rules, FX, attribution (small;
                                 the apps check it on start to see if anything changed)
    <out>/properties.json.gz    every active property with its full detail
    <out>/localities.json.gz    the gazetteer, with area photos, for search and posting

The apps download these when the version changes, keep them on the device, and do all
searching, map grouping and cost estimates locally. Host the folder anywhere static
(GitHub Pages via .github/workflows/data.yml, or bundled into the app).
"""

import gzip
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

import psycopg

from mpf.detail import area_photos, property_detail
from mpf.domain.text import norm
from mpf.repo import get_setting, load_fx, load_rules

ATTRIBUTION = [
    "Map data and places © OpenStreetMap contributors (ODbL)",
    "Area photos: Wikimedia Commons contributors, licences as credited",
]
FORMAT = 1


def _default(o):
    if hasattr(o, "isoformat"):
        return o.isoformat()
    return float(o) if o.__class__.__name__ == "Decimal" else str(o)


def _write_gz(path: Path, data) -> bytes:
    raw = json.dumps(data, default=_default, ensure_ascii=False, separators=(",", ":")).encode()
    # mtime=0 keeps the file byte-identical when the data has not changed.
    path.write_bytes(gzip.compress(raw, mtime=0))
    return raw


def export(conn: psycopg.Connection, out: Path, public_base_url: str = "") -> dict:
    out.mkdir(parents=True, exist_ok=True)
    rules, fx = load_rules(conn), load_fx(conn)

    cards = {r["id"]: r for r in conn.execute(
        """SELECT p.id, best.price_mur_minor, loc.search_text,
                  EXISTS (SELECT 1 FROM price_event pe JOIN listing l2 ON l2.id = pe.listing_id
                          WHERE l2.property_id = p.id AND l2.status = 'active'
                            AND pe.new_price_minor < pe.old_price_minor AND pe.at > now() - interval '60 days') AS price_dropped,
                  (SELECT min(first_seen_at) FROM listing WHERE property_id = p.id AND status = 'active') AS first_seen_at
           FROM property p LEFT JOIN locality loc ON loc.id = p.locality_id
           JOIN LATERAL (SELECT price_mur_minor FROM listing WHERE property_id = p.id AND status = 'active'
                         ORDER BY price_mur_minor LIMIT 1) best ON true
           WHERE p.status = 'active'"""
    ).fetchall()}

    properties = []
    for pid, card in cards.items():
        d = property_detail(conn, pid, rules=rules, fx=fx, public_base_url=public_base_url)
        if d is None or d.get("status") != "active":
            continue
        d.pop("costs", None)  # the apps compute these for the user's deposit and term
        d.pop("area_photos", None)  # stored once per locality instead
        d.update(price_mur_minor=card["price_mur_minor"], price_dropped=card["price_dropped"],
                 first_seen_at=card["first_seen_at"], search_text=card["search_text"] or "",
                 thumb=d["photos"][0]["url"] if d["photos"] else None,
                 foreign_eligible=d["eligibility"]["status"] == "yes")
        properties.append(d)
    properties.sort(key=lambda p: p["id"])

    localities = []
    for loc in conn.execute("SELECT * FROM locality ORDER BY name").fetchall():
        localities.append({"name": loc["name"], "district": loc["district"], "region": loc["region"],
                           "lat": loc["lat"], "lon": loc["lon"], "aliases": loc["aliases"],
                           "search_text": loc["search_text"] or norm(loc["name"]),
                           "area_photos": area_photos(conn, loc["id"])})

    props_raw = _write_gz(out / "properties.json.gz", properties)
    locs_raw = _write_gz(out / "localities.json.gz", localities)
    meta = {
        "format": FORMAT,
        "version": hashlib.sha256(props_raw + locs_raw).hexdigest()[:16],
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "count": len(properties),
        "rules": {"label": rules.label, "registration_duty_pct": str(rules.registration_duty_pct)},
        "fx": {"day": fx.day.isoformat(), "mur_per_unit": {k: str(v) for k, v in fx.mur_per_unit.items()}},
        "assumed_mortgage_rate_pct": str(get_setting(conn, "assumed_mortgage_rate_pct")),
        "attribution": ATTRIBUTION,
    }
    (out / "meta.json").write_text(json.dumps(meta, indent=1))
    return meta
