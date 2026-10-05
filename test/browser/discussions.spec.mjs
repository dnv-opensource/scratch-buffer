import { test, expect } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { execFileSync } from "node:child_process";

const repository = JSON.parse(execFileSync("bb", ["-e",
  "(require '[clojure.edn :as edn] '[cheshire.core :as json]) (println (json/generate-string (:repository (edn/read-string (slurp \"site.edn\")))))"
], { encoding: "utf8" }));
const date = "2026-10-05T14:00:00Z";
const url = `${repository}/discussions/42`;
const snapshot = {
  version: 1, repository, status: "ready", "generated-at": date,
  discussions: [{
    number: 42, title: "A good question", url, body: "<script>window.discussionExecuted = true</script>\nA public conversation.",
    "created-at": date, "updated-at": date,
    author: { login: "reader", url: "https://github.com/reader" },
    comments: [{
      url: `${url}#discussioncomment-1`, body: "A useful comment.", "created-at": date, author: null,
      replies: [{
        url: `${url}#discussioncomment-2`, body: "A useful reply.", "created-at": date,
        author: { login: "another-reader", url: "https://github.com/another-reader" }
      }]
    }]
  }]
};

async function ready(page, path = "snippets/eglot/") {
  await page.goto(path);
  await expect(page.locator("html")).toHaveAttribute("data-enhanced", "true");
}

async function linkThread(page, number = 42) {
  await page.evaluate(number => window.scittle.core.eval_string(`
    (swap! scratch.app/app update-in [:content :snippets]
           (fn [records] (mapv #(if (= "eglot" (:id %)) (assoc % :discussion-number ${number}) %) records)))
  `), number);
}

test.describe("discussion snapshots", () => {
  test.use({ serviceWorkers: "block" });

  test("shows loading on buffer open, reads safely, and reuses the snapshot through navigation", async ({ page, baseURL }) => {
    let release;
    const pending = new Promise(resolve => { release = resolve; });
    let requests = 0;
    const externalRequests = [];
    page.on("request", request => {
      if (new URL(request.url()).origin !== new URL(baseURL).origin) externalRequests.push(request.url());
    });
    await page.route("**/data/generated/discussions.json", async route => {
      requests++;
      expect(route.request().headers().authorization).toBeUndefined();
      await pending;
      await route.fulfill({ json: snapshot });
    });
    await ready(page);
    const panel = page.locator(".discussion-note");
    await expect(panel).toHaveAttribute("aria-busy", "true");
    await expect(panel.getByRole("status")).toContainText("Loading discussion snapshot...");
    await expect(panel.locator(".loading-indicator")).toBeVisible();
    release();
    await expect(panel).toHaveAttribute("aria-busy", "false");
    await expect(panel.getByRole("status")).toContainText("Read-only snapshot / Updated 2026-10-05 14:00:00 UTC");
    const summary = panel.locator("summary");
    await summary.focus();
    await page.keyboard.press("Enter");
    await expect(panel.locator(".discussion-body").first()).toHaveText(snapshot.discussions[0].body);
    await expect(panel).toContainText("A useful comment.");
    await expect(panel).toContainText("A useful reply.");
    await expect(panel).toContainText("Deleted GitHub account");
    await expect(panel.locator("script, img, iframe")).toHaveCount(0);
    expect(await page.evaluate(() => window.discussionExecuted)).toBeUndefined();
    await page.getByRole("navigation", { name: "Main navigation" }).getByRole("link", { name: "About", exact: true }).click();
    await expect(panel.getByRole("status")).toContainText("Read-only snapshot");
    expect(requests).toBe(1);
    expect(externalRequests).toEqual([]);
  });

  test("linked threads show all saved comments and keep the correct GitHub posting link", async ({ page }) => {
    await page.route("**/data/generated/discussions.json", route => route.fulfill({ json: snapshot }));
    await ready(page);
    await linkThread(page);
    const panel = page.locator(".discussion-note");
    await expect(panel.getByRole("heading", { name: "Discussion", exact: true })).toBeVisible();
    await expect(panel.getByRole("heading", { name: "A good question", exact: true })).toBeVisible();
    await expect(panel).toContainText("A useful reply.");
    await expect(panel.getByRole("link", { name: "Discuss this page on GitHub", exact: true })).toHaveAttribute("href", url);
    await expect(panel.getByRole("link", { name: "Reply on GitHub", exact: true })).toHaveAttribute("href", url);
    await linkThread(page, 99);
    await expect(panel).toContainText("This thread is not in the published snapshot");
    await expect(panel.getByRole("link", { name: "Discuss this page on GitHub", exact: true }))
      .toHaveAttribute("href", `${repository}/discussions/99`);
  });

  for (const failure of ["HTTP", "invalid JSON", "unsafe URL", "network"]) {
    test(`${failure} failure is explicit and supports retry without losing the buffer`, async ({ page }) => {
      let requests = 0;
      const warnings = [];
      page.on("console", message => { if (message.type() === "warning") warnings.push(message.text()); });
      await page.route("**/data/generated/discussions.json", route => {
        requests++;
        if (requests > 1) return route.fulfill({ json: snapshot });
        if (failure === "HTTP") return route.fulfill({ status: 503, body: "Unavailable" });
        if (failure === "invalid JSON") return route.fulfill({ contentType: "application/json", body: "{broken" });
        if (failure === "network") return route.abort();
        return route.fulfill({ json: {
          ...snapshot, discussions: [{ ...snapshot.discussions[0], url: "https://github.com.evil.org/thread" }]
        } });
      });
      await ready(page);
      const panel = page.locator(".discussion-note");
      await expect(panel.getByRole("status")).toContainText("Could not load the discussion snapshot");
      await expect(page.getByRole("heading", { name: "Let Eglot do the introductions", exact: true })).toBeVisible();
      await expect(page.locator("pre code")).toContainText("eglot-ensure");
      expect(warnings.some(warning => warning.includes("Could not load the discussion snapshot."))).toBe(true);
      await panel.getByRole("button", { name: "Retry loading discussions", exact: true }).click();
      await expect(panel.getByRole("status")).toContainText("Read-only snapshot");
      await expect(panel.getByRole("button", { name: "Retry loading discussions", exact: true })).toHaveCount(0);
      expect(requests).toBe(2);
      await expect(page).toHaveURL(/\/snippets\/eglot\/$/);
    });
  }

  for (const [status, text] of [
    ["ready", "No discussions in this snapshot yet"],
    ["disabled", "GitHub Discussions were not enabled"],
    ["unavailable", "No discussion snapshot was included"]
  ]) {
    test(`${status} empty state is distinct from a failed request`, async ({ page }) => {
      await page.route("**/data/generated/discussions.json", route => route.fulfill({
        json: { ...snapshot, status, discussions: [], "generated-at": status === "unavailable" ? null : date }
      }));
      await ready(page);
      await expect(page.locator(".discussion-note")).toContainText(text);
      await expect(page.getByRole("button", { name: "Retry loading discussions", exact: true })).toHaveCount(0);
      await expect(page.getByRole("link", { name: "Discuss this page on GitHub", exact: true })).toBeVisible();
    });
  }

  test("limits the recent list clearly and keeps long text accessible across widths and themes", async ({ page }) => {
    const data = { ...snapshot, discussions: Array.from({ length: 7 }, (_, index) => ({
      ...snapshot.discussions[0], number: index + 1, title: `${"Long-unbroken-title".repeat(8)} ${index + 1}`,
      url: `${repository}/discussions/${index + 1}`, comments: [], body: "Long-unbroken-text".repeat(40)
    })) };
    await page.route("**/data/generated/discussions.json", route => route.fulfill({ json: data }));
    await page.emulateMedia({ reducedMotion: "reduce" });
    await ready(page);
    await expect(page.locator(".discussion-thread")).toHaveCount(6);
    await expect(page.locator(".discussion-note")).toContainText("Showing the six most recently updated threads");
    await page.locator("summary").first().click();
    for (const width of [320, 1440]) {
      await page.setViewportSize({ width, height: 900 });
      for (const mode of ["light", "dark"]) {
        await page.evaluate(mode => window.scittle.core.eval_string(`(scratch.app/dispatch [[:theme/set :${mode}]])`), mode);
        await expect(page.locator("html")).toHaveAttribute("data-theme", mode);
        const result = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"]).analyze();
        expect(result.violations).toEqual([]);
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      }
    }
  });

  test("help and search buffers do not start unnecessary snapshot requests", async ({ page }) => {
    let requests = 0;
    await page.route("**/data/generated/discussions.json", route => { requests++; return route.fulfill({ json: snapshot }); });
    for (const path of ["help/", "search/", "not-published/"]) {
      await ready(page, path);
      await expect(page.locator(".discussion-note")).toHaveCount(0);
    }
    expect(requests).toBe(0);
  });
});

test("offline buffers read the precached snapshot with a timestamp and no live-data claim", async ({ page, context, baseURL }) => {
  await ready(page);
  await page.evaluate(() => navigator.serviceWorker.ready);
  await page.reload();
  await expect.poll(() => page.evaluate(() => !!navigator.serviceWorker.controller)).toBe(true);
  const snapshotURL = `${baseURL}data/generated/discussions.json`;
  expect(await page.evaluate(async url => !!await caches.match(url), snapshotURL)).toBe(true);
  await page.evaluate(async ({ url, snapshot }) => {
    const names = await caches.keys();
    const cache = await caches.open(names.find(name => name.startsWith("scratch-buffer:")));
    await cache.put(url, new Response(JSON.stringify(snapshot), { headers: { "Content-Type": "application/json" } }));
  }, { url: snapshotURL, snapshot });
  await context.setOffline(true);
  await ready(page, "about/");
  await expect(page.locator(".network-note")).toContainText("saved discussion snapshots");
  await expect(page.locator(".discussion-status")).toContainText("Read-only snapshot / Updated");
  await page.locator("summary").click();
  await expect(page.locator(".discussion-note")).toContainText("A useful reply.");
  await expect(page.locator(".discussion-status")).not.toContainText("live");
});
