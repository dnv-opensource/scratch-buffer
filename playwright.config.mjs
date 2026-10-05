import { defineConfig } from "@playwright/test";

const base = process.env.BASE_PATH || "/";
const origin = `http://127.0.0.1:${process.env.PORT || 4173}`;
export default defineConfig({
  testDir: "./test/browser",
  fullyParallel: true,
  workers: 2,
  retries: process.env.CI ? 1 : 0,
  use: {
    baseURL: `${origin}${base.endsWith("/") ? base : base + "/"}`,
    trace: "retain-on-failure"
  },
  webServer: {
    command: "node scripts/serve.mjs",
    url: `${origin}${base}`,
    reuseExistingServer: !process.env.CI
  }
});
