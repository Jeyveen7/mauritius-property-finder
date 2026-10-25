"""HTTP API for the Android app. All amounts are {minor, currency}; all labels are
codes the app translates, so French and English share one API."""

import hashlib
import json
from collections.abc import Iterator
from pathlib import Path
from decimal import Decimal
from typing import Annotated, Literal
from uuid import UUID

import psycopg
from fastapi import Body, Depends, FastAPI, File, Form, Header, HTTPException, Query, Request, UploadFile
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field
from psycopg.types.json import Jsonb

from mpf import alerts, config, db, detail, media, search
from mpf.api import auth
from mpf.domain.money import Money
from mpf.domain.text import norm
from mpf.domain.units import to_m2
from mpf.extract import ExtractedPost, Extractor, ExtractionFailed
from mpf.extract.rules import RulesExtractor
from mpf.ingest import pipeline
from mpf.ingest.adapters import PushedFeedAdapter, RawListing, csv_to_feed
from mpf.repo import Gazetteer, load_fx, load_rules

USER_POSTS_SOURCE = "user-posts"


class DeviceToken(BaseModel):
    token: str
    user_id: str


class PhoneStart(BaseModel):
    phone: str


class GoogleSignIn(BaseModel):
    id_token: str


class PhoneVerify(BaseModel):
    code: str = Field(pattern=r"^\d{6}$")


class MeUpdate(BaseModel):
    locale: Literal["en", "fr"] | None = None
    push_token: str | None = None


class SavedSearchIn(BaseModel):
    filters: search.Filters


class LeadIn(BaseModel):
    listing_id: UUID
    channel: Literal["source_page", "phone", "whatsapp", "email"] = "source_page"


class ReportIn(BaseModel):
    reason: Literal["sold", "wrong_details", "scam", "duplicate", "other"]


class PostIn(BaseModel):
    text: str = Field(min_length=10, max_length=5000)


class PublishIn(BaseModel):
    """The user-confirmed fields of a shared post."""

    type: Literal["land", "apartment", "house"]
    locality: str
    size_value: Decimal = Field(gt=0)
    size_unit: Literal["m2", "perch", "toise", "arpent"]
    size_is_plot: bool
    price_whole: int = Field(gt=0)
    price_currency: Literal["MUR", "EUR", "USD"] = "MUR"
    negotiable: bool = False
    bedrooms: int | None = Field(None, ge=0, le=20)


def create_app(settings: config.Settings | None = None, *, otp_sender: auth.OtpSender | None = None,
               extractor: Extractor | None = None, google_verifier: auth.GoogleVerifier | None = None) -> FastAPI:
    settings = settings or config.load()
    pool = db.pool(settings.database_url)
    otp_sender = otp_sender or auth.LogOtpSender()
    app = FastAPI(title="Mauritius property finder API", version="0.1.0")
    settings.media_dir.mkdir(parents=True, exist_ok=True)
    app.mount("/media", StaticFiles(directory=settings.media_dir), name="media")
    web_dir = Path(__file__).resolve().parents[2] / "web"
    if web_dir.exists():
        # The installable web app for iPhone and desktop, served from the same origin as the API.
        app.mount("/app", StaticFiles(directory=web_dir, html=True), name="web")
    if google_verifier is None and settings.google_client_ids:
        google_verifier = auth.google_verifier(settings.google_client_ids)

    def get_conn() -> Iterator[psycopg.Connection]:
        with pool.connection() as conn:
            yield conn

    Conn = Annotated[psycopg.Connection, Depends(get_conn)]

    def current_user(conn: Conn, authorization: Annotated[str | None, Header()] = None) -> dict:
        if not authorization or not authorization.startswith("Bearer "):
            raise HTTPException(401, "missing_token")
        user = auth.user_for_token(conn, authorization.removeprefix("Bearer "))
        if user is None:
            raise HTTPException(401, "invalid_token")
        return user

    User = Annotated[dict, Depends(current_user)]

    def get_extractor(conn: psycopg.Connection) -> Extractor:
        if extractor is not None:
            return extractor
        if settings.extractor == "claude":
            from mpf.extract.claude import ClaudeExtractor
            return ClaudeExtractor(model=settings.extract_model, effort=settings.extract_effort)
        return RulesExtractor(Gazetteer(conn).names())

    # --- auth ---------------------------------------------------------------

    @app.post("/v1/auth/device", response_model=DeviceToken)
    def device(conn: Conn):
        token, user_id = auth.issue_device_token(conn)
        return DeviceToken(token=token, user_id=user_id)

    @app.post("/v1/auth/phone/start", status_code=204)
    def phone_start(body: PhoneStart, conn: Conn, user: User):
        try:
            auth.start_phone_verification(conn, user["id"], body.phone, otp_sender)
        except ValueError as e:
            raise HTTPException(422, str(e)) from None

    @app.get("/v1/auth/config")
    def auth_config():
        """What the apps need to offer Google sign-in (client IDs are public)."""
        return {"google_client_ids": list(settings.google_client_ids)}

    @app.post("/v1/auth/google", status_code=204)
    def google_sign_in(body: GoogleSignIn, conn: Conn, user: User):
        if google_verifier is None:
            raise HTTPException(503, "google_sign_in_not_configured")
        try:
            auth.verify_google(conn, user["id"], body.id_token, google_verifier)
        except ValueError as e:
            raise HTTPException(401, str(e) or "invalid_google_token") from None

    @app.post("/v1/auth/phone/verify", status_code=204)
    def phone_verify(body: PhoneVerify, conn: Conn, user: User):
        if not auth.verify_phone(conn, user["id"], body.code):
            raise HTTPException(422, "invalid_code")

    @app.patch("/v1/me", status_code=204)
    def update_me(body: MeUpdate, conn: Conn, user: User):
        if body.locale:
            conn.execute("UPDATE app_user SET locale = %s WHERE id = %s", (body.locale, user["id"]))
        if body.push_token:
            conn.execute("UPDATE app_user SET push_token = %s WHERE id = %s", (body.push_token, user["id"]))

    # --- reference data -----------------------------------------------------

    @app.get("/v1/localities")
    def localities(conn: Conn, q: Annotated[str, Query(min_length=1, max_length=60)]):
        nq = norm(q)
        rows = conn.execute(
            """SELECT name, district, region, lat, lon FROM locality
               WHERE search_text LIKE %(like)s OR word_similarity(%(q)s, search_text) >= 0.5
               ORDER BY (search_text LIKE %(prefix)s) DESC, word_similarity(%(q)s, search_text) DESC, name
               LIMIT 10""",
            {"q": nq, "like": f"%{nq}%", "prefix": f"{nq}%"},
        ).fetchall()
        return {"items": rows}

    @app.get("/v1/rules")
    def rules(conn: Conn):
        r = load_rules(conn)
        fx = load_fx(conn)
        return {"label": r.label, "registration_duty_pct": str(r.registration_duty_pct),
                "residency_min_usd": str(r.residency_min_usd), "g2_min_price_mur": str(r.g2_min_price_mur),
                "fx": {"day": fx.day.isoformat(), "mur_per_unit": {k: str(v) for k, v in fx.mur_per_unit.items()}}}

    # --- search -------------------------------------------------------------

    def _card_json(c: dict) -> dict:
        return {
            "id": str(c["id"]), "type": c["type"], "title": c["title"],
            "locality": c["locality"], "district": c["district"], "region": c["region"],
            "lat": c["lat"], "lon": c["lon"],
            "price": {"minor": c["price_minor"], "currency": c["currency"]},
            "plot_m2": c["plot_m2"], "floor_m2": c["floor_m2"], "bedrooms": c["bedrooms"],
            "sources": c["n_sources"], "foreign_eligible": c["foreign_eligible"],
            "unverified": c["unverified"], "price_dropped": c["price_dropped"],
            "first_seen_at": c["first_seen_at"].isoformat(), "last_checked_at": c["last_checked_at"].isoformat(),
            "thumb": detail.media_url(c["thumb"], settings.public_base_url),
        }

    def _bbox(bbox: str | None) -> search.BBox | None:
        if not bbox:
            return None
        try:
            min_lon, min_lat, max_lon, max_lat = (float(x) for x in bbox.split(","))
        except ValueError:
            raise HTTPException(422, "bbox must be min_lon,min_lat,max_lon,max_lat") from None
        return search.BBox(min_lat=min_lat, min_lon=min_lon, max_lat=max_lat, max_lon=max_lon)

    @app.get("/v1/map")
    def map_view(conn: Conn, filters: Annotated[search.Filters, Query()],
                 bbox: Annotated[str, Query(description="min_lon,min_lat,max_lon,max_lat")],
                 zoom: Annotated[int, Query(ge=5, le=20)]):
        fx = load_fx(conn)
        box = _bbox(bbox)
        return {"count": search.count(conn, filters, fx, box),
                "items": search.clusters(conn, filters, fx, box, zoom)}

    @app.get("/v1/properties")
    def list_properties(conn: Conn, filters: Annotated[search.Filters, Query()],
                        bbox: str | None = None, ids: Annotated[list[UUID] | None, Query()] = None,
                        sort: Literal["new", "price", "value"] = "new",
                        limit: Annotated[int, Query(ge=1, le=100)] = 30,
                        offset: Annotated[int, Query(ge=0)] = 0):
        fx = load_fx(conn)
        box = _bbox(bbox)
        rows = search.cards(conn, filters, fx, bbox=box, ids=ids, order=sort, limit=limit, offset=offset)
        return {"count": search.count(conn, filters, fx, box) if ids is None else len(rows),
                "items": [_card_json(c) for c in rows]}

    @app.get("/v1/properties/{property_id}")
    def get_property(property_id: UUID, conn: Conn,
                     deposit_pct: Annotated[int, Query(ge=0, le=90)] = 20,
                     term_years: Annotated[int, Query(ge=1, le=35)] = 20):
        d = detail.property_detail(conn, property_id, rules=load_rules(conn), fx=load_fx(conn),
                                   deposit_pct=deposit_pct, term_years=term_years,
                                   public_base_url=settings.public_base_url)
        if d is None:
            raise HTTPException(404, "not_found")
        return d

    # --- saved items, searches and alerts -----------------------------------

    @app.put("/v1/saved/{property_id}", status_code=204)
    def save_property(property_id: UUID, conn: Conn, user: User):
        try:
            conn.execute("INSERT INTO saved_property (user_id, property_id) VALUES (%s, %s) ON CONFLICT DO NOTHING",
                         (user["id"], property_id))
        except psycopg.errors.ForeignKeyViolation:
            raise HTTPException(404, "not_found") from None

    @app.delete("/v1/saved/{property_id}", status_code=204)
    def unsave_property(property_id: UUID, conn: Conn, user: User):
        conn.execute("DELETE FROM saved_property WHERE user_id = %s AND property_id = %s", (user["id"], property_id))

    @app.get("/v1/saved")
    def saved(conn: Conn, user: User):
        ids = [r["property_id"] for r in conn.execute(
            "SELECT property_id FROM saved_property WHERE user_id = %s ORDER BY created_at DESC", (user["id"],))]
        rows = search.cards(conn, search.Filters(), load_fx(conn), ids=ids, limit=100) if ids else []
        gone = sorted(str(i) for i in set(ids) - {r["id"] for r in rows})
        return {"items": [_card_json(c) for c in rows], "no_longer_listed": gone}

    @app.post("/v1/searches", status_code=201)
    def create_search(body: SavedSearchIn, conn: Conn, user: User):
        row = conn.execute(
            "INSERT INTO saved_search (user_id, filters) VALUES (%s, %s) RETURNING id",
            (user["id"], Jsonb(body.filters.model_dump(mode="json"))),
        ).fetchone()
        return {"id": str(row["id"])}

    @app.get("/v1/searches")
    def list_searches(conn: Conn, user: User):
        fx = load_fx(conn)
        rows = conn.execute("SELECT * FROM saved_search WHERE user_id = %s ORDER BY created_at", (user["id"],)).fetchall()
        return {"items": [{"id": str(r["id"]), "filters": r["filters"],
                           "matches": search.count(conn, search.Filters.model_validate(r["filters"]), fx)}
                          for r in rows]}

    @app.delete("/v1/searches/{search_id}", status_code=204)
    def delete_search(search_id: UUID, conn: Conn, user: User):
        conn.execute("DELETE FROM saved_search WHERE id = %s AND user_id = %s", (search_id, user["id"]))

    @app.get("/v1/alerts")
    def list_alerts(conn: Conn, user: User):
        rows = conn.execute(
            """SELECT id, kind, saved_search_id, property_id, created_at FROM alert
               WHERE user_id = %s ORDER BY created_at DESC LIMIT 100""",
            (user["id"],),
        ).fetchall()
        return {"items": [{**r, "saved_search_id": str(r["saved_search_id"]) if r["saved_search_id"] else None,
                           "property_id": str(r["property_id"]), "created_at": r["created_at"].isoformat()}
                          for r in rows]}

    # --- leads and reports --------------------------------------------------

    @app.post("/v1/properties/{property_id}/leads", status_code=201)
    def create_lead(property_id: UUID, body: LeadIn, conn: Conn, user: User):
        listing = conn.execute(
            "SELECT * FROM listing WHERE id = %s AND property_id = %s AND status = 'active'",
            (body.listing_id, property_id),
        ).fetchone()
        if listing is None:
            raise HTTPException(404, "not_found")
        conn.execute(
            "INSERT INTO lead (user_id, property_id, listing_id, source_id, channel) VALUES (%s, %s, %s, %s, %s)",
            (user["id"], property_id, listing["id"], listing["source_id"], body.channel),
        )
        return {"open_url": listing["contact_url"] or listing["url"]}

    @app.post("/v1/properties/{property_id}/reports", status_code=201)
    def report(property_id: UUID, body: ReportIn, conn: Conn, user: User):
        with conn.transaction():
            try:
                conn.execute(
                    "INSERT INTO report (user_id, property_id, reason) VALUES (%s, %s, %s) ON CONFLICT DO NOTHING",
                    (user["id"], property_id, body.reason),
                )
            except psycopg.errors.ForeignKeyViolation:
                raise HTTPException(404, "not_found") from None
            n = conn.execute("SELECT count(*) AS n FROM report WHERE property_id = %s", (property_id,)).fetchone()["n"]
            hidden = n >= settings.reports_to_hide
            if hidden:
                # Hidden until someone reviews it; alerts and search skip hidden properties.
                conn.execute("UPDATE property SET status = 'hidden', updated_at = now() WHERE id = %s AND status = 'active'",
                             (property_id,))
        return {"reports": n, "hidden": hidden}

    # --- shared posts -------------------------------------------------------

    @app.post("/v1/posts", status_code=201)
    def create_post(body: PostIn, conn: Conn, user: User):
        if not user["verified_at"]:
            raise HTTPException(403, "verification_required")
        today = conn.execute(
            "SELECT count(*) AS n FROM user_post WHERE user_id = %s AND created_at > now() - interval '1 day'",
            (user["id"],),
        ).fetchone()["n"]
        if today >= settings.max_posts_per_day:
            raise HTTPException(429, "daily_post_limit")
        ext = get_extractor(conn)
        try:
            extracted: ExtractedPost = ext.extract(body.text)
        except ExtractionFailed:
            # Never block the user on the AI service: fall back to the pattern reader.
            ext = RulesExtractor(Gazetteer(conn).names())
            extracted = ext.extract(body.text)
        place = Gazetteer(conn).resolve(extracted.locality)
        row = conn.execute(
            "INSERT INTO user_post (user_id, raw_text, extracted, extractor) VALUES (%s, %s, %s, %s) RETURNING id",
            (user["id"], body.text, Jsonb(extracted.model_dump()), ext.name),
        ).fetchone()
        return {"id": str(row["id"]), "extracted": extracted.model_dump(),
                "locality_match": {"name": place["name"], "district": place["district"]} if place else None}

    @app.post("/v1/posts/{post_id}/publish", status_code=201)
    def publish_post(post_id: UUID, body: PublishIn, conn: Conn, user: User):
        post = conn.execute("SELECT * FROM user_post WHERE id = %s AND user_id = %s", (post_id, user["id"])).fetchone()
        if post is None:
            raise HTTPException(404, "not_found")
        size_m2 = to_m2(body.size_value, body.size_unit)
        raw = RawListing(
            ref=str(post_id), type=body.type,
            title={"land": "Plot", "apartment": "Apartment", "house": "House"}[body.type] + " from a shared post",
            locality=body.locality, price=Money.of(body.price_whole, body.price_currency),
            negotiable=body.negotiable, bedrooms=body.bedrooms,
            plot_m2=size_m2 if body.size_is_plot else None,
            floor_m2=None if body.size_is_plot else size_m2,
            verified=False,
        )
        ctx = pipeline.context(conn)
        run = pipeline.RunReport(source_id=USER_POSTS_SOURCE)
        try:
            with conn.transaction():
                listing = pipeline.upsert(conn, ctx, USER_POSTS_SOURCE, raw, run)
                conn.execute("UPDATE user_post SET status = 'published', listing_id = %s WHERE id = %s",
                             (listing["id"], post_id))
                # The poster's photos now belong to the listing; their hashes help merge duplicates.
                hashes = [r["phash"] for r in conn.execute(
                    "UPDATE photo SET listing_id = %s WHERE user_post_id = %s RETURNING phash",
                    (listing["id"], post_id)).fetchall() if r["phash"]]
                if hashes:
                    conn.execute("UPDATE listing SET image_hashes = %s WHERE id = %s", (hashes, listing["id"]))
        except pipeline.Rejected as e:
            raise HTTPException(422, str(e)) from None
        property_id = conn.execute("SELECT property_id FROM listing WHERE id = %s", (listing["id"],)).fetchone()["property_id"]
        return {"property_id": str(property_id), "merged_with_existing": run.merged > 0}

    @app.post("/v1/posts/{post_id}/photos", status_code=201)
    def upload_photos(post_id: UUID, conn: Conn, user: User,
                      files: Annotated[list[UploadFile], File()],
                      rights_confirmed: Annotated[bool, Form(description="I took these photos or own the rights to them")]):
        if not rights_confirmed:
            raise HTTPException(422, "rights_not_confirmed")
        post = conn.execute("SELECT id FROM user_post WHERE id = %s AND user_id = %s", (post_id, user["id"])).fetchone()
        if post is None:
            raise HTTPException(404, "not_found")
        have = conn.execute("SELECT count(*) AS n FROM photo WHERE user_post_id = %s", (post_id,)).fetchone()["n"]
        if have + len(files) > settings.max_photos_per_post:
            raise HTTPException(422, "too_many_photos")
        stored = []
        for f in files:
            try:
                info = media.store_upload(f.file.read(media.MAX_UPLOAD_BYTES + 1), settings.media_dir)
            except media.BadImage as e:
                raise HTTPException(422, str(e)) from None
            row = conn.execute(
                """INSERT INTO photo (kind, user_post_id, uploader_id, storage_path, width, height, phash, rights_confirmed_at)
                   VALUES ('user_upload', %s, %s, %s, %s, %s, %s, now()) RETURNING id""",
                (post_id, user["id"], info["storage_path"], info["width"], info["height"], info["phash"]),
            ).fetchone()
            stored.append({"id": str(row["id"]), "url": detail.media_url(info["storage_path"], settings.public_base_url)})
        return {"items": stored}

    # --- partners -------------------------------------------------------------

    def partner_source(conn: psycopg.Connection, authorization: str | None) -> dict:
        if not authorization or not authorization.startswith("Bearer "):
            raise HTTPException(401, "missing_partner_key")
        key = hashlib.sha256(authorization.removeprefix("Bearer ").encode()).hexdigest()
        source = conn.execute("SELECT * FROM source WHERE api_key_sha256 = %s AND active", (key,)).fetchone()
        if source is None:
            raise HTTPException(401, "invalid_partner_key")
        return source

    @app.post("/v1/partner/feed")
    async def partner_feed(request: Request, conn: Conn, authorization: Annotated[str | None, Header()] = None):
        """A partner pushes its full inventory: feed JSON (docs/partner-feed.md) or CSV."""
        source = partner_source(conn, authorization)
        raw = (await request.body()).decode("utf-8-sig")
        try:
            document = csv_to_feed(raw) if "csv" in request.headers.get("content-type", "") else json.loads(raw)
            adapter = PushedFeedAdapter(source["id"], source["name"], document, images_licensed=source["images_licensed"])
            run = pipeline.run_source(conn, adapter, settings)
        except (ValueError, KeyError) as e:
            raise HTTPException(422, f"invalid_feed: {e}") from None
        alerts.record(conn, run, load_fx(conn))
        conn.execute("UPDATE source SET last_run_at = now(), last_run_report = %s WHERE id = %s",
                     (Jsonb(run.summary()), source["id"]))
        return run.summary()

    from mpf.api.admin import admin_router
    app.include_router(admin_router(settings, pool))

    return app
