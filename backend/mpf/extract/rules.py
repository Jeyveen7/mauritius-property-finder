"""Offline pattern-based extractor. Used in development and as a fallback when the
Claude API is unavailable; it handles the common shapes only and says so through
low confidence scores."""

import re

from mpf.domain.text import norm
from mpf.domain.units import UNIT_ALIASES
from mpf.extract.schema import Confidence, ExtractedPost

_TYPE_WORDS = [
    ("apartment", re.compile(r"\b(appartement|apartment|flat|penthouse|duplex)\b")),
    ("house", re.compile(r"\b(maison|villa|house|bungalow|lakaz)\b")),
    ("land", re.compile(r"\b(terrain|land|plot|morcellement|lot)\b")),
]
_SIZE = re.compile(r"(\d+(?:[.,]\d+)?)\s*(m2|m²|sqm|metres? carres?|perches?|toises?|arpents?)")
# "4 200 000", "4,200,000" and "4.200.000" use thousands separators; "4.2" and "4,2" are decimals.
_NUM = r"(\d{1,3}(?:[ ,.]\d{3})+|\d+(?:[.,]\d+)?)"
_PRICE = re.compile(r"(?:rs\.?|mur|roupies?)\s*" + _NUM + r"\s*(m\b|millions?|mil\b|k\b)?")
_PRICE_EUR = re.compile(r"(?:€|eur(?:os?)?)\s*" + _NUM + r"\s*(m\b|millions?|k\b)?")
_PRICE_USD = re.compile(r"(?:\$|usd)\s*" + _NUM + r"\s*(m\b|millions?|k\b)?")
_NEGOTIABLE = re.compile(r"\b(a discuter|negociable|nego|negotiable|ono)\b")
_SCHEMES = [("SMART_CITY", "smart city"), ("PDS", "pds"), ("IRS", "irs"), ("RES", "res"), ("G+2", "g 2")]


def _number(digits: str, suffix: str | None) -> int | None:
    digits = digits.strip().replace(" ", "")
    if re.fullmatch(r"\d{1,3}([.,]\d{3})+", digits):
        value = float(re.sub(r"[.,]", "", digits))  # thousands separators
    else:
        try:
            value = float(digits.replace(",", "."))
        except ValueError:
            return None
    if suffix:
        value *= 1_000 if suffix.startswith("k") else 1_000_000
    return round(value)


class RulesExtractor:
    name = "rules"

    def __init__(self, known_localities: list[str] | None = None):
        self.known_localities = sorted(known_localities or [], key=len, reverse=True)

    def extract(self, text: str) -> ExtractedPost:
        raw = norm(text.replace("²", "2").replace("€", " eur ").replace("$", " usd "))
        lowered = text.lower()
        conf = {"type": 0.0, "locality": 0.0, "size": 0.0, "price": 0.0}

        type_ = next((t for t, rx in _TYPE_WORDS if rx.search(raw)), None)
        if type_:
            conf["type"] = 0.7

        size_value = size_unit = None
        m = _SIZE.search(lowered.replace("²", "2"))
        if m:
            unit_word = norm(m.group(2))
            size_unit = UNIT_ALIASES.get(unit_word) or UNIT_ALIASES.get(unit_word.rstrip("s"))
            size_value = float(m.group(1).replace(",", "."))
            conf["size"] = 0.7 if size_unit else 0.0

        price = currency = None
        for rx, cur in ((_PRICE, "MUR"), (_PRICE_EUR, "EUR"), (_PRICE_USD, "USD")):
            pm = rx.search(lowered.replace("€", " eur ").replace("$", " usd "))
            if pm and (value := _number(pm.group(1), pm.group(2))):
                price, currency = value, cur
                conf["price"] = 0.6
                break

        locality = next((name for name in self.known_localities if norm(name) in raw), None)
        if locality:
            conf["locality"] = 0.8

        scheme = next((s for s, word in _SCHEMES if re.search(rf"\b{word}\b", raw)), None)
        beds = re.search(r"\b(\d)\s*(?:chambres?|bed(?:room)?s?|ch)\b", raw)

        return ExtractedPost(
            type=type_, locality=locality, size_value=size_value, size_unit=size_unit,
            size_is_plot=(type_ == "land") if type_ else None,
            price_whole=price, price_currency=currency,
            negotiable=bool(_NEGOTIABLE.search(raw)), scheme=scheme,
            bedrooms=int(beds.group(1)) if beds else None,
            confidence=Confidence(**conf),
        )
