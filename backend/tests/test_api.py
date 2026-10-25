import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from mpf import alerts
from mpf.api.app import create_app
from mpf.ingest import pipeline
from mpf.ingest.adapters import PartnerFeedAdapter
from mpf.repo import load_fx

FEEDS = Path(__file__).resolve().parent.parent / "seed" / "sample_feeds"
ISLAND = "57.2,-20.6,57.9,-19.9"


class CapturingOtp:
    def __init__(self):
        self.codes = {}

    def send(self, phone, code):
        self.codes[phone] = code


@pytest.fixture
def otp():
    return CapturingOtp()


@pytest.fixture
def client(conn, settings, otp):
    for feed in ("portal_a.json", "portal_b.json"):
        pipeline.run_source(conn, PartnerFeedAdapter(str(FEEDS / feed)), settings)
    with TestClient(create_app(settings, otp_sender=otp)) as c:
        yield c


def signed_in(client) -> dict:
    token = client.post("/v1/auth/device").json()["token"]
    return {"Authorization": f"Bearer {token}"}


def ids(resp) -> set[str]:
    return {i["locality"] for i in resp.json()["items"]}


def test_search_ignores_accents_and_matches_districts(client):
    assert ids(client.get("/v1/properties", params={"q": "pereybere"})) == {"Pereybère"}
    assert ids(client.get("/v1/properties", params={"q": "mahebourg"})) == {"Mahébourg"}
    assert ids(client.get("/v1/properties", params={"q": "black river"})) == {"Tamarin", "Flic en Flac", "Rivière Noire"}
    # OpenStreetMap spells it with a typographic apostrophe; straight-quote searches still match.
    assert ids(client.get("/v1/properties", params={"q": "pointe d'esny"})) == {"Pointe d’Esny"}


def test_filters_budget_in_euros_and_foreign_buyers(client):
    resp = client.get("/v1/properties", params={"max_price": 150_000, "currency": "EUR", "type": "land"}).json()
    # €150k at 52.5 MUR/EUR is Rs 7,875,000, in cents.
    assert resp["count"] == 4
    assert all(i["price"]["minor"] < 787_500_000 for i in resp["items"])
    foreign = client.get("/v1/properties", params={"foreign_eligible": True}).json()
    assert {i["locality"] for i in foreign["items"]} == {"Grand Baie", "Tamarin", "Côte d’Or", "Bel Ombre"}
    half_arpent = client.get("/v1/properties", params={"min_plot_m2": 2110.4}).json()
    assert {i["locality"] for i in half_arpent["items"]} == {"Bel Ombre", "Moka"}


def test_map_groups_pins_and_stacks_identical_points(client):
    island = client.get("/v1/map", params={"bbox": ISLAND, "zoom": 9}).json()
    kinds = {i["kind"] for i in island["items"]}
    assert island["count"] == 17 and "cluster" in kinds | {"stack"}
    # Without exact coordinates, Grand Baie listings share the village centre: no zoom separates them.
    close = client.get("/v1/map", params={"bbox": "57.55,-20.06,57.62,-20.0", "zoom": 18}).json()
    stacks = [i for i in close["items"] if i["kind"] == "stack"]
    assert stacks and all(len(s["ids"]) == s["count"] for s in stacks)


def test_property_detail(client):
    items = client.get("/v1/properties", params={"q": "ebene"}).json()["items"]
    d = client.get(f"/v1/properties/{items[0]['id']}").json()
    assert d["eligibility"]["status"] == "unknown" and d["eligibility"]["reason"] == "g2_floors_unknown"
    # The only other Centre apartment is in Côte d'Or.
    assert d["price_check"] == {"available": False, "reason": "too_few_comparables", "comparables": 1, "minimum": 5}
    assert d["costs"]["registration_duty_pct"] == "5"
    assert d["coast_distance_m"] > 1000 and d["location_precision"] == "locality"

    land = client.get("/v1/properties", params={"q": "belle mare"}).json()["items"][0]
    check = client.get(f"/v1/properties/{land['id']}").json()["price_check"]
    assert check["available"] is False  # East has one land listing


def test_saved_search_gets_alert_for_new_match(client, conn, settings, tmp_path):
    h = signed_in(client)
    client.post("/v1/searches", headers=h, json={"filters": {"type": "land", "q": "goodlands"}}).raise_for_status()
    feed = {"feed_version": 1, "source": {"id": "t", "name": "t"}, "listings": [
        {"ref": "G1", "type": "land", "title": "Plot in Goodlands", "locality": "Goodlands",
         "price": {"amount": "2500000", "currency": "MUR"}, "plot": {"value": 10, "unit": "perch"}}]}
    path = tmp_path / "feed.json"
    path.write_text(json.dumps(feed))
    report = pipeline.run_source(conn, PartnerFeedAdapter(str(path)), settings)
    assert alerts.record(conn, report, load_fx(conn)) == 1
    assert [a["kind"] for a in client.get("/v1/alerts", headers=h).json()["items"]] == ["new_match"]
    searches = client.get("/v1/searches", headers=h).json()["items"]
    assert searches[0]["matches"] == 1


def test_shared_post_needs_verified_phone_then_publishes_unverified(client, otp):
    h = signed_in(client)
    text = "Terrain résidentiel à vendre Pointe aux Piments, 12 perches. Rs 4.2M à discuter."
    assert client.post("/v1/posts", headers=h, json={"text": text}).status_code == 403

    client.post("/v1/auth/phone/start", headers=h, json={"phone": "+230 5712 3456"}).raise_for_status()
    assert client.post("/v1/auth/phone/verify", headers=h, json={"code": "000000"}).status_code in (204, 422)
    client.post("/v1/auth/phone/verify", headers=h, json={"code": otp.codes["+23057123456"]}).raise_for_status()

    post = client.post("/v1/posts", headers=h, json={"text": text}).json()
    ex = post["extracted"]
    assert (ex["type"], ex["locality"], ex["price_whole"], ex["size_unit"]) == ("land", "Pointe aux Piments", 4_200_000, "perch")
    assert post["locality_match"]["district"] == "Pamplemousses"

    bad = client.post(f"/v1/posts/{post['id']}/publish", headers=h, json={**ex, "locality": "Atlantis", "size_is_plot": True})
    assert bad.status_code == 422
    pub = client.post(f"/v1/posts/{post['id']}/publish", headers=h, json={**ex, "size_is_plot": True}).json()
    d = client.get(f"/v1/properties/{pub['property_id']}").json()
    assert d["verified"] is False and d["sources"][0]["source"] == "Shared by users"
    assert d["plot_m2"] == 506.52  # 12 perches


def test_reports_hide_property_after_threshold(client, settings):
    pid = client.get("/v1/properties", params={"q": "mahebourg"}).json()["items"][0]["id"]
    for _ in range(settings.reports_to_hide):
        r = client.post(f"/v1/properties/{pid}/reports", headers=signed_in(client), json={"reason": "scam"}).json()
    assert r["hidden"] is True
    assert client.get(f"/v1/properties/{pid}").status_code == 404
    assert "Mahébourg" not in ids(client.get("/v1/properties"))


def test_lead_is_recorded_against_its_source(client, conn):
    h = signed_in(client)
    pid = client.get("/v1/properties", params={"q": "tamarin"}).json()["items"][0]["id"]
    listing_id = client.get(f"/v1/properties/{pid}").json()["sources"][0]["listing_id"]
    client.post(f"/v1/properties/{pid}/leads", headers=h, json={"listing_id": listing_id}).raise_for_status()
    assert conn.execute("SELECT source_id FROM lead").fetchone()["source_id"] == "sample-portal-a"


def test_requests_without_token_are_rejected(client):
    assert client.get("/v1/saved").status_code == 401
    assert client.get("/v1/saved", headers={"Authorization": "Bearer nope"}).status_code == 401
