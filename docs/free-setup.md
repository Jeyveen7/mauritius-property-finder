# Running it for free

Everything the app uses is free or open source. A few services need a free account, which only you can create. Each one is optional: the app keeps working without it, minus that feature.

| Feature | Service | Cost | What you do |
|---|---|---|---|
| Map tiles | OpenFreeMap (OpenStreetMap data) | Free, no account | Nothing: already set up. Keep the "© OpenStreetMap" credit visible (the map shows it). |
| Villages and coastline | OpenStreetMap via Overpass | Free, no account | Already imported into `backend/seed/`. Refresh: `mpf.cli osm-import` (`--cache` if the public servers are slow). |
| Area photos | Wikimedia Commons | Free, no account | Set `MPF_CONTACT` to your email or project URL (Wikimedia refuses anonymous bots), then `mpf.cli area-photos`. |
| Account verification | — | Free | Not needed without a server: posts you add stay on your own device. (Google sign-in remains in the optional API server.) |
| Alerts | On-device background refresh | Free, no account | Nothing: the Android app checks for new data twice a day and notifies you locally. No Firebase needed. |
| SMS codes | — | Never free | Not used. Phone verification stays available in the code if you ever add a provider (`OtpSender`). |
| iPhone | Installable web app on GitHub Pages | Free | Open `https://<you>.github.io/<repo>/` in Safari → Share → Add to Home Screen. No Apple developer account needed. |
| Approved projects | Economic Development Board public lists (PDS, RES, IRS) | Free | Already in `backend/seed/edb_projects.csv` (facts only; contact details dropped). When the EDB updates a PDF: `pdftotext -layout list.pdf list.txt`, then `mpf.cli edb-import list.txt --scheme PDS`. |
| Partner listings | `data/sources.toml` + their feed URL or CSV | Free | Add the partner to the file (after they accept the partner terms); the daily build imports it. |
| Other websites | Polite crawler in the daily build | Free | Only after you read a site's terms; add it under `[[crawl]]` with your name and what the terms say. |

## Publishing (no server)

1. Put this folder in a **public** GitHub repository.
2. Repository **Settings → Pages → Source: GitHub Actions**.
3. Optionally, in **Settings → Variables**, add `MPF_CONTACT` (your email or project URL). This enables area photos and crawling.
4. The `data` workflow runs daily and whenever `data/` changes. You can also run it by hand from the Actions tab. It publishes:
   - `https://<you>.github.io/<repo>/`: the web app. Open it on an iPhone and choose Add to Home Screen.
   - `https://<you>.github.io/<repo>/data/`: the snapshot the apps download.
5. Build the Android app pointing at it. Share the APK however you like (free), or publish it on Google Play (one-off $25 developer fee).

```bash
./gradlew :app:assembleRelease -PdataBaseUrl=https://<you>.github.io/<repo>/data
```

To add listings, edit `data/sources.toml` on GitHub, or commit a partner's CSV under `data/partners/`.

## Scheduled jobs (only if you run the optional API server yourself)

```
*/15 * * * *  cd backend && .venv/bin/python -m mpf.cli deliver-alerts
0 3 * * *     cd backend && .venv/bin/python -m mpf.cli purge
30 3 * * *    cd backend && .venv/bin/python -m mpf.cli crawl <source-id>        # per reviewed source
0 4 * * 1     cd backend && .venv/bin/python -m mpf.cli area-photos
0 5 * * *     cd backend && .venv/bin/python -m mpf.cli hash-images
```

Exchange rates: record the Bank of Mauritius indicative rates (free on its website) with `mpf.cli fx --eur … --usd …`, then run `recompute-eligibility`.
