"""Property search: filters, map clusters and paged lists, all on the server.

A "card" is one property summarised over its active listings: its cheapest asking
price, how many sources carry it, and whether any of them is open to foreign buyers.
"""

import math
from decimal import Decimal
from typing import Literal

import psycopg
from pydantic import BaseModel, Field

from mpf.domain.money import FxTable, Money
from mpf.domain.text import norm

Region = Literal["North", "West", "Centre", "East", "South"]

CARD_SQL = """
SELECT p.id, p.type, p.lat, p.lon, loc.name AS locality, loc.district, loc.region,
       loc.search_text,
       best.title, best.price_minor, best.currency, best.price_mur_minor,
       agg.n_sources, agg.foreign_eligible, agg.plot_m2, agg.floor_m2, agg.bedrooms,
       agg.unverified, agg.first_seen_at, agg.last_checked_at,
       EXISTS (SELECT 1 FROM price_event pe JOIN listing l2 ON l2.id = pe.listing_id
               WHERE l2.property_id = p.id AND l2.status = 'active'
                 AND pe.new_price_minor < pe.old_price_minor
                 AND pe.at > now() - interval '60 days') AS price_dropped,
       -- First photo we may show: a licensed partner photo, else one the poster took.
       coalesce(
         (SELECT l3.image_urls[1] FROM listing l3 JOIN source s3 ON s3.id = l3.source_id
          WHERE l3.property_id = p.id AND l3.status = 'active' AND s3.images_licensed
            AND cardinality(l3.image_urls) > 0 LIMIT 1),
         (SELECT ph.storage_path FROM photo ph JOIN listing l4 ON l4.id = ph.listing_id
          WHERE l4.property_id = p.id AND l4.status = 'active' AND ph.status = 'active'
          ORDER BY ph.created_at LIMIT 1)) AS thumb
FROM property p
LEFT JOIN locality loc ON loc.id = p.locality_id
JOIN LATERAL (
  SELECT count(*) AS n_sources, bool_or(l.foreign_eligible) AS foreign_eligible,
         max(l.plot_m2) AS plot_m2, max(l.floor_m2) AS floor_m2, max(l.bedrooms) AS bedrooms,
         bool_and(NOT l.verified) AS unverified, min(l.first_seen_at) AS first_seen_at,
         max(l.last_checked_at) AS last_checked_at
  FROM listing l WHERE l.property_id = p.id AND l.status = 'active'
) agg ON agg.n_sources > 0
JOIN LATERAL (
  SELECT l.title, l.price_minor, l.currency, l.price_mur_minor
  FROM listing l WHERE l.property_id = p.id AND l.status = 'active'
  ORDER BY l.price_mur_minor, l.first_seen_at LIMIT 1
) best ON true
WHERE p.status = 'active'
"""


class Filters(BaseModel):
    type: Literal["land", "apartment", "house"] | None = None
    regions: list[Region] = Field(default_factory=list)
    q: str | None = Field(None, description="Town, village or district; accents and case ignored")
    min_price: Decimal | None = None
    max_price: Decimal | None = None
    currency: Literal["MUR", "EUR", "USD"] = "MUR"
    min_plot_m2: Decimal | None = None
    min_bedrooms: int | None = None
    foreign_eligible: bool = False


class BBox(BaseModel):
    min_lat: float
    min_lon: float
    max_lat: float
    max_lon: float


def where(filters: Filters, fx: FxTable, bbox: BBox | None = None) -> tuple[str, dict]:
    """SQL conditions over CARD_SQL's columns."""
    clauses, params = [], {}
    if filters.type:
        clauses.append("type = %(type)s")
        params["type"] = filters.type
    if filters.regions:
        clauses.append("region = ANY(%(regions)s)")
        params["regions"] = list(filters.regions)
    if filters.q and (q := norm(filters.q)):
        clauses.append("(search_text LIKE %(q_like)s OR word_similarity(%(q)s, search_text) >= 0.6)")
        params.update(q=q, q_like=f"%{q}%")
    if filters.min_price is not None:
        clauses.append("price_mur_minor >= %(min_mur)s")
        params["min_mur"] = fx.convert(Money.of(filters.min_price, filters.currency), "MUR").minor
    if filters.max_price is not None:
        clauses.append("price_mur_minor < %(max_mur)s")
        params["max_mur"] = fx.convert(Money.of(filters.max_price, filters.currency), "MUR").minor
    if filters.min_plot_m2 is not None:
        # 1% slack so "half an arpent" plots match a half-arpent minimum.
        clauses.append("plot_m2 >= %(min_plot)s * 0.99")
        params["min_plot"] = filters.min_plot_m2
    if filters.min_bedrooms is not None:
        clauses.append("type <> 'land' AND bedrooms >= %(min_beds)s")
        params["min_beds"] = filters.min_bedrooms
    if filters.foreign_eligible:
        clauses.append("foreign_eligible")
    if bbox:
        clauses.append("lat BETWEEN %(min_lat)s AND %(max_lat)s AND lon BETWEEN %(min_lon)s AND %(max_lon)s")
        params.update(bbox.model_dump())
    return (" AND ".join(clauses) or "true"), params


def cards(conn: psycopg.Connection, filters: Filters, fx: FxTable, *, bbox: BBox | None = None,
          ids: list | None = None, order: str = "new", limit: int = 50, offset: int = 0) -> list[dict]:
    cond, params = where(filters, fx, bbox)
    if ids is not None:
        cond += " AND id = ANY(%(ids)s)"
        params["ids"] = ids
    order_sql = {
        "new": "first_seen_at DESC",
        "price": "price_mur_minor ASC",
        "value": "price_mur_minor / NULLIF(CASE WHEN type = 'land' THEN plot_m2 ELSE floor_m2 END, 0) ASC",
    }[order]
    sql = f"SELECT * FROM ({CARD_SQL}) c WHERE {cond} ORDER BY {order_sql}, id LIMIT %(limit)s OFFSET %(offset)s"
    return conn.execute(sql, {**params, "limit": limit, "offset": offset}).fetchall()


def count(conn: psycopg.Connection, filters: Filters, fx: FxTable, bbox: BBox | None = None) -> int:
    cond, params = where(filters, fx, bbox)
    return conn.execute(f"SELECT count(*) AS n FROM ({CARD_SQL}) c WHERE {cond}", params).fetchone()["n"]


def clusters(conn: psycopg.Connection, filters: Filters, fx: FxTable, bbox: BBox, zoom: int,
             cell_px: int = 64) -> list[dict]:
    """Group properties into grid cells about `cell_px` screen pixels wide at `zoom`
    (Web Mercator tiles of 256 px). A cell with one property is a pin; a cell whose
    properties all share one coordinate is a "stack" (the app lists them, since no
    zoom level separates them); anything else is a cluster to zoom into."""
    cond, params = where(filters, fx, bbox)
    rows = conn.execute(
        f"SELECT id, lat, lon, price_minor, currency, price_mur_minor, unverified FROM ({CARD_SQL}) c WHERE {cond}",
        params,
    ).fetchall()
    cell_deg = 360 / (256 * 2 ** zoom) * cell_px
    cells: dict[tuple[int, int], list[dict]] = {}
    for r in rows:
        key = (math.floor(r["lon"] / cell_deg), math.floor(_merc_y(r["lat"]) / cell_deg))
        cells.setdefault(key, []).append(r)

    out = []
    for members in cells.values():
        if len(members) == 1:
            r = members[0]
            out.append({"kind": "pin", "id": str(r["id"]), "lat": r["lat"], "lon": r["lon"],
                        "price": {"minor": r["price_minor"], "currency": r["currency"]},
                        "unverified": r["unverified"]})
            continue
        lat = sum(m["lat"] for m in members) / len(members)
        lon = sum(m["lon"] for m in members) / len(members)
        same_point = len({(round(m["lat"], 6), round(m["lon"], 6)) for m in members}) == 1
        out.append({"kind": "stack" if same_point else "cluster", "count": len(members),
                    "lat": lat, "lon": lon,
                    "ids": [str(m["id"]) for m in members] if same_point else None})
    return out


def _merc_y(lat: float) -> float:
    """Latitude in Mercator "degrees", so grid cells are square on screen."""
    return math.degrees(math.log(math.tan(math.pi / 4 + math.radians(lat) / 2)))
