"""Admin pages (server-rendered, no JavaScript): duplicate reviews, reported
properties, sources and their terms review, partner keys and feed uploads.

Sign-in is the MPF_ADMIN_TOKEN, kept in an HttpOnly, SameSite=Strict cookie; every
form POST also checks the Origin header. Admin is off when no token is set.
"""

import hashlib
import json
import re
import secrets
from pathlib import Path
from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, Form, HTTPException, Request, UploadFile
from fastapi.responses import HTMLResponse, RedirectResponse
from fastapi.templating import Jinja2Templates
from psycopg.types.json import Jsonb
from psycopg_pool import ConnectionPool

from mpf import alerts, config, review
from mpf.ingest import pipeline
from mpf.ingest.adapters import PushedFeedAdapter, csv_to_feed
from mpf.repo import load_fx

COOKIE = "mpf_admin"
templates = Jinja2Templates(directory=Path(__file__).resolve().parent / "templates")


def admin_router(settings: config.Settings, pool: ConnectionPool) -> APIRouter:
    router = APIRouter(prefix="/admin", include_in_schema=False)

    def signed_in(request: Request) -> str:
        token = request.cookies.get(COOKIE, "")
        if not settings.admin_token or not secrets.compare_digest(token, settings.admin_token):
            raise HTTPException(303, headers={"Location": "/admin/login"})
        if request.method == "POST":
            origin = request.headers.get("origin")
            if origin and origin.rstrip("/") != f"{request.url.scheme}://{request.url.netloc}":
                raise HTTPException(403, "cross-site form post")
        return "admin"

    Admin = Annotated[str, Depends(signed_in)]

    def render(request: Request, name: str, **ctx) -> HTMLResponse:
        return templates.TemplateResponse(request, name, ctx)

    @router.get("/login", response_class=HTMLResponse)
    def login_page(request: Request):
        return render(request, "login.html", enabled=bool(settings.admin_token))

    @router.post("/login")
    def login(request: Request, token: Annotated[str, Form()]):
        if not settings.admin_token or not secrets.compare_digest(token, settings.admin_token):
            return render(request, "login.html", enabled=bool(settings.admin_token), error=True)
        resp = RedirectResponse("/admin", status_code=303)
        resp.set_cookie(COOKIE, token, httponly=True, samesite="strict", secure=request.url.scheme == "https")
        return resp

    @router.post("/logout")
    def logout(_: Admin):
        resp = RedirectResponse("/admin/login", status_code=303)
        resp.delete_cookie(COOKIE)
        return resp

    @router.get("", response_class=HTMLResponse)
    def dashboard(request: Request, _: Admin):
        with pool.connection() as conn:
            counts = conn.execute(
                """SELECT (SELECT count(*) FROM review_item WHERE status = 'open') AS reviews,
                          (SELECT count(*) FROM property WHERE status = 'hidden') AS hidden,
                          (SELECT count(*) FROM property WHERE status = 'active') AS active,
                          (SELECT count(*) FROM source) AS sources,
                          (SELECT count(*) FROM listing WHERE NOT verified AND status = 'active') AS unverified"""
            ).fetchone()
        return render(request, "dashboard.html", counts=counts)

    @router.get("/reviews", response_class=HTMLResponse)
    def reviews(request: Request, _: Admin):
        with pool.connection() as conn:
            return render(request, "reviews.html", items=review.open_items(conn))

    @router.post("/reviews/{item_id}")
    def decide(item_id: int, _: Admin, decision: Annotated[str, Form()]):
        with pool.connection() as conn:
            try:
                review.resolve(conn, item_id, decision, "admin")
            except (LookupError, ValueError) as e:
                raise HTTPException(422, str(e)) from None
        return RedirectResponse("/admin/reviews", status_code=303)

    @router.get("/reports", response_class=HTMLResponse)
    def reports(request: Request, _: Admin):
        with pool.connection() as conn:
            return render(request, "reports.html", items=review.hidden_properties(conn))

    @router.post("/reports/{property_id}")
    def settle(property_id: UUID, _: Admin, action: Annotated[str, Form()]):
        with pool.connection() as conn:
            review.settle_reports(conn, property_id, action)
        return RedirectResponse("/admin/reports", status_code=303)

    @router.get("/sources", response_class=HTMLResponse)
    def sources(request: Request, _: Admin):
        with pool.connection() as conn:
            rows = conn.execute(
                """SELECT s.*, (SELECT count(*) FROM listing l WHERE l.source_id = s.id AND l.status = 'active') AS listings
                   FROM source s ORDER BY s.kind, s.name"""
            ).fetchall()
        return render(request, "sources.html", sources=rows, new_key=None)

    @router.post("/sources")
    def create_source(request: Request, _: Admin, id: Annotated[str, Form(pattern=r"^[a-z0-9-]{3,40}$")],
                      name: Annotated[str, Form()], kind: Annotated[str, Form(pattern="^(partner_feed|crawl)$")],
                      contact_email: Annotated[str, Form()] = "", images_licensed: Annotated[bool, Form()] = False,
                      partner_terms: Annotated[bool, Form()] = False):
        key = None
        with pool.connection() as conn:
            if kind == "partner_feed":
                if not partner_terms:
                    raise HTTPException(422, "the partner must accept the partner terms first")
                key = "mpk_" + secrets.token_urlsafe(32)
            conn.execute(
                """INSERT INTO source (id, name, kind, images_licensed, contact_email, api_key_sha256,
                                       partner_terms_accepted_at)
                   VALUES (%s, %s, %s, %s, %s, %s, CASE WHEN %s THEN now() END)""",
                (id, name, kind, images_licensed, contact_email or None,
                 hashlib.sha256(key.encode()).hexdigest() if key else None, partner_terms),
            )
            rows = conn.execute("SELECT s.*, 0 AS listings FROM source s ORDER BY s.kind, s.name").fetchall()
        # The key is shown once and only its hash is stored.
        return render(request, "sources.html", sources=rows, new_key={"source": id, "key": key} if key else None)

    @router.post("/sources/{source_id}/terms")
    def review_terms(source_id: str, _: Admin, reviewer: Annotated[str, Form(min_length=2)],
                     note: Annotated[str, Form(min_length=10)],
                     start_urls: Annotated[str, Form()] = "", allowed: Annotated[bool, Form()] = False):
        urls = [u.strip() for u in re.split(r"\s+", start_urls) if u.strip().startswith("https://")]
        with pool.connection() as conn:
            conn.execute(
                """UPDATE source SET terms_reviewed_at = CASE WHEN %s THEN now() END, terms_reviewed_by = %s,
                     terms_note = %s, crawl_start_urls = %s WHERE id = %s""",
                (allowed, reviewer, note, urls, source_id),
            )
        return RedirectResponse("/admin/sources", status_code=303)

    @router.post("/sources/{source_id}/active")
    def toggle(source_id: str, _: Admin):
        with pool.connection() as conn:
            conn.execute("UPDATE source SET active = NOT active WHERE id = %s", (source_id,))
        return RedirectResponse("/admin/sources", status_code=303)

    @router.post("/sources/{source_id}/upload")
    async def upload_feed(request: Request, source_id: str, _: Admin, file: UploadFile):
        """Upload an agency's spreadsheet (CSV) or feed JSON on its behalf."""
        text = (await file.read()).decode("utf-8-sig")
        with pool.connection() as conn:
            src = conn.execute("SELECT * FROM source WHERE id = %s", (source_id,)).fetchone()
            if src is None:
                raise HTTPException(404, "no such source")
            document = csv_to_feed(text) if file.filename.lower().endswith(".csv") else json.loads(text)
            run = pipeline.run_source(conn, PushedFeedAdapter(src["id"], src["name"], document,
                                                              images_licensed=src["images_licensed"]), settings)
            alerts.record(conn, run, load_fx(conn))
            conn.execute("UPDATE source SET last_run_at = now(), last_run_report = %s WHERE id = %s",
                         (Jsonb(run.summary()), source_id))
        return RedirectResponse("/admin/sources", status_code=303)

    return router
