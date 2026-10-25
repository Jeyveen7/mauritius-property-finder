"""Photo handling: uploads are re-encoded (which strips EXIF, including GPS, from the
user's camera), size-limited, and fingerprinted with a perceptual hash so the same
photo on two sites can link duplicate listings."""

import io
import uuid
from datetime import date
from pathlib import Path

import httpx
import imagehash
from PIL import Image, ImageOps, UnidentifiedImageError

MAX_UPLOAD_BYTES = 12 * 1024 * 1024
MAX_SIDE = 2048
ACCEPTED_FORMATS = {"JPEG", "PNG", "WEBP", "HEIF", "MPO"}
# Refuse decompression bombs well before Pillow's own limit.
Image.MAX_IMAGE_PIXELS = 60_000_000


class BadImage(ValueError):
    pass


def phash(img: Image.Image) -> str:
    return str(imagehash.phash(img))


def store_upload(data: bytes, media_dir: Path) -> dict:
    """Validates and stores one uploaded photo; returns its relative path, size and hash."""
    if len(data) > MAX_UPLOAD_BYTES:
        raise BadImage("too_large")
    try:
        img = Image.open(io.BytesIO(data))
        fmt = img.format
        img.load()
    except (UnidentifiedImageError, OSError, Image.DecompressionBombError):
        raise BadImage("not_an_image") from None
    if fmt not in ACCEPTED_FORMATS:
        raise BadImage("unsupported_format")

    img = ImageOps.exif_transpose(img).convert("RGB")  # honour rotation, then drop metadata
    img.thumbnail((MAX_SIDE, MAX_SIDE))
    rel = Path("uploads") / date.today().strftime("%Y/%m") / f"{uuid.uuid4()}.jpg"
    out = media_dir / rel
    out.parent.mkdir(parents=True, exist_ok=True)
    img.save(out, "JPEG", quality=85, optimize=True)  # no exif= argument: metadata is not written
    return {"storage_path": str(rel), "width": img.width, "height": img.height, "phash": phash(img)}


def hash_remote(url: str, user_agent: str) -> str | None:
    """Perceptual hash of a licensed remote image, for duplicate detection only."""
    try:
        with httpx.stream("GET", url, timeout=30, headers={"User-Agent": user_agent}, follow_redirects=True) as r:
            r.raise_for_status()
            chunks, size = [], 0
            for chunk in r.iter_bytes():
                size += len(chunk)
                if size > MAX_UPLOAD_BYTES:
                    return None
                chunks.append(chunk)
        return phash(Image.open(io.BytesIO(b"".join(chunks))))
    except (httpx.HTTPError, UnidentifiedImageError, OSError):
        return None
