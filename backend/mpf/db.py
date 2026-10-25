from pathlib import Path

import psycopg
from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool

MIGRATIONS_DIR = Path(__file__).resolve().parent.parent / "migrations"


# Autocommit: single statements commit on their own, and `with conn.transaction():`
# blocks are the unit of atomicity everywhere in the code.
CONNECT_KWARGS = {"row_factory": dict_row, "autocommit": True}


def connect(url: str) -> psycopg.Connection:
    return psycopg.connect(url, **CONNECT_KWARGS)


def pool(url: str, min_size: int = 1, max_size: int = 10) -> ConnectionPool:
    return ConnectionPool(url, min_size=min_size, max_size=max_size, kwargs=CONNECT_KWARGS, open=True)


def migrate(conn: psycopg.Connection) -> list[str]:
    """Apply any migrations in migrations/ that have not run yet, in name order."""
    conn.execute("CREATE TABLE IF NOT EXISTS schema_migration (name text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())")
    done = {r["name"] for r in conn.execute("SELECT name FROM schema_migration")}
    applied = []
    for path in sorted(MIGRATIONS_DIR.glob("*.sql")):
        if path.name in done:
            continue
        with conn.transaction():
            conn.execute(path.read_text())
            conn.execute("INSERT INTO schema_migration (name) VALUES (%s)", (path.name,))
        applied.append(path.name)
    return applied
