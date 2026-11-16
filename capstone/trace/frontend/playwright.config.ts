import { defineConfig } from "@playwright/test";
if (process.env.TRACE_BROWSER_ACCEPTANCE !== "disposable")
  throw new Error(
    "Set TRACE_BROWSER_ACCEPTANCE=disposable after provisioning the dedicated 15174 / 18082 / 55437 test stack.",
  );
export default defineConfig({
  testDir: "./test/browser",
  workers: 1,
  fullyParallel: false,
  retries: 0,
  timeout: 60000,
  expect: { timeout: 10000 },
  outputDir: "/tmp/trace-final-hardening/playwright-results",
  reporter: [
    ["list"],
    [
      "json",
      { outputFile: "/tmp/trace-final-hardening/playwright-results.json" },
    ],
  ],
  use: {
    baseURL: "http://127.0.0.1:15174",
    channel: "chrome",
    headless: true,
    actionTimeout: 10000,
    screenshot: "only-on-failure",
    trace: "retain-on-failure",
    viewport: { width: 1440, height: 1000 },
  },
});
