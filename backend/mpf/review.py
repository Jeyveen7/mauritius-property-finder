"""Human decisions on possible duplicates and on reported properties."""

import psycopg


def open_items(conn: psycopg.Connection) -> list[dict]:
    """Open duplicate reviews with both sides' facts, best match first."""
    return conn.execute(
        """SELECT r.id, r.score, r.created_at,
                  l.title AS new_title, l.source_id AS new_source, l.url AS new_url, l.price_minor AS new_price,
                  l.currency AS new_currency, l.plot_m2 AS new_plot, l.floor_m2 AS new_floor,
                  loc.name AS locality,
                  c.title AS old_title, c.source_id AS old_source, c.url AS old_url, c.price_minor AS old_price,
                  c.currency AS old_currency, c.plot_m2 AS old_plot, c.floor_m2 AS old_floor
           FROM review_item r
           JOIN listing l ON l.id = r.listing_id
           LEFT JOIN locality loc ON loc.id = l.locality_id
           JOIN LATERAL (SELECT * FROM listing x WHERE x.property_id = r.candidate_property_id AND x.status = 'active'
                         ORDER BY x.first_seen_at LIMIT 1) c ON true
           WHERE r.status = 'open' ORDER BY r.score DESC"""
    ).fetchall()


def resolve(conn: psycopg.Connection, item_id: int, decision: str, by: str) -> None:
    """`merge` moves the listing onto the candidate property (followers move too);
    `separate` keeps them apart. Both are logged in merge_decision / review_item."""
    if decision not in ("merge", "separate"):
        raise ValueError("decision must be merge or separate")
    with conn.transaction():
        item = conn.execute("SELECT * FROM review_item WHERE id = %s AND status = 'open' FOR UPDATE",
                            (item_id,)).fetchone()
        if item is None:
            raise LookupError("no open review item with that id")
        if decision == "merge":
            old = conn.execute("SELECT property_id FROM listing WHERE id = %s", (item["listing_id"],)).fetchone()["property_id"]
            conn.execute("UPDATE listing SET property_id = %s WHERE id = %s", (item["candidate_property_id"], item["listing_id"]))
            retired = conn.execute(
                """UPDATE property SET status = 'gone', updated_at = now() WHERE id = %s
                   AND NOT EXISTS (SELECT 1 FROM listing WHERE property_id = %s AND status = 'active')
                   RETURNING id""",
                (old, old),
            ).fetchone()
            if retired:
                # Whoever saved the duplicate keeps following the merged property.
                conn.execute(
                    """INSERT INTO saved_property (user_id, property_id)
                       SELECT user_id, %s FROM saved_property WHERE property_id = %s ON CONFLICT DO NOTHING""",
                    (item["candidate_property_id"], old),
                )
                conn.execute("DELETE FROM saved_property WHERE property_id = %s", (old,))
            conn.execute(
                "INSERT INTO merge_decision (listing_id, property_id, action, score, decided_by) VALUES (%s, %s, 'merge', %s, %s)",
                (item["listing_id"], item["candidate_property_id"], item["score"], f"human:{by}"),
            )
        conn.execute(
            "UPDATE review_item SET status = %s, resolved_at = now(), resolved_by = %s WHERE id = %s",
            ("merged" if decision == "merge" else "separate", by, item_id),
        )


def hidden_properties(conn: psycopg.Connection) -> list[dict]:
    return conn.execute(
        """SELECT p.id, loc.name AS locality, (SELECT title FROM listing WHERE property_id = p.id LIMIT 1) AS title,
                  count(r.id) AS reports, string_agg(DISTINCT r.reason, ', ') AS reasons
           FROM property p LEFT JOIN locality loc ON loc.id = p.locality_id
           LEFT JOIN report r ON r.property_id = p.id
           WHERE p.status = 'hidden' GROUP BY p.id, loc.name ORDER BY count(r.id) DESC"""
    ).fetchall()


def settle_reports(conn: psycopg.Connection, property_id, action: str) -> None:
    """`restore` puts a wrongly reported property back; `remove` takes it down for good."""
    with conn.transaction():
        if action == "restore":
            conn.execute("UPDATE property SET status = 'active', updated_at = now() WHERE id = %s", (property_id,))
            conn.execute("DELETE FROM report WHERE property_id = %s", (property_id,))
        elif action == "remove":
            conn.execute("UPDATE property SET status = 'gone', updated_at = now() WHERE id = %s", (property_id,))
            conn.execute("UPDATE listing SET status = 'hidden' WHERE property_id = %s", (property_id,))
        else:
            raise ValueError("action must be restore or remove")
