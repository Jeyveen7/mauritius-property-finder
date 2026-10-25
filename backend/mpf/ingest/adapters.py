"""Source adapters: one per source, each returning that source's full current inventory.

Only the partner-feed adapter exists so far; it reads feed format v1
(docs/partner-feed.md). Crawlers for sources whose terms allow it implement the
same `SourceAdapter` protocol.
"""

import json
from collections.abc import Iterable
from dataclasses import dataclass, field
from decimal import Decimal
from pathlib import Path
from typing import Literal, Protocol

import httpx
from pydantic import BaseModel

from mpf.domain.money import Money
from mpf.domain.units import to_m2


@dataclass(frozen=True)
class RawListing:
    ref: str
    type: str
    title: str
    locality: str | None
    price: Money
    lat: float | None = None
    lon: float | None = None
    negotiable: bool = False
    plot_m2: Decimal | None = None
    floor_m2: Decimal | None = None
    bedrooms: int | None = None
    scheme: str | None = None
    floors_above_ground: int | None = None
    coastal: bool = False
    agency_name: str | None = None
    url: str | None = None
    contact_url: str | None = None
    image_urls: tuple[str, ...] = ()
    image_hashes: tuple[str, ...] = ()
    verified: bool = True


class SourceAdapter(Protocol):
    source_id: str
    source_name: str
    kind: str
    images_licensed: bool

    def fetch(self) -> Iterable[RawListing]: ...


# --- Partner feed v1 -------------------------------------------------------

class _Price(BaseModel):
    amount: Decimal
    currency: Literal["MUR", "EUR", "USD"]


class _Area(BaseModel):
    value: Decimal
    unit: Literal["m2", "perch", "toise", "arpent"]


class _FeedListing(BaseModel):
    ref: str
    type: Literal["land", "apartment", "house"]
    title: str
    locality: str | None = None
    lat: float | None = None
    lon: float | None = None
    price: _Price
    negotiable: bool = False
    plot: _Area | None = None
    floor_m2: Decimal | None = None
    bedrooms: int | None = None
    scheme: Literal["PDS", "IRS", "RES", "SMART_CITY", "IHS", "G+2"] | None = None
    floors_above_ground: int | None = None
    coastal: bool = False
    agency: str | None = None
    url: str | None = None
    contact_url: str | None = None
    images: list[str] = []
    image_hashes: list[str] = []


class _FeedSource(BaseModel):
    id: str
    name: str


class _Feed(BaseModel):
    feed_version: Literal[1]
    source: _FeedSource
    listings: list[_FeedListing]


def _from_feed_item(item: "_FeedListing", images_licensed: bool) -> RawListing:
    return RawListing(
        ref=item.ref, type=item.type, title=item.title, locality=item.locality,
        lat=item.lat, lon=item.lon,
        price=Money.of(item.price.amount, item.price.currency),
        negotiable=item.negotiable,
        plot_m2=to_m2(item.plot.value, item.plot.unit) if item.plot else None,
        floor_m2=item.floor_m2, bedrooms=item.bedrooms, scheme=item.scheme,
        floors_above_ground=item.floors_above_ground, coastal=item.coastal,
        agency_name=item.agency, url=item.url, contact_url=item.contact_url,
        # Image URLs are only kept when the partner agreement licenses them.
        image_urls=tuple(item.images) if images_licensed else (),
        image_hashes=tuple(item.image_hashes),
    )


@dataclass
class PushedFeedAdapter:
    """A feed a partner pushed to the API. The source comes from the partner's API key,
    never from the document, so one partner cannot write into another's listings."""

    source_id: str
    source_name: str
    document: dict
    images_licensed: bool = True
    kind: str = "partner_feed"

    def fetch(self) -> Iterable[RawListing]:
        listings = [_FeedListing.model_validate(x) for x in self.document.get("listings", [])]
        return (_from_feed_item(item, self.images_licensed) for item in listings)


CSV_COLUMNS = ["ref", "type", "title", "locality", "price", "currency", "plot_value", "plot_unit",
               "floor_m2", "bedrooms", "scheme", "floors_above_ground", "url", "images"]


def csv_to_feed(text: str) -> dict:
    """Spreadsheet export (CSV with the columns in CSV_COLUMNS) to a feed document.
    `images` holds photo URLs separated by `|`."""
    import csv
    import io

    def opt(row, key, cast=str):
        value = (row.get(key) or "").strip()
        return cast(value) if value else None

    listings = []
    for row in csv.DictReader(io.StringIO(text)):
        item = {"ref": row["ref"].strip(), "type": row["type"].strip(), "title": row["title"].strip(),
                "locality": opt(row, "locality"),
                "price": {"amount": row["price"].strip().replace(",", ""), "currency": opt(row, "currency") or "MUR"},
                "floor_m2": opt(row, "floor_m2"), "bedrooms": opt(row, "bedrooms", int),
                "scheme": opt(row, "scheme"), "floors_above_ground": opt(row, "floors_above_ground", int),
                "url": opt(row, "url"), "images": [u for u in (row.get("images") or "").split("|") if u.strip()]}
        if opt(row, "plot_value"):
            item["plot"] = {"value": row["plot_value"].strip(), "unit": opt(row, "plot_unit") or "m2"}
        listings.append(item)
    return {"feed_version": 1, "listings": listings}


@dataclass
class PartnerFeedAdapter:
    """Reads a partner's JSON feed from a local file or an HTTPS URL."""

    location: str
    images_licensed: bool = False
    kind: str = "partner_feed"
    _feed: _Feed | None = field(default=None, repr=False)

    def _load(self) -> _Feed:
        if self._feed is None:
            if self.location.startswith(("http://", "https://")):
                resp = httpx.get(self.location, timeout=60, headers={"User-Agent": "mpf-ingest/0.1"})
                resp.raise_for_status()
                data = resp.json()
            else:
                data = json.loads(Path(self.location).read_text())
            self._feed = _Feed.model_validate(data)
        return self._feed

    @property
    def source_id(self) -> str:
        return self._load().source.id

    @property
    def source_name(self) -> str:
        return self._load().source.name

    def fetch(self) -> Iterable[RawListing]:
        for item in self._load().listings:
            yield _from_feed_item(item, self.images_licensed)
