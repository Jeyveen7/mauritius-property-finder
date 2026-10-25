"""Photos of each area from Wikimedia Commons: free to reuse under their licence when
the author and licence are credited. Shown as "photos of the area", never as the
property. Only licences that allow commercial reuse are kept."""

import html
import re
import time

import httpx
import psycopg

API = "https://commons.wikimedia.org/w/api.php"
ALLOWED = re.compile(r"^(cc0|public domain|pd|cc by(-sa)? [0-9.]+)", re.I)
PER_LOCALITY = 6


def _client(contact: str) -> httpx.Client:
    # Wikimedia's API policy asks for a descriptive User-Agent with contact details.
    return httpx.Client(timeout=30, headers={"User-Agent": f"mpf-area-photos/0.1 ({contact})"})


def _plain(value: str) -> str:
    return html.unescape(re.sub(r"<[^>]+>", "", value or "")).strip()


def fetch(client: httpx.Client, lat: float, lon: float, radius_m: int = 3000) -> list[dict]:
    params = {
        "action": "query", "format": "json", "generator": "geosearch",
        "ggscoord": f"{lat}|{lon}", "ggsradius": radius_m, "ggsnamespace": 6, "ggslimit": 40,
        "prop": "imageinfo", "iiprop": "url|extmetadata|mime", "iiurlwidth": 800,
    }
    resp = client.get(API, params=params)
    resp.raise_for_status()
    pages = resp.json().get("query", {}).get("pages", {})
    out = []
    for page in sorted(pages.values(), key=lambda p: p.get("index", 0)):
        info = (page.get("imageinfo") or [{}])[0]
        meta = info.get("extmetadata", {})
        license_name = _plain(meta.get("LicenseShortName", {}).get("value", ""))
        if info.get("mime") not in ("image/jpeg", "image/png") or not ALLOWED.match(license_name):
            continue
        if "NonCommercial" in meta.get("UsageTerms", {}).get("value", ""):
            continue
        out.append({
            "title": page["title"], "thumb_url": info["thumburl"], "page_url": info["descriptionurl"],
            "author": _plain(meta.get("Artist", {}).get("value", "")) or "Unknown author",
            "license": license_name, "license_url": meta.get("LicenseUrl", {}).get("value"),
        })
        if len(out) >= PER_LOCALITY:
            break
    return out


def refresh_all(conn: psycopg.Connection, contact: str, only_missing: bool = True) -> int:
    stored = 0
    with _client(contact) as client:
        sql = "SELECT id, lat, lon FROM locality"
        if only_missing:
            sql += " WHERE NOT EXISTS (SELECT 1 FROM area_photo a WHERE a.locality_id = locality.id)"
        for loc in conn.execute(sql).fetchall():
            try:
                photos = fetch(client, loc["lat"], loc["lon"])
            except (httpx.HTTPError, ValueError):
                continue
            with conn.transaction():
                for p in photos:
                    conn.execute(
                        """INSERT INTO area_photo (locality_id, title, thumb_url, page_url, author, license, license_url)
                           VALUES (%(loc)s, %(title)s, %(thumb_url)s, %(page_url)s, %(author)s, %(license)s, %(license_url)s)
                           ON CONFLICT (locality_id, title) DO UPDATE SET thumb_url = EXCLUDED.thumb_url,
                             author = EXCLUDED.author, license = EXCLUDED.license, fetched_at = now()""",
                        {**p, "loc": loc["id"]},
                    )
                    stored += 1
            time.sleep(0.5)  # be gentle with a free service
    return stored
