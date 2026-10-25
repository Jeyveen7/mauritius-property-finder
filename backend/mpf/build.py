"""The daily build: import every configured source, then export the static snapshot.
Runs in GitHub Actions (free), so nobody hosts a server. See data/sources.toml."""

import tomllib
from dataclasses import dataclass
from pathlib import Path

import psycopg
from psycopg.types.json import Jsonb

from mpf import alerts, config, snapshot
from mpf.ingest import pipeline
from mpf.ingest.adapters import PartnerFeedAdapter, PushedFeedAdapter, csv_to_feed
from mpf.ingest.crawl import CrawlRefused, StructuredDataCrawler
from mpf.repo import load_fx


@dataclass
class _Named:
    """Gives a file or URL adapter the id and name from the config, not from the file."""
    inner: object
    source_id: str
    source_name: str
    images_licensed: bool
    kind: str = "partner_feed"

    def fetch(self):
        return self.inner.fetch()


def _partner_adapter(entry: dict, base: Path):
    licensed = bool(entry.get("images_licensed"))
    if entry.get("feed_url"):
        return _Named(PartnerFeedAdapter(entry["feed_url"], images_licensed=licensed), entry["id"], entry["name"], licensed)
    path = (base / entry["file"]).resolve()
    text = path.read_text(encoding="utf-8-sig")
    import json
    document = csv_to_feed(text) if path.suffix.lower() == ".csv" else json.loads(text)
    return PushedFeedAdapter(entry["id"], entry["name"], document, images_licensed=licensed)


def run(conn: psycopg.Connection, sources_file: Path, out: Path, settings: config.Settings) -> dict:
    cfg = tomllib.loads(sources_file.read_text())
    base = sources_file.parent
    report = {"partners": {}, "crawls": {}, "errors": []}
    fx = load_fx(conn)

    for entry in cfg.get("partner", []):
        try:
            run_ = pipeline.run_source(conn, _partner_adapter(entry, base), settings)
            alerts.record(conn, run_, fx)
            report["partners"][entry["id"]] = run_.summary()
        except Exception as e:  # one broken feed must not stop the others
            report["errors"].append(f"{entry['id']}: {e}")

    for entry in cfg.get("crawl", []):
        reviewed = bool(entry.get("terms_reviewed_by") and entry.get("terms_note"))
        conn.execute(
            """INSERT INTO source (id, name, kind, crawl_start_urls, terms_reviewed_at, terms_reviewed_by, terms_note)
               VALUES (%s, %s, 'crawl', %s, CASE WHEN %s THEN now() END, %s, %s)
               ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, crawl_start_urls = EXCLUDED.crawl_start_urls,
                 terms_reviewed_at = coalesce(source.terms_reviewed_at, EXCLUDED.terms_reviewed_at),
                 terms_reviewed_by = EXCLUDED.terms_reviewed_by, terms_note = EXCLUDED.terms_note""",
            (entry["id"], entry["name"], entry["start_urls"], reviewed, entry.get("terms_reviewed_by"), entry.get("terms_note")),
        )
        try:
            crawler = StructuredDataCrawler(entry["id"], entry["name"], entry["start_urls"], terms_reviewed=reviewed,
                                            contact=config.require_contact(settings),
                                            images_licensed=bool(entry.get("images_licensed")))
            run_ = pipeline.run_source(conn, crawler, settings)
            report["crawls"][entry["id"]] = run_.summary()
            conn.execute("UPDATE source SET last_run_at = now(), last_run_report = %s WHERE id = %s",
                         (Jsonb(run_.summary()), entry["id"]))
        except (CrawlRefused, SystemExit) as e:
            report["errors"].append(f"{entry['id']}: refused: {e}")
        except Exception as e:
            report["errors"].append(f"{entry['id']}: {e}")

    report["snapshot"] = {k: v for k, v in snapshot.export(conn, out).items() if k in ("version", "count")}
    return report
