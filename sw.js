const REVISION = "{{revision}}";
const PRECACHE = {{precache}};
const SCOPE = new URL(self.registration.scope);
const PREFIX = `scratch-buffer:${SCOPE.pathname}:`;
const CACHE = `${PREFIX}${REVISION}`;
const urls = PRECACHE.map(path => new URL(path || "./", SCOPE).href);
const known = new Set(urls);

self.addEventListener("install", event => {
  event.waitUntil(caches.open(CACHE).then(cache => cache.addAll(urls)));
});

self.addEventListener("activate", event => {
  event.waitUntil(
    caches.keys().then(keys => Promise.all(
      keys.filter(key => key.startsWith(PREFIX) && key !== CACHE).map(key => caches.delete(key))
    )).then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", event => {
  const url = new URL(event.request.url);
  if (event.request.method !== "GET" || url.origin !== SCOPE.origin ||
      !url.pathname.startsWith(SCOPE.pathname)) return;
  url.search = "";
  if (event.request.mode === "navigate") {
    event.respondWith((async () => {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), 4000);
      try {
        const response = await fetch(event.request, { signal: controller.signal });
        return response;
      } catch (error) {
        console.info("Offline navigation: using the published cache.", error);
        const cache = await caches.open(CACHE);
        const canonical = new URL(url.href);
        canonical.pathname = canonical.pathname.replace(/\/index\.html$/, "/");
        if (!canonical.pathname.endsWith("/")) canonical.pathname += "/";
        const cached = await cache.match(canonical.href);
        if (cached) return cached;
        const missing = await cache.match(new URL("404.html", SCOPE).href);
        return new Response(await missing.text(), {
          status: 404,
          headers: { "Content-Type": "text/html; charset=utf-8" }
        });
      } finally {
        clearTimeout(timer);
      }
    })());
  } else if (known.has(url.href)) {
    event.respondWith(caches.open(CACHE).then(async cache => {
      const cached = await cache.match(url.href);
      return cached || fetch(event.request);
    }));
  }
});
