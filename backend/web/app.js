// Property finder web app for iPhone (Add to Home Screen) and desktop. It needs no server:
// it downloads the daily data snapshot (data/*.json.gz, published by GitHub Actions),
// keeps it in the browser, and searches it locally. Saved items and your own posts stay
// in this browser. Maps: MapLibre + OpenFreeMap (free, open source).
"use strict";

const $ = (sel, root = document) => root.querySelector(sel);
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const store = {
  get(k, d = null) { try { return JSON.parse(localStorage.getItem(k)) ?? d; } catch { return d; } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch { /* private mode or full */ } },
};
const DATA = new URL("data/", location.href);

// --- Data -----------------------------------------------------------------

const norm = (s) => String(s ?? "").normalize("NFD").replace(/\p{Mn}+/gu, "").toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();

async function gunzipJson(resp) {
  const text = await new Response(resp.body.pipeThrough(new DecompressionStream("gzip"))).text();
  return JSON.parse(text);
}

/** Loads the snapshot; the browser's HTTP cache and the service worker keep it offline. */
async function loadData() {
  const meta = await (await fetch(new URL("meta.json", DATA), { cache: "no-cache" })).json();
  const v = `?v=${meta.version}`;
  const [properties, localities, projects] = await Promise.all([
    fetch(new URL("properties.json.gz" + v, DATA)).then(gunzipJson),
    fetch(new URL("localities.json.gz" + v, DATA)).then(gunzipJson),
    meta.projects ? fetch(new URL("projects.json.gz" + v, DATA)).then(gunzipJson) : [],
  ]);
  return { meta, properties, localities, projects, byId: new Map(properties.map((p) => [p.id, p])),
           projectById: new Map(projects.map((p) => [p.id, p])),
           locByName: new Map(localities.map((l) => [l.name, l])) };
}

const personal = {
  get saved() { return new Set(store.get("saved", [])); },
  set saved(s) { store.set("saved", [...s]); },
  get searches() { return store.get("searches", []); },
  set searches(v) { store.set("searches", v); },
  get mine() { return store.get("mine", []); },
  set mine(v) { store.set("mine", v); },
  get hidden() { return new Set(store.get("hidden", [])); },
  set hidden(s) { store.set("hidden", [...s]); },
};

// --- Formatting -----------------------------------------------------------

const SYM = { MUR: "Rs ", EUR: "€", USD: "$" };
const M2 = { m2: 1, perch: 42.21, toise: 3.7987, arpent: 4220.87 };
const BANDS = { MUR: [5e6, 15e6, 30e6], EUR: [1e5, 3e5, 6e5], USD: [1e5, 3e5, 7e5] };
const state = {
  filters: { type: null, regions: new Set(), q: "", band: null, beds: null, foreign: false, projects: false },
  prefs: store.get("prefs", { currency: "MUR", unit: "m2" }),
  data: null, view: "map", count: 0, stack: null,
};

function rate(c) { return c === "MUR" ? 1 : Number(state.data?.meta.fx.mur_per_unit[c]); }
function convert(m, to) {
  if (!state.data || m.currency === to || !rate(to) || !rate(m.currency)) return m;
  return { minor: Math.round((m.minor * rate(m.currency)) / rate(to)), currency: to };
}
const money = (m) => SYM[m.currency] + Math.round(m.minor / 100).toLocaleString("en-US");
function short(m) {
  const v = m.minor / 100;
  const s = v >= 1e6 ? +(v / 1e6).toFixed(v >= 1e8 ? 0 : 1) + "M" : v >= 1e3 ? Math.round(v / 1e3) + "k" : Math.round(v);
  return SYM[m.currency] + s;
}
const shown = (m) => money(convert(m, state.prefs.currency));
function area(m2, unit) {
  const v = m2 / M2[unit];
  const n = v >= 100 ? Math.round(v).toLocaleString("en-US") : v >= 10 ? v.toFixed(1) : v.toFixed(2);
  return `${n} ${t("unit_" + unit)}`;
}
function sizeText(c) {
  const parts = [];
  if (c.bedrooms && c.type !== "land") parts.push(t("bed", { n: c.bedrooms }));
  if (c.floor_m2 && c.type !== "land") parts.push(area(c.floor_m2, "m2"));
  if (c.plot_m2) parts.push(area(c.plot_m2, state.prefs.unit));
  return parts.join(" · ");
}
const roundLabel = (v, cur) => SYM[cur] + (v >= 1e6 ? +(v / 1e6).toFixed(1) + "M" : Math.round(v / 1e3) + "k");
function bandLabel(i) {
  const [a, b, c] = BANDS[state.prefs.currency], cur = state.prefs.currency;
  return [`${t("under")} ${roundLabel(a, cur)}`, `${roundLabel(a, cur)}–${roundLabel(b, cur)}`,
          `${roundLabel(b, cur)}–${roundLabel(c, cur)}`, `${roundLabel(c, cur)}+`][i];
}

/** The current filters in the saved-search format (budgets in the display currency). */
function currentFilters() {
  const f = state.filters, cur = state.prefs.currency, b = BANDS[cur];
  const [lo, hi] = f.band === null ? [null, null] : [[null, b[0]], [b[0], b[1]], [b[1], b[2]], [b[2], null]][f.band];
  return { type: f.type, regions: [...f.regions], q: f.q.trim() || null, currency: cur,
           min_price: lo, max_price: hi, min_bedrooms: f.beds, foreign_eligible: f.foreign };
}

/** Mirrors backend/mpf/search.py and the Android Search.matches. */
function matches(p, f) {
  if (f.type && p.type !== f.type) return false;
  if (f.regions?.length && !f.regions.includes(p.region)) return false;
  const murMinor = (whole) => convert({ minor: whole * 100, currency: f.currency }, "MUR").minor;
  if (f.min_price != null && p.price_mur_minor < murMinor(f.min_price)) return false;
  if (f.max_price != null && p.price_mur_minor >= murMinor(f.max_price)) return false;
  if (f.min_bedrooms && (p.type === "land" || (p.bedrooms || 0) < f.min_bedrooms)) return false;
  if (f.foreign_eligible && !p.foreign_eligible) return false;
  const q = norm(f.q);
  if (q) {
    const text = p.search_text || norm(`${p.locality} ${p.district}`), words = text.split(" ");
    if (!text.includes(q) && !q.split(" ").every((w) => words.some((x) => x.startsWith(w)))) return false;
  }
  return true;
}

function allProperties() {
  const hidden = personal.hidden;
  return [...(state.data?.properties ?? []), ...personal.mine].filter((p) => !hidden.has(p.id));
}
const findById = (id) => state.data?.byId.get(id) ?? personal.mine.find((m) => m.id === id);

function costs(price, depositPct, termYears) {
  const r = Number(state.data.meta.assumed_mortgage_rate_pct) / 1200, n = termYears * 12;
  const loan = (price.minor * (100 - depositPct)) / 100;
  const monthly = r === 0 ? loan / n : (loan * r) / (1 - (1 + r) ** -n);
  const upfront = (price.minor * depositPct) / 100 + (price.minor * Number(state.data.meta.rules.registration_duty_pct)) / 100;
  const m = (x) => ({ minor: Math.round(x), currency: price.currency });
  return { loan: m(loan), monthly: m(monthly), upfront: m(upfront), deposit_pct: depositPct, term_years: termYears,
           assumed_rate_pct: state.data.meta.assumed_mortgage_rate_pct, registration_duty_pct: state.data.meta.rules.registration_duty_pct };
}

function toast(msg) {
  const el = $("#toast"); el.textContent = msg; el.hidden = false;
  clearTimeout(toast.timer); toast.timer = setTimeout(() => (el.hidden = true), 2800);
}

// --- Cards ----------------------------------------------------------------

const projectPlace = (p) => ` ${norm(p.location)} `.includes(` ${norm(p.district)} `) ? p.location : `${p.location} · ${p.district}`;

/** An official EDB approved project: a development open to foreign buyers, with no price. */
function projectCardHtml(p) {
  return `<button class="card" data-open="${esc(p.id)}"><div class="body">
      <div><span class="tag ok">${esc(p.scheme)}</span> <span class="tag">${esc(p.status)}</span></div>
      <strong>${esc(p.developer)}</strong>
      <span>${esc(p.project)}</span>
      <span class="muted">${esc(projectPlace(p))}</span>
    </div></button>`;
}

function cardHtml(c) {
  if (c.scheme) return projectCardHtml(c);
  const isNew = Date.now() - Date.parse(c.first_seen_at) < 3 * 864e5;
  return `<button class="card" data-open="${esc(c.id)}">
    ${c.thumb && !c.mine ? `<img src="${esc(c.thumb)}" alt="" loading="lazy">` : ""}
    <div class="body">
      <div><span class="price">${esc(shown(c.price))}</span> ${c.price_dropped ? `<span class="tag drop">${t("dropped")}</span>` : ""}</div>
      <strong>${esc(c.title)}</strong>
      <span class="muted">${esc([c.locality, sizeText(c)].filter(Boolean).join(" · "))}</span>
      <div class="tags">
        ${c.mine ? `<span class="tag warn">${t("only_here")}</span>` : ""}
        ${c.foreign_eligible ? `<span class="tag ok">${t("foreigners")}</span>` : ""}
        ${!c.verified && !c.mine ? `<span class="tag warn">${t("unverified")}</span>` : ""}
        ${isNew ? `<span class="tag new">${t("new")}</span>` : ""}
        <span class="tag">${(c.sources?.length || 1) === 1 ? t("on_site") : t("on_sites", { n: c.sources.length })}</span>
      </div>
    </div></button>`;
}
document.addEventListener("click", (e) => {
  const open = e.target.closest("[data-open]");
  if (open) openDetail(open.dataset.open);
});

// --- Map and list -----------------------------------------------------------

const map = new maplibregl.Map({
  container: "map", style: "https://tiles.openfreemap.org/styles/liberty",
  center: [57.55, -20.25], zoom: 9, attributionControl: { compact: true },
});
map.dragRotate.disable(); map.touchZoomRotate.disableRotation();
let markers = [];

const mercY = (lat) => (180 / Math.PI) * Math.log(Math.tan(Math.PI / 4 + (lat * Math.PI) / 360));

/** Same grouping as the backend and the Android app: pins, clusters, and stacks at one point. */
function clusters(items, zoom, cellPx = 64) {
  const cell = (360 / (256 * 2 ** zoom)) * cellPx, cells = new Map();
  for (const p of items) {
    const key = `${Math.floor(p.lon / cell)},${Math.floor(mercY(p.lat) / cell)}`;
    (cells.get(key) ?? cells.set(key, []).get(key)).push(p);
  }
  return [...cells.values()].map((g) => {
    if (g.length === 1) return { kind: "pin", id: g[0].id, lat: g[0].lat, lon: g[0].lon, price: g[0].price, scheme: g[0].scheme, unverified: !g[0].verified && !g[0].scheme };
    const same = new Set(g.map((p) => `${p.lat.toFixed(6)},${p.lon.toFixed(6)}`)).size === 1;
    return { kind: same ? "stack" : "cluster", count: g.length, ids: same ? g.map((p) => p.id) : null,
             lat: g.reduce((a, p) => a + p.lat, 0) / g.length, lon: g.reduce((a, p) => a + p.lon, 0) / g.length };
  });
}

function found() {
  const f = currentFilters();
  if (state.filters.projects) return (state.data?.projects ?? []).filter((p) => matchesProject(p, f));
  return allProperties().filter((p) => matches(p, f));
}
function matchesProject(p, f) {
  if (f.regions?.length && !f.regions.includes(p.region)) return false;
  const q = norm(f.q), words = p.search_text.split(" ");
  return !q || p.search_text.includes(q) || q.split(" ").every((w) => words.some((x) => x.startsWith(w)));
}

function loadMap() {
  const b = map.getBounds(), all = found();
  const visible = all.filter((p) => p.lat >= b.getSouth() && p.lat <= b.getNorth() && p.lon >= b.getWest() && p.lon <= b.getEast());
  markers.forEach((m) => m.remove());
  markers = clusters(visible, map.getZoom()).map((it) => {
    const el = document.createElement("div");
    if (it.kind === "pin") {
      el.className = "pin" + (it.unverified ? " unverified" : "") + (it.scheme ? " project" : "");
      el.textContent = it.scheme ?? short(convert(it.price, state.prefs.currency));
      el.onclick = (e) => { e.stopPropagation(); preview(it.id); };
    } else {
      el.className = "group"; el.textContent = it.count;
      el.onclick = (e) => {
        e.stopPropagation();
        if (it.kind === "stack") showList(it.ids); else map.easeTo({ center: [it.lon, it.lat], zoom: map.getZoom() + 2 });
      };
    }
    return new maplibregl.Marker({ element: el, anchor: "bottom" }).setLngLat([it.lon, it.lat]).addTo(map);
  });
  state.count = all.length;
  renderChrome();
}
map.on("moveend", () => state.view === "map" && state.data && loadMap());
map.on("click", () => ($("#preview").hidden = true));

function preview(id) {
  const p = findById(id) ?? state.data?.projectById.get(id), el = $("#preview");
  el.innerHTML = p ? cardHtml(p) : ""; el.hidden = !p;
}

function sortList(list) {
  return [...list].sort((a, b) => b.first_seen_at.localeCompare(a.first_seen_at));
}
function showList(ids = null) {
  state.view = "list"; state.stack = ids;
  const all = found(), list = ids ? all.filter((p) => ids.includes(p.id)) : all;
  state.count = all.length;
  const sorted = state.filters.projects ? [...list].sort((a, b) => a.developer.localeCompare(b.developer)) : sortList(list);
  const intro = state.filters.projects ? `<div class="panel">${t("projects_intro")}</div>`
    : !state.data?.properties.length ? `<div class="panel">${t("no_listings_yet")}</div>` : "";
  $("#list").innerHTML = `<div class="cards">${intro}${list.length ? sorted.map(cardHtml).join("") : `<p>${t("no_results")}</p>`}</div>`;
  renderChrome();
}
function refresh() { if (state.data) (state.view === "map" ? loadMap() : showList(state.stack)); }

function renderChrome() {
  const f = state.filters;
  $("#map").hidden = state.view !== "map"; $("#list").hidden = state.view !== "list";
  $(".search").classList.toggle("opaque", state.view === "list");
  $("#preview").hidden = state.view !== "map" || $("#preview").hidden;
  $("#toggleView").textContent = state.view === "map" ? t(f.projects ? "list_projects" : "list_n", { n: state.count }) : t("map");
  $("#saveSearch").hidden = f.projects;
  const active = [f.type, f.regions.size, f.band !== null, f.beds, f.foreign].filter(Boolean).length;
  $("#filterCount").hidden = !active; $("#filterCount").textContent = active;
  $("#quickChips").innerHTML = ["land", "apartment", "house"].map((ty) =>
    `<button class="chip ${f.type === ty ? "on" : ""}" data-type="${ty}">${t(ty)}</button>`).join("") +
    `<button class="chip ${f.foreign ? "on" : ""}" data-foreign>${t("foreigners")}</button>` +
    (state.data?.projects.length ? `<button class="chip ${f.projects ? "on" : ""}" data-projects>${t("projects")}</button>` : "");
}
$("#quickChips").addEventListener("click", (e) => {
  const b = e.target.closest("button"); if (!b) return;
  if (b.dataset.type) state.filters.type = state.filters.type === b.dataset.type ? null : b.dataset.type;
  if (b.hasAttribute("data-foreign")) state.filters.foreign = !state.filters.foreign;
  // Projects and listings are separate views; picking a listing filter leaves the projects view.
  if (b.hasAttribute("data-projects")) state.filters.projects = !state.filters.projects;
  else state.filters.projects = false;
  $("#preview").hidden = true;
  refresh();
});
$("#toggleView").onclick = () => { if (state.view === "map") showList(); else { state.view = "map"; renderChrome(); loadMap(); } };
let qTimer;
$("#q").addEventListener("input", (e) => { state.filters.q = e.target.value; clearTimeout(qTimer); qTimer = setTimeout(refresh, 250); });
$("#saveSearch").onclick = () => {
  const f = currentFilters(), searches = personal.searches;
  if (!searches.some((s) => JSON.stringify(s.filters) === JSON.stringify(f))) {
    personal.searches = [...searches, { id: crypto.randomUUID(), filters: f, seen: allProperties().filter((p) => matches(p, f)).map((p) => p.id) }];
  }
  toast(t("search_saved"));
};

// --- Filters sheet --------------------------------------------------------

function chips(group, options, isOn) {
  return `<div class="wrap">${options.map(([v, label]) => `<button class="chip ${isOn(v) ? "on" : ""}" data-g="${group}" data-v="${esc(v)}">${esc(label)}</button>`).join("")}</div>`;
}
function openFilters() {
  const f = state.filters, s = $("#sheet");
  s.innerHTML = `<div class="inner">
    <div class="row" style="align-items:center"><h2 style="margin:0;flex:1">${t("filters")}</h2><button class="btn secondary" data-reset>${t("reset")}</button></div>
    <div><strong>${t("region")}</strong>${chips("region", ["North", "West", "Centre", "East", "South"].map((r) => [r, r]), (v) => f.regions.has(v))}</div>
    <div><strong>${t("budget")}</strong>${chips("band", [["", t("any")], ...[0, 1, 2, 3].map((i) => [i, bandLabel(i)])], (v) => String(f.band ?? "") === String(v))}</div>
    <div><strong>${t("bedrooms")}</strong>${chips("beds", [["", t("any")], ...[1, 2, 3, 4].map((n) => [n, n + "+"])], (v) => String(f.beds ?? "") === String(v))}</div>
    <div><strong>${t("plot_units")}</strong>${chips("unit", Object.keys(M2).map((u) => [u, t("unit_" + u)]), (v) => state.prefs.unit === v)}</div>
    <div><strong>${t("prices_in")}</strong>${chips("cur", [["MUR", "Rs"], ["EUR", "€"], ["USD", "$"]], (v) => state.prefs.currency === v)}</div>
    <button class="btn" data-close>${t("show_n", { n: state.count })}</button></div>`;
  if (!s.open) s.showModal();
}
$("#filtersBtn").onclick = openFilters;
$("#sheet").addEventListener("click", (e) => {
  const b = e.target.closest("button"), f = state.filters;
  if (e.target === $("#sheet") || b?.hasAttribute("data-close")) return $("#sheet").close();
  if (!b) return;
  if (b.hasAttribute("data-reset")) Object.assign(f, { type: null, regions: new Set(), band: null, beds: null, foreign: false });
  const { g, v } = b.dataset;
  if (g === "region") f.regions.has(v) ? f.regions.delete(v) : f.regions.add(v);
  if (g === "band") f.band = v === "" ? null : Number(v);
  if (g === "beds") f.beds = v === "" ? null : Number(v);
  if (g === "unit") state.prefs.unit = v;
  if (g === "cur") state.prefs.currency = v;
  store.set("prefs", state.prefs);
  refresh(); openFilters();
});

// --- Search everywhere -------------------------------------------------------
// The same search on the listing sites and social networks, as links. Linking is all this
// does: nothing is copied from those sites, and the listings open on the sites themselves.
const REGION_FR = { North: "Nord", West: "Ouest", Centre: "Centre", East: "Est", South: "Sud" };

/** Mirrors the Android domain/Elsewhere.kt. */
function elsewhereLinks() {
  const f = state.filters, cf = currentFilters(), q = norm(f.q);
  const loc = q ? state.data?.localities.find((l) => norm(l.name) === q || l.aliases.some((a) => norm(a) === q)) : null;
  const region = loc?.region ?? (f.regions.size === 1 ? [...f.regions][0] : null);
  const place = loc?.name ?? (q ? f.q.trim() : region ? REGION_FR[region] : "");
  const mur = (whole) => (whole == null ? null : Math.round(convert({ minor: whole * 100, currency: cf.currency }, "MUR").minor / 100));
  const lo = mur(cf.min_price), hi = mur(cf.max_price);
  const text = `${{ land: "terrain", house: "maison", apartment: "appartement" }[f.type] ?? "immobilier"} à vendre ${place} Maurice`.replace(/\s+/g, " ");
  const url = (base, params = {}) => {
    const u = new URL(base);
    for (const [k, v] of Object.entries(params)) if (v != null && v !== "") u.searchParams.set(k, v);
    return u.href;
  };
  const lexTypes = { land: "residential_land-agricultural_land", apartment: "apartment-penthouse", house: "villa-townhouse_duplex" }[f.type]
    ?? "villa-townhouse_duplex-apartment-penthouse-residential_land";
  const pcType = { land: "land", apartment: "apartments", house: "houses" }[f.type] ?? "property";
  return [
    { name: "lexpressproperty.com", note: t("ew_filters"),
      url: url(`https://www.lexpressproperty.com/en/buy-mauritius/${lexTypes}/`, { city: loc?.lexpress_city ?? region?.toLowerCase(),
        price_min: lo, price_max: hi, currency: lo || hi ? "MUR" : null, foreigners: f.foreign ? "yes" : null }) },
    { name: "PropertyCloud", note: t("ew_region"),
      url: `https://www.propertycloud.mu/${pcType}-for-sale${region ? "/" + region.toLowerCase() : ""}${f.foreign ? "/foreigners" : ""}` },
    { name: "Westimmo", note: t("ew_type"),
      url: f.type === "land" ? "https://real-estate-mauritius.mu/en/buy-invest-mauritius/land-for-sale" : "https://real-estate-mauritius.mu/en/buy-property-mauritius/" },
    { name: t("ew_fb_posts"), note: t("ew_text", { q: text }), url: url("https://www.facebook.com/search/posts/", { q: text }) },
    { name: "Facebook Marketplace", note: t("ew_text", { q: text }), url: url("https://www.facebook.com/marketplace/search/", { query: text }) },
    { name: "Instagram", note: t("ew_text", { q: text }), url: url("https://www.instagram.com/explore/search/keyword/", { q: text }) },
    { name: "Google", note: t("ew_text", { q: text }), url: url("https://www.google.com/search", { q: text }) },
  ];
}
function openElsewhere() {
  const s = $("#sheet");
  s.innerHTML = `<div class="inner">
    <div class="row" style="align-items:center"><h2 style="margin:0;flex:1">${t("everywhere")}</h2><button class="btn secondary" data-close>${t("close")}</button></div>
    <div class="muted">${t("everywhere_intro")}</div>
    ${elsewhereLinks().map((l) => `<a class="panel elsewhere" href="${esc(l.url)}" target="_blank" rel="noopener noreferrer">
      <strong>${esc(l.name)} ↗</strong><span class="muted">${esc(l.note)}</span></a>`).join("")}
    <div class="muted">${t("everywhere_add")}</div></div>`;
  if (!s.open) s.showModal();
}
$("#elsewhere").onclick = openElsewhere;

// --- Detail ---------------------------------------------------------------

function eligibilityText(e) {
  const scheme = (e.route || "").replace("scheme:", "").replace("SMART_CITY", "Smart City");
  return { open_scheme: t("open_scheme", { s: scheme }) }[e.reason] ?? t(e.reason === "citizens_and_permit_holders" ? "citizens" : e.reason);
}
function openProject(p) {
  const dlg = $("#detail"), link = (href, label) =>
    `<a class="btn secondary" style="display:grid;place-items:center;text-decoration:none" href="${esc(href)}" target="_blank" rel="noopener">${esc(label)}</a>`;
  dlg.innerHTML = `<div class="inner">
    <div class="row"><button class="btn secondary" data-close>${t("close")}</button></div>
    <div><div class="row" style="gap:6px"><span class="tag ok">${esc(p.scheme)}</span><span class="tag">${esc(p.status)}</span></div>
      <h2 style="margin:6px 0">${esc(p.developer)}</h2>
      <div class="muted">${esc(projectPlace(p) + " · " + p.region)}</div>
      <div class="muted">${t("village_centre")}</div></div>
    <div class="panel"><h3>${t("project")}</h3><div>${esc(p.project)}</div></div>
    <div class="panel"><div class="row" style="justify-content:space-between"><h3>${t("can_foreigners")}</h3><span class="tag ok">${t("yes")}</span></div>
      <div>${esc(t("project_scheme", { s: p.scheme_name }))}</div></div>
    ${p.website ? link(p.website, t("developer_site")) : ""}
    <div class="panel"><h3>${t("project_source")}</h3><div class="muted">${t("project_source_note")}</div>${link(p.source_url, t("open_edb"))}</div>
  </div>`;
  delete dlg.dataset.id;
  if (!dlg.open) dlg.showModal();
}

function openDetail(id, deposit = 20, termYears = 20) {
  const project = state.data?.projectById.get(id);
  if (project) return openProject(project);
  const d = findById(id);
  if (!d) return toast(t("gone"));
  const dlg = $("#detail"), e = d.eligibility, pc = d.price_check, c = costs(d.price, deposit, termYears);
  const areaPhotos = state.data?.locByName.get(d.locality)?.area_photos ?? [];
  const saved = personal.saved.has(d.id);
  const pill = !e ? "" : e.status === "yes" ? `<span class="tag ok">${t("yes")}</span>` : e.status === "unknown" ? `<span class="tag warn">${t("unknown")}</span>` : `<span class="tag">${t("no")}</span>`;
  const mins = (Date.now() - Date.parse(d.last_checked_at)) / 6e4;
  const ago = mins < 60 ? Math.round(mins) + "m" : mins < 2880 ? Math.round(mins / 60) + "h" : Math.round(mins / 1440) + "d";
  const pcText = !pc ? "" : !pc.available ? t("pc_none", { n: pc.comparables, m: pc.minimum ?? 5 })
    : pc.diff_pct <= -3 ? t("pc_below", { p: -pc.diff_pct, r: pc.scope, n: pc.comparables })
    : pc.diff_pct >= 3 ? t("pc_above", { p: pc.diff_pct, r: pc.scope, n: pc.comparables }) : t("pc_inline", { r: pc.scope, n: pc.comparables });
  dlg.innerHTML = `<div class="inner">
    <div class="row" style="justify-content:space-between">
      <button class="btn secondary" data-close>${t("close")}</button>
      ${d.mine ? "" : `<button class="btn ${saved ? "" : "secondary"}" data-save>${saved ? t("saved") : "♡ " + t("save")}</button>`}
    </div>
    ${d.photos.length && !d.mine ? `<div class="gallery">${d.photos.map((p) => `<img src="${esc(p.url)}" alt="" title="${esc(p.credit || t("seller_photo"))}">`).join("")}</div>` : ""}
    <div><div class="price" style="font-size:28px">${esc(shown(d.price))}</div>
      <h2 style="margin:4px 0">${esc(d.title)}</h2>
      <div class="muted">${esc([d.locality, d.district, d.region].filter(Boolean).join(" · "))}</div>
      ${d.location_precision === "locality" ? `<div class="muted">${t("village_centre")}</div>` : ""}</div>
    ${d.mine ? `<div class="panel">${t("only_here_note")}</div>` : d.verified ? "" : `<div class="panel" style="background:var(--warn-bg);color:var(--warn)">${t("unverified_note")}</div>`}
    ${e ? `<div class="panel"><div class="row" style="justify-content:space-between"><h3>${t("can_foreigners")}</h3>${pill}</div>
      <div>${esc(eligibilityText(e))}</div>${e.residency ? `<div>${t("residency")}</div>` : ""}
      <div class="muted">${esc(t("rules", { r: e.rules_version }))}</div></div>` : ""}
    <div class="panel facts">
      <span class="muted">${t("size")}</span><strong>${esc(sizeText(d))}</strong>
      ${d.mine ? "" : `<span class="muted">${t("coast")}</span><strong>${(d.coast_distance_m / 1000).toFixed(1)} km</strong>
      <span class="muted">${t("checked")}</span><strong>${t("checked_ago", { t: ago })}</strong>`}</div>
    ${areaPhotos.length ? `<div class="panel"><h3>${t("area_photos")}</h3><div class="muted">${t("area_note")}</div>
      <div class="area">${areaPhotos.map((p) => `<a href="${esc(p.page_url)}" target="_blank" rel="noopener"><img src="${esc(p.url)}" alt="" loading="lazy">${esc(p.author)} · ${esc(p.license)}</a>`).join("")}</div></div>` : ""}
    ${pc ? `<div class="panel"><h3>${t("price_check")}</h3><div>${esc(pcText)}</div></div>` : ""}
    ${d.history.length ? `<div class="panel"><h3>${t("history")}</h3>${d.history.map((h) =>
      `<div class="row" style="justify-content:space-between"><span>${h.kind === "first_seen" ? t("first_seen") : t("price_change")} · ${new Date(h.at).toLocaleDateString(LANG)}<br><span class="muted">${esc(h.source)}</span></span><strong>${esc(shown(h.price))}</strong></div>`).join("")}</div>` : ""}
    <div class="panel"><h3>${t("cost")}</h3><div class="price">${esc(t("per_month", { a: shown(c.monthly) }))}</div>
      <div class="muted">${esc(t("loan", { a: shown(c.loan), y: c.term_years }))}</div>
      <strong>${t("deposit")}</strong>${chips("dep", [10, 20, 30, 40].map((v) => [v, v + "%"]), (v) => v === c.deposit_pct)}
      <strong>${t("term")}</strong>${chips("term", [15, 20, 25].map((v) => [v, t("years", { y: v })]), (v) => v === c.term_years)}
      <strong>${esc(t("upfront", { a: shown(c.upfront) }))}</strong>
      <div class="muted">${esc(t("upfront_note", { d: c.registration_duty_pct }))} ${esc(t("cost_note", { r: c.assumed_rate_pct }))}</div></div>
    ${d.coastal ? `<div class="panel">${t("coastal")}</div>` : ""}
    <div class="panel"><h3>${d.sources.length === 1 ? t("sources_1") : t("sources_n", { n: d.sources.length })}</h3>
      ${d.sources.map((s) => `<div class="row" style="justify-content:space-between;align-items:center"><span><strong>${esc(s.agency || s.source)}</strong><br><span class="muted">${esc(s.source)} · ${esc(shown(s.price))}</span></span>
        ${/^https?:\/\//.test(s.url || "") ? `<a class="btn secondary" style="display:grid;place-items:center;text-decoration:none" href="${esc(s.url)}" target="_blank" rel="noopener">${t("open")}</a>` : ""}</div>`).join("")}
      <div class="muted">${t("sources_note")}</div></div>
    <button class="btn secondary" data-hide>${d.mine ? t("delete") : t("hide")}</button></div>`;
  dlg.dataset.id = d.id; dlg.dataset.dep = c.deposit_pct; dlg.dataset.term = c.term_years;
  if (!dlg.open) dlg.showModal();
}
$("#detail").addEventListener("click", (e) => {
  const dlg = $("#detail"), b = e.target.closest("button"), id = dlg.dataset.id;
  if (e.target === dlg || b?.hasAttribute("data-close")) return dlg.close();
  if (!b) return;
  if (b.dataset.g === "dep") openDetail(id, Number(b.dataset.v), Number(dlg.dataset.term));
  if (b.dataset.g === "term") openDetail(id, Number(dlg.dataset.dep), Number(b.dataset.v));
  if (b.hasAttribute("data-save")) {
    const s = personal.saved, prices = store.get("lastSeenPrices", {});
    if (s.has(id)) { s.delete(id); delete prices[id]; } else { s.add(id); prices[id] = findById(id).price_mur_minor; }
    personal.saved = s; store.set("lastSeenPrices", prices);
    openDetail(id, Number(dlg.dataset.dep), Number(dlg.dataset.term));
  }
  if (b.hasAttribute("data-hide")) {
    if (id.startsWith("mine-")) personal.mine = personal.mine.filter((m) => m.id !== id);
    else { const h = personal.hidden; h.add(id); personal.hidden = h; }
    dlg.close(); refresh();
  }
});

// --- Saved and alerts -----------------------------------------------------

/** What changed since the last visit for saved searches and saved properties. */
function newsSinceLastVisit() {
  const notes = [], all = state.data.properties, prices = store.get("lastSeenPrices", {});
  personal.searches = personal.searches.map((s) => {
    const ids = all.filter((p) => matches(p, s.filters)).map((p) => p.id);
    const fresh = ids.filter((id) => !(s.seen || []).includes(id));
    if (s.seen && fresh.length) notes.push(t("news_matches", { n: fresh.length }));
    return { ...s, seen: ids };
  });
  for (const id of personal.saved) {
    const p = state.data.byId.get(id);
    if (!p) notes.push(t("news_gone"));
    else if (prices[id] && p.price_mur_minor < prices[id]) notes.push(t("news_drop", { title: p.title }));
    if (p) prices[id] = p.price_mur_minor;
  }
  store.set("lastSeenPrices", prices);
  return notes;
}

function loadSaved() {
  const all = allProperties(), saved = [...personal.saved].map(findById).filter(Boolean);
  $("#savedPage").innerHTML = `<h1>${t("saved_title")}</h1>
    <h3>${t("searches")}</h3>
    ${personal.searches.length ? personal.searches.map((s) => `<div class="panel row" style="justify-content:space-between;margin-bottom:8px">
        <span><strong>${esc([s.filters.type ? t(s.filters.type) : t("all"), ...(s.filters.regions || []), s.filters.q && `“${s.filters.q}”`, s.filters.foreign_eligible && t("foreigners")].filter(Boolean).join(" · "))}</strong><br>
        <span class="muted">${t("matches", { n: all.filter((p) => matches(p, s.filters)).length })}</span></span>
        <button class="btn secondary" data-del="${esc(s.id)}">${t("delete")}</button></div>`).join("") : `<p class="muted">${t("searches_empty")}</p>`}
    <h3>${t("homes")}</h3>
    <div class="cards" style="display:grid;gap:12px">${saved.length ? saved.map(cardHtml).join("") : `<p class="muted">${t("homes_empty")}</p>`}</div>
    ${personal.mine.length ? `<h3>${t("my_listings")}</h3><div class="cards" style="display:grid;gap:12px">${personal.mine.map(cardHtml).join("")}</div>` : ""}`;
}
$("#savedPage").addEventListener("click", (e) => {
  const b = e.target.closest("[data-del]"); if (!b) return;
  personal.searches = personal.searches.filter((s) => s.id !== b.dataset.del); loadSaved();
});

// --- Add a post (kept in this browser) ------------------------------------

const UNIT = (w) => (w.startsWith("perch") || w === "lapers" ? "perch" : w.startsWith("toise") ? "toise" : w.startsWith("arpent") ? "arpent" : "m2");
const NUM = "(\\d{1,3}(?:[ ,.]\\d{3})+|\\d+(?:[.,]\\d+)?)";
function number(d, suffix) {
  d = d.replace(/ /g, "");
  let v = /^\d{1,3}([.,]\d{3})+$/.test(d) ? Number(d.replace(/[.,]/g, "")) : Number(d.replace(",", "."));
  if (suffix) v *= suffix.startsWith("k") ? 1e3 : 1e6;
  return Math.round(v);
}
/** Mirrors the Android PostReader: facts only, never names or phone numbers. */
function readPost(text) {
  const low = text.toLowerCase().replace(/²/g, "2").normalize("NFD").replace(/\p{Mn}+/gu, ""), n = norm(text);
  const type = /\b(appartement|apartment|flat|penthouse|duplex)\b/.test(n) ? "apartment"
    : /\b(maison|villa|house|bungalow|lakaz)\b/.test(n) ? "house" : "land";
  const s = low.match(/(\d+(?:[.,]\d+)?)\s*(m2|sqm|metres? carres?|perches?|lapers|toises?|arpents?)/);
  let price = null, currency = "MUR";
  for (const [cur, rx] of [["MUR", `(?:rs\\.?|mur|roupies?)\\s*${NUM}\\s*(m\\b|millions?|mil\\b|k\\b)?`],
                           ["EUR", `(?:€|eur(?:os?)?)\\s*${NUM}\\s*(m\\b|millions?|k\\b)?`], ["USD", `(?:\\$|usd)\\s*${NUM}\\s*(m\\b|millions?|k\\b)?`]]) {
    const m = low.match(new RegExp(rx)); if (m) { price = number(m[1], m[2]); currency = cur; break; }
  }
  const place = state.data.localities.flatMap((l) => [l.name, ...l.aliases].map((k) => [norm(k), l]))
    .filter(([k]) => k && new RegExp(`\\b${k.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}\\b`).test(n))
    .sort((a, b) => b[0].length - a[0].length)[0]?.[1];
  return { type, place, size: s ? Number(s[1].replace(",", ".")) : null, unit: s ? UNIT(norm(s[2])) : "m2", price, currency,
           negotiable: /\b(a discuter|negociable|nego|negotiable|ono)\b/.test(n), link: text.match(/https?:\/\/\S+/)?.[0] };
}

function renderPost() {
  const page = $("#postPage");
  page.innerHTML = `<h1>${t("post_title")}</h1><p>${t("post_intro")}</p>
    <textarea id="postText" rows="6"></textarea>
    <p><button class="btn" id="readPost">${t("read_post")}</button></p><p class="muted">${t("no_copy")}</p><div id="draft"></div>`;
  $("#readPost").onclick = () => {
    if (!state.data) return toast(t("error"));
    const text = $("#postText").value, x = readPost(text);
    $("#draft").innerHTML = `<div class="panel" style="gap:10px">
      ${chips("ptype", [["land", t("land")], ["apartment", t("apartment")], ["house", t("house")]], (v) => v === x.type)}
      <label>${t("town")}<input id="dLoc" list="towns" value="${esc(x.place?.name ?? "")}"></label>
      <datalist id="towns">${state.data.localities.map((l) => `<option value="${esc(l.name)}">`).join("")}</datalist>
      <label>${t("size")}<input id="dSize" value="${esc(x.size ?? "")}" inputmode="decimal"></label>
      ${chips("punit", Object.keys(M2).map((u) => [u, t("unit_" + u)]), (v) => v === x.unit)}
      <label>${t("price")}<input id="dPrice" value="${esc(x.price ?? "")}" inputmode="numeric"></label>
      ${chips("pcur", [["MUR", "Rs"], ["EUR", "€"], ["USD", "$"]], (v) => v === x.currency)}
      <button class="btn" id="publish">${t("add")}</button></div>`;
    const pick = (g) => $(`#draft [data-g="${g}"].on`)?.dataset.v;
    $("#draft").onclick = (e) => {
      const b = e.target.closest("[data-g]"); if (!b) return;
      document.querySelectorAll(`#draft [data-g="${b.dataset.g}"]`).forEach((y) => y.classList.toggle("on", y === b));
    };
    $("#publish").onclick = () => {
      const loc = state.data.localities.find((l) => norm(l.name) === norm($("#dLoc").value));
      const size = Number($("#dSize").value.replace(",", ".")), whole = Number($("#dPrice").value.replace(/\D/g, ""));
      if (!loc) return toast(t("unknown_town"));
      if (!size || !whole) return toast(t("error"));
      const type = pick("ptype"), m2 = size * M2[pick("punit")], now = new Date().toISOString();
      const price = { minor: whole * 100, currency: pick("pcur") }, id = "mine-" + crypto.randomUUID();
      personal.mine = [...personal.mine, {
        id, mine: true, type, title: t("mine_" + type), locality: loc.name, district: loc.district, region: loc.region,
        lat: loc.lat, lon: loc.lon, location_precision: "locality", price, price_mur_minor: convert(price, "MUR").minor,
        plot_m2: type === "land" ? m2 : null, floor_m2: type === "land" ? null : m2, negotiable: x.negotiable, verified: false,
        first_seen_at: now, last_checked_at: now, search_text: loc.search_text, photos: [], history: [],
        sources: [{ listing_id: id, source: t("shared_by_you"), kind: "user_post", url: x.link ?? null, price, first_seen_at: now, last_checked_at: now, verified: false }],
      }];
      toast(t("added_here")); renderPost(); openDetail(id);
    };
  };
}

// --- Tabs and start-up ------------------------------------------------------

document.querySelectorAll("[data-t]").forEach((el) => (el.textContent = t(el.dataset.t)));
document.querySelectorAll("[data-tp]").forEach((el) => (el.placeholder = t(el.dataset.tp)));
document.querySelectorAll("[data-ta]").forEach((el) => el.setAttribute("aria-label", t(el.dataset.ta)));
document.querySelector(".tabs").addEventListener("click", (e) => {
  const b = e.target.closest("button"); if (!b) return;
  document.querySelectorAll(".tabs button").forEach((x) => x.classList.toggle("on", x === b));
  document.querySelectorAll(".tab").forEach((x) => x.classList.toggle("active", x.id === b.dataset.tab));
  if (b.dataset.tab === "saved") loadSaved();
  if (b.dataset.tab === "post") renderPost();
  if (b.dataset.tab === "explore") map.resize();
});

renderChrome();
loadData().then((data) => {
  state.data = data;
  // Until listing sources join, open on the official projects rather than an empty map.
  if (!data.properties.length && !personal.mine.length && data.projects.length) state.filters.projects = true;
  const notes = newsSinceLastVisit();
  if (notes.length) toast(notes.join(" "));
  refresh();
}).catch(() => toast(t("offline")));
if ("serviceWorker" in navigator) navigator.serviceWorker.register("sw.js").catch(() => {});
