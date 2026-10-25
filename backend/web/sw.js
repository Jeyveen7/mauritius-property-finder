// Keeps the app and the last listings snapshot available offline (network first).
const SHELL = "mpf-shell-v2";
const FILES = ["./", "index.html", "style.css", "i18n.js", "app.js", "manifest.webmanifest", "icon.svg"];
self.addEventListener("install", (e) => e.waitUntil(caches.open(SHELL).then((c) => c.addAll(FILES))));
self.addEventListener("activate", (e) => e.waitUntil(
  caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== SHELL).map((k) => caches.delete(k))))));
self.addEventListener("fetch", (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== "GET" || url.origin !== location.origin || !url.pathname.startsWith(new URL(self.registration.scope).pathname)) return;
  // Network first, cache as fallback, so updates show up immediately when online.
  e.respondWith(fetch(e.request).then((r) => {
    const copy = r.clone(); caches.open(SHELL).then((c) => c.put(e.request, copy)); return r;
  }).catch(() => caches.match(e.request)));
});
