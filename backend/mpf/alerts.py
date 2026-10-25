"""Turns an import run into alerts (new matches for saved searches, price drops and
removals for saved properties) and hands unsent alerts to a push sender."""

import logging
from typing import Protocol

import psycopg

from mpf import search
from mpf.domain.money import FxTable
from mpf.ingest.pipeline import RunReport
from mpf.push import FcmSender, LogPushSender, TokenGone

log = logging.getLogger(__name__)


def record(conn: psycopg.Connection, report: RunReport, fx: FxTable) -> int:
    created = 0
    with conn.transaction():
        if report.new_property_ids:
            for s in conn.execute("SELECT * FROM saved_search").fetchall():
                filters = search.Filters.model_validate(s["filters"])
                ids = [c["id"] for c in search.cards(conn, filters, fx, ids=list(report.new_property_ids), limit=500)]
                for pid in ids:
                    conn.execute(
                        "INSERT INTO alert (user_id, kind, saved_search_id, property_id) VALUES (%s, 'new_match', %s, %s)",
                        (s["user_id"], s["id"], pid),
                    )
                    created += 1
        for kind, ids in (("price_drop", report.price_drop_property_ids), ("gone", report.gone_property_ids)):
            if not ids:
                continue
            rows = conn.execute(
                """INSERT INTO alert (user_id, kind, property_id)
                   SELECT user_id, %s, property_id FROM saved_property WHERE property_id = ANY(%s)
                   RETURNING id""",
                (kind, list(ids)),
            ).fetchall()
            created += len(rows)
    return created


class PushSender(Protocol):
    def send(self, push_token: str, kind: str, property_id: str) -> None: ...


def sender_for(settings) -> PushSender:
    if settings.fcm_service_account:
        return FcmSender(settings.fcm_service_account)
    return LogPushSender()


def deliver(conn: psycopg.Connection, sender: PushSender, batch: int = 500) -> int:
    sent = 0
    with conn.transaction():
        rows = conn.execute(
            """SELECT a.id, a.kind, a.property_id, u.push_token FROM alert a JOIN app_user u ON u.id = a.user_id
               WHERE a.sent_at IS NULL ORDER BY a.created_at LIMIT %s FOR UPDATE OF a SKIP LOCKED""",
            (batch,),
        ).fetchall()
        for r in rows:
            if r["push_token"]:
                try:
                    sender.send(r["push_token"], r["kind"], str(r["property_id"]))
                except TokenGone:
                    conn.execute("UPDATE app_user SET push_token = NULL WHERE push_token = %s", (r["push_token"],))
            # Alerts without a device token stay readable in the app's inbox.
            conn.execute("UPDATE alert SET sent_at = now() WHERE id = %s", (r["id"],))
            sent += 1
    return sent
