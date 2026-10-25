import hashlib
import json
import re
import unicodedata


def norm(text: str | None) -> str:
    """Lower-case, strip accents and punctuation: "Pointe d'Esny" -> "pointe d esny"."""
    if not text:
        return ""
    decomposed = unicodedata.normalize("NFD", text)
    stripped = "".join(c for c in decomposed if unicodedata.category(c) != "Mn")
    return re.sub(r"[^a-z0-9]+", " ", stripped.lower()).strip()


def shingles(text: str | None, n: int = 3) -> set[str]:
    words = norm(text).split()
    return {" ".join(words[i:i + n]) for i in range(max(1, len(words) - n + 1))} if words else set()


def jaccard(a: set[str], b: set[str]) -> float:
    if not a or not b:
        return 0.0
    return len(a & b) / len(a | b)


def content_hash(fields: dict) -> str:
    """Stable fingerprint of a listing's facts, used to skip unchanged listings."""
    canonical = json.dumps(fields, sort_keys=True, default=str, ensure_ascii=False)
    return hashlib.sha256(canonical.encode()).hexdigest()
