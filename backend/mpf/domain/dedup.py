"""Scores how likely a new listing and an existing property are the same real place.

High scores merge automatically, a middle band goes to a human review queue, and
anything lower becomes a new property. Every decision is logged and can be undone.
"""

from dataclasses import dataclass
from decimal import Decimal

from mpf.domain.geo import distance_m
from mpf.domain.text import jaccard, shingles

AUTO_MERGE = 0.85
REVIEW = 0.60


@dataclass(frozen=True)
class Candidate:
    type: str
    lat: float
    lon: float
    geo_precision: str  # "exact" | "locality"
    locality_id: int | None
    price_mur_minor: int
    plot_m2: Decimal | None
    floor_m2: Decimal | None
    bedrooms: int | None
    title: str
    image_hashes: tuple[str, ...] = ()


def _close(a: Decimal | int | None, b: Decimal | int | None, tolerance: float) -> float | None:
    if a is None or b is None:
        return None
    a, b = float(a), float(b)
    if a <= 0 or b <= 0:
        return None
    diff = abs(a - b) / max(a, b)
    return max(0.0, 1 - diff / tolerance) if diff <= tolerance else 0.0


def _image_match(a: tuple[str, ...], b: tuple[str, ...]) -> bool:
    """Perceptual hashes (64-bit hex) within 6 bits of each other."""
    for x in a:
        for y in b:
            if bin(int(x, 16) ^ int(y, 16)).count("1") <= 6:
                return True
    return False


def score(new: Candidate, existing: Candidate) -> float:
    if new.type != existing.type:
        return 0.0
    if new.bedrooms and existing.bedrooms and new.bedrooms != existing.bedrooms:
        return 0.0

    # Location: precise coordinates count more than "somewhere in this village".
    if new.geo_precision == "exact" and existing.geo_precision == "exact":
        d = distance_m(new.lat, new.lon, existing.lat, existing.lon)
        if d > 500:
            return 0.0
        location = 1.0 if d <= 150 else 0.6
    elif new.locality_id is not None and new.locality_id == existing.locality_id:
        location = 0.5
    else:
        return 0.0

    signals: list[tuple[float, float]] = [(location, 0.30)]
    price = _close(new.price_mur_minor, existing.price_mur_minor, 0.10)
    if price is not None:
        signals.append((price, 0.25))
    size = _close(new.plot_m2, existing.plot_m2, 0.05)
    if size is None:
        size = _close(new.floor_m2, existing.floor_m2, 0.05)
    if size is not None:
        signals.append((size, 0.25))
    signals.append((jaccard(shingles(new.title, 2), shingles(existing.title, 2)), 0.20))

    total_weight = sum(w for _, w in signals)
    s = sum(v * w for v, w in signals) / total_weight
    if new.image_hashes and existing.image_hashes and _image_match(new.image_hashes, existing.image_hashes):
        s = max(s, 0.9)
    return round(s, 3)


def decide(best_score: float) -> str:
    if best_score >= AUTO_MERGE:
        return "merge"
    if best_score >= REVIEW:
        return "review"
    return "new"
