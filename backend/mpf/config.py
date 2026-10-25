import os
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Settings:
    database_url: str
    # "claude" uses the Claude API; "rules" uses the offline pattern extractor.
    extractor: str
    extract_model: str
    extract_effort: str
    # Days to keep the raw text of user posts before `mpf purge` clears it.
    post_retention_days: int
    max_posts_per_day: int
    # Reports from distinct users that hide a property until it is reviewed.
    reports_to_hide: int
    # Missed source checks after which a listing is marked gone.
    missed_checks_to_expire: int
    # Uploaded photos are stored here and served at <public_base_url>/media/.
    media_dir: Path
    public_base_url: str
    max_photos_per_post: int
    # Bearer token for the admin pages; admin is disabled when empty.
    admin_token: str
    # OAuth client IDs whose Google ID tokens are accepted (comma-separated); empty disables.
    google_client_ids: tuple[str, ...]
    # Firebase service-account JSON for push; empty logs pushes instead of sending them.
    fcm_service_account: str
    # Identifies us to Wikimedia and OpenStreetMap, as their API policies require.
    contact: str


def load() -> Settings:
    e = os.environ.get
    return Settings(
        database_url=e("MPF_DATABASE_URL", "postgresql:///mpf"),
        extractor=e("MPF_EXTRACTOR", "rules"),
        extract_model=e("MPF_EXTRACT_MODEL", "claude-opus-5-5"),
        extract_effort=e("MPF_EXTRACT_EFFORT", "low"),
        post_retention_days=int(e("MPF_POST_RETENTION_DAYS", "30")),
        max_posts_per_day=int(e("MPF_MAX_POSTS_PER_DAY", "5")),
        reports_to_hide=int(e("MPF_REPORTS_TO_HIDE", "3")),
        missed_checks_to_expire=int(e("MPF_MISSED_CHECKS_TO_EXPIRE", "2")),
        media_dir=Path(e("MPF_MEDIA_DIR", str(Path(__file__).resolve().parent.parent / "media"))),
        public_base_url=e("MPF_PUBLIC_BASE_URL", "http://127.0.0.1:8000").rstrip("/"),
        max_photos_per_post=int(e("MPF_MAX_PHOTOS_PER_POST", "8")),
        admin_token=e("MPF_ADMIN_TOKEN", ""),
        google_client_ids=tuple(x for x in e("MPF_GOOGLE_CLIENT_IDS", "").split(",") if x),
        fcm_service_account=e("MPF_FCM_SERVICE_ACCOUNT", ""),
        contact=e("MPF_CONTACT", ""),
    )


def require_contact(settings: Settings) -> str:
    """Wikimedia's bot policy and good crawling practice both require a real way to
    reach us (an email or project URL) in every request's User-Agent."""
    if "@" not in settings.contact and "http" not in settings.contact:
        raise SystemExit("Set MPF_CONTACT to an email address or project URL first: "
                         "Wikimedia refuses anonymous bots, and sites deserve to know who crawls them.")
    return settings.contact
