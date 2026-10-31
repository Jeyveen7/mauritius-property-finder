"""Official government facts: the Economic Development Board's published lists of approved
property schemes open to foreign buyers (PDS, RES, IRS).

These are real, public and legal to show: facts about approved projects (developer,
place, what is being built, scheme, status, the developer's own website), re-keyed with
attribution and a link back to the EDB document. Contact people, e-mails, phone and fax
numbers in the lists are personal data and are never imported.

The lists are PDFs. Convert one with `pdftotext -layout`, then:
    python -m mpf.cli edb-import List-of-Approved-PDS-Projects.txt --scheme PDS
which rewrites that scheme's rows in seed/edb_projects.csv. The daily build only reads the CSV.
"""

import csv
import re
from pathlib import Path

from mpf.domain.text import norm
from mpf.repo import Gazetteer

SEED = Path(__file__).resolve().parents[1] / "seed" / "edb_projects.csv"
COLUMNS = ["scheme", "developer", "location", "project", "status", "website", "source_url"]
ATTRIBUTION = "Approved projects: Economic Development Board, Mauritius (public lists; facts only)"
SOURCES = {
    "PDS": "https://edbmauritius.org/wp-content/uploads/2022/10/List-of-Approved-PDS-Projects.pdf",
    "RES": "https://edbmauritius.org/wp-content/uploads/2023/09/List-of-Approved-RES-Projects.pdf",
    "IRS": "https://edbmauritius.org/wp-content/uploads/2023/09/List-of-IRS-projects.pdf",
}
SCHEME_NAMES = {
    "PDS": "Property Development Scheme",
    "RES": "Real Estate Scheme",
    "IRS": "Integrated Resort Scheme",
}

# Labels as printed in the lists. Only the first group is kept.
_KEEP = {"Location": "location", "Project": "project", "Web page address": "website", "Status": "status"}
_DROP = ("Contact person", "Address", "E-mail", "Tel No.", "Fax No.")
_LABEL = re.compile(r"^(" + "|".join(re.escape(l) for l in [*_KEEP, *_DROP]) + r")\b\s*(.*)$")
_NUMBER = re.compile(r"^-?\d+\s+")
_WRAPS = {"location", "project"}  # fields whose text continues on the next lines
# Hamlets and estates named in the lists that the village list doesn't have: the village
# they belong to. Spelling slips in the lists are corrected here too.
_NEAR = {
    "bain boeuf": "Cap Malheureux", "cap malhereux": "Cap Malheureux", "mare seche": "Pereybère",
    "pointe aux cannoniers": "Grand Baie", "pointe aux canonniers": "Grand Baie",
    "calodyne": "Grand Gaube", "saint antoine": "Goodlands", "pointe des lascars": "Rivière du Rempart",
    "la mivoie": "Rivière Noire", "yemen": "Rivière Noire", "bois sec": "Bois Chéri",
}


def _clean(s: str) -> str:
    return re.sub(r"\s+", " ", s).strip(" ,;")


def parse(text: str, scheme: str) -> list[dict]:
    lines = [l.rstrip() for l in text.splitlines()]
    labelled = [_LABEL.match(_NUMBER.sub("", l.strip())) for l in lines]
    rows: list[dict] = []
    row: dict | None = None
    field: str | None = None
    for i, line in enumerate(lines):
        s = _NUMBER.sub("", line.strip())
        if not s:
            continue
        m = labelled[i]
        if m:
            label, value = m.group(1), m.group(2)
            field = _KEEP.get(label)
            if row is not None and field:
                row[field] = value.strip()
            if field not in _WRAPS:
                field = None  # continuation lines of dropped fields (addresses) are ignored
            continue
        nxt = next((j for j in range(i + 1, len(lines)) if lines[j].strip()), None)
        starts_entry = nxt is not None and labelled[nxt] and labelled[nxt].group(1) == "Location"
        if starts_entry:
            row = {"scheme": scheme, "developer": _clean(s), "location": "", "project": "",
                   "status": "", "website": "", "source_url": SOURCES.get(scheme, "")}
            rows.append(row)
            field = None
        elif row is not None and field in _WRAPS:
            row[field] += " " + s
    for r in rows:
        for k in ("developer", "location", "project", "status", "website"):
            r[k] = _clean(r[k])
        r["status"] = _status(r["status"], scheme)
        if r["website"] and not r["website"].startswith("http"):
            r["website"] = "https://" + r["website"]
    return [r for r in rows if r["location"] and r["project"]]


def _status(raw: str, scheme: str) -> str:
    s = raw.lower()
    if "letter of approval" in s:
        return "Letter of approval"
    tail = " (completed)" if "completed" in s else " (under construction)" if "construction" in s else ""
    return f"{scheme} certificate{tail}"


def write_scheme(rows: list[dict], scheme: str, path: Path = SEED) -> int:
    kept = []
    if path.exists():
        with open(path, newline="", encoding="utf-8") as f:
            kept = [r for r in csv.DictReader(f) if r["scheme"] != scheme]
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=COLUMNS)
        w.writeheader()
        w.writerows(sorted(kept + rows, key=lambda r: (r["scheme"], norm(r["developer"]))))
    return len(rows)


def _place(gaz: Gazetteer, location: str) -> dict | None:
    """'Mare Seche, Pereybère' → the most specific part the gazetteer knows."""
    whole = gaz.resolve(location)
    if whole:
        return whole
    parts = [p.strip() for p in re.split(r"[,/&()]| and | - ", location) if p.strip()]
    for part in reversed(parts):
        hit = gaz.resolve(part) or gaz.resolve(_NEAR.get(norm(part)))
        if hit:
            return hit
    # Last resort: a known place name inside the text ("Petite Rivière Noire" → Rivière Noire).
    text = f" {norm(location)} "
    best = None
    for row in gaz.rows:
        for name in [row["name"], *row["aliases"]]:
            key = norm(name)
            if len(key) >= 4 and f" {key} " in text and (best is None or len(key) > best[0]):
                best = (len(key), row)
    return best[1] if best else None


def projects(conn, path: Path = SEED) -> list[dict]:
    """The approved projects, placed on the map at their village (never more precise)."""
    if not path.exists():
        return []
    gaz = Gazetteer(conn)
    out = []
    with open(path, newline="", encoding="utf-8") as f:
        for i, r in enumerate(csv.DictReader(f)):
            loc = _place(gaz, r["location"])
            out.append({
                "id": f"edb-{r['scheme'].lower()}-{i}",
                "scheme": r["scheme"], "scheme_name": SCHEME_NAMES.get(r["scheme"], r["scheme"]),
                "developer": r["developer"], "location": r["location"], "project": r["project"],
                "status": r["status"], "website": r["website"] or None, "source_url": r["source_url"],
                "locality": loc["name"] if loc else None, "district": loc["district"] if loc else None,
                "region": loc["region"] if loc else None,
                "lat": loc["lat"] if loc else None, "lon": loc["lon"] if loc else None,
                "search_text": norm(" ".join([r["developer"], r["location"], r["project"],
                                              loc["name"] if loc else "", loc["district"] if loc else ""])),
            })
    return out
