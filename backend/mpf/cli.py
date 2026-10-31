"""Operations entry point: `python -m mpf.cli <command>`."""

import argparse
import json
import logging
from datetime import date
from decimal import Decimal

from psycopg.types.json import Jsonb

from mpf import alerts, config, db, review
from mpf.ingest import pipeline
from mpf.ingest.adapters import PartnerFeedAdapter
from mpf.repo import load_fx, seed_reference_data


def cmd_migrate(conn, args, settings):
    print("applied:", db.migrate(conn) or "nothing")


def cmd_seed(conn, args, settings):
    with conn.transaction():
        seed_reference_data(conn)
    print("reference data loaded")


def cmd_ingest(conn, args, settings):
    fx = load_fx(conn)
    for location in args.feeds:
        adapter = PartnerFeedAdapter(location, images_licensed=args.images_licensed)
        report = pipeline.run_source(conn, adapter, settings)
        n_alerts = alerts.record(conn, report, fx)
        print(json.dumps({**report.summary(), "alerts": n_alerts}, default=str))


def cmd_deliver_alerts(conn, args, settings):
    print("delivered:", alerts.deliver(conn, alerts.sender_for(settings)))


def cmd_fx(conn, args, settings):
    """Record the day's reference rates, then re-price listings in MUR for filtering."""
    day = date.fromisoformat(args.day) if args.day else date.today()
    with conn.transaction():
        for currency, rate in (("EUR", args.eur), ("USD", args.usd)):
            conn.execute(
                """INSERT INTO fx_rate (day, currency, mur_per_unit, source) VALUES (%s, %s, %s, %s)
                   ON CONFLICT (day, currency) DO UPDATE SET mur_per_unit = EXCLUDED.mur_per_unit, source = EXCLUDED.source""",
                (day, currency, Decimal(rate), args.source),
            )
        conn.execute(
            """UPDATE listing l SET price_mur_minor = CASE l.currency WHEN 'MUR' THEN l.price_minor
                 ELSE round(l.price_minor * (SELECT mur_per_unit FROM fx_rate f WHERE f.currency = l.currency
                                             ORDER BY day DESC LIMIT 1)) END"""
        )
    print("fx recorded for", day, "- now run recompute-eligibility")


def cmd_recompute_eligibility(conn, args, settings):
    print("listings updated:", pipeline.recompute_eligibility(conn))


def cmd_purge(conn, args, settings):
    n = conn.execute(
        """UPDATE user_post SET raw_text = NULL, raw_deleted_at = now()
           WHERE raw_text IS NOT NULL AND created_at < now() - make_interval(days => %s)""",
        (settings.post_retention_days,),
    ).rowcount
    print("post texts purged:", n)


def cmd_review(conn, args, settings):
    if args.resolve is None:
        for r in review.open_items(conn):
            print(json.dumps(r, default=str))
        return
    try:
        review.resolve(conn, args.resolve, args.decision, args.by)
    except LookupError as e:
        raise SystemExit(str(e)) from None
    print("resolved", args.resolve, args.decision)


def cmd_crawl(conn, args, settings):
    from mpf.ingest.crawl import CrawlRefused, StructuredDataCrawler
    src = conn.execute("SELECT * FROM source WHERE id = %s AND kind = 'crawl'", (args.source,)).fetchone()
    if src is None:
        raise SystemExit("no crawl source with that id (add it in the admin pages)")
    crawler = StructuredDataCrawler(src["id"], src["name"], list(src["crawl_start_urls"]),
                                    terms_reviewed=src["terms_reviewed_at"] is not None,
                                    contact=config.require_contact(settings),
                                    images_licensed=src["images_licensed"], max_pages=args.max_pages)
    try:
        report = pipeline.run_source(conn, crawler, settings)
    except CrawlRefused as e:
        raise SystemExit(f"refused: {e}") from None
    alerts.record(conn, report, load_fx(conn))
    conn.execute("UPDATE source SET last_run_at = now(), last_run_report = %s WHERE id = %s",
                 (Jsonb(report.summary()), src["id"]))
    print(json.dumps(report.summary(), default=str))


def cmd_area_photos(conn, args, settings):
    from mpf import commons
    print("area photos stored:", commons.refresh_all(conn, config.require_contact(settings), only_missing=not args.all))


def cmd_hash_images(conn, args, settings):
    """Perceptual hashes of licensed partner photos, used to merge duplicate listings."""
    from mpf import media
    n = 0
    for l in conn.execute(
        """SELECT l.id, l.image_urls FROM listing l JOIN source s ON s.id = l.source_id
           WHERE s.images_licensed AND cardinality(l.image_urls) > 0 AND cardinality(l.image_hashes) = 0"""
    ).fetchall():
        hashes = [h for h in (media.hash_remote(u, f"mpf-images/0.1 ({config.require_contact(settings)})")
                              for u in l["image_urls"][:4]) if h]
        if hashes:
            conn.execute("UPDATE listing SET image_hashes = %s WHERE id = %s", (hashes, l["id"]))
            n += 1
    print("listings hashed:", n)


def cmd_osm_import(conn, args, settings):
    from mpf import osm
    from pathlib import Path
    print(json.dumps(osm.write_seeds(Path(args.cache) if args.cache else None)), "- now run `seed`")


def cmd_export_snapshot(conn, args, settings):
    from pathlib import Path
    from mpf import snapshot
    meta = snapshot.export(conn, Path(args.out), settings.public_base_url)
    print(json.dumps({k: meta[k] for k in ("version", "count", "generated_at")}))


def cmd_edb_import(conn, args, settings):
    from pathlib import Path
    from mpf import official
    rows = official.parse(Path(args.text).read_text(encoding="utf-8"), args.scheme)
    print(args.scheme, "projects:", official.write_scheme(rows, args.scheme))


def cmd_build(conn, args, settings):
    from pathlib import Path
    from mpf import build
    report = build.run(conn, Path(args.sources), Path(args.out), settings)
    print(json.dumps(report, default=str, indent=1))
    if report["errors"] and args.strict:
        raise SystemExit(1)


def cmd_serve(conn, args, settings):
    import uvicorn
    from mpf.api.app import create_app
    uvicorn.run(create_app(settings), host=args.host, port=args.port)


def main(argv=None):
    logging.basicConfig(level=logging.INFO)
    parser = argparse.ArgumentParser(prog="mpf")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("migrate").set_defaults(fn=cmd_migrate)
    sub.add_parser("seed").set_defaults(fn=cmd_seed)
    p = sub.add_parser("ingest", help="import partner feeds (files or URLs)")
    p.add_argument("feeds", nargs="+")
    p.add_argument("--images-licensed", action="store_true")
    p.set_defaults(fn=cmd_ingest)
    sub.add_parser("deliver-alerts").set_defaults(fn=cmd_deliver_alerts)
    p = sub.add_parser("fx", help="record daily reference rates (MUR per unit)")
    p.add_argument("--eur", required=True)
    p.add_argument("--usd", required=True)
    p.add_argument("--day")
    p.add_argument("--source", default="Bank of Mauritius")
    p.set_defaults(fn=cmd_fx)
    sub.add_parser("recompute-eligibility").set_defaults(fn=cmd_recompute_eligibility)
    sub.add_parser("purge").set_defaults(fn=cmd_purge)
    p = sub.add_parser("review", help="list open duplicate reviews, or resolve one")
    p.add_argument("--resolve", type=int)
    p.add_argument("--decision", choices=["merge", "separate"])
    p.add_argument("--by", default="ops")
    p.set_defaults(fn=cmd_review)
    p = sub.add_parser("crawl", help="crawl a source whose terms have been reviewed")
    p.add_argument("source")
    p.add_argument("--max-pages", type=int, default=500)
    p.set_defaults(fn=cmd_crawl)
    p = sub.add_parser("area-photos", help="fetch freely licensed photos of each area from Wikimedia Commons")
    p.add_argument("--all", action="store_true", help="refresh every locality, not only those without photos")
    p.set_defaults(fn=cmd_area_photos)
    sub.add_parser("hash-images", help="fingerprint licensed partner photos for duplicate detection").set_defaults(fn=cmd_hash_images)
    p = sub.add_parser("osm-import", help="rebuild the village list and coastline from OpenStreetMap")
    p.add_argument("--cache", help="folder with saved Overpass responses: districts.json, places.json, coastline.json")
    p.set_defaults(fn=cmd_osm_import)
    p = sub.add_parser("export-snapshot", help="write the static data files the apps download")
    p.add_argument("--out", default="snapshot")
    p.set_defaults(fn=cmd_export_snapshot)
    p = sub.add_parser("edb-import", help="re-key an EDB approved-projects list (pdftotext -layout output)")
    p.add_argument("text")
    p.add_argument("--scheme", required=True, choices=["PDS", "RES", "IRS"])
    p.set_defaults(fn=cmd_edb_import)
    p = sub.add_parser("build", help="import every source in data/sources.toml and export the snapshot")
    p.add_argument("--sources", default="../data/sources.toml")
    p.add_argument("--out", default="snapshot")
    p.add_argument("--strict", action="store_true", help="exit with an error if any source failed")
    p.set_defaults(fn=cmd_build)
    p = sub.add_parser("serve")
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=8000)
    p.set_defaults(fn=cmd_serve)

    args = parser.parse_args(argv)
    settings = config.load()
    with db.connect(settings.database_url) as conn:
        args.fn(conn, args, settings)


if __name__ == "__main__":
    main()
