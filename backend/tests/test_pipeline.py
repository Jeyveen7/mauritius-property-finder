from dataclasses import dataclass
from decimal import Decimal
from pathlib import Path

from mpf import alerts
from mpf.domain.money import Money
from mpf.ingest import pipeline
from mpf.ingest.adapters import PartnerFeedAdapter, RawListing
from mpf.repo import load_fx

FEEDS = Path(__file__).resolve().parent.parent / "seed" / "sample_feeds"


@dataclass
class ListAdapter:
    source_id: str
    listings: list[RawListing]
    source_name: str = "test source"
    kind: str = "partner_feed"
    images_licensed: bool = False

    def fetch(self):
        return iter(self.listings)


def plot(ref="R1", **over) -> RawListing:
    base = dict(ref=ref, type="land", title="Residential plot near the beach", locality="Pereybère",
                lat=-20.0151, lon=57.5952, price=Money.of(5_900_000, "MUR"), plot_m2=Decimal(507))
    return RawListing(**{**base, **over})


def test_sample_feed_import_is_idempotent(conn, settings):
    first = pipeline.run_source(conn, PartnerFeedAdapter(str(FEEDS / "portal_a.json")), settings)
    assert (first.created, first.rejected) == (13, [])
    second = pipeline.run_source(conn, PartnerFeedAdapter(str(FEEDS / "portal_a.json")), settings)
    assert (second.created, second.updated, second.unchanged) == (0, 0, 13)
    assert conn.execute("SELECT count(*) AS n FROM snapshot").fetchone()["n"] == 13


def test_foreign_currency_is_kept_as_advertised(conn, settings):
    pipeline.run_source(conn, PartnerFeedAdapter(str(FEEDS / "portal_a.json")), settings)
    villa = conn.execute("SELECT * FROM listing WHERE source_ref = 'A-104'").fetchone()
    assert (villa["price_minor"], villa["currency"]) == (136_000_000, "USD")
    assert villa["price_mur_minor"] == 136_000_000 * 455 // 10
    assert villa["eligibility"]["status"] == "yes" and villa["eligibility"]["residency"]
    g2_without_floors = conn.execute("SELECT eligibility FROM listing WHERE source_ref = 'A-107'").fetchone()
    assert g2_without_floors["eligibility"]["status"] == "unknown"


def test_unknown_locality_is_rejected_not_guessed(conn, settings):
    report = pipeline.run_source(conn, ListAdapter("t", [plot(locality="Atlantis", lat=None, lon=None)]), settings)
    assert report.rejected == [("R1", "unknown_locality:Atlantis")]


def test_price_drop_records_event_and_alerts_saved_users(conn, settings):
    pipeline.run_source(conn, ListAdapter("t", [plot()]), settings)
    pid = conn.execute("SELECT property_id FROM listing").fetchone()["property_id"]
    user = conn.execute("INSERT INTO app_user DEFAULT VALUES RETURNING id").fetchone()["id"]
    conn.execute("INSERT INTO saved_property (user_id, property_id) VALUES (%s, %s)", (user, pid))

    report = pipeline.run_source(conn, ListAdapter("t", [plot(price=Money.of(5_500_000, "MUR"))]), settings)
    assert (report.updated, report.price_changes) == (1, 1)
    ev = conn.execute("SELECT * FROM price_event").fetchone()
    assert (ev["old_price_minor"], ev["new_price_minor"]) == (590_000_000, 550_000_000)
    assert alerts.record(conn, report, load_fx(conn)) == 1
    assert conn.execute("SELECT kind FROM alert").fetchone()["kind"] == "price_drop"


def test_listing_expires_after_missed_checks_and_comes_back(conn, settings):
    pipeline.run_source(conn, ListAdapter("t", [plot()]), settings)
    first_miss = pipeline.run_source(conn, ListAdapter("t", []), settings)
    assert first_miss.expired == 0
    second_miss = pipeline.run_source(conn, ListAdapter("t", []), settings)
    assert second_miss.expired == 1 and len(second_miss.gone_property_ids) == 1
    assert conn.execute("SELECT status FROM property").fetchone()["status"] == "gone"

    pipeline.run_source(conn, ListAdapter("t", [plot()]), settings)
    assert conn.execute("SELECT status FROM listing").fetchone()["status"] == "active"
    assert conn.execute("SELECT status FROM property").fetchone()["status"] == "active"


def test_same_place_on_two_sources_merges_into_one_property(conn, settings):
    pipeline.run_source(conn, ListAdapter("a", [plot()]), settings)
    report = pipeline.run_source(
        conn, ListAdapter("b", [plot(ref="B1", title="Plot near the beach, Pereybère", lat=-20.0152, lon=57.5951,
                                     price=Money.of(5_950_000, "MUR"))]), settings)
    assert report.merged == 1
    assert conn.execute("SELECT count(DISTINCT property_id) AS n FROM listing").fetchone()["n"] == 1


def test_review_queue_merge_moves_listing_and_followers(conn, settings, monkeypatch):
    from mpf import cli
    pipeline.run_source(conn, PartnerFeedAdapter(str(FEEDS / "portal_a.json")), settings)
    pipeline.run_source(conn, PartnerFeedAdapter(str(FEEDS / "portal_b.json")), settings)
    item = conn.execute(
        """SELECT r.*, l.property_id AS dup FROM review_item r JOIN listing l ON l.id = r.listing_id
           WHERE l.source_ref = 'B-9001'"""
    ).fetchone()
    user = conn.execute("INSERT INTO app_user DEFAULT VALUES RETURNING id").fetchone()["id"]
    conn.execute("INSERT INTO saved_property (user_id, property_id) VALUES (%s, %s)", (user, item["dup"]))

    monkeypatch.setenv("MPF_DATABASE_URL", settings.database_url)
    cli.main(["review", "--resolve", str(item["id"]), "--decision", "merge", "--by", "test"])

    assert conn.execute("SELECT count(*) AS n FROM listing WHERE property_id = %s",
                        (item["candidate_property_id"],)).fetchone()["n"] == 2
    assert conn.execute("SELECT status FROM property WHERE id = %s", (item["dup"],)).fetchone()["status"] == "gone"
    assert conn.execute("SELECT property_id FROM saved_property WHERE user_id = %s",
                        (user,)).fetchone()["property_id"] == item["candidate_property_id"]
    assert conn.execute("SELECT decided_by FROM merge_decision WHERE action = 'merge'").fetchone()["decided_by"] == "human:test"


def test_two_adverts_from_one_source_are_never_merged(conn, settings):
    report = pipeline.run_source(conn, ListAdapter("a", [plot(), plot(ref="R2")]), settings)
    assert (report.merged, report.new_properties) == (0, 2)


def test_rules_change_is_applied_by_recompute(conn, settings):
    pipeline.run_source(conn, PartnerFeedAdapter(str(FEEDS / "portal_a.json")), settings)
    conn.execute(
        """INSERT INTO rules_version (effective_from, label, rules)
           SELECT DATE '2026-10-20', 'test: G+2 minimum raised', jsonb_set(rules, '{g2,min_price_mur}', '20000000')
           FROM rules_version ORDER BY effective_from DESC LIMIT 1"""
    )
    pipeline.recompute_eligibility(conn)
    e = conn.execute("SELECT eligibility FROM listing WHERE source_ref = 'A-103'").fetchone()["eligibility"]
    assert (e["status"], e["reason"], e["rules_version"]) == ("no", "g2_below_min_price", "test: G+2 minimum raised")
    conn.execute("DELETE FROM rules_version WHERE label LIKE 'test:%'")
