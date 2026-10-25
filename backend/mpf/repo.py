"""Reference data: foreign-buyer rules, FX rates, settings and the locality gazetteer."""

import csv
import json
from datetime import date
from decimal import Decimal
from pathlib import Path

import psycopg
from psycopg.types.json import Jsonb

from mpf.domain.eligibility import Rules
from mpf.domain.money import FxTable
from mpf.domain.text import norm

SEED_DIR = Path(__file__).resolve().parent.parent / "seed"


def load_rules(conn: psycopg.Connection, on: date | None = None) -> Rules:
    row = conn.execute(
        "SELECT * FROM rules_version WHERE effective_from <= %s ORDER BY effective_from DESC LIMIT 1",
        (on or date.today(),),
    ).fetchone()
    if row is None:
        raise LookupError("no rules_version in effect; run `mpf seed`")
    return Rules.from_row(row)


def load_fx(conn: psycopg.Connection) -> FxTable:
    rows = conn.execute(
        "SELECT DISTINCT ON (currency) day, currency, mur_per_unit FROM fx_rate ORDER BY currency, day DESC"
    ).fetchall()
    if not rows:
        raise LookupError("no FX rates; run `mpf seed` or `mpf fx`")
    return FxTable(day=min(r["day"] for r in rows), mur_per_unit={r["currency"]: r["mur_per_unit"] for r in rows})


def get_setting(conn: psycopg.Connection, key: str):
    row = conn.execute("SELECT value FROM setting WHERE key = %s", (key,)).fetchone()
    if row is None:
        raise LookupError(f"setting {key} missing")
    return row["value"]


class Gazetteer:
    """Resolves a place name as written by a source or a user to a locality row."""

    def __init__(self, conn: psycopg.Connection):
        self.rows = conn.execute("SELECT * FROM locality").fetchall()
        self._by_key: dict[str, dict] = {}
        for row in self.rows:
            for name in [row["name"], *row["aliases"]]:
                self._by_key[norm(name)] = row

    def resolve(self, name: str | None) -> dict | None:
        return self._by_key.get(norm(name)) if name else None

    def names(self) -> list[str]:
        return [n for row in self.rows for n in [row["name"], *row["aliases"]]]


def seed_reference_data(conn: psycopg.Connection) -> None:
    """Idempotent load of localities, the current rules, FX rates and settings."""
    seed_rows: dict[str, set[str]] = {}  # name -> normalised aliases
    with open(SEED_DIR / "localities.csv", newline="") as f:
        for r in csv.DictReader(f):
            aliases = [a for a in r["aliases"].split("|") if a]
            seed_rows[r["name"]] = {norm(a) for a in aliases}
            search_text = norm(" ".join([r["name"], r["district"], *aliases]))
            conn.execute(
                """INSERT INTO locality (name, district, region, lat, lon, aliases, search_text)
                   VALUES (%s, %s, %s, %s, %s, %s, %s)
                   ON CONFLICT (name) DO UPDATE SET district = EXCLUDED.district, region = EXCLUDED.region,
                     lat = EXCLUDED.lat, lon = EXCLUDED.lon, aliases = EXCLUDED.aliases,
                     search_text = EXCLUDED.search_text""",
                (r["name"], r["district"], r["region"], float(r["lat"]), float(r["lon"]), aliases, search_text),
            )

    # Localities dropped from the seed (e.g. a hand-typed name now spelt as in
    # OpenStreetMap) are merged into the seed entry that has their name as an alias.
    seed_names = {r["name"] for r in conn.execute("SELECT name FROM locality").fetchall()} & set(seed_rows)
    for old in conn.execute("SELECT * FROM locality WHERE NOT (name = ANY(%s))", (list(seed_names),)).fetchall():
        key = norm(old["name"])
        match = next((n for n, aliases in seed_rows.items() if key == norm(n) or key in aliases), None)
        if match is None:
            continue
        target = conn.execute("SELECT id FROM locality WHERE name = %s", (match,)).fetchone()
        for table in ("property", "listing"):
            conn.execute(f"UPDATE {table} SET locality_id = %s WHERE locality_id = %s", (target["id"], old["id"]))
        conn.execute("DELETE FROM area_photo WHERE locality_id = %s", (old["id"],))
        conn.execute("DELETE FROM locality WHERE id = %s", (old["id"],))

    for path in sorted(SEED_DIR.glob("rules_*.json")):
        doc = json.loads(path.read_text())
        conn.execute(
            """INSERT INTO rules_version (effective_from, label, rules, legal_reviewed_by)
               VALUES (%s, %s, %s, %s) ON CONFLICT (effective_from) DO NOTHING""",
            (doc["effective_from"], doc["label"], Jsonb(doc["rules"]), doc["legal_reviewed_by"]),
        )

    # Placeholder rates so a fresh database works; `mpf fx` records real daily rates.
    for currency, rate in (("EUR", Decimal("52.5")), ("USD", Decimal("45.5"))):
        conn.execute(
            """INSERT INTO fx_rate (day, currency, mur_per_unit, source)
               VALUES (DATE '2026-10-01', %s, %s, 'seed placeholder') ON CONFLICT DO NOTHING""",
            (currency, rate),
        )

    conn.execute(
        """INSERT INTO setting (key, value) VALUES ('assumed_mortgage_rate_pct', '6.0')
           ON CONFLICT (key) DO NOTHING"""
    )
    conn.execute(
        """INSERT INTO source (id, name, kind) VALUES ('user-posts', 'Shared by users', 'user_post')
           ON CONFLICT (id) DO NOTHING"""
    )
