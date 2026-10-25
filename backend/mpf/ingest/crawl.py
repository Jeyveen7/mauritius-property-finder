"""A polite crawler for sites that publish schema.org listing data (JSON-LD), the
structured data sites add for search engines.

Safeguards, in order:
1. A source is never crawled until a person has read its terms and recorded that they
   allow it (`source.terms_reviewed_at`, set in the admin pages).
2. robots.txt is obeyed, including Crawl-delay, and a `Content-Signal: search=no`
   reservation stops the crawl.
3. Pages marked `noindex` are skipped.
4. Only facts and the page link are kept: no descriptions, no photos (unless the
   source's images are licensed), no names or phone numbers.
"""

import json
import re
import time
import urllib.robotparser
from collections.abc import Iterable
from dataclasses import dataclass, field
from decimal import Decimal, InvalidOperation
from urllib.parse import urljoin, urlparse
from xml.etree import ElementTree

import httpx

from mpf.domain.money import Money
from mpf.ingest.adapters import RawListing

USER_AGENT = "mpf-crawler/0.1 (+{contact})"
LISTING_TYPES = {"RealEstateListing", "Offer", "Product", "Residence", "House", "SingleFamilyResidence",
                 "Apartment", "Accommodation", "Place", "LandmarksOrHistoricalBuildings"}
JSONLD = re.compile(r'<script[^>]+type=["\']application/ld\+json["\'][^>]*>(.*?)</script>', re.S | re.I)
NOINDEX = re.compile(r'<meta[^>]+name=["\']robots["\'][^>]+content=["\'][^"\']*noindex', re.I)


class CrawlRefused(Exception):
    pass


def _types(node: dict) -> set[str]:
    t = node.get("@type", [])
    return set(t if isinstance(t, list) else [t])


def _walk(data) -> Iterable[dict]:
    if isinstance(data, list):
        for x in data:
            yield from _walk(x)
    elif isinstance(data, dict):
        yield data
        for key in ("@graph", "itemOffered", "offers", "mainEntity", "about"):
            if key in data:
                yield from _walk(data[key])


def _num(value) -> Decimal | None:
    if isinstance(value, dict):
        value = value.get("value")
    try:
        return Decimal(str(value).replace(",", "").strip()) if value not in (None, "") else None
    except InvalidOperation:
        return None


def listing_from_jsonld(blocks: list, page_url: str) -> RawListing | None:
    """Maps the first listing-like JSON-LD node on a page to a RawListing."""
    nodes = [n for b in blocks for n in _walk(b)]
    item = next((n for n in nodes if _types(n) & LISTING_TYPES and n.get("name")), None)
    if item is None:
        return None
    offer = next((n for n in nodes if "Offer" in _types(n) and n.get("price")), item if item.get("price") else None)
    if offer is None:
        return None
    price, currency = _num(offer.get("price")), offer.get("priceCurrency", "MUR")
    if price is None or currency not in ("MUR", "EUR", "USD"):
        return None
    subject = item.get("itemOffered") if isinstance(item.get("itemOffered"), dict) else item
    address = subject.get("address") or item.get("address") or {}
    geo = subject.get("geo") or item.get("geo") or {}
    floor = _num(subject.get("floorSize"))
    plot = _num(subject.get("lotSize") or subject.get("landSize"))
    beds = _num(subject.get("numberOfBedrooms") or subject.get("numberOfRooms"))
    kind = "apartment" if _types(subject) & {"Apartment"} else "house" if _types(subject) & {"House", "SingleFamilyResidence"} \
        else "land" if plot and not floor else "house"
    if plot is None and floor is None:
        return None
    return RawListing(
        ref=page_url, type=kind, title=str(item["name"])[:140],
        locality=address.get("addressLocality") if isinstance(address, dict) else None,
        lat=float(geo["latitude"]) if isinstance(geo, dict) and geo.get("latitude") else None,
        lon=float(geo["longitude"]) if isinstance(geo, dict) and geo.get("longitude") else None,
        price=Money.of(price, currency), plot_m2=plot, floor_m2=floor,
        bedrooms=int(beds) if beds else None, url=page_url,
    )


@dataclass
class StructuredDataCrawler:
    source_id: str
    source_name: str
    start_urls: list[str]
    terms_reviewed: bool
    contact: str
    images_licensed: bool = False
    max_pages: int = 500
    kind: str = "crawl"
    client: httpx.Client | None = None
    pages_fetched: int = field(default=0, init=False)

    def _http(self) -> httpx.Client:
        if self.client is None:
            self.client = httpx.Client(timeout=30, follow_redirects=True,
                                       headers={"User-Agent": USER_AGENT.format(contact=self.contact)})
        return self.client

    def _robots(self, url: str) -> tuple[urllib.robotparser.RobotFileParser, float]:
        root = f"{urlparse(url).scheme}://{urlparse(url).netloc}"
        text = self._http().get(root + "/robots.txt").text
        if re.search(r"content-signal:\s*[^\n]*search\s*=\s*no", text, re.I):
            raise CrawlRefused("site reserves rights against search indexing (Content-Signal)")
        rp = urllib.robotparser.RobotFileParser()
        rp.parse(text.splitlines())
        delay = rp.crawl_delay(USER_AGENT) or 2.0
        return rp, max(float(delay), 2.0)

    def _page_urls(self, rp) -> Iterable[str]:
        for start in self.start_urls:
            if start.endswith(".xml"):
                tree = ElementTree.fromstring(self._http().get(start).content)
                for loc in tree.iter("{http://www.sitemaps.org/schemas/sitemap/0.9}loc"):
                    yield loc.text.strip()
            else:
                yield start

    def fetch(self) -> Iterable[RawListing]:
        if not self.terms_reviewed:
            raise CrawlRefused("terms not reviewed: record in the admin pages that this site's terms allow it")
        rp, delay = self._robots(self.start_urls[0])
        agent = USER_AGENT.format(contact=self.contact)
        for url in self._page_urls(rp):
            if self.pages_fetched >= self.max_pages:
                break
            if not rp.can_fetch(agent, url):
                continue
            time.sleep(delay)
            resp = self._http().get(url)
            self.pages_fetched += 1
            if resp.status_code != 200 or NOINDEX.search(resp.text) or "noindex" in resp.headers.get("X-Robots-Tag", ""):
                continue
            blocks = []
            for raw in JSONLD.findall(resp.text):
                try:
                    blocks.append(json.loads(raw))
                except json.JSONDecodeError:
                    continue
            listing = listing_from_jsonld(blocks, urljoin(url, url))
            if listing:
                yield listing
