import gzip
import json
from pathlib import Path

from mpf import build, official, snapshot
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


SAMPLE_LIST = """
                                     List of Approved PDS Projects

-1   Maritime Realty Co Ltd
     Location                     Grand Baie
     Project                      Development of 13 villas, 12 apartments, a gym, a spa, a coffee shop with
                                  a kid's corner
     Contact person               Mr . Somebody
     Address                      23, Club Road Vacoas
     E-mail                       someone@example.mu
     Tel No.                      +2306984136
     Fax No.
     Web page address             example-villas.com
     Status                       Letter of Approval

-2   Bain Boeuf Homes Ltd
     Location                     Bain Boeuf
     Project                      8 villas
     Contact person               Ms Other
     Address                      Royal Road
                                  Pereybere
     E-mail                       other@example.mu
     Tel No.
     Fax No.
     Web page address
     Status                       PDS Certificate/ completed
"""


def test_official_lists_keep_facts_and_drop_contacts():
    rows = official.parse(SAMPLE_LIST, "PDS")
    assert [r["developer"] for r in rows] == ["Maritime Realty Co Ltd", "Bain Boeuf Homes Ltd"]
    a, b = rows
    assert a["project"].endswith("a coffee shop with a kid's corner")
    assert a["website"] == "https://example-villas.com" and a["status"] == "Letter of approval"
    assert b["status"] == "PDS certificate (completed)" and b["website"] == ""
    text = json.dumps(rows)
    assert "@" not in text and "Somebody" not in text and "6984136" not in text and "Royal Road" not in text


def test_snapshot_publishes_official_projects_placed_on_villages(conn, tmp_path):
    meta = snapshot.export(conn, tmp_path)
    projects = json.loads(gzip.decompress((tmp_path / "projects.json.gz").read_bytes()))
    assert meta["projects"] == len(projects) > 150
    assert all(p["lat"] and p["locality"] and p["source_url"].startswith("https://edbmauritius.org/") for p in projects)
    assert official.ATTRIBUTION in meta["attribution"]


def test_build_retires_sources_removed_from_the_file(conn, settings, tmp_path):
    cfg = tmp_path / "sources.toml"
    cfg.write_text(f'[[partner]]\nid = "p1"\nname = "P1"\nfile = "{FEEDS / "portal_b.json"}"\n')
    assert build.run(conn, cfg, tmp_path / "a", settings)["snapshot"]["count"] == 4
    cfg.write_text("")
    report = build.run(conn, cfg, tmp_path / "b", settings)
    assert report["retired"] == {"p1": 4} and report["snapshot"]["count"] == 0
