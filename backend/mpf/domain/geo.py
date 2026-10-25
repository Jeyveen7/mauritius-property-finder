import json
import math
from functools import lru_cache
from pathlib import Path

EARTH_RADIUS_M = 6_371_000
COASTLINE_FILE = Path(__file__).resolve().parent.parent.parent / "seed" / "coastline.json"


def distance_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Great-circle distance (haversine)."""
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * EARTH_RADIUS_M * math.asin(math.sqrt(a))


@lru_cache(maxsize=1)
def _coastline() -> list[list[tuple[float, float]]]:
    """Coastline polylines of (lat, lon), from OpenStreetMap (see mpf/osm.py)."""
    return [[tuple(p) for p in line] for line in json.loads(COASTLINE_FILE.read_text())["lines"]]


def coast_distance_m(lat: float, lon: float) -> float:
    """Distance to the nearest coastline segment, on a local flat projection
    (accurate to well under 1% across an island 60 km wide)."""
    k_lon = 111_320 * math.cos(math.radians(lat))
    k_lat = 110_574
    # Skip lines whose bounding box is already further than the best distance.
    best = math.inf
    for line in _coastline():
        pts = [((p[1] - lon) * k_lon, (p[0] - lat) * k_lat) for p in line]
        xs, ys = [p[0] for p in pts], [p[1] for p in pts]
        box = math.hypot(max(0.0, min(xs), -max(xs)), max(0.0, min(ys), -max(ys)))
        if box >= best:
            continue
        for (ax, ay), (bx, by) in zip(pts, pts[1:]):
            dx, dy = bx - ax, by - ay
            seg = dx * dx + dy * dy
            t = 0.0 if seg == 0 else max(0.0, min(1.0, -(ax * dx + ay * dy) / seg))
            best = min(best, math.hypot(ax + t * dx, ay + t * dy))
    return best


def bbox_around(lat: float, lon: float, radius_m: float) -> tuple[float, float, float, float]:
    """(min_lat, min_lon, max_lat, max_lon) covering a circle of radius_m."""
    dlat = radius_m / 110_574
    dlon = radius_m / (111_320 * math.cos(math.radians(lat)))
    return lat - dlat, lon - dlon, lat + dlat, lon + dlon
