"""Photos, partner feeds, Google sign-in, crawling and admin: all without network."""

import hashlib
import io
import json
from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient
from PIL import Image

from mpf import commons, media
from mpf.api.app import create_app
from mpf.ingest import pipeline
from mpf.ingest.adapters import PartnerFeedAdapter, csv_to_feed
from mpf.ingest.crawl import JSONLD, CrawlRefused, StructuredDataCrawler, listing_from_jsonld

FEEDS = Path(__file__).resolve().parent.parent / "seed" / "sample_feeds"


def jpeg_with_gps() -> bytes:
    img = Image.new("RGB", (64, 48), (200, 120, 40))
    exif = Image.Exif()
    exif[0x8825] = {2: (20.0, 1.0, 0.0)}  # GPSInfo: a location the phone recorded
    buf = io.BytesIO()
    img.save(buf, "JPEG", exif=exif)
    return buf.getvalue()


def test_upload_is_reencoded_without_location_metadata(tmp_path):
    raw = jpeg_with_gps()
    assert Image.open(io.BytesIO(raw)).getexif().get(0x8825)
    info = media.store_upload(raw, tmp_path)
    saved = Image.open(tmp_path / info["storage_path"])
    assert not saved.getexif()
    assert len(info["phash"]) == 16
    with pytest.raises(media.BadImage):
        media.store_upload(b"not an image", tmp_path)


def test_commons_keeps_only_reusable_licences():
    pages = {"query": {"pages": {
        "1": {"index": 1, "title": "File:Beach.jpg", "imageinfo": [{"mime": "image/jpeg", "thumburl": "https://u/1",
              "descriptionurl": "https://c/1", "extmetadata": {"LicenseShortName": {"value": "CC BY-SA 4.0"},
                                                              "Artist": {"value": "<a href='x'>Ana</a>"}}}]},
        "2": {"index": 2, "title": "File:NC.jpg", "imageinfo": [{"mime": "image/jpeg", "thumburl": "https://u/2",
              "descriptionurl": "https://c/2", "extmetadata": {"LicenseShortName": {"value": "CC BY-NC 2.0"}}}]},
        "3": {"index": 3, "title": "File:Map.svg", "imageinfo": [{"mime": "image/svg+xml", "thumburl": "https://u/3",
              "descriptionurl": "https://c/3", "extmetadata": {"LicenseShortName": {"value": "CC0"}}}]},
    }}}
    client = httpx.Client(transport=httpx.MockTransport(lambda r: httpx.Response(200, json=pages)))
    out = commons.fetch(client, -20.0, 57.6)
    assert [(p["title"], p["author"], p["license"]) for p in out] == [("File:Beach.jpg", "Ana", "CC BY-SA 4.0")]


def test_csv_inventory_becomes_a_feed():
    doc = csv_to_feed("ref,type,title,locality,price,currency,plot_value,plot_unit,floor_m2,bedrooms,scheme,floors_above_ground,url,images\n"
                      "X1,land,Plot,Goodlands,\"2,500,000\",MUR,10,perch,,,,,https://a.mu/x1,https://a.mu/1.jpg|https://a.mu/2.jpg\n")
    item = doc["listings"][0]
    assert item["price"] == {"amount": "2500000", "currency": "MUR"}
    assert item["plot"] == {"value": "10", "unit": "perch"} and len(item["images"]) == 2


JSONLD_PAGE = """<html><head><script type="application/ld+json">{"@context":"https://schema.org",
"@type":"RealEstateListing","name":"Plot in Goodlands","offers":{"@type":"Offer","price":"2500000","priceCurrency":"MUR"},
"itemOffered":{"@type":"Place","address":{"addressLocality":"Goodlands"},"lotSize":{"value":422}},
"description":"long text we never store"}</script></head></html>"""


def test_jsonld_maps_to_facts_only():
    blocks = [json.loads(b) for b in JSONLD.findall(JSONLD_PAGE)]
    l = listing_from_jsonld(blocks, "https://site.mu/l/1")
    assert (l.type, l.locality, l.price.minor, l.plot_m2, l.url) == ("land", "Goodlands", 250_000_000, 422, "https://site.mu/l/1")
    assert l.image_urls == ()


def _site(robots: str, page: str = JSONLD_PAGE) -> httpx.Client:
    def handler(req: httpx.Request) -> httpx.Response:
        if req.url.path == "/robots.txt":
            return httpx.Response(200, text=robots)
        return httpx.Response(200, text=page)
    return httpx.Client(transport=httpx.MockTransport(handler))


def test_crawler_safeguards(monkeypatch):
    monkeypatch.setattr("mpf.ingest.crawl.time.sleep", lambda s: None)
    args = dict(source_id="s", source_name="S", start_urls=["https://site.mu/l/1"], contact="test")
    with pytest.raises(CrawlRefused, match="terms"):
        list(StructuredDataCrawler(**args, terms_reviewed=False, client=_site("")).fetch())
    with pytest.raises(CrawlRefused, match="Content-Signal"):
        list(StructuredDataCrawler(**args, terms_reviewed=True,
                                   client=_site("User-agent: *\nContent-Signal: search=no\n")).fetch())
    blocked = StructuredDataCrawler(**args, terms_reviewed=True, client=_site("User-agent: *\nDisallow: /l/\n"))
    assert list(blocked.fetch()) == []
    noindex = StructuredDataCrawler(**args, terms_reviewed=True,
                                    client=_site("", '<meta name="robots" content="noindex">' + JSONLD_PAGE))
    assert list(noindex.fetch()) == []
    ok = StructuredDataCrawler(**args, terms_reviewed=True, client=_site("User-agent: *\nAllow: /\n"))
    assert [l.title for l in ok.fetch()] == ["Plot in Goodlands"]


@pytest.fixture
def app_client(conn, settings, tmp_path):
    from dataclasses import replace
    s = replace(settings, media_dir=tmp_path, admin_token="admin-secret", google_client_ids=("web-client",))

    def fake_google(token: str) -> dict:
        if token != "good":
            raise ValueError("invalid_google_token")
        return {"sub": "g-123", "email": "a@example.com", "email_verified": True, "aud": "web-client"}

    pipeline.run_source(conn, PartnerFeedAdapter(str(FEEDS / "portal_a.json")), s)
    with TestClient(create_app(s, google_verifier=fake_google)) as c:
        yield c


def device(client) -> dict:
    return {"Authorization": "Bearer " + client.post("/v1/auth/device").json()["token"]}


def test_google_sign_in_unlocks_posting_and_photos(app_client):
    h = device(app_client)
    assert app_client.post("/v1/posts", headers=h, json={"text": "Terrain Goodlands 10 perches Rs 2.5M"}).status_code == 403
    assert app_client.post("/v1/auth/google", headers=h, json={"id_token": "bad"}).status_code == 401
    app_client.post("/v1/auth/google", headers=h, json={"id_token": "good"}).raise_for_status()

    post = app_client.post("/v1/posts", headers=h, json={"text": "Terrain Goodlands 10 perches Rs 2.5M"}).json()
    files = [("files", ("p.jpg", jpeg_with_gps(), "image/jpeg"))]
    assert app_client.post(f"/v1/posts/{post['id']}/photos", headers=h, files=files,
                           data={"rights_confirmed": "false"}).status_code == 422
    up = app_client.post(f"/v1/posts/{post['id']}/photos", headers=h, files=files, data={"rights_confirmed": "true"})
    assert up.status_code == 201
    photo_url = up.json()["items"][0]["url"]
    assert app_client.get(photo_url.split("8000", 1)[-1]).status_code == 200

    ex = post["extracted"]
    pub = app_client.post(f"/v1/posts/{post['id']}/publish", headers=h, json={**ex, "size_is_plot": True}).json()
    d = app_client.get(f"/v1/properties/{pub['property_id']}").json()
    assert [p["kind"] for p in d["photos"]] == ["user_upload"]
    card = app_client.get("/v1/properties", params={"ids": [pub["property_id"]]}).json()["items"][0]
    assert card["thumb"] == photo_url


def test_partner_pushes_csv_with_its_key_only(app_client, conn):
    key = "mpk_test"
    conn.execute("INSERT INTO source (id, name, kind, images_licensed, api_key_sha256) VALUES ('agency-x', 'Agency X', 'partner_feed', true, %s)",
                 (hashlib.sha256(key.encode()).hexdigest(),))
    csv = ("ref,type,title,locality,price,currency,plot_value,plot_unit,floor_m2,bedrooms,scheme,floors_above_ground,url,images\n"
           "X1,land,Plot by the river,Goodlands,2500000,MUR,10,perch,,,,,https://a.mu/x1,https://a.mu/1.jpg\n")
    assert app_client.post("/v1/partner/feed", content=csv, headers={"Content-Type": "text/csv"}).status_code == 401
    r = app_client.post("/v1/partner/feed", content=csv, headers={"Content-Type": "text/csv", "Authorization": f"Bearer {key}"})
    assert r.status_code == 200 and r.json()["created"] == 1
    row = conn.execute("SELECT source_id, image_urls FROM listing WHERE source_ref = 'X1'").fetchone()
    assert (row["source_id"], row["image_urls"]) == ("agency-x", ["https://a.mu/1.jpg"])


def test_admin_needs_token_and_can_resolve_reviews(app_client, conn, settings):
    pipeline.run_source(conn, PartnerFeedAdapter(str(FEEDS / "portal_b.json")), settings)
    assert app_client.get("/admin/reviews", follow_redirects=False).status_code == 303
    assert "not right" in app_client.post("/admin/login", data={"token": "wrong"}).text
    app_client.post("/admin/login", data={"token": "admin-secret"}, follow_redirects=False)
    page = app_client.get("/admin/reviews")
    assert page.status_code == 200 and "Same place: merge" in page.text
    item = conn.execute("SELECT id FROM review_item WHERE status = 'open' ORDER BY id LIMIT 1").fetchone()["id"]
    app_client.post(f"/admin/reviews/{item}", data={"decision": "merge"}, follow_redirects=False)
    assert conn.execute("SELECT status FROM review_item WHERE id = %s", (item,)).fetchone()["status"] == "merged"
    assert app_client.post(f"/admin/reviews/{item}", data={"decision": "merge"},
                           headers={"Origin": "https://evil.example"}).status_code == 403
    created = app_client.post("/admin/sources", data={"id": "agency-y", "name": "Agency Y", "kind": "partner_feed",
                                                      "partner_terms": "true"})
    assert "mpk_" in created.text
