# Mauritius property finder

One place to find land, apartments and houses for sale in Mauritius. Listings are gathered from partner feeds and from posts users share, duplicates are merged, and each listing shows whether a foreign buyer may purchase it.

- `backend/` — Python 3.11+, FastAPI, Postgres. Import pipeline, search API, alerts.
- `android/` — Kotlin, Jetpack Compose, MapLibre, ML Kit. The app.
- `docs/partner-feed.md` — the feed format partners send.

## How it runs without a server

```
data/sources.toml ─► GitHub Actions, daily, free ─► import partner feeds and reviewed sites
                     (backend/ pipeline + Postgres     merge duplicates, apply foreign-buyer rules,
                      inside the job)                  keep price history (state saved between runs)
                                                   ─► GitHub Pages, free:
                                                        data/meta.json, properties.json.gz, localities.json.gz
                                                        the web app (iPhone and desktop)
Android app / web app ─► download the snapshot only when its version changes, keep it on the device,
                          search, group the map and estimate costs locally, work offline.
                          Saved items, saved searches, alerts and your own posts stay on the device.
```

- **Nothing to host.** The pipeline runs in GitHub Actions. The data and the web app are static files on GitHub Pages. Both are free for a public repository.
- **Alerts without push.** The Android app refreshes twice a day in the background, compares the new snapshot with your saved searches and properties, and shows a local notification. The web app shows what changed when you open it.
- **Your own posts are private.** A post you share into the app is read on the phone (text recognition for screenshots included). It goes on your map only, with a link back to the original.
- **The API server still works** (`mpf.cli serve`) if you ever want accounts, shared posts or admin pages, but nothing needs it.

The data model and its safeguards are unchanged:
- A property is the real place, and a listing is one advert of it.
- Duplicates are merged cautiously.
- Prices are kept as integer cents in the currency they were advertised in.
- Foreign-buyer rules are versioned in `seed/rules_*.json`.
- A listing missing from two runs of its source is removed.

## Running the backend

```bash
cd backend
python3 -m venv .venv && .venv/bin/pip install -e '.[dev]'
createdb mpf
export MPF_DATABASE_URL=postgresql:///mpf
.venv/bin/python -m mpf.cli migrate
.venv/bin/python -m mpf.cli seed
.venv/bin/python -m mpf.cli ingest seed/sample_feeds/portal_a.json seed/sample_feeds/portal_b.json
.venv/bin/python -m mpf.cli serve        # http://127.0.0.1:8000/docs
```

Tests use a throwaway `mpf_test` database:

```bash
.venv/bin/python -m pytest
```

To read posts with Claude instead of the offline pattern reader, set `MPF_EXTRACTOR=claude`. Credentials come from `ANTHROPIC_API_KEY` or an `ant auth login` profile. The model is `MPF_EXTRACT_MODEL` (default `claude-opus-5-5`) at effort `MPF_EXTRACT_EFFORT` (default `low`). If the Claude call fails, posts fall back to the pattern reader. One live test is skipped unless `MPF_LIVE_CLAUDE=1`.

Operations commands: `ingest`, `deliver-alerts`, `fx --eur 52.4 --usd 45.6`, `recompute-eligibility` (after a rules change), `purge` (clears raw post text after `MPF_POST_RETENTION_DAYS`), `review` / `review --resolve ID --decision merge|separate`.

## Running the app

Open `android/` in Android Studio, or build with:

```bash
cd android && ./gradlew :app:assembleDebug
```

The debug build talks to `http://10.0.2.2:8000/`, which is the backend on your machine as seen from the emulator.

## Free services and what's left

Everything runs on free or open-source services. See `docs/free-setup.md` for the free accounts to create:
- **Maps:** OpenFreeMap tiles, OpenStreetMap villages (290) and coastline (24,000 points).
- **Photos:** Wikimedia Commons area photos, user uploads, licensed partner photos.
- **Verification:** Google sign-in instead of SMS.
- **Push:** Firebase Cloud Messaging.
- **iPhone:** an installable web app at `/app`.

Admin pages are at `/admin` (set `MPF_ADMIN_TOKEN`).

Still needed from you:
- **Free accounts:** Google OAuth client ID, Firebase project, and `MPF_CONTACT` for Wikimedia.
- **Real listings:** partners must agree (`docs/legal/partner-terms.md`). The crawler only runs on sites whose terms you've reviewed.
- **Legal:** a lawyer's review of the wording (`docs/legal/lawyer-review-pack.md`) and the Data Protection Office filing (`docs/legal/data-protection-registration.md`).
- **Hosting:** a free always-on machine (`docs/free-setup.md`).
