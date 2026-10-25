import gzip
import json
from pathlib import Path

from mpf import build, snapshot
from mpf.ingest import pipeline
from mpf.ingest.adapters import PartnerFeedAdapter

ROOT = Path(__file__).resolve().parents[2]
FEEDS = ROOT / "backend" / "seed" / "sample_feeds"


def _read(out: Path):
    meta = json.loads((out / "meta.json").read_text())
    props = json.loads(gzip.decompress((out / "properties.json.gz").read_bytes()))
    locs = json.loads(gzip.decompress((out / "localities.json.gz").read_bytes()))
    return meta, props, locs


def test_snapshot_has_everything_the_apps_need(conn, settings, tmp_path):
    pipeline.run_source(conn, PartnerFeedAdapter(str(FEEDS / "portal_a.json")), settings)
    meta = snapshot.export(conn, tmp_path)
    m, props, locs = _read(tmp_path)
    assert m == meta and m["count"] == len(props) == 13
    assert {"rules", "fx", "assumed_mortgage_rate_pct", "attribution", "version"} <= m.keys()
    p = next(x for x in props if x["locality"] == "Tamarin")
    assert p["price"] == {"minor": 136_000_000, "currency": "USD"} and p["foreign_eligible"]
    assert {"eligibility", "price_check", "history", "sources", "search_text", "price_mur_minor"} <= p.keys()
    assert "costs" not in p  # computed on the phone for the user's deposit and term
    assert len(locs) == 290 and all("area_photos" in l for l in locs)


def test_snapshot_version_only_changes_with_data(conn, settings, tmp_path):
    pipeline.run_source(conn, PartnerFeedAdapter(str(FEEDS / "portal_a.json")), settings)
    first = snapshot.export(conn, tmp_path / "a")["version"]
    assert snapshot.export(conn, tmp_path / "b")["version"] == first
    conn.execute("UPDATE listing SET price_minor = price_minor - 100000, price_mur_minor = price_mur_minor - 100000 WHERE source_ref = 'A-101'")
    assert snapshot.export(conn, tmp_path / "c")["version"] != first


def test_build_reads_sources_file_and_refuses_unreviewed_crawls(conn, settings, tmp_path):
    cfg = tmp_path / "sources.toml"
    cfg.write_text(f'''
[[partner]]
id = "p1"
name = "Partner one"
file = "{FEEDS / 'portal_b.json'}"

[[crawl]]
id = "site"
name = "Some site"
start_urls = ["https://example.mu/sitemap.xml"]
''')
    report = build.run(conn, cfg, tmp_path / "out", settings)
    assert report["partners"]["p1"]["created"] == 4
    assert report["snapshot"]["count"] == 4
    assert any("site" in e and "refused" in e for e in report["errors"])
