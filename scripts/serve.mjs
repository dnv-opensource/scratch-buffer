import { createServer } from "node:http";
import { readFile, stat } from "node:fs/promises";
import { resolve, extname, sep } from "node:path";

const root = resolve("_site");
const base = `/${(process.env.BASE_PATH || "").replace(/^\/+|\/+$/g, "")}/`.replace("//", "/");
const types = {
  ".html": "text/html; charset=utf-8", ".css": "text/css; charset=utf-8",
  ".js": "text/javascript; charset=utf-8", ".cljs": "text/plain; charset=utf-8",
  ".cljc": "text/plain; charset=utf-8", ".edn": "text/plain; charset=utf-8",
  ".png": "image/png", ".svg": "image/svg+xml", ".webmanifest": "application/manifest+json",
  ".map": "application/json; charset=utf-8",
  ".ics": "text/calendar; charset=utf-8"
};
const server = createServer(async (request, response) => {
  try {
    const url = new URL(request.url, "http://localhost");
    if (url.pathname === base.slice(0, -1)) {
      response.writeHead(301, { Location: base }).end();
      return;
    }
    if (!url.pathname.startsWith(base)) {
      response.writeHead(404).end("Not in this site’s base path.");
      return;
    }
    let path = resolve(root, decodeURIComponent(url.pathname.slice(base.length)));
    if (path !== root && !path.startsWith(root + sep)) {
      response.writeHead(403).end("Forbidden");
      return;
    }
    const info = await stat(path);
    if (info.isDirectory()) {
      if (!url.pathname.endsWith("/")) {
        response.writeHead(301, { Location: url.pathname + "/" + url.search }).end();
        return;
      }
      path = resolve(path, "index.html");
    }
    response.writeHead(200, { "Content-Type": types[extname(path)] || "application/octet-stream", "Cache-Control": "no-cache" });
    response.end(await readFile(path));
  } catch (error) {
    if (error.code === "ENOENT" || error.code === "ENOTDIR") {
      response.writeHead(404, { "Content-Type": "text/html; charset=utf-8" });
      response.end(await readFile(resolve(root, "404.html")));
    } else {
      console.error("Static server request failed.", error);
      response.writeHead(500).end("Cannot serve this page.");
    }
  }
});
server.listen(Number(process.env.PORT || 4173), "127.0.0.1", () => {
  console.log(`Serving ${root} at http://127.0.0.1:${server.address().port}${base}`);
});
