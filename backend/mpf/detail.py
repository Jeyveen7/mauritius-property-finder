"""Everything the listing screen shows about one property."""

import statistics
from decimal import Decimal

import psycopg

from mpf.domain import costs
from mpf.domain.eligibility import Rules
from mpf.domain.geo import coast_distance_m
from mpf.domain.money import FxTable, Money
from mpf.repo import get_setting

PRICE_CHECK_MIN_COMPARABLES = 5
PRICE_CHECK_WINDOW_DAYS = 180


def _money_json(m: Money) -> dict:
    return {"minor": m.minor, "currency": m.currency}


def eligibility_summary(listings: list[dict]) -> dict:
    """The most favourable answer any listing's facts support: one source may state
    the scheme or building height that another omits."""
    by_status = {l["eligibility"]["status"]: l["eligibility"] for l in listings}
    for status in ("yes", "unknown", "no"):
        if status in by_status:
            return by_status[status]
    raise ValueError("property has no listings")


def price_check(conn: psycopg.Connection, prop: dict, best: dict) -> dict:
    """Asking price per m² against comparable active properties: same type, same
    region, first seen in the last 180 days. Hidden below 5 comparables."""
    area_col = "plot_m2" if prop["type"] == "land" else "floor_m2"
    area = best[area_col]
    if not area:
        return {"available": False, "reason": "no_area", "comparables": 0}
    rows = conn.execute(
        f"""SELECT DISTINCT ON (l.property_id) l.price_mur_minor, l.{area_col} AS area
            FROM listing l JOIN property p ON p.id = l.property_id
            JOIN locality loc ON loc.id = p.locality_id
            WHERE l.status = 'active' AND p.status = 'active' AND p.type = %s AND loc.region = %s
              AND l.{area_col} > 0 AND p.id <> %s
              AND l.first_seen_at > now() - make_interval(days => %s)
            ORDER BY l.property_id, l.price_mur_minor""",
        (prop["type"], prop["region"], prop["id"], PRICE_CHECK_WINDOW_DAYS),
    ).fetchall()
    if len(rows) < PRICE_CHECK_MIN_COMPARABLES:
        return {"available": False, "reason": "too_few_comparables", "comparables": len(rows),
                "minimum": PRICE_CHECK_MIN_COMPARABLES}
    per_m2 = [Decimal(r["price_mur_minor"]) / Decimal(r["area"]) for r in rows]
    median = Decimal(statistics.median(per_m2))
    mine = Decimal(best["price_mur_minor"]) / Decimal(area)
    return {
        "available": True, "comparables": len(rows), "scope": prop["region"],
        "median_mur_minor_per_m2": int(median), "this_mur_minor_per_m2": int(mine),
        "diff_pct": int(((mine / median) - 1) * 100),
        "min_mur_minor_per_m2": int(min(per_m2)), "max_mur_minor_per_m2": int(max(per_m2)),
    }


def history(conn: psycopg.Connection, listing_ids: list) -> list[dict]:
    events = conn.execute(
        """SELECT 'first_seen' AS kind, l.first_seen_at AS at, s.price_minor AS price_minor, s.currency,
                  NULL::bigint AS old_price_minor, src.name AS source
           FROM listing l JOIN source src ON src.id = l.source_id
           JOIN LATERAL (SELECT price_minor, currency FROM snapshot WHERE listing_id = l.id
                         ORDER BY fetched_at LIMIT 1) s ON true
           WHERE l.id = ANY(%(ids)s)
           UNION ALL
           SELECT 'price_change', pe.at, pe.new_price_minor, pe.currency, pe.old_price_minor, src.name
           FROM price_event pe JOIN listing l ON l.id = pe.listing_id JOIN source src ON src.id = l.source_id
           WHERE pe.listing_id = ANY(%(ids)s)
           ORDER BY at DESC""",
        {"ids": listing_ids},
    ).fetchall()
    return [{"kind": e["kind"], "at": e["at"].isoformat(), "source": e["source"],
             "price": {"minor": e["price_minor"], "currency": e["currency"]},
             "old_price": {"minor": e["old_price_minor"], "currency": e["currency"]} if e["old_price_minor"] else None}
            for e in events]


def media_url(path: str | None, public_base_url: str) -> str | None:
    """Stored uploads are relative paths under /media/; licensed photos are full URLs."""
    if not path:
        return None
    return path if path.startswith(("http://", "https://")) else f"{public_base_url}/media/{path}"


def photos(conn: psycopg.Connection, listings: list[dict], public_base_url: str) -> list[dict]:
    out = []
    for l in listings:
        if l["images_licensed"]:
            out += [{"url": u, "kind": "licensed", "credit": l["agency_name"] or l["source_name"]} for u in l["image_urls"]]
    rows = conn.execute(
        """SELECT storage_path FROM photo WHERE listing_id = ANY(%s) AND status = 'active' ORDER BY created_at""",
        ([l["id"] for l in listings],),
    ).fetchall()
    out += [{"url": media_url(r["storage_path"], public_base_url), "kind": "user_upload", "credit": None} for r in rows]
    return out


def area_photos(conn: psycopg.Connection, locality_id: int | None) -> list[dict]:
    if locality_id is None:
        return []
    return conn.execute(
        """SELECT thumb_url AS url, page_url, author, license, license_url FROM area_photo
           WHERE locality_id = %s ORDER BY id LIMIT 6""",
        (locality_id,),
    ).fetchall()


def property_detail(conn: psycopg.Connection, property_id, *, rules: Rules, fx: FxTable,
                    deposit_pct: int = 20, term_years: int = 20, public_base_url: str = "") -> dict | None:
    prop = conn.execute(
        """SELECT p.*, loc.name AS locality, loc.district, loc.region
           FROM property p LEFT JOIN locality loc ON loc.id = p.locality_id WHERE p.id = %s""",
        (property_id,),
    ).fetchone()
    if prop is None or prop["status"] == "hidden":
        return None
    listings = conn.execute(
        """SELECT l.*, s.name AS source_name, s.kind AS source_kind, s.images_licensed
           FROM listing l JOIN source s ON s.id = l.source_id
           WHERE l.property_id = %s AND l.status = 'active' ORDER BY l.price_mur_minor""",
        (property_id,),
    ).fetchall()
    if not listings:
        return {"id": str(prop["id"]), "status": "gone"}
    best = listings[0]
    price = Money(best["price_minor"], best["currency"])
    rate = Decimal(str(get_setting(conn, "assumed_mortgage_rate_pct")))
    est = costs.estimate(price, deposit_pct=deposit_pct, term_years=term_years,
                         annual_rate_pct=rate, registration_duty_pct=rules.registration_duty_pct)
    coast_m = coast_distance_m(prop["lat"], prop["lon"])

    return {
        "id": str(prop["id"]), "status": prop["status"], "type": prop["type"],
        "title": best["title"], "locality": prop["locality"], "district": prop["district"], "region": prop["region"],
        "lat": prop["lat"], "lon": prop["lon"],
        "location_precision": "exact" if any(l["geo_precision"] == "exact" for l in listings) else "locality",
        "price": _money_json(price),
        "price_mur": _money_json(fx.convert(price, "MUR")),
        "fx_day": fx.day.isoformat(),
        "plot_m2": best["plot_m2"], "floor_m2": best["floor_m2"], "bedrooms": best["bedrooms"],
        "negotiable": any(l["negotiable"] for l in listings),
        "coastal": any(l["coastal"] for l in listings),
        "verified": any(l["verified"] for l in listings),
        "coast_distance_m": round(coast_m, -1),
        "last_checked_at": max(l["last_checked_at"] for l in listings).isoformat(),
        "eligibility": eligibility_summary(listings),
        "price_check": price_check(conn, prop, best),
        "history": history(conn, [l["id"] for l in listings]),
        "photos": photos(conn, listings, public_base_url),
        # Freely licensed photos of the surroundings: never of this property.
        "area_photos": area_photos(conn, prop["locality_id"]),
        "costs": {
            "deposit_pct": deposit_pct, "term_years": term_years,
            "assumed_rate_pct": str(est.annual_rate_pct),
            "registration_duty_pct": str(est.registration_duty_pct),
            "loan": _money_json(est.loan), "monthly": _money_json(est.monthly), "upfront": _money_json(est.upfront),
        },
        "sources": [
            {"listing_id": str(l["id"]), "source": l["source_name"], "kind": l["source_kind"], "url": l["url"],
             "agency": l["agency_name"], "price": _money_json(Money(l["price_minor"], l["currency"])),
             "first_seen_at": l["first_seen_at"].isoformat(), "last_checked_at": l["last_checked_at"].isoformat(),
             "verified": l["verified"],
             "images": l["image_urls"] if l["images_licensed"] else []}
            for l in listings
        ],
    }
