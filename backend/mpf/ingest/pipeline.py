"""Import one source: normalise each listing, upsert it, record snapshots and price
changes, attach it to a property (merging duplicates), and expire listings the
source no longer carries.

Every source run is one transaction per listing, so a bad listing never blocks the rest.
"""

from dataclasses import dataclass, field

import psycopg
from psycopg.types.json import Jsonb

from mpf.config import Settings
from mpf.domain import dedup, eligibility
from mpf.domain.eligibility import Rules
from mpf.domain.geo import bbox_around
from mpf.domain.money import FxTable, Money
from mpf.domain.text import content_hash
from mpf.ingest.adapters import RawListing, SourceAdapter
from mpf.repo import Gazetteer, load_fx, load_rules

CANDIDATE_RADIUS_M = 2_000


@dataclass
class RunReport:
    source_id: str
    seen: int = 0
    created: int = 0
    updated: int = 0
    unchanged: int = 0
    price_changes: int = 0
    merged: int = 0
    queued_for_review: int = 0
    new_properties: int = 0
    expired: int = 0
    rejected: list[tuple[str, str]] = field(default_factory=list)
    # For the alert matcher.
    new_property_ids: set = field(default_factory=set)
    price_drop_property_ids: set = field(default_factory=set)
    gone_property_ids: set = field(default_factory=set)

    def summary(self) -> dict:
        """The counts, without the id sets used internally by the alert matcher."""
        return {k: v for k, v in vars(self).items() if not k.endswith("_ids")}


class Rejected(Exception):
    pass


@dataclass
class _Context:
    rules: Rules
    fx: FxTable
    gazetteer: Gazetteer


def _facts(raw: RawListing, locality: dict, lat: float, lon: float, precision: str,
           price_mur_minor: int, elig: dict) -> dict:
    return {
        "type": raw.type, "title": raw.title, "locality_id": locality["id"],
        "lat": lat, "lon": lon, "geo_precision": precision,
        "price_minor": raw.price.minor, "currency": raw.price.currency, "price_mur_minor": price_mur_minor,
        "negotiable": raw.negotiable, "plot_m2": raw.plot_m2, "floor_m2": raw.floor_m2,
        "bedrooms": raw.bedrooms, "scheme": raw.scheme, "floors_above_ground": raw.floors_above_ground,
        "coastal": raw.coastal, "agency_name": raw.agency_name, "url": raw.url,
        "contact_url": raw.contact_url, "image_urls": list(raw.image_urls),
        "image_hashes": list(raw.image_hashes), "verified": raw.verified, "eligibility": elig,
    }


def normalise(ctx: _Context, raw: RawListing) -> dict:
    locality = ctx.gazetteer.resolve(raw.locality)
    if locality is None:
        raise Rejected(f"unknown_locality:{raw.locality}")
    if raw.plot_m2 is None and raw.floor_m2 is None:
        raise Rejected("no_size")
    if raw.lat is not None and raw.lon is not None:
        lat, lon, precision = raw.lat, raw.lon, "exact"
    else:
        lat, lon, precision = locality["lat"], locality["lon"], "locality"
    price_mur = ctx.fx.convert(raw.price, "MUR").minor
    elig = eligibility.evaluate(ctx.rules, ctx.fx, type=raw.type, scheme=raw.scheme,
                                floors_above_ground=raw.floors_above_ground, price=raw.price)
    return _facts(raw, locality, lat, lon, precision, price_mur, elig.to_json())


_COLUMNS = ["type", "title", "locality_id", "lat", "lon", "geo_precision", "price_minor", "currency",
            "price_mur_minor", "negotiable", "plot_m2", "floor_m2", "bedrooms", "scheme",
            "floors_above_ground", "coastal", "agency_name", "url", "contact_url", "image_urls",
            "image_hashes", "verified", "eligibility"]


def _params(facts: dict) -> list:
    return [Jsonb(facts[c]) if c == "eligibility" else facts[c] for c in _COLUMNS]


def _candidate(row: dict) -> dedup.Candidate:
    return dedup.Candidate(
        type=row["type"], lat=row["lat"], lon=row["lon"], geo_precision=row["geo_precision"],
        locality_id=row["locality_id"], price_mur_minor=row["price_mur_minor"],
        plot_m2=row["plot_m2"], floor_m2=row["floor_m2"], bedrooms=row["bedrooms"],
        title=row["title"], image_hashes=tuple(row["image_hashes"] or ()),
    )


def attach_to_property(conn: psycopg.Connection, listing: dict, report: RunReport) -> None:
    """Merge the listing into the best-matching property, queue it for review, or
    give it a new property."""
    min_lat, min_lon, max_lat, max_lon = bbox_around(listing["lat"], listing["lon"], CANDIDATE_RADIUS_M)
    others = conn.execute(
        """SELECT l.*, l.property_id AS pid FROM listing l JOIN property p ON p.id = l.property_id
           WHERE p.status = 'active' AND l.status = 'active' AND l.type = %(type)s AND l.id <> %(id)s
             AND (l.locality_id = %(locality_id)s OR (l.lat BETWEEN %(min_lat)s AND %(max_lat)s
                                                      AND l.lon BETWEEN %(min_lon)s AND %(max_lon)s))
             -- One source rarely lists the same place twice; two of its adverts are two places.
             AND NOT EXISTS (SELECT 1 FROM listing s WHERE s.property_id = l.property_id
                             AND s.source_id = %(source_id)s AND s.status = 'active')
           LIMIT 200""",
        {**listing, "min_lat": min_lat, "max_lat": max_lat, "min_lon": min_lon, "max_lon": max_lon},
    ).fetchall()

    new = _candidate(listing)
    best_pid, best = None, 0.0
    for other in others:
        s = dedup.score(new, _candidate(other))
        if s > best:
            best_pid, best = other["pid"], s

    decision = dedup.decide(best) if best_pid else "new"
    if decision == "merge":
        conn.execute("UPDATE listing SET property_id = %s WHERE id = %s", (best_pid, listing["id"]))
        if listing["geo_precision"] == "exact":
            conn.execute("UPDATE property SET lat = %s, lon = %s, updated_at = now() WHERE id = %s",
                         (listing["lat"], listing["lon"], best_pid))
        conn.execute(
            "INSERT INTO merge_decision (listing_id, property_id, action, score, decided_by) VALUES (%s, %s, 'merge', %s, 'auto')",
            (listing["id"], best_pid, best),
        )
        report.merged += 1
        return

    pid = conn.execute(
        "INSERT INTO property (type, locality_id, lat, lon) VALUES (%s, %s, %s, %s) RETURNING id",
        (listing["type"], listing["locality_id"], listing["lat"], listing["lon"]),
    ).fetchone()["id"]
    conn.execute("UPDATE listing SET property_id = %s WHERE id = %s", (pid, listing["id"]))
    conn.execute(
        "INSERT INTO merge_decision (listing_id, property_id, action, score, decided_by) VALUES (%s, %s, 'new', %s, 'auto')",
        (listing["id"], pid, best if best_pid else None),
    )
    report.new_properties += 1
    report.new_property_ids.add(pid)
    if decision == "review":
        conn.execute(
            """INSERT INTO review_item (listing_id, candidate_property_id, score) VALUES (%s, %s, %s)
               ON CONFLICT DO NOTHING""",
            (listing["id"], best_pid, best),
        )
        report.queued_for_review += 1


def upsert(conn: psycopg.Connection, ctx: _Context, source_id: str, raw: RawListing,
           report: RunReport) -> dict:
    facts = normalise(ctx, raw)
    # Hash only what the source said: FX moves and rule changes are not listing changes.
    h = content_hash({k: v for k, v in facts.items() if k not in ("price_mur_minor", "eligibility")})
    existing = conn.execute(
        "SELECT * FROM listing WHERE source_id = %s AND source_ref = %s FOR UPDATE", (source_id, raw.ref)
    ).fetchone()

    if existing is None:
        cols = ", ".join(_COLUMNS)
        marks = ", ".join(["%s"] * len(_COLUMNS))
        row = conn.execute(
            f"INSERT INTO listing (source_id, source_ref, {cols}, content_hash) VALUES (%s, %s, {marks}, %s) RETURNING *",
            [source_id, raw.ref, *_params(facts), h],
        ).fetchone()
        conn.execute(
            "INSERT INTO snapshot (listing_id, price_minor, currency, status, content_hash) VALUES (%s, %s, %s, 'active', %s)",
            (row["id"], row["price_minor"], row["currency"], h),
        )
        report.created += 1
        attach_to_property(conn, row, report)
        return row

    was_gone = existing["status"] == "gone"
    conn.execute(
        """UPDATE listing SET last_seen_at = now(), last_checked_at = now(), missed_checks = 0,
             status = CASE WHEN status = 'gone' THEN 'active' ELSE status END
           WHERE id = %s""",
        (existing["id"],),
    )
    if was_gone and existing["property_id"]:
        conn.execute("UPDATE property SET status = 'active', updated_at = now() WHERE id = %s AND status = 'gone'",
                     (existing["property_id"],))

    if existing["content_hash"] == h:
        report.unchanged += 1
        return existing

    sets = ", ".join(f"{c} = %s" for c in _COLUMNS)
    row = conn.execute(
        f"UPDATE listing SET {sets}, content_hash = %s WHERE id = %s RETURNING *",
        [*_params(facts), h, existing["id"]],
    ).fetchone()
    # A snapshot is kept whenever the facts change; last_checked_at records every visit.
    conn.execute(
        "INSERT INTO snapshot (listing_id, price_minor, currency, status, content_hash) VALUES (%s, %s, %s, %s, %s)",
        (row["id"], row["price_minor"], row["currency"], row["status"], h),
    )
    if (existing["price_minor"], existing["currency"]) != (row["price_minor"], row["currency"]):
        old_in_new_ccy = existing["price_minor"] if existing["currency"] == row["currency"] else \
            ctx.fx.convert(_money(existing), row["currency"]).minor
        conn.execute(
            "INSERT INTO price_event (listing_id, old_price_minor, new_price_minor, currency) VALUES (%s, %s, %s, %s)",
            (row["id"], old_in_new_ccy, row["price_minor"], row["currency"]),
        )
        report.price_changes += 1
        if row["price_minor"] < old_in_new_ccy and row["property_id"]:
            report.price_drop_property_ids.add(row["property_id"])
    report.updated += 1
    return row


def _money(row: dict) -> Money:
    return Money(row["price_minor"], row["currency"])


def expire_unseen(conn: psycopg.Connection, source_id: str, seen_refs: set[str],
                  missed_to_expire: int, report: RunReport) -> None:
    rows = conn.execute(
        """UPDATE listing SET missed_checks = missed_checks + 1, last_checked_at = now()
           WHERE source_id = %s AND status = 'active' AND NOT (source_ref = ANY(%s))
           RETURNING id, property_id, missed_checks, price_minor, currency, content_hash""",
        (source_id, list(seen_refs)),
    ).fetchall()
    for r in rows:
        if r["missed_checks"] < missed_to_expire:
            continue
        conn.execute("UPDATE listing SET status = 'gone' WHERE id = %s", (r["id"],))
        conn.execute(
            "INSERT INTO snapshot (listing_id, price_minor, currency, status, content_hash) VALUES (%s, %s, %s, 'gone', %s)",
            (r["id"], r["price_minor"], r["currency"], r["content_hash"]),
        )
        report.expired += 1
        still_listed = conn.execute(
            "SELECT 1 FROM listing WHERE property_id = %s AND status = 'active' LIMIT 1", (r["property_id"],)
        ).fetchone()
        if not still_listed:
            conn.execute("UPDATE property SET status = 'gone', updated_at = now() WHERE id = %s AND status = 'active'",
                         (r["property_id"],))
            report.gone_property_ids.add(r["property_id"])


def ensure_source(conn: psycopg.Connection, adapter: SourceAdapter) -> None:
    conn.execute(
        """INSERT INTO source (id, name, kind, images_licensed) VALUES (%s, %s, %s, %s)
           ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, images_licensed = EXCLUDED.images_licensed""",
        (adapter.source_id, adapter.source_name, adapter.kind, adapter.images_licensed),
    )


def context(conn: psycopg.Connection) -> _Context:
    return _Context(rules=load_rules(conn), fx=load_fx(conn), gazetteer=Gazetteer(conn))


def run_source(conn: psycopg.Connection, adapter: SourceAdapter, settings: Settings) -> RunReport:
    """Import a source's full inventory. The adapter must return everything the source
    currently lists: listings it omits count as a missed check."""
    with conn.transaction():
        ensure_source(conn, adapter)
    ctx = context(conn)
    report = RunReport(source_id=adapter.source_id)
    seen: set[str] = set()
    for raw in adapter.fetch():
        report.seen += 1
        seen.add(raw.ref)
        try:
            with conn.transaction():
                upsert(conn, ctx, adapter.source_id, raw, report)
        except Rejected as e:
            report.rejected.append((raw.ref, str(e)))
    with conn.transaction():
        expire_unseen(conn, adapter.source_id, seen, settings.missed_checks_to_expire, report)
    return report


def recompute_eligibility(conn: psycopg.Connection) -> int:
    """Re-run the foreign-buyer rules on every listing, e.g. after a new rules_version."""
    ctx = context(conn)
    n = 0
    with conn.transaction():
        for row in conn.execute("SELECT * FROM listing").fetchall():
            elig = eligibility.evaluate(ctx.rules, ctx.fx, type=row["type"], scheme=row["scheme"],
                                        floors_above_ground=row["floors_above_ground"], price=_money(row))
            conn.execute("UPDATE listing SET eligibility = %s WHERE id = %s", (Jsonb(elig.to_json()), row["id"]))
            n += 1
    return n
