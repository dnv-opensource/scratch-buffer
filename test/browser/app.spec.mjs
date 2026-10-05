import { test, expect } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { readFile } from "node:fs/promises";
import { execFileSync } from "node:child_process";
import { createServer } from "node:http";
import { createHash } from "node:crypto";

const publishedMembers = JSON.parse(execFileSync("bb", ["-cp", "src:scripts", "-e",
  "(require '[scratch.content :as content] '[cheshire.core :as json]) (println (json/generate-string (:members (content/load-data \".\"))))"
], { encoding: "utf8" }));
const memberRoutes = publishedMembers.slice(0, 1).map(member => `people/${member.github}/`);
const memberName = member => member["full-name"] || member.github;
let communityData;

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => { Math.random = () => 0; });
});

async function ready(page, path = "") {
  await page.goto(path);
  await expect(page.locator("html")).toHaveAttribute("data-enhanced", "true");
}
async function readyWithCommunity(page, path = "") {
  communityData ??= execFileSync("bb", ["-cp", "src:scripts:test", "-e",
    "(require '[scratch.content :as content] '[scratch.fixtures :as fixtures]) (prn (fixtures/with-community (content/load-data \".\")))"
  ], { encoding: "utf8" }).trim();
  await ready(page, path);
  await page.evaluate(data => window.scittle.core.eval_string(`
    (swap! scratch.app/app update :content merge ${data})
    (scratch.app/dispatch
      [[:buffer/open (str (scratch.router/local-path (:base @scratch.app/app) js/location.pathname)
                         js/location.hash) {:pop? true}]
       [:meetings/prepare]])
  `), communityData);
}
async function command(page, query) {
  await page.getByRole("button", { name: "M-x: open commands", exact: true }).click();
  await page.getByRole("combobox").fill(query);
  await page.getByRole("combobox").press("Enter");
}

test("Teams links and command open isolated new tabs without leaving the site", async ({ page, context }) => {
  const site = JSON.parse(execFileSync("bb", ["-e",
    "(require '[clojure.edn :as edn] '[cheshire.core :as json]) (println (json/generate-string (edn/read-string (slurp \"site.edn\"))))"
  ], { encoding: "utf8" }));
  const url = site["teams-url"];
  const requests = [];
  const referrers = [];
  context.on("request", request => {
    if (request.url().startsWith("https://teams.microsoft.com/")) {
      requests.push(request.url());
      referrers.push(request.headers().referer);
    }
  });
  await context.route("https://teams.microsoft.com/**", route => route.fulfill({
    contentType: "text/html", body: "<h1>Teams navigation fixture</h1>"
  }));
  await ready(page);
  const chat = page.getByRole("link", { name: "Open Teams chat (opens in a new tab)", exact: true });
  await expect(chat).toHaveAttribute("href", url);
  await expect(chat).toHaveAttribute("target", "_blank");
  await expect(chat).toHaveAttribute("rel", "noopener noreferrer");
  await expect(page.getByRole("link", { name: "Teams chat (opens in a new tab)", exact: true })).toHaveAttribute("href", url);
  expect(requests).toEqual([]);
  const originalURL = page.url();
  const popupPromise = context.waitForEvent("page");
  await command(page, "teams");
  const popup = await popupPromise;
  await expect(popup).toHaveURL(url);
  await expect(popup.getByRole("heading", { name: "Teams navigation fixture" })).toBeVisible();
  expect(await popup.evaluate(() => window.opener)).toBeNull();
  expect(await popup.evaluate(() => document.referrer)).toBe("");
  await expect(page).toHaveURL(originalURL);
  await expect(page.locator(".mode-buffer.dynamic-only")).toHaveText("*scratch-buffer*");
  expect(requests).toEqual([url]);
  expect(referrers).toEqual([undefined]);
  await popup.close();
  await command(page, "about");
  await expect(page).toHaveURL(/\/about\/$/);
  const aboutURL = page.url();
  const linkPopupPromise = context.waitForEvent("page");
  await page.getByRole("link", { name: "Open Teams chat (opens in a new tab)", exact: true }).click();
  const linkPopup = await linkPopupPromise;
  await expect(linkPopup).toHaveURL(url);
  await expect(linkPopup.getByRole("heading", { name: "Teams navigation fixture" })).toBeVisible();
  expect(await linkPopup.evaluate(() => window.opener)).toBeNull();
  expect(await linkPopup.evaluate(() => document.referrer)).toBe("");
  expect(referrers).toEqual([undefined, undefined]);
  await expect(page).toHaveURL(aboutURL);
});

test("a blocked Teams command keeps the buffer open and explains the ordinary link alternative", async ({ page, context }) => {
  await page.addInitScript(() => { window.open = () => null; });
  await ready(page, "snippets/eglot/");
  const originalURL = page.url();
  const pages = context.pages().length;
  await command(page, "teams");
  await expect(page.locator(".notice")).toContainText("Your browser blocked the new tab");
  await expect(page.locator(".notice")).toContainText("Use the Teams chat link");
  await expect(page).toHaveURL(originalURL);
  expect(context.pages()).toHaveLength(pages);
});

test("Teams opens in a separate tab without JavaScript", async ({ browser, baseURL }) => {
  const context = await browser.newContext({ javaScriptEnabled: false });
  await context.route("https://teams.microsoft.com/**", route => route.fulfill({
    contentType: "text/html", body: "<h1>Teams navigation fixture</h1>"
  }));
  const page = await context.newPage();
  try {
    await page.goto(baseURL);
    const popupPromise = context.waitForEvent("page");
    await page.getByRole("link", { name: "Teams chat (opens in a new tab)", exact: true }).click();
    const popup = await popupPromise;
    await expect(popup.getByRole("heading", { name: "Teams navigation fixture" })).toBeVisible();
    await expect(page).toHaveURL(baseURL);
  } finally {
    await context.close();
  }
});

test("hero selection is random on page load and stable through buffer navigation", async ({ browser, baseURL }) => {
  const context = await browser.newContext({ reducedMotion: "reduce" });
  await context.addInitScript(() => {
    const loads = Number(sessionStorage.getItem("test-hero-loads") || 0);
    sessionStorage.setItem("test-hero-loads", String(loads + 1));
    Math.random = () => loads % 2 === 0 ? 0 : 0.999;
  });
  const page = await context.newPage();
  try {
    await page.goto(baseURL);
    await expect(page.locator("html")).toHaveAttribute("data-enhanced", "true");
    await expect(page.locator("#package-constellation")).toHaveCount(1);
    await expect(page.locator(".buffer-content")).toHaveCSS("transform", "none");
    const intro = await page.locator(".home-intro").boundingBox();
    await page.reload();
    await expect(page.locator("html")).toHaveAttribute("data-enhanced", "true");
    await expect(page.locator("#parenthesis-mobile")).toHaveCount(1);
    await expect(page.locator("#package-constellation")).toHaveCount(0);
    await expect(page.locator(".buffer-content")).toHaveCSS("transform", "none");
    expect(await page.locator(".home-intro").boundingBox()).toEqual(intro);
    await command(page, "meetings");
    await page.goBack();
    await expect(page.locator("#parenthesis-mobile")).toHaveCount(1);
    await command(page, "toggle-theme");
    await expect(page.locator("#parenthesis-mobile")).toHaveCount(1);
    await page.reload();
    await expect(page.locator("#package-constellation")).toHaveCount(1);
  } finally {
    await context.close();
  }
});

test("orbital beams have independent velocities, fading tails, center words, and shared motion controls", async ({ browser, baseURL }) => {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  await context.addInitScript(() => { Math.random = () => 0.999; });
  const page = await context.newPage();
  const errors = [];
  page.on("pageerror", error => errors.push(error.message));
  try {
    await page.goto(baseURL);
    await expect(page.locator("html")).toHaveAttribute("data-enhanced", "true");
    const figure = page.locator("#parenthesis-mobile");
    const animations = () => figure.evaluate(el => el.getAnimations({ subtree: true }).map(animation => ({
      state: animation.playState, time: animation.currentTime,
      beam: animation.effect.target.classList.contains("orbital-beam"),
      duration: animation.effect.getTiming().duration
    })));
    const running = () => animations().then(items => items.some(item => item.state === "running"));
    await expect(figure.locator(".orbital-beam")).toHaveCount(12);
    await expect(figure.locator(".beam-tail")).toHaveCount(12);
    await expect(figure.locator("button, figcaption")).toHaveCount(0);
    await expect(figure.locator(".orbit-word-current")).toHaveText("tramp");
    expect(await figure.locator("linearGradient").evaluateAll(gradients => gradients.every(gradient => {
      const stops = [...gradient.querySelectorAll("stop")].map(stop => Number(stop.getAttribute("stop-opacity")));
      return stops[0] === 0 && stops.at(-1) === 1 && stops.every((value, index) => index === 0 || value > stops[index - 1]);
    }))).toBe(true);
    await expect.poll(running).toBe(true);
    const beams = (await animations()).filter(item => item.beam);
    expect(beams).toHaveLength(12);
    expect(new Set(beams.map(item => item.duration)).size).toBe(12);
    expect(await figure.evaluate(el => el.getAnimations({ subtree: true }).some(animation =>
      animation.effect.target.tagName.toLowerCase() === "svg"
    ))).toBe(false);
    expect(await figure.evaluate(el => el.getAnimations({ subtree: true })
      .filter(animation => animation.effect.target.classList.contains("orbital-beam"))
      .every(animation => animation.effect.getKeyframes().length === 49)
    )).toBe(true);
    await expect.poll(() => page.evaluate(() =>
      window.scittle.core.eval_string("(some? (:timer @scratch.motion/runtime))")
    )).toBe(true);
    const stage = figure.locator(".hero-stage");
    const transform = await stage.evaluate(el => getComputedStyle(el).transform);
    const box = await stage.boundingBox();
    await page.mouse.move(box.x + box.width * 0.8, box.y + box.height * 0.25);
    await expect.poll(() => stage.evaluate(el => getComputedStyle(el).transform)).not.toBe(transform);
    await page.keyboard.press("Alt+x");
    await expect.poll(running).toBe(false);
    await page.keyboard.press("Escape");
    await expect.poll(running).toBe(true);
    await command(page, "toggle-animation");
    await expect(figure).toHaveAttribute("data-paused", "true");
    await expect.poll(running).toBe(false);
    const frozen = await animations();
    await page.waitForTimeout(200);
    expect(await animations()).toEqual(frozen);
    await command(page, "about");
    expect(await page.evaluate(() => document.getAnimations().filter(animation =>
      animation.effect.target.classList.contains("orbital-beam")
    ).length)).toBe(0);
    await command(page, "scratch-buffer");
    await expect(figure).toHaveAttribute("data-paused", "true");
    await expect.poll(running).toBe(false);
    await command(page, "toggle-animation");
    await expect.poll(running).toBe(true);
    await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
    await expect.poll(running).toBe(false);
    await page.evaluate(() => window.scrollTo(0, 0));
    await expect.poll(running).toBe(true);
    await page.evaluate(() => {
      Object.defineProperty(document, "hidden", { configurable: true, value: true });
      document.dispatchEvent(new Event("visibilitychange"));
    });
    await expect.poll(running).toBe(false);
    await page.evaluate(() => {
      delete document.hidden;
      document.dispatchEvent(new Event("visibilitychange"));
    });
    await expect.poll(running).toBe(true);
    await page.emulateMedia({ reducedMotion: "reduce" });
    await expect(figure).toHaveAttribute("data-reduced", "true");
    await expect.poll(animations).toEqual([]);
    await page.emulateMedia({ reducedMotion: "no-preference" });
    await expect.poll(running).toBe(true);
    await page.evaluate(() => navigator.serviceWorker.ready);
    await page.reload();
    await expect.poll(() => page.evaluate(() => !!navigator.serviceWorker.controller)).toBe(true);
    await context.setOffline(true);
    await page.reload();
    await expect(figure.locator(".beam-tail")).toHaveCount(12);
    await expect(page.locator("html")).toHaveAttribute("data-enhanced", "true");
    expect(errors).toEqual([]);
  } finally {
    await context.close();
  }
});

test("orbital words crossfade through the package collection and settle safely when paused", async ({ browser, baseURL }) => {
  const context = await browser.newContext();
  await context.addInitScript(() => { Math.random = () => 0.999; });
  const page = await context.newPage();
  const errors = [];
  page.on("pageerror", error => errors.push(error.message));
  try {
    await page.goto(baseURL);
    await expect(page.locator("html")).toHaveAttribute("data-enhanced", "true");
    const names = await page.evaluate(() =>
      window.scittle.core.eval_string("(clj->js (get-in @scratch.app/app [:content :constellation :names]))"));
    const current = page.locator(".orbit-word-current");
    const incoming = page.locator(".orbit-word-incoming");
    await expect(current).toHaveText(names[0]);
    await expect(incoming).toHaveCount(1, { timeout: 8000 });
    await expect(incoming).toHaveText(names[1]);
    const opacities = await page.locator(".orbit-word").evaluate(el => {
      for (const animation of el.getAnimations({ subtree: true })) {
        animation.pause();
        animation.currentTime = 600;
      }
      return [...el.querySelectorAll("text")].map(text => Number(getComputedStyle(text).opacity));
    });
    expect(opacities.every(value => value > 0 && value < 1)).toBe(true);
    expect(opacities[0] + opacities[1]).toBeCloseTo(1, 3);
    await command(page, "toggle-animation");
    await expect(incoming).toHaveCount(0);
    await expect(current).toHaveText(names[1]);
    await page.waitForTimeout(200);
    await expect(current).toHaveText(names[1]);
    expect(await page.evaluate(() => window.scittle.core.eval_string(
      "(get-in @scratch.app/app [:ui :constellation :cursor])"
    ))).toBe(0);
    await command(page, "about");
    await command(page, "scratch-buffer");
    await expect(current).toHaveText(names[1]);
    await command(page, "toggle-animation");
    await expect(current).toHaveText(names[2], { timeout: 8000 });
    await expect(incoming).toHaveCount(0);
    expect(errors).toEqual([]);
  } finally {
    await context.close();
  }
});

test("the orbital mobile is a faint accessible background on mobile and static with reduced motion", async ({ browser, baseURL }) => {
  const context = await browser.newContext({ reducedMotion: "reduce" });
  await context.addInitScript(() => { Math.random = () => 0.999; });
  const page = await context.newPage();
  try {
    for (const width of [320, 375, 768, 1440]) {
      await page.setViewportSize({ width, height: 900 });
      await page.goto(baseURL);
      await expect(page.locator("html")).toHaveAttribute("data-enhanced", "true");
      const figure = page.locator("#parenthesis-mobile");
      await expect(figure).toHaveAttribute("aria-hidden", "true");
      await expect(figure).toHaveAttribute("data-reduced", "true");
      await expect(figure.locator(".orbit-word-current")).toHaveText("tramp");
      expect(await figure.evaluate(el => el.getAnimations({ subtree: true }).length)).toBe(0);
      if (width < 700) {
        await expect(figure).toHaveCSS("opacity", "0.09");
        await expect(figure).toHaveCSS("position", "absolute");
        await expect(figure).toHaveCSS("pointer-events", "none");
        const hero = await page.locator(".home-hero").boundingBox();
        const intro = await page.locator(".home-intro").boundingBox();
        expect(Math.abs(hero.height - intro.height)).toBeLessThan(1);
      }
      for (const theme of ["light", "dark"]) {
        await page.evaluate(mode => window.scittle.core.eval_string(`(scratch.app/dispatch [[:theme/set :${mode}]])`), theme);
        await expect(page.locator("html")).toHaveAttribute("data-theme", theme);
        await expect(page.locator(".mode-command-label")).toHaveCSS("color",
          await page.locator(".mode-line").evaluate(el => getComputedStyle(el).color));
        const results = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"]).analyze();
        expect(results.violations).toEqual([]);
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      }
    }
  } finally {
    await context.close();
  }
});

for (const missingAPI of ["animation", "visibility"]) {
  test(`the orbital mobile stays static without ${missingAPI} APIs`, async ({ browser, baseURL }) => {
    const context = await browser.newContext();
    await context.addInitScript(api => {
      Math.random = () => 0.999;
      if (api === "animation") Element.prototype.animate = undefined;
      else window.IntersectionObserver = undefined;
    }, missingAPI);
    const page = await context.newPage();
    const errors = [];
    page.on("pageerror", error => errors.push(error.message));
    try {
      await page.goto(baseURL);
      await expect(page.locator("html")).toHaveAttribute("data-enhanced", "true");
      const figure = page.locator("#parenthesis-mobile");
      await expect(figure.locator(".beam-tail")).toHaveCount(12);
      expect(await figure.evaluate(el => el.getAnimations({ subtree: true }).length)).toBe(0);
      await command(page, "toggle-animation");
      await expect(page.locator(".notice")).toContainText("still home illustration");
      expect(errors).toEqual([]);
    } finally {
      await context.close();
    }
  });
}
test("the console welcomes curious readers once per page load", async ({ page }) => {
  const welcomes = [];
  page.on("console", message => {
    if (message.type() === "info" && message.text().includes("Welcome to *scratch-buffer*.")) {
      welcomes.push(message.text());
    }
  });
  await ready(page);
  expect(welcomes).toHaveLength(1);
  expect(welcomes[0]).toContain("        \\  |  /\n         \\ | /\n      ---- * ----\n         / | \\\n        /  |  \\_");
  expect(welcomes[0]).toContain("A small website. A perfectly reasonable amount of Lisp.");
  expect(welcomes[0]).toContain("M-x is downstairs. Your yak can wait.");
  await command(page, "meetings");
  await command(page, "toggle-theme");
  expect(welcomes).toHaveLength(1);
  await expect(page.locator("body")).not.toContainText("Your yak can wait.");
  await page.reload();
  await expect(page.locator("html")).toHaveAttribute("data-enhanced", "true");
  expect(welcomes).toHaveLength(2);
});

test("normal routes, command execution, history and focus", async ({ page }) => {
  const errors = [];
  page.on("pageerror", error => errors.push(error.message));
  await ready(page);
  await command(page, "meetings");
  await expect(page).toHaveURL(/\/meetings\/$/);
  await expect(page).toHaveTitle(/^Meetings/);
  await expect(page.locator("#buffer")).toBeFocused();
  await expect(page.locator(".mode-buffer.dynamic-only")).toHaveText("*meetings*");
  await command(page, "people");
  await page.goBack();
  await expect(page.getByRole("heading", { name: "Meetings", exact: true })).toBeVisible();
  await page.goForward();
  await expect(page.getByRole("heading", { name: "People", exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByRole("heading", { name: "People", exact: true })).toBeVisible();
  await command(page, "snippets");
  await page.getByRole("link", { name: "Let Eglot do the introductions", exact: true }).click();
  await expect(page).toHaveURL(/\/snippets\/eglot\/$/);
  await page.reload();
  await expect(page.getByRole("heading", { name: "Let Eglot do the introductions", exact: true })).toBeVisible();
  expect(errors).toEqual([]);
});

test("consolidated navigation keeps joining, talks, and buffer switching discoverable", async ({ page }) => {
  await ready(page);
  const nav = page.getByRole("navigation", { name: "Main navigation" });
  await expect(nav.getByRole("link")).toHaveText(["About", "Meetings", "People", "Snippets"]);
  await page.getByRole("link", { name: "How to join", exact: true }).click();
  await expect(page).toHaveURL(/\/people\/#join$/);
  await expect(page.locator("#join")).toBeFocused();
  await page.goBack();
  await expect(page).toHaveURL(/\/$/);
  await page.goForward();
  await expect(page.locator("#join")).toBeFocused();
  await page.reload();
  await expect(page.locator("#join")).toBeFocused();
  await command(page, "meetings");
  await command(page, "join-user-group");
  await expect(page).toHaveURL(/\/people\/#join$/);
  await expect(page.locator("#join")).toBeFocused();
  await command(page, "scratch-buffer");
  await page.getByRole("link", { name: "Talks & material", exact: true }).click();
  await expect(page).toHaveURL(/\/meetings\/#talks$/);
  await expect(page.locator("#talks")).toBeFocused();
  await expect(nav.getByRole("link", { name: "Meetings", exact: true })).toHaveAttribute("aria-current", "page");
  await command(page, "switch-buffer");
  const options = await page.getByRole("option").allTextContents();
  expect(options.some(text => /\*(join|talks|packages)\*/.test(text))).toBe(false);
});

test("meeting and talk fixtures link their material in both directions", async ({ page }) => {
  await readyWithCommunity(page, "meetings/");
  await page.getByRole("link", { name: "A smaller init.el", exact: true }).click();
  await expect(page).toHaveURL(/\/meetings\/talks\/a-smaller-init\/$/);
  await page.getByRole("link", { name: "A small show & tell", exact: true }).click();
  await expect(page).toHaveURL(/\/meetings\/example-first-gathering\/$/);
  await expect(page.getByRole("heading", { name: "Talks from this meeting", exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "A smaller init.el", exact: true })).toHaveAttribute("href", /\/meetings\/talks\/a-smaller-init\/$/);
});

for (const javaScriptEnabled of [true, false]) {
  test(`published pages exclude fictional records ${javaScriptEnabled ? "with" : "without"} JavaScript`, async ({ browser, baseURL }) => {
    const context = await browser.newContext({ javaScriptEnabled });
    const page = await context.newPage();
    try {
      for (const path of ["people/", "meetings/"]) {
        await page.goto(`${baseURL}${path}`);
        if (javaScriptEnabled) await expect(page.locator("html")).toHaveAttribute("data-enhanced", "true");
        await expect(page.locator(".example-label")).toHaveCount(0);
        await expect(page.locator("#buffer")).not.toContainText("A small show & tell");
        await expect(page.locator("#buffer")).not.toContainText("A smaller init.el");
        await expect(page.locator("#buffer")).not.toContainText("example-member");
      }
      for (const path of ["people/example-member/", "meetings/example-first-gathering/",
        "meetings/talks/a-smaller-init/", "talks/a-smaller-init/"]) {
        const response = await page.goto(`${baseURL}${path}`);
        expect(response.status()).toBe(404);
        if (javaScriptEnabled) await expect(page.locator("html")).toHaveAttribute("data-enhanced", "true");
        await expect(page.getByRole("heading", { name: "That buffer isn’t here." })).toBeVisible();
      }
      for (const path of ["data/generated/site.edn", "app.cljs", "sw.js"]) {
        const response = await page.request.get(`${baseURL}${path}`);
        expect(response.ok()).toBe(true);
        const body = await response.text();
        for (const id of ["example-member", "example-first-gathering", "a-smaller-init"]) {
          expect(body).not.toContain(id);
        }
      }
    } finally {
      await context.close();
    }
  });
}

for (const javaScriptEnabled of [true, false]) {
  test(`legacy URLs forward to consolidated buffers ${javaScriptEnabled ? "with" : "without"} JavaScript`, async ({ browser, baseURL }) => {
    const context = await browser.newContext({ javaScriptEnabled });
    const page = await context.newPage();
    try {
      for (const [legacy, target, heading] of [
        ["join/", "people/#join", "People"],
        ["packages/", "snippets/", "Snippets & tips"],
        ["packages/eglot/", "snippets/eglot/", "Let Eglot do the introductions"],
        ["packages/magit/", "snippets/magit/", "Magit"],
        ["talks/", "meetings/#talks", "Meetings"]
      ]) {
        await page.goto(`${baseURL}${legacy}`);
        await expect(page).toHaveURL(`${baseURL}${target}`);
        await expect(page.locator("#buffer h1")).toHaveText(heading);
        if (javaScriptEnabled) {
          await expect(page.locator("html")).toHaveAttribute("data-enhanced", "true");
          if (target.includes("#")) await expect(page.locator(`#${target.split("#")[1]}`)).toBeFocused();
        }
      }
    } finally {
      await context.close();
    }
  });
}

test("snippets include package configurations and searchable built-in examples", async ({ page }) => {
  await ready(page, "snippets/");
  expect(await page.locator(".reading-list > li").count()).toBeGreaterThanOrEqual(9);
  await expect(page.locator("#buffer")).toContainText("built into Emacs 29");
  for (const [id, library] of [
    ["which-key", "which-key"], ["icomplete-vertical", "icomplete"],
    ["savehist", "savehist"], ["flymake", "flymake"], ["eldoc", "eldoc"], ["magit", "magit"]
  ]) {
    await ready(page, `snippets/${id}/`);
    await expect(page.locator("#buffer pre code")).toContainText(`(use-package ${library}`);
  }
  await command(page, "search");
  await page.getByRole("combobox").fill("which-key");
  await page.getByRole("option").filter({ has: page.locator(".candidate-name", { hasText: "Let the next key come to you" }) }).click();
  await expect(page).toHaveURL(/\/snippets\/which-key\/$/);
});

test("keyboard, cancellation, history, buffer switching, search", async ({ page }) => {
  await ready(page);
  await page.keyboard.press("Alt+x");
  await expect(page.getByRole("combobox")).toBeFocused();
  await page.getByRole("combobox").fill("mee");
  await expect(page.getByRole("option").first()).toContainText("meetings");
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("ArrowUp");
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/\/meetings\/$/);
  await page.keyboard.press("Control+x");
  await page.keyboard.press("b");
  await expect(page.getByRole("combobox")).toHaveAccessibleName("Switch buffer");
  await page.keyboard.press("Control+g");
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await page.keyboard.press("Control+s");
  await page.getByRole("combobox").fill("language server");
  await expect(page.getByRole("option", { name: /Let Eglot/ })).toBeVisible();
  await page.keyboard.press("Escape");
  await page.keyboard.press("Alt+x");
  await page.keyboard.press("Alt+p");
  await expect(page.getByRole("combobox")).toHaveValue("meetings");
  await page.keyboard.press("Alt+n");
  await expect(page.getByRole("combobox")).toHaveValue("");
  await page.getByRole("combobox").fill("flibbertigibbet");
  await expect(page.getByText("No result matching")).toBeVisible();
  await page.keyboard.press("Escape");
  await page.getByRole("button", { name: "M-x: open commands", exact: true }).click();
  await page.getByRole("button", { name: "M-x: close commands", exact: true }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "M-x: open commands", exact: true })).toBeFocused();
});

test("compact branding and underline-only minibuffer focus", async ({ page }) => {
  await ready(page);
  await expect(page.locator(".brand-name")).toHaveText("scratch-buffer");
  await expect(page.getByRole("link", { name: "scratch-buffer home", exact: true })).toBeVisible();
  expect(await page.locator(".brand-mark").evaluate(el => parseFloat(getComputedStyle(el).fontSize))).toBeLessThan(32);
  await page.keyboard.press("Alt+x");
  await expect(page.getByRole("combobox")).toBeFocused();
  await expect(page.getByRole("combobox")).toHaveCSS("outline-style", "none");
  await expect(page.getByRole("combobox")).toHaveCSS("border-bottom-width", "2px");
  expect(await page.getByRole("combobox").evaluate(el =>
    getComputedStyle(el).borderBottomColor === getComputedStyle(document.querySelector(".minibuffer-prompt label")).color
  )).toBe(true);
  await expect(page.getByRole("button", { name: "Close command interface" })).toHaveCount(0);
  await page.getByRole("button", { name: "M-x: close commands", exact: true }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
});

test("completion highlights matches, explains state, and ranks relevant commands", async ({ page }) => {
  await ready(page, "people/");
  await page.keyboard.press("Alt+x");
  await expect(page.getByRole("option").first().locator(".candidate-name")).toHaveText("copy-member-template");
  await page.getByRole("combobox").fill("mee");
  await expect(page.getByRole("option").first().locator(".candidate-name")).toHaveText("meetings");
  await expect(page.getByRole("option").first().locator(".completion-match").first()).toHaveText("mee");
  await page.getByRole("combobox").fill("mtgs");
  expect((await page.getByRole("option").first().locator(".candidate-name .completion-match").allTextContents()).join("")).toBe("mtgs");
  await page.getByRole("combobox").press("Enter");
  await expect(page).toHaveURL(/\/meetings\/$/);
  await page.keyboard.press("Alt+x");
  const names = await page.locator(".candidate-name").allTextContents();
  expect(names.slice(0, 3)).toEqual(["submit-talk", "suggest-topic", "propose-meeting"]);
  expect(names).toContain("scratch-buffer");
  await page.getByRole("combobox").fill("toggle-theme");
  await expect(page.locator(".candidate-annotation")).toHaveText("currently light");
  await page.emulateMedia({ colorScheme: "dark" });
  await expect(page.locator(".candidate-annotation")).toHaveText("currently dark");
  await page.getByRole("combobox").press("Enter");
  await command(page, "set-theme");
  await page.getByRole("option", { name: /system Follow your device/ }).click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "system");
  await page.keyboard.press("Alt+x");
  await page.getByRole("combobox").fill("light");
  await expect(page.getByRole("option").first().locator(".candidate-summary .completion-match")).toHaveText("light");
  await page.getByRole("combobox").fill("two fields");
  await expect(page.getByRole("option").first().locator(".candidate-name")).toHaveText("copy-member-template");
  expect((await page.locator(".candidate-annotation .completion-match").allTextContents()).join(" ")).toBe("two fields");
});

test("typed completion labels never duplicate text when selecting and reusing random-tip", async ({ page }) => {
  const errors = [];
  page.on("pageerror", error => errors.push(error.message));
  await ready(page, "meetings/");
  for (let round = 0; round < 3; round++) {
    await page.keyboard.press("Alt+x");
    for (const character of "random-tip") {
      await page.getByRole("combobox").pressSequentially(character);
      const expected = await page.evaluate(() =>
        JSON.parse(window.scittle.core.eval_string(`
          (js/JSON.stringify (clj->js
            (mapv #(select-keys % [:name :description :annotation])
                  (scratch.commands/candidates @scratch.app/app))))
        `))
      );
      await expect(page.locator(".candidate-name")).toHaveText(expected.map(candidate => candidate.name));
      await expect(page.locator(".candidate-summary")).toHaveText(expected.map(candidate => candidate.description));
      await expect(page.locator(".candidate-annotation")).toHaveText(
        expected.filter(candidate => candidate.annotation).map(candidate => candidate.annotation)
      );
    }
    await page.getByRole("option").first().click();
    await expect(page.locator(".tip-notification")).toBeVisible();
    await expect(page).toHaveURL(/\/meetings\/$/);
    await page.keyboard.press("Alt+x");
    await page.keyboard.press("Alt+p");
    await expect(page.locator(".candidate-name")).toHaveText(["random-tip"]);
    await page.keyboard.press("Alt+n");
    await expect(page.locator(".candidate-name").filter({ hasText: /^random-tip$/ })).toHaveCount(1);
    await page.keyboard.press("Escape");
  }
  expect(errors).toEqual([]);
});

for (const reducedMotion of ["no-preference", "reduce"]) {
  test(`random-tip shows a temporary notification with ${reducedMotion} motion`, async ({ browser, baseURL }) => {
    const context = await browser.newContext({ reducedMotion, viewport: { width: 375, height: 812 } });
    const page = await context.newPage();
    try {
      await page.clock.install();
      await page.goto(`${baseURL}meetings/`);
      await expect(page.locator("html")).toHaveAttribute("data-enhanced", "true");
      const y = await page.evaluate(() => window.scrollY);
      await command(page, "random-tip");
      await expect(page).toHaveURL(/\/meetings\/$/);
      await expect(page.locator("#mx-button")).toBeFocused();
      expect(await page.evaluate(() => window.scrollY)).toBe(y);
      const notification = page.locator(".tip-notification");
      await expect(notification).toBeVisible();
      const first = await notification.locator(".tip-command").innerText();
      const expected = await page.evaluate(() => window.scittle.core.eval_string(`
        (get-in @scratch.app/app [:ui :tip-notice :description])
      `));
      await expect(notification).toContainText(expected);
      const bounds = await notification.boundingBox();
      const footer = await page.locator(".mode-line").boundingBox();
      expect(bounds.y).toBeGreaterThanOrEqual(0);
      expect(bounds.y + bounds.height).toBeLessThan(footer.y);
      if (reducedMotion === "reduce") {
        expect(await notification.evaluate(el => parseFloat(getComputedStyle(el).animationDuration))).toBeLessThan(0.01);
      }
      await page.clock.runFor(4000);
      await expect(notification).toBeVisible();
      await command(page, "random-tip");
      await expect(notification.locator(".tip-command")).not.toHaveText(first);
      await page.clock.runFor(4000);
      await expect(notification).toBeVisible();
      await page.clock.runFor(2100);
      await expect(notification).toHaveCount(0);
      await expect(page).toHaveURL(/\/meetings\/$/);
    } finally {
      await context.close();
    }
  });
}

test("people ends with concise joining instructions and a copyable minimal template", async ({ page }) => {
  await page.addInitScript(() => {
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText(text) { window.copiedTemplate = text; return Promise.resolve(); } }
    });
  });
  await page.setViewportSize({ width: 375, height: 812 });
  await ready(page, "people/#join");
  await expect(page.locator(".mode-command-label")).toBeVisible();
  await expect(page.locator(".mode-command-label")).toHaveText("Commands");
  await expect(page.locator("#join")).toContainText("a tip, a package configuration, or a typo fix");
  await expect(page.getByRole("heading", { name: "Join the group", exact: true })).toHaveCount(1);
  const template = await page.locator("pre[aria-label='Member entry template'] code").innerText();
  await expect(page.getByRole("button", { name: "Copy member template", exact: true })).toHaveCount(0);
  await page.getByRole("button", { name: "Copy code to clipboard", exact: true }).click();
  await expect(page.locator(".notice")).toContainText("Code copied to clipboard.");
  expect(await page.evaluate(() => window.copiedTemplate)).toBe(template);
  expect(template).toMatch(/^\{:github "your-github-handle"\n :joined "\d{4}-\d{2}"\}$/);
  await page.evaluate(() => { window.copiedTemplate = null; });
  await command(page, "copy-member-template");
  await expect.poll(() => page.evaluate(() => window.copiedTemplate)).toBe(template);
  await expect(page.locator(".notice")).toContainText("Member template copied.");
});

for (const failure of ["denied", "unsupported"]) {
  test(`clipboard ${failure} gives explicit manual-copy guidance`, async ({ page }) => {
    await page.addInitScript(mode => {
      Object.defineProperty(navigator, "clipboard", {
        value: mode === "unsupported" ? undefined : {
          writeText() { return Promise.reject(new DOMException("Denied for this test", "NotAllowedError")); }
        }
      });
    }, failure);
    await ready(page, "people/#join");
    await page.getByRole("button", { name: "Copy code to clipboard", exact: true }).click();
    await expect(page.locator(".notice")).toContainText("copy");
    await expect(page.locator(".notice")).toContainText("manually");
    await expect(page.locator(".notice")).not.toContainText("copied");
    await expect(page.locator(".join-section pre code")).toBeVisible();
    await command(page, "copy-member-template");
    await expect(page.locator(".notice")).toContainText("Open People to select and copy the template manually.");
  });
}

test("every code block copies its exact text with the shared keyboard-accessible icon", async ({ page }) => {
  const snippets = JSON.parse(execFileSync("bb", ["-cp", "src:scripts", "-e",
    "(require '[scratch.content :as content] '[cheshire.core :as json]) (println (json/generate-string (mapv #(select-keys % [:id :code]) (filter :code (:snippets (content/load-data \".\"))))))"
  ], { encoding: "utf8" }));
  await page.addInitScript(() => {
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText(text) { window.copiedCode = text; return Promise.resolve(); } }
    });
  });
  for (const [index, record] of [{ id: null }, ...snippets].entries()) {
    await ready(page, record.id ? `snippets/${record.id}/` : "people/#join");
    const blocks = page.locator(".code-block");
    await expect(blocks).toHaveCount(await page.locator("pre").count());
    const button = blocks.getByRole("button", { name: "Copy code to clipboard", exact: true });
    await expect(button).toHaveCount(1);
    await expect(button.locator("svg")).toHaveAttribute("aria-hidden", "true");
    const dimensions = await button.evaluate(el => ({
      width: parseFloat(getComputedStyle(el).width),
      height: parseFloat(getComputedStyle(el).height)
    }));
    expect(dimensions.width).toBeGreaterThanOrEqual(44);
    expect(dimensions.height).toBeGreaterThanOrEqual(44);
    const text = record.code || await blocks.locator("pre code").textContent();
    if (index % 2) {
      await button.focus();
      await page.keyboard.press("Enter");
    } else {
      await button.click();
    }
    await expect.poll(() => page.evaluate(() => window.copiedCode)).toBe(text);
    await expect(page.locator(".notice")).toContainText("Code copied to clipboard.");
    await expect(button).toBeFocused();
    await expect(page.getByRole("button", { name: "Copy member template", exact: true })).toHaveCount(0);
  }
  await ready(page, "snippets/which-key/");
  const changed = " \t;; Keep whitespace, quotes, and <tags>.\n(message \"\u03bb < B & C\")\n\n";
  await page.evaluate(text => window.scittle.core.eval_string(`
    (swap! scratch.app/app update-in [:content :snippets]
           (fn [records] (mapv #(if (= "which-key" (:id %)) (assoc % :code ${JSON.stringify(text)}) %) records)))
  `), changed);
  await page.getByRole("button", { name: "Copy code to clipboard", exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.copiedCode)).toBe(changed);
});

test("mobile code copying remains unobscured while long lines scroll independently", async ({ browser, baseURL }) => {
  const context = await browser.newContext({ hasTouch: true, viewport: { width: 320, height: 812 }, reducedMotion: "reduce" });
  await context.addInitScript(() => {
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText(text) { window.copiedCode = text; return Promise.resolve(); } }
    });
  });
  const page = await context.newPage();
  try {
    await page.goto(`${baseURL}snippets/eglot/`);
    await expect(page.locator("html")).toHaveAttribute("data-enhanced", "true");
    const text = `;; ${"A long first line ".repeat(10)}\n(message "Done")\n`;
    await page.evaluate(text => window.scittle.core.eval_string(`
      (swap! scratch.app/app update-in [:content :snippets]
             (fn [records] (mapv #(if (= "eglot" (:id %)) (assoc % :code ${JSON.stringify(text)}) %) records)))
    `), text);
    const button = page.getByRole("button", { name: "Copy code to clipboard", exact: true });
    await button.scrollIntoViewIfNeeded();
    const position = await button.boundingBox();
    const layout = await page.locator(".code-block").evaluate(block => {
      const code = block.querySelector("code");
      const range = document.createRange();
      range.setStart(code.firstChild, 0);
      range.setEnd(code.firstChild, code.firstChild.textContent.indexOf("\n"));
      const line = range.getBoundingClientRect();
      const copy = block.querySelector("button").getBoundingClientRect();
      return { lineTop: line.top, copyBottom: copy.bottom, overflows: block.querySelector("pre").scrollWidth > block.clientWidth };
    });
    expect(layout.overflows).toBe(true);
    expect(layout.lineTop).toBeGreaterThan(layout.copyBottom);
    await page.locator("pre").evaluate(pre => { pre.scrollLeft = pre.scrollWidth; });
    expect(await button.boundingBox()).toEqual(position);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await button.tap();
    await expect.poll(() => page.evaluate(() => window.copiedCode)).toBe(text);
    await expect(page.locator(".notice")).toContainText("Code copied to clipboard.");
    await expect(page).toHaveURL(/\/snippets\/eglot\/$/);
  } finally {
    await context.close();
  }
});

for (const failure of ["denied", "unsupported"]) {
  test(`snippet copying ${failure} reports failure without claiming success`, async ({ page }) => {
    const warnings = [];
    page.on("console", message => { if (message.type() === "warning") warnings.push(message.text()); });
    await page.addInitScript(mode => {
      Object.defineProperty(navigator, "clipboard", {
        value: mode === "unsupported" ? undefined : {
          writeText() { return Promise.reject(new DOMException("Denied for this test", "NotAllowedError")); }
        }
      });
    }, failure);
    await ready(page, "snippets/eglot/");
    await page.getByRole("button", { name: "Copy code to clipboard", exact: true }).click();
    await expect(page.locator(".notice")).toContainText("Select the code and copy it manually.");
    await expect(page.locator(".notice")).not.toContainText("Code copied");
    await expect(page.locator("pre code")).toContainText("(use-package eglot");
    expect(warnings.some(message => /Clipboard writing|Could not copy/.test(message))).toBe(true);
  });
}

test("authors have linked full-name bylines and local avatars on pages and posts", async ({ page, baseURL }) => {
  const author = publishedMembers.find(member => member.github === "hkjels");
  for (const path of ["", "about/", "snippets/", "snippets/eglot/"]) {
    await ready(page, path);
    const bylines = path === "snippets/"
      ? page.locator(".reading-list li").filter({ has: page.getByRole("link", { name: "Let Eglot do the introductions", exact: true }) }).locator(".bylines")
      : page.locator(".bylines");
    expect(await bylines.count()).toBeGreaterThan(0);
    const byline = bylines.first();
    await expect(byline).toContainText(`By ${memberName(author)}`);
    await expect(byline.getByRole("link", { name: memberName(author), exact: true }))
      .toHaveAttribute("href", `${new URL(baseURL).pathname}people/hkjels/`);
    await expect(byline.locator("img")).toHaveAttribute("src", `${new URL(baseURL).pathname}assets/avatars/hkjels.png`);
    await byline.scrollIntoViewIfNeeded();
    await expect.poll(() => byline.locator("img").evaluate(img => img.naturalWidth)).toBe(96);
  }
  await page.locator(".bylines").first().getByRole("link", { name: memberName(author), exact: true }).click();
  await expect(page).toHaveURL(/\/people\/hkjels\/$/);
  await expect(page.getByRole("heading", { name: memberName(author), exact: true })).toBeVisible();
  await expect(page.locator(".mode-buffer.dynamic-only")).toHaveText("*person/hkjels*");
  expect(await page.title()).toContain(memberName(author));
  await expect(page.locator("#buffer")).toContainText("@hkjels");
  await expect(page.getByRole("link", { name: "Let Eglot do the introductions", exact: true })).toBeVisible();
  await command(page, "search");
  await page.getByRole("combobox").fill(memberName(author));
  await expect(page.getByRole("option").filter({ has: page.locator(".candidate-name", { hasText: memberName(author) }) })).toHaveCount(1);
  await page.getByRole("combobox").fill(author.github);
  await expect(page.getByRole("option").filter({ has: page.locator(".candidate-name", { hasText: "Let Eglot do the introductions" }) })).toHaveCount(1);
});

test("different authors and speakers are credited on both member pages", async ({ page }) => {
  const author = publishedMembers.find(member => member.github === "hkjels");
  await readyWithCommunity(page, "meetings/talks/a-smaller-init/");
  await page.evaluate(() => window.scittle.core.eval_string(`
    (swap! scratch.app/app update-in [:content :talks]
           (fn [records] (mapv #(if (= "a-smaller-init" (:id %)) (assoc % :author "hkjels") %) records)))
  `));
  await expect(page.locator(".member-byline")).toHaveCount(2);
  await expect(page.locator(".member-byline").first()).toContainText(`By ${memberName(author)}`);
  await expect(page.locator(".member-byline").last()).toContainText("Speaker example-member");
  await page.locator(".member-byline").first().getByRole("link").click();
  await expect(page.getByRole("link", { name: "A smaller init.el", exact: true })).toBeVisible();
  await page.getByRole("link", { name: "A smaller init.el", exact: true }).click();
  await page.locator(".member-byline").last().getByRole("link").click();
  await expect(page).toHaveURL(/\/people\/example-member\/$/);
  await expect(page.getByRole("link", { name: "A smaller init.el", exact: true })).toBeVisible();
});

test("local-time formatting failure leaves the published meeting time readable", async ({ page }) => {
  await page.addInitScript(() => { Intl.DateTimeFormat = undefined; });
  await readyWithCommunity(page, "meetings/example-first-gathering/");
  await expect(page.locator(".notice")).toContainText("Meeting times use the published time zone.");
  await expect(page.locator(".meeting-clock")).toContainText("Europe/Oslo / UTC+02:00");
  await expect(page.locator(".meeting-local")).toHaveCount(0);
});

test("meeting dates show published offset and device-local time without inviting to examples", async ({ browser, baseURL }) => {
  const context = await browser.newContext({ timezoneId: "Pacific/Auckland" });
  const page = await context.newPage();
  await readyWithCommunity(page, `${baseURL}meetings/example-first-gathering/`);
  await expect(page.locator(".meeting-clock")).toContainText("Europe/Oslo / UTC+02:00");
  await expect(page.locator(".meeting-clock time")).toHaveAttribute("datetime", "2026-10-21T12:00:00Z");
  await expect(page.locator(".meeting-local")).toContainText(/22 Oct 2026.*01:00 to 01:30.*Pacific\/Auckland/);
  await expect(page.getByRole("link", { name: "Add to calendar", exact: true })).toHaveCount(0);
  await command(page, "add-to-calendar");
  await expect(page.locator(".notice")).toContainText("Example meetings are not invitations.");
  await context.close();
});

test("real scheduled meetings download generated calendars and expose published material", async ({ page, baseURL }) => {
  const fixture = JSON.parse(execFileSync("bb", ["-cp", "src:scripts", "-e", `
    (require '[scratch.calendar :as calendar] '[scratch.content :as content] '[cheshire.core :as json])
    (let [data (content/load-data ".")
          meeting (-> (first (:meetings (content/read-record "test/fixtures/community.edn")))
                      (assoc :title "Calendar test meeting" :status :scheduled :example? false
                             :join-url "https://example.org/meeting"
                             :notes-url "https://example.org/notes"
                             :recording-url "https://example.org/recording")
                      calendar/enrich)]
      (println (json/generate-string {:meeting meeting :calendar (calendar/document data meeting)})))
  `], { encoding: "utf8" }));
  // Chromium cannot save download responses fulfilled through request interception.
  const server = createServer(async (request, response) => {
    const source = new URL(baseURL);
    source.pathname = new URL(request.url, source).pathname;
    if (source.pathname.endsWith("/meetings/example-first-gathering/event.ics")) {
      response.writeHead(200, { "Content-Type": "text/calendar; charset=utf-8" }).end(fixture.calendar);
      return;
    }
    try {
      const upstream = await fetch(source);
      const body = Buffer.from(await upstream.arrayBuffer());
      response.writeHead(upstream.status, Object.fromEntries(upstream.headers)).end(body);
    } catch (error) {
      console.error("Calendar fixture server failed.", error);
      response.writeHead(502).end("Could not serve the calendar test fixture.");
    }
  });
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  try {
    const origin = new URL(baseURL);
    origin.port = String(server.address().port);
    await readyWithCommunity(page, `${origin}meetings/example-first-gathering/`);
    await page.evaluate(meeting => {
      const encoded = JSON.stringify(JSON.stringify(meeting));
      window.scittle.core.eval_string(`
        (let [record (js->clj (js/JSON.parse ${encoded}) :keywordize-keys true)]
          (swap! scratch.app/app update-in [:content :meetings]
                 (fn [records]
                   (mapv #(if (= (:id record) (:id %))
                            (assoc record :status :scheduled :tags (set (map keyword (:tags record))))
                            %) records)))
          (scratch.app/dispatch [[:meetings/prepare]]))
      `);
    }, fixture.meeting);
    const calendar = page.getByRole("link", { name: "Add to calendar", exact: true });
    await expect(calendar).toHaveAttribute("download", "example-first-gathering.ics");
    await expect(page.getByRole("link", { name: "Read meeting notes", exact: true })).toHaveAttribute("href", "https://example.org/notes");
    await expect(page.getByRole("link", { name: "Watch the recording", exact: true })).toHaveAttribute("href", "https://example.org/recording");
    for (const action of [() => calendar.click(), () => command(page, "add-to-calendar")]) {
      const downloadPromise = page.waitForEvent("download");
      await action();
      const download = await downloadPromise;
      expect(download.suggestedFilename()).toBe("example-first-gathering.ics");
      expect(await readFile(await download.path(), "utf8")).toBe(fixture.calendar);
    }
    await page.evaluate(() => window.scittle.core.eval_string(`
      (swap! scratch.app/app update-in [:content :meetings]
             (fn [records]
               (mapv #(if (= "example-first-gathering" (:id %)) (assoc % :status :past) %) records)))
    `));
    await expect(calendar).toHaveCount(0);
    await expect(page.getByRole("link", { name: "Read meeting notes", exact: true })).toBeVisible();
    await expect(page.getByRole("link", { name: "Watch the recording", exact: true })).toBeVisible();
    await expect(page).toHaveURL(/\/meetings\/example-first-gathering\/$/);
  } finally {
    await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
  }
});

test("related reading links across content types and preserves navigation history", async ({ page }) => {
  await ready(page, "snippets/eglot/");
  const related = page.locator(".related-reading");
  await expect(related.getByRole("heading", { name: "Related reading", exact: true })).toBeVisible();
  await expect(related.getByRole("link", { name: "Catch the small mistakes early", exact: true })).toHaveAttribute("href", /\/snippets\/flymake\/$/);
  await related.getByRole("link", { name: "Catch the small mistakes early", exact: true }).click();
  await expect(page).toHaveURL(/\/snippets\/flymake\/$/);
  await expect(page.locator(".related-reading").getByRole("link", { name: "Let Eglot do the introductions", exact: true })).toBeVisible();
  await page.goBack();
  await expect(page).toHaveURL(/\/snippets\/eglot\/$/);
});

test("join, meeting detail, and related reading stay accessible in both themes", async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 812 });
  for (const theme of ["light", "dark"]) {
    for (const route of ["people/#join", "meetings/example-first-gathering/", "snippets/eglot/"]) {
      await readyWithCommunity(page, route);
      await page.evaluate(mode => window.scittle.core.eval_string(`(scratch.app/dispatch [[:theme/set :${mode}]])`), theme);
      await expect(page.locator("html")).toHaveAttribute("data-theme", theme);
      const results = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"]).analyze();
      expect(results.violations).toEqual([]);
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    }
  }
});

async function constellationLabels(page) {
  return page.locator(".constellation-current").allTextContents();
}

async function constellationAnimations(page) {
  return page.locator("#package-constellation").evaluate(el =>
    el.getAnimations({ subtree: true }).map(animation => ({
      state: animation.playState, time: animation.currentTime
    }))
  );
}

test("a large corpus crossfades through fixed six-arm asterisk anchors", async ({ page }) => {
  const errors = [];
  page.on("pageerror", error => errors.push(error.message));
  await ready(page);
  const figure = page.locator("#package-constellation");
  await expect(figure).toBeVisible();
  expect(Number(await figure.getAttribute("data-name-count"))).toBeGreaterThanOrEqual(100);
  await expect(figure.locator("svg")).toHaveAttribute("aria-hidden", "true");
  await expect(figure.locator(".constellation-slot")).toHaveCount(18);
  const geometry = () => figure.locator(".constellation-slot").evaluateAll(slots =>
    slots.map(slot => [slot.dataset.arm, slot.dataset.ring, slot.getAttribute("transform")])
  );
  const anchors = await geometry();
  for (const arm of ["0", "1", "2", "3", "4", "5"]) {
    expect(anchors.filter(anchor => anchor[0] === arm)).toHaveLength(3);
  }
  const before = await constellationLabels(page);
  await expect.poll(() => figure.locator(".constellation-incoming").count()).toBeGreaterThan(0);
  await expect.poll(() => constellationLabels(page)).not.toEqual(before);
  expect(await geometry()).toEqual(anchors);
  expect(await figure.locator("text").evaluateAll(labels =>
    labels.every(label => label.getBBox().width <= 143)
  )).toBe(true);
  await expect(figure.locator("figcaption, button")).toHaveCount(0);
  expect(await figure.evaluate(el => el.getAnimations({ subtree: true }).some(animation =>
    animation.effect.target.tagName.toLowerCase() === "svg" &&
    animation.effect.getKeyframes().some(frame => frame.transform?.includes("rotateY(-10deg)"))
  ))).toBe(true);
  const stage = figure.locator(".constellation-stage");
  await expect(stage).toHaveCSS("will-change", "transform");
  const transform = await stage.evaluate(el => getComputedStyle(el).transform);
  const box = await stage.boundingBox();
  await page.mouse.move(box.x + box.width * 0.8, box.y + box.height * 0.25);
  await expect.poll(() => stage.evaluate(el => getComputedStyle(el).transform)).not.toBe(transform);
  expect(await geometry()).toEqual(anchors);
  expect(errors).toEqual([]);
});

test("pause freezes labels and motion, and survives leaving the home buffer", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await ready(page);
  await expect.poll(() => page.locator(".constellation-incoming").count()).toBeGreaterThan(0);
  await command(page, "toggle-animation");
  await expect(page.locator("#package-constellation")).toHaveAttribute("data-paused", "true");
  await expect(page.locator(".notice")).toContainText("Home illustration paused.");
  await expect(page.locator(".constellation-incoming")).toHaveCount(0);
  const labels = await constellationLabels(page);
  const animations = await constellationAnimations(page);
  expect(animations.every(animation => animation.state !== "running")).toBe(true);
  await page.waitForTimeout(1800);
  expect(await constellationLabels(page)).toEqual(labels);
  expect(await constellationAnimations(page)).toEqual(animations);
  await page.getByRole("link", { name: "About", exact: true }).click();
  await expect(page.locator("#package-constellation")).toHaveCount(0);
  await page.getByRole("link", { name: "scratch-buffer home", exact: true }).click();
  await expect(page.locator("#package-constellation")).toHaveAttribute("data-paused", "true");
  expect(await constellationLabels(page)).toEqual(labels);
  await expect(page.locator("#buffer")).toBeFocused();
  await expect(page.locator(".buffer-content")).toHaveCSS("transform", "none");
  await command(page, "toggle-animation");
  await expect(page.locator(".notice")).toContainText("Home illustration resumed.");
  await expect.poll(() => constellationLabels(page)).not.toEqual(labels);
});

test("reduced motion stays static and responds immediately to preference changes", async ({ page }) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
  await ready(page);
  const figure = page.locator("#package-constellation");
  await expect(figure).toHaveAttribute("data-reduced", "true");
  await expect(figure.locator("figcaption, button")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Pause package animation", exact: true })).toHaveCount(0);
  const labels = await constellationLabels(page);
  await page.waitForTimeout(1800);
  expect(await constellationLabels(page)).toEqual(labels);
  expect(await constellationAnimations(page)).toEqual([]);
  await page.emulateMedia({ reducedMotion: "no-preference" });
  await expect.poll(() => page.locator(".constellation-incoming").count()).toBeGreaterThan(0);
  await page.emulateMedia({ reducedMotion: "reduce" });
  await expect(figure).toHaveAttribute("data-reduced", "true");
  await expect(page.locator(".constellation-incoming")).toHaveCount(0);
  const settled = await constellationLabels(page);
  await page.waitForTimeout(1800);
  expect(await constellationLabels(page)).toEqual(settled);
  expect(await constellationAnimations(page)).toEqual([]);
});

test("the minibuffer, offscreen hero, and hidden document suspend native animations", async ({ page }) => {
  await ready(page);
  await expect.poll(() => page.locator(".constellation-incoming").count()).toBeGreaterThan(0);
  await page.keyboard.press("Alt+x");
  await expect(page.getByRole("combobox")).toBeFocused();
  await expect.poll(async () =>
    (await constellationAnimations(page)).some(animation => animation.state === "running")
  ).toBe(false);
  const labels = await constellationLabels(page);
  await page.waitForTimeout(1800);
  expect(await constellationLabels(page)).toEqual(labels);
  await page.keyboard.press("Escape");
  await expect.poll(() => constellationLabels(page)).not.toEqual(labels);
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  await expect.poll(async () =>
    (await constellationAnimations(page)).some(animation => animation.state === "running")
  ).toBe(false);
  const offscreen = await constellationLabels(page);
  await page.waitForTimeout(1800);
  expect(await constellationLabels(page)).toEqual(offscreen);
  await page.evaluate(() => window.scrollTo(0, 0));
  await expect.poll(async () =>
    (await constellationAnimations(page)).some(animation => animation.state === "running")
  ).toBe(true);
  await page.evaluate(() => {
    Object.defineProperty(document, "hidden", { configurable: true, value: true });
    document.dispatchEvent(new Event("visibilitychange"));
  });
  const hidden = await constellationLabels(page);
  await page.waitForTimeout(1800);
  expect(await constellationLabels(page)).toEqual(hidden);
  expect((await constellationAnimations(page)).every(animation => animation.state !== "running")).toBe(true);
  await page.evaluate(() => {
    delete document.hidden;
    document.dispatchEvent(new Event("visibilitychange"));
  });
  await expect.poll(() => constellationLabels(page)).not.toEqual(hidden);
});

test("navigation cancels pending fades and remounts a clean constellation", async ({ page }) => {
  const errors = [];
  page.on("pageerror", error => errors.push(error.message));
  await ready(page);
  await expect.poll(() => page.locator(".constellation-incoming").count()).toBeGreaterThan(0);
  await page.getByRole("link", { name: "Meetings", exact: true }).click();
  await page.waitForTimeout(1800);
  expect(await page.evaluate(() => document.getAnimations().filter(animation => animation.playState === "running").length)).toBe(0);
  await page.goBack();
  await expect(page.locator(".constellation-slot")).toHaveCount(18);
  const labels = await constellationLabels(page);
  expect(new Set(labels).size).toBe(18);
  await expect.poll(() => constellationLabels(page)).not.toEqual(labels);
  await page.reload();
  await expect(page.locator(".constellation-slot")).toHaveCount(18);
  expect(errors).toEqual([]);
});

for (const missingAPI of ["animation", "visibility"]) {
  test(`without ${missingAPI} APIs the hero remains useful and has no dead controls`, async ({ page }) => {
    const errors = [];
    page.on("pageerror", error => errors.push(error.message));
    await page.addInitScript(api => {
      if (api === "animation") Element.prototype.animate = undefined;
      else window.IntersectionObserver = undefined;
    }, missingAPI);
    await ready(page);
    await expect(page.locator(".constellation-current")).toHaveCount(18);
    await expect(page.locator("#package-constellation figcaption, #package-constellation button")).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Pause package animation", exact: true })).toHaveCount(0);
    await command(page, "meetings");
    await expect(page).toHaveURL(/\/meetings\/$/);
    expect(errors).toEqual([]);
  });
}

test("mobile hero is a transparent background and navigation stays in the mode-line", async ({ page }) => {
  for (const width of [320, 375, 700]) {
    await page.setViewportSize({ width, height: 900 });
    await ready(page);
    const figure = page.locator("#package-constellation");
    const hero = await page.locator(".home-hero").boundingBox();
    const intro = await page.locator(".home-intro").boundingBox();
    await expect(page.getByRole("navigation", { name: "Main navigation" })).toBeHidden();
    await expect(figure).toHaveCSS("position", "absolute");
    await expect(figure).toHaveCSS("opacity", "0.09");
    await expect(figure).toHaveCSS("pointer-events", "none");
    await expect(figure).toHaveAttribute("aria-hidden", "true");
    await expect(figure.locator("figcaption, button")).toHaveCount(0);
    expect(Math.abs(hero.height - intro.height)).toBeLessThan(1);
    expect(await page.locator(".home-intro a").first().evaluate(el => {
      const box = el.getBoundingClientRect();
      return document.elementFromPoint(box.x + box.width / 2, box.y + box.height / 2) === el;
    })).toBe(true);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.getByRole("button", { name: "Switch buffer: *scratch-buffer*", exact: true }).click();
    await page.getByRole("option", { name: /\*meetings\*/ }).click();
    await expect(page).toHaveURL(/\/meetings\/$/);
  }
});

async function navigationLabelBox(link) {
  return link.evaluate(el => {
    const range = document.createRange();
    range.selectNodeContents(el);
    const box = range.getBoundingClientRect();
    return { x: box.x, width: box.width };
  });
}

async function underlineMatches(page, label) {
  const nav = page.getByRole("navigation", { name: "Main navigation" });
  const link = nav.getByRole("link", { name: label, exact: true });
  await expect.poll(async () => {
    const target = await link.boundingBox();
    const text = await navigationLabelBox(link);
    const line = await nav.locator(".nav-underline").boundingBox();
    return Math.abs(text.x - line.x) < 1 && Math.abs(text.width - line.width) < 1 &&
      Math.abs(target.y + target.height - 6 - line.y) < 1;
  }).toBe(true);
}

test("desktop underline slides on hover and stays with the active route", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await ready(page, "about/");
  const nav = page.getByRole("navigation", { name: "Main navigation" });
  const line = nav.locator(".nav-underline");
  await expect(line).toHaveCSS("opacity", "1");
  await underlineMatches(page, "About");
  const about = await navigationLabelBox(nav.getByRole("link", { name: "About", exact: true }));
  const meetings = nav.getByRole("link", { name: "Meetings", exact: true });
  const destination = await navigationLabelBox(meetings);
  await meetings.hover();
  await expect.poll(() => line.evaluate(el =>
    Number(el.getAnimations()[0]?.effect.getKeyframes().at(-1).transform.match(/scaleX\(([\d.]+)\)/)?.[1])
  )).toBeCloseTo(destination.width, 1);
  const halfway = await line.evaluate(el => {
    const animation = el.getAnimations()[0];
    animation.pause();
    animation.currentTime = 100;
    const x = el.getBoundingClientRect().x;
    animation.play();
    return x;
  });
  expect(halfway).toBeGreaterThan(about.x);
  expect(halfway).toBeLessThan(destination.x);
  await underlineMatches(page, "Meetings");
  await meetings.click();
  await expect(meetings).toHaveAttribute("aria-current", "page");
  await nav.getByRole("link", { name: "People", exact: true }).hover();
  await underlineMatches(page, "People");
  await page.mouse.move(5, 5);
  await underlineMatches(page, "Meetings");
  await page.goBack();
  await underlineMatches(page, "About");
  await page.goForward();
  await underlineMatches(page, "Meetings");
  await ready(page, "snippets/eglot/");
  await underlineMatches(page, "Snippets");
  await page.setViewportSize({ width: 768, height: 900 });
  await underlineMatches(page, "Snippets");
});

test("desktop underline stays continuous across the spaces between labels", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await ready(page, "about/");
  const nav = page.getByRole("navigation", { name: "Main navigation" });
  const meetings = nav.getByRole("link", { name: "Meetings", exact: true });
  const people = nav.getByRole("link", { name: "People", exact: true });
  const meetingBox = await meetings.boundingBox();
  const peopleBox = await people.boundingBox();
  expect(Math.abs(meetingBox.x + meetingBox.width - peopleBox.x)).toBeLessThan(0.1);
  const meetingText = await navigationLabelBox(meetings);
  const peopleText = await navigationLabelBox(people);
  const left = meetingText.x + meetingText.width;
  const spacing = peopleText.x - left;
  expect(spacing).toBeGreaterThan(10);
  const y = meetingBox.y + meetingBox.height / 2;
  await meetings.hover();
  await underlineMatches(page, "Meetings");
  await page.mouse.move(left + spacing * 0.25, y);
  await underlineMatches(page, "Meetings");
  await page.mouse.move(left + spacing * 0.75, y);
  await underlineMatches(page, "People");
  await page.mouse.click(left + spacing * 0.75, y);
  await expect(page).toHaveURL(/\/people\/$/);
  await page.mouse.move(5, 5);
  await underlineMatches(page, "People");
});

test("desktop underline follows keyboard focus and respects reduced motion", async ({ page }) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
  await ready(page, "people/");
  const nav = page.getByRole("navigation", { name: "Main navigation" });
  await underlineMatches(page, "People");
  await nav.getByRole("link", { name: "Snippets", exact: true }).hover();
  await page.getByRole("link", { name: "scratch-buffer home", exact: true }).focus();
  await page.keyboard.press("Tab");
  await expect(nav.getByRole("link", { name: "About", exact: true })).toBeFocused();
  await underlineMatches(page, "About");
  await page.keyboard.press("Tab");
  await expect(nav.getByRole("link", { name: "Meetings", exact: true })).toBeFocused();
  await underlineMatches(page, "Meetings");
  await nav.getByRole("link", { name: "People", exact: true }).hover();
  await underlineMatches(page, "People");
  await page.mouse.move(5, 5);
  await underlineMatches(page, "Meetings");
  expect(await nav.locator(".nav-underline").evaluate(el =>
    el.getAnimations().map(animation => animation.effect.getTiming().duration)
  )).toEqual([0]);
  await page.locator("#buffer").focus();
  await underlineMatches(page, "People");
});

test("member experience is shown on directory and profile pages", async ({ page }) => {
  await readyWithCommunity(page, "people/");
  const member = page.locator(".people-list > li").filter({ has: page.getByRole("link", { name: "example-member", exact: true }) });
  await expect(member.getByText("Emacs since 2018", { exact: true })).toBeVisible();
  await page.getByRole("link", { name: "example-member", exact: true }).click();
  await expect(page.getByText("Emacs since 2018", { exact: true })).toBeVisible();
});

for (const javaScriptEnabled of [true, false]) {
  test(`People avatars are self-hosted and accessible ${javaScriptEnabled ? "with" : "without"} JavaScript`, async ({ browser, baseURL }) => {
    const context = await browser.newContext({ javaScriptEnabled });
    const page = await context.newPage();
    const externalRequests = [];
    context.on("request", request => {
      if (new URL(request.url()).origin !== new URL(baseURL).origin) externalRequests.push(request.url());
    });
    try {
      await page.goto(`${baseURL}people/`);
      if (javaScriptEnabled) await expect(page.locator("html")).toHaveAttribute("data-enhanced", "true");
      const avatars = page.locator(".member-avatar img");
      await expect(avatars).toHaveCount(publishedMembers.length);
      for (const [index, member] of publishedMembers.entries()) {
        const avatar = avatars.nth(index);
        await avatar.scrollIntoViewIfNeeded();
        await expect(avatar).toHaveAttribute("src", `${new URL(baseURL).pathname}assets/avatars/${member.github}.png`);
        await expect(avatar).toHaveAttribute("alt", "");
        await expect(avatar).toHaveAttribute("loading", "lazy");
        await expect(avatar).toHaveAttribute("decoding", "async");
        await expect.poll(() => avatar.evaluate(img => img.naturalWidth)).toBe(96);
        expect(await avatar.evaluate(img => img.naturalHeight)).toBe(96);
        await expect(page.getByRole("link", { name: memberName(member), exact: true })).toBeVisible();
      }
      for (const width of [320, 1440]) {
        await page.setViewportSize({ width, height: 900 });
        for (const theme of ["light", "dark"]) {
          await page.evaluate(mode => document.documentElement.dataset.theme = mode, theme);
          if (javaScriptEnabled) {
            const results = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"]).analyze();
            expect(results.violations).toEqual([]);
          }
          expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
        }
      }
      expect(externalRequests).toEqual([]);
    } finally {
      await context.close();
    }
  });
}

test("an unavailable avatar leaves the directory and member links usable", async ({ page }) => {
  await page.route("**/assets/avatars/*.png", route => route.abort());
  await ready(page, "people/");
  for (const [index, member] of publishedMembers.entries()) {
    const avatar = page.locator(".member-avatar").nth(index);
    await expect(avatar.locator(".avatar-initial")).toHaveText(member.github[0].toUpperCase());
    await expect.poll(() => avatar.locator("img").evaluate(img => img.complete && img.naturalWidth === 0)).toBe(true);
    await page.getByRole("link", { name: memberName(member), exact: true }).click();
    await expect(page.getByRole("heading", { name: memberName(member), exact: true })).toBeVisible();
    await ready(page, "people/");
  }
});

test("member avatars are included in the offline edition", async ({ page, context }) => {
  await ready(page, "people/");
  await page.evaluate(() => navigator.serviceWorker.ready);
  await page.reload();
  await expect.poll(() => page.evaluate(() => !!navigator.serviceWorker.controller)).toBe(true);
  const urls = await page.locator(".member-avatar img").evaluateAll(images => images.map(img => img.src));
  expect(await page.evaluate(async urls => (await Promise.all(urls.map(url => caches.match(url)))).every(Boolean), urls)).toBe(true);
  await context.setOffline(true);
  await ready(page, "people/");
  await expect(page.locator(".network-note")).toContainText("Offline");
  for (const avatar of await page.locator(".member-avatar img").all()) {
    await avatar.scrollIntoViewIfNeeded();
    await expect.poll(() => avatar.evaluate(img => img.naturalWidth)).toBe(96);
  }
  await ready(page, "snippets/eglot/");
  await expect(page.locator(".bylines")).toContainText(memberName(publishedMembers.find(member => member.github === "hkjels")));
  await page.locator(".bylines img").scrollIntoViewIfNeeded();
  await expect.poll(() => page.locator(".bylines img").evaluate(img => img.naturalWidth)).toBe(96);
  await expect(page.getByRole("button", { name: "Copy code to clipboard", exact: true })).toBeVisible();
});

test("meeting timeline places dates beside entries and adapts on mobile", async ({ page }) => {
  for (const width of [768, 320]) {
    await page.setViewportSize({ width, height: 900 });
    await readyWithCommunity(page, "meetings/");
    await expect(page.getByRole("list", { name: "Meeting timeline" })).toBeVisible();
    const stamp = await page.locator(".meeting-stamp").boundingBox();
    const content = await page.locator(".meeting-content").boundingBox();
    const node = await page.locator(".meeting-node").boundingBox();
    expect(node.width).toBe(node.height);
    await expect(page.locator(".meeting-node")).toHaveCSS("border-radius", "50%");
    await expect(page.locator(".meeting-date")).toHaveText("2026-10-21");
    await expect(page.locator(".meeting-time")).toHaveText("14:00");
    if (width > 550) {
      expect(stamp.x + stamp.width).toBeLessThan(node.x);
      expect(node.x + node.width).toBeLessThan(content.x);
    } else {
      expect(stamp.y + stamp.height).toBeLessThanOrEqual(content.y);
      expect(node.x + node.width).toBeLessThan(content.x);
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    for (const theme of ["light", "dark"]) {
      await page.evaluate(mode => document.documentElement.dataset.theme = mode, theme);
      const results = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"]).analyze();
      expect(results.violations).toEqual([]);
    }
  }
});

test("pointer completion, theme persistence and system changes", async ({ page }) => {
  await ready(page);
  await command(page, "set-theme");
  await page.getByRole("option", { name: /dark After hours/ }).click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await page.reload();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await command(page, "toggle-theme");
  await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
  await command(page, "set-theme");
  await page.getByRole("option", { name: /system Follow your device/ }).click();
  await page.emulateMedia({ colorScheme: "dark" });
  await expect(page.locator("html")).toHaveCSS("color-scheme", "dark");
  await page.emulateMedia({ colorScheme: "light" });
  await expect(page.locator("html")).toHaveCSS("color-scheme", "light");
});

test("scroll restoration and percentage", async ({ page }) => {
  await ready(page);
  await page.evaluate(() => window.scrollTo(0, 500));
  await expect(page.locator(".mode-scroll")).toHaveText(/%|Bot/);
  await command(page, "people");
  await page.goBack();
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(500);
});

test("tip notifications pause while being read and can open the full snippet", async ({ page }) => {
  await page.clock.install();
  await ready(page);
  await expect(page.locator(".tip-section")).toHaveCount(0);
  await page.evaluate(() => window.scrollTo(0, 500));
  const y = await page.evaluate(() => window.scrollY);
  await command(page, "random-tip");
  expect(await page.evaluate(() => window.scrollY)).toBe(y);
  const notification = page.locator(".tip-notification");
  await notification.hover();
  await page.clock.runFor(7000);
  await expect(notification).toBeVisible();
  await page.mouse.move(0, 0);
  const link = notification.getByRole("link", { name: "Read this tip", exact: true });
  await link.focus();
  await page.clock.runFor(7000);
  await expect(notification).toBeVisible();
  const href = await link.getAttribute("href");
  await link.click();
  await expect(page).toHaveURL(new RegExp(href + "$"));
  await notification.getByRole("button", { name: "Dismiss", exact: true }).click();
  await expect(notification).toHaveCount(0);
  await expect(page.locator("#mx-button")).toBeFocused();
});

test("tip notifications are accessible and expire independently of important messages", async ({ page }) => {
  await page.clock.install();
  await page.setViewportSize({ width: 320, height: 812 });
  await ready(page, "meetings/");
  for (const theme of ["light", "dark"]) {
    await page.evaluate(mode => window.scittle.core.eval_string(`
      (scratch.app/dispatch [[:theme/set :${mode}] [:ui/notice "Important message"]])
    `), theme);
    await command(page, "random-tip");
    await expect(page.locator(".tip-notification")).toBeVisible();
    const results = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"]).analyze();
    expect(results.violations).toEqual([]);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.clock.runFor(6100);
    await expect(page.locator(".tip-notification")).toHaveCount(0);
    await expect(page.locator(".notice")).toContainText("Important message");
  }
});

test("safe useful HTML without JavaScript", async ({ browser, baseURL }) => {
  const context = await browser.newContext({ javaScriptEnabled: false });
  const page = await context.newPage();
  await page.goto(`${baseURL}${memberRoutes[0] || "people/"}`);
  await expect(page.getByRole("link", { name: "Teams chat (opens in a new tab)", exact: true })).toHaveAttribute("href", /^https:\/\/teams\.microsoft\.com\/l\/channel\//);
  if (publishedMembers.length) {
    await expect(page.getByRole("heading", { name: memberName(publishedMembers[0]), exact: true })).toBeVisible();
    await expect(page.getByRole("link", { name: "Public GitHub profile", exact: true })).toHaveAttribute("href", `https://github.com/${publishedMembers[0].github}`);
    await page.getByRole("link", { name: "Back to people" }).click();
  }
  await expect(page.locator("#buffer")).not.toContainText("fictional example record");
  await expect(page.getByRole("heading", { name: "People", exact: true })).toBeVisible();
  await page.getByRole("link", { name: "scratch-buffer home", exact: true }).click();
  await expect(page.locator(".constellation-current")).toHaveCount(18);
  await expect(page.locator("#package-constellation figcaption")).toHaveCount(0);
  await expect(page.locator(".constellation-control")).toHaveCount(0);
  await page.goto(`${baseURL}people/#join`);
  await expect(page.locator(".join-section pre code")).toContainText(":joined");
  await expect(page.getByRole("button", { name: "Copy member template", exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Copy code to clipboard", exact: true })).toBeHidden();
  await page.goto(`${baseURL}snippets/eglot/`);
  const author = publishedMembers.find(member => member.github === "hkjels");
  await expect(page.locator(".bylines").getByRole("link", { name: memberName(author), exact: true }))
    .toHaveAttribute("href", `${new URL(baseURL).pathname}people/hkjels/`);
  await expect.poll(() => page.locator(".bylines img").evaluate(img => img.naturalWidth)).toBe(96);
  await expect(page.locator("pre code")).toContainText("(use-package eglot");
  await expect(page.getByRole("button", { name: "Copy code to clipboard", exact: true })).toBeHidden();
  await page.locator(".related-reading").getByRole("link", { name: "Catch the small mistakes early", exact: true }).click();
  await expect(page).toHaveURL(/\/snippets\/flymake\/$/);
  await page.goto(`${baseURL}meetings/`);
  await expect(page.getByRole("heading", { name: "Meetings", exact: true })).toBeVisible();
  await expect(page.locator("#buffer")).not.toContainText("A small show & tell");
  await expect(page.getByRole("link", { name: "Add to calendar", exact: true })).toHaveCount(0);
  await context.close();
});

test("mobile without JavaScript still has a bottom page directory", async ({ browser, baseURL }) => {
  const context = await browser.newContext({ javaScriptEnabled: false, viewport: { width: 375, height: 812 } });
  const page = await context.newPage();
  await page.goto(baseURL);
  await expect(page.getByRole("navigation", { name: "Main navigation" })).toBeHidden();
  await page.locator(".mode-line").getByRole("link", { name: "Help", exact: true }).click();
  await page.locator(".page-directory").getByRole("link", { name: "Meetings", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Meetings", exact: true })).toBeVisible();
  await context.close();
});

test("an unavailable runtime leaves ordinary content and links usable", async ({ page }) => {
  await page.route("**/vendor/scittle.js", route => route.abort());
  await page.goto("people/#join");
  await expect(page.getByRole("heading", { name: "Join the group", exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "M-x: open commands", exact: true })).toBeHidden();
  await page.getByRole("link", { name: "People", exact: true }).click();
  await expect(page.getByRole("heading", { name: "People", exact: true })).toBeVisible();
});

test("first paint is styled in the saved theme without waiting for the application runtime", async ({ page }) => {
  await page.route("**/vendor/*.js", route => route.abort());
  await page.addInitScript(() => {
    localStorage.setItem("scratch-theme", "dark");
    window.firstPaintTheme = null;
    window.inlinePolicyViolations = [];
    document.addEventListener("securitypolicyviolation", event => {
      if (event.blockedURI === "inline") window.inlinePolicyViolations.push(event.violatedDirective);
    });
    new PerformanceObserver(list => {
      if (list.getEntries().some(entry => entry.name === "first-contentful-paint")) {
        window.firstPaintTheme = document.documentElement.dataset.theme;
      }
    }).observe({ type: "paint", buffered: true });
  });
  await page.goto("");
  await expect(page.locator(".home-title")).toBeVisible();
  await expect.poll(() => page.evaluate(() => window.firstPaintTheme)).toBe("dark");
  await expect(page.locator("body")).toHaveCSS("background-color", "rgb(23, 33, 38)");
  await expect(page.locator("html")).not.toHaveAttribute("data-enhanced", "true");
  expect(await page.evaluate(() => window.inlinePolicyViolations)).toEqual([]);
  expect(await page.evaluate(() => performance.getEntriesByType("resource")
    .filter(entry => entry.name.endsWith("/site.css") || entry.name.endsWith("/theme.js"))
  )).toEqual([]);
});

test("generated pages authorize only their exact embedded stylesheet and theme script", async ({ page }) => {
  for (const route of ["", "people/", ...memberRoutes, "not-published/"]) {
    await page.goto(route);
    const policy = await page.locator("meta[http-equiv='Content-Security-Policy']").getAttribute("content");
    const css = await page.locator("head > style").textContent();
    const theme = await page.locator("head > script:not([src])").textContent();
    expect(policy).toContain(`style-src 'self' 'sha256-${createHash("sha256").update(css).digest("base64")}'`);
    expect(policy).toContain(`script-src 'self' 'sha256-${createHash("sha256").update(theme).digest("base64")}'`);
    expect(policy).not.toContain("unsafe-inline");
    await expect(page.locator("link[rel='stylesheet']")).toHaveCount(0);
    expect(css).toContain("--mode-line-bg");
    await expect(page.locator("main h1")).toBeVisible();
  }
});

test("runtime source maps match loaded bundles without adding normal-page or offline downloads", async ({ page }) => {
  const dependencies = JSON.parse(await readFile("runtime-deps.json", "utf8"));
  const debuggerSession = await page.context().newCDPSession(page);
  const scripts = [];
  debuggerSession.on("Debugger.scriptParsed", script => {
    if (/\/vendor\/scittle(?:\.replicant)?\.js$/.test(script.url)) scripts.push(script);
  });
  await debuggerSession.send("Debugger.enable");
  await ready(page);
  expect(scripts).toHaveLength(2);
  for (const script of scripts) {
    const bundle = script.url.split("/").pop();
    expect(script.sourceMapURL).toBe(`${bundle}.map`);
    const response = await page.request.get(new URL(script.sourceMapURL, script.url).href);
    expect(response.ok()).toBe(true);
    const bytes = await response.body();
    const dependency = dependencies.find(entry => entry.file === script.sourceMapURL);
    const original = await readFile(`.cache/vendor/${dependency.file}`);
    expect(createHash("sha256").update(original).digest("hex")).toBe(dependency.sha256);
    const indexed = JSON.parse(original.toString("utf8"));
    const map = JSON.parse(bytes.toString("utf8"));
    expect(map.version).toBe(3);
    expect(map.file).toBe(bundle);
    expect(map.sections).toBeUndefined();
    expect(map.mappings).toBe(";".repeat(indexed.sections[0].offset.line) + indexed.sections[0].map.mappings);
    expect(map.sources).toEqual(indexed.sections[0].map.sources);
    expect(map.sourcesContent).toEqual(indexed.sections[0].map.sourcesContent);
    expect(map.names).toEqual(indexed.sections[0].map.names);
    expect(map.sourcesContent).toHaveLength(map.sources.length);
    expect(map.sourcesContent.every(source => typeof source === "string")).toBe(true);
    const librarySource = bundle === "scittle.js" ? "scittle/core.cljs" : "replicant/core.cljc";
    expect(map.sources).toContain(librarySource);
    expect(map.sourcesContent[map.sources.indexOf(librarySource)]).toContain("(ns ");
  }
  expect(await page.evaluate(() => performance.getEntriesByType("resource")
    .some(entry => entry.name.endsWith(".js.map"))
  )).toBe(false);
  await page.evaluate(() => navigator.serviceWorker.ready);
  expect(await page.evaluate(async () => {
    const names = await caches.keys();
    const keys = (await Promise.all(names.map(async name => (await caches.open(name)).keys()))).flat();
    return keys.some(request => new URL(request.url).pathname.endsWith(".map"));
  })).toBe(false);
});

test("all content, membership and navigation surfaces render", async ({ page }) => {
  for (const route of ["about/", "meetings/", "snippets/", "people/", "help/", "search/", "messages/"]) {
    await ready(page, route);
    await expect(page.locator("main h1")).toBeVisible();
  }
  for (const route of memberRoutes) {
    await ready(page, route);
    await expect(page.getByRole("link", { name: "Public GitHub profile", exact: true })).toBeVisible();
    await expect(page.locator("#buffer")).not.toContainText("fictional example record");
  }
  await ready(page, "snippets/eglot/");
  await expect(page.locator("pre code")).toContainText("eglot-ensure");
  await expect(page.getByRole("link", { name: "Discuss this page on GitHub" }))
    .toHaveAttribute("href", "https://github.com/dnv-opensource/scratch-buffer/discussions");
  await ready(page, "not-published/");
  await expect(page.getByRole("heading", { name: "That buffer isn’t here." })).toBeVisible();
});

for (const width of [320, 375, 768, 1440]) {
  test(`responsive accessibility at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await ready(page);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    const bar = await page.locator(".mode-line").boundingBox();
    expect(Math.round(bar.y + bar.height)).toBe(900);
    await expect(page.getByRole("button", { name: "M-x: open commands", exact: true })).toBeVisible();
    for (const theme of ["light", "dark"]) {
      await page.evaluate(mode => document.documentElement.dataset.theme = mode, theme);
      const results = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"]).analyze();
      expect(results.violations).toEqual([]);
    }
    await page.getByRole("button", { name: "M-x: open commands", exact: true }).click();
    const results = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"]).analyze();
    expect(results.violations).toEqual([]);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    for (const route of ["people/#join", ...memberRoutes, "meetings/", "snippets/eglot/", "help/"]) {
      await ready(page, route);
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    }
  });
}

test("touch navigation and reduced motion", async ({ browser, baseURL }) => {
  const context = await browser.newContext({ viewport: { width: 375, height: 812 }, hasTouch: true, isMobile: true, reducedMotion: "reduce" });
  const page = await context.newPage();
  await page.goto(baseURL);
  await expect(page.locator("html")).toHaveAttribute("data-enhanced", "true");
  await page.getByRole("button", { name: "M-x: open commands", exact: true }).tap();
  await page.getByRole("button", { name: "M-x: close commands", exact: true }).tap();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await page.getByRole("button", { name: "M-x: open commands", exact: true }).tap();
  await page.getByRole("combobox").fill("people");
  await page.getByRole("option").first().tap();
  await expect(page.getByRole("heading", { name: "People", exact: true })).toBeVisible();
  expect(await page.locator(".buffer-content").evaluate(el => parseFloat(getComputedStyle(el).animationDuration))).toBeLessThan(0.01);
  await context.close();
});

test("offline shell, deep routes, GitHub failure and installation explanation", async ({ page, context }) => {
  await ready(page);
  await page.evaluate(() => navigator.serviceWorker.ready);
  await page.reload();
  await expect.poll(() => page.evaluate(() => !!navigator.serviceWorker.controller)).toBe(true);
  await command(page, "install");
  await expect(page.locator(".notice")).toContainText("To install");
  await context.setOffline(true);
  await page.goto("snippets/repeat-mode/");
  await expect(page.locator("html")).toHaveAttribute("data-enhanced", "true");
  await expect(page.getByRole("heading", { name: "Give a command an encore" })).toBeVisible();
  await expect(page.locator(".network-note")).toContainText("Offline");
  await command(page, "discuss-page");
  await expect(page.locator(".notice")).toContainText("GitHub Discussions need a connection");
  await command(page, "teams");
  await expect(page.locator(".notice")).toContainText("Teams chat needs a connection");
  await expect(page).toHaveURL(/\/snippets\/repeat-mode\/$/);
  await page.reload();
  await expect(page.getByRole("heading", { name: "Give a command an encore" })).toBeVisible();
  await ready(page, "join/");
  await expect(page).toHaveURL(/\/people\/#join$/);
  await expect(page.locator("#join")).toBeFocused();
  await ready(page, "packages/eglot/");
  await expect(page).toHaveURL(/\/snippets\/eglot\/$/);
  await ready(page, "talks/");
  await expect(page).toHaveURL(/\/meetings\/#talks$/);
  await expect(page.locator(".network-note")).toContainText("Offline");
});

test("offline preparation failure is explicit and does not block reading", async ({ page }) => {
  await page.addInitScript(() => {
    Object.defineProperty(navigator.serviceWorker, "register", {
      value: () => Promise.reject(new Error("Registration unavailable for this test"))
    });
  });
  await ready(page);
  await expect(page.locator(".notice")).toContainText("Offline reading could not be prepared");
  await command(page, "meetings");
  await expect(page.getByRole("heading", { name: "Meetings", exact: true })).toBeVisible();
});

test("new-tab links retain canonical URLs", async ({ page, context }) => {
  await ready(page);
  const target = page.getByRole("link", { name: "Meetings", exact: true });
  const href = await target.getAttribute("href");
  expect(href).toMatch(/\/meetings\/$/);
  const popupPromise = context.waitForEvent("page");
  await target.click({ modifiers: ["ControlOrMeta"], delay: 50 });
  const popup = await popupPromise;
  await expect(popup.getByRole("heading", { name: "Meetings", exact: true })).toBeVisible();
  await expect(page).not.toHaveURL(/\/meetings\/$/);
});
