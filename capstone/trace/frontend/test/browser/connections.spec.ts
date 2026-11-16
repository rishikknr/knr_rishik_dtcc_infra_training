import { test, expect, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { readFileSync } from "node:fs";
const fixture = JSON.parse(
  readFileSync(
    process.env.TRACE_BROWSER_FIXTURE ??
      "/tmp/trace-final-hardening/browser-fixture.json",
    "utf8",
  ),
);
const team = "a2000000-0000-4000-8000-000000000001";
async function login(page: Page) {
  await page.goto("/#login");
  await page
    .getByLabel("Email", { exact: true })
    .fill("owner@hardening.example.test");
  await page.getByLabel("Password", { exact: true }).fill(fixture.password);
  await page.getByRole("button", { name: "Sign In", exact: true }).click();
  await expect(page.getByLabel("Account menu", { exact: true })).toBeVisible();
  const token = await (await page.request.get("/api/auth/csrf")).json();
  await page.request.post("/api/auth/organization", {
    data: { organizationId: "a1000000-0000-4000-8000-000000000001" },
    headers: { [token.headerName]: token.token },
  });
  await page.goto("/#connections/new");
  await page.reload();
}
async function source(page: Page, bucket: string, name: string) {
  await page.getByRole("button", { name: /Amazon S3/ }).click();
  await page.getByRole("button", { name: "Continue", exact: true }).click();
  await page.getByLabel("Connection name", { exact: true }).fill(name);
  await page.getByLabel("Owning team", { exact: true }).selectOption(team);
  await page.getByLabel("AWS Region", { exact: true }).fill("ap-south-1");
  await page.getByLabel("Bucket name", { exact: true }).fill(bucket);
  await page.getByLabel("Folder prefix", { exact: true }).fill("baseline/");
  await page.getByLabel("Maximum objects", { exact: true }).fill("10000000");
  await page
    .getByRole("button", { name: "Save and continue", exact: true })
    .click();
  await expect(
    page.getByText("Enter a whole number between 1 and 1000.", { exact: true }),
  ).toBeVisible();
  await page.getByLabel("Maximum objects", { exact: true }).fill("1000");
  await page
    .getByRole("button", { name: "Save and continue", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Authentication", exact: true }),
  ).toBeVisible();
  await page.getByLabel(/Access Key ID/).fill("controlled-test-key");
  await page.getByLabel(/Secret Access Key/).fill("controlled-test-secret");
  await page
    .getByRole("button", { name: "Save credentials and continue", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Access guide", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: /Continue to test/ }).click();
}
test("S3 wizard tests SDK access, previews object metadata, explicitly commits and reuses asset identities", async ({
  page,
}) => {
  await login(page);
  const sourceName = `Guided S3 SDK simulation ${Date.now()}`;
  await source(page, "trace-fixture-inventory", sourceName);
  await page
    .getByRole("button", { name: "Test Connection", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Connected successfully", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: /Continue to discovery/ }).click();
  await page
    .getByRole("button", { name: "Discover Metadata", exact: true })
    .click();
  await page.getByRole("button", { name: /Review metadata preview/ }).click();
  await expect(
    page.getByRole("heading", { name: "2 discovered assets" }),
  ).toBeVisible();
  await expect(page.getByText('"controlled-etag"').first()).toBeVisible();
  await expect(
    page.getByText("CONTROLLED S3 SDK SIMULATION — NOT LIVE AWS VERIFICATION", {
      exact: true,
    }),
  ).toBeVisible();
  await expect(
    page.getByRole("link", { name: /Inspect registered assets/ }),
  ).toHaveCount(0);
  await page.getByLabel("Search inventory").fill("orders");
  await expect(page.getByText("1 of 2 assets", { exact: true })).toBeVisible();
  await page.getByLabel("Search inventory").clear();
  await page.screenshot({
    path: "../docs/connections/s3-preview-desktop.png",
    fullPage: true,
  });
  const access = await new AxeBuilder({ page })
    .include(".connections-experience")
    .analyze();
  expect(access.violations).toEqual([]);
  await page
    .getByRole("button", { name: "Commit to Catalog", exact: true })
    .click();
  await page.getByRole("link", { name: /Inspect registered assets/ }).click();
  await expect(
    page.getByRole("heading", { name: "Metadata catalog", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText("orders.csv", { exact: true }).first(),
  ).toBeVisible();
  const sourceId = new URL(page.url()).hash.split("source=")[1];
  const before = (
    await (
      await page.request.get(`/api/browse/assets?source=${sourceId}`)
    ).json()
  ).items
    .map((a: { id: string }) => a.id)
    .sort();
  expect(before).toHaveLength(2);
  const stored = await (
    await page.request.get(`/api/connections/${sourceId}`)
  ).json();
  expect(stored.has_credentials).toBe(true);
  expect(JSON.stringify(stored)).not.toContain("controlled-test-secret");
  expect(stored.last_successful_test_at).toBeTruthy();
  await page.goto(`/#connections/${sourceId}/setup`);
  await page
    .getByRole("button", { name: "Use stored credentials", exact: true })
    .click();
  await page.getByRole("button", { name: /Continue to test/ }).click();
  await page.getByRole("button", { name: /Continue to discovery/ }).click();
  await page
    .getByRole("button", { name: "Discover Metadata", exact: true })
    .click();
  await page.getByRole("button", { name: /Review metadata preview/ }).click();
  await page
    .getByRole("button", { name: "Commit to Catalog", exact: true })
    .click();
  const after = (
    await (
      await page.request.get(`/api/browse/assets?source=${sourceId}`)
    ).json()
  ).items
    .map((a: { id: string }) => a.id)
    .sort();
  expect(after).toEqual(before);
  await page.goto("/#connections");
  await page
    .getByRole("searchbox", { name: "Filter connections", exact: true })
    .fill(sourceName);
  await page.getByRole("button", { name: "Search", exact: true }).click();
  await expect(
    page.getByRole("link", { name: sourceName, exact: true }),
  ).toBeVisible();
  await page.screenshot({
    path: "../docs/connections/connections-desktop.png",
    fullPage: true,
  });
});
test("S3 failures remain editable, show scoped recovery and support accessible mobile setup", async ({
  page,
}) => {
  await login(page);
  await source(
    page,
    "trace-fixture-denied",
    `Guided denied S3 simulation ${Date.now()}`,
  );
  await page
    .getByRole("button", { name: "Test Connection", exact: true })
    .click();
  await expect(page.getByRole("alert")).toContainText("s3:ListBucket");
  await expect(
    page.getByRole("button", { name: /Continue to discovery/ }),
  ).toBeDisabled();
  await page.getByText("Technical details", { exact: true }).click();
  await expect(
    page.getByText("S3_LIST_ACCESS_DENIED", { exact: true }),
  ).toBeVisible();
  await page.screenshot({
    path: "../docs/connections/s3-failure-desktop.png",
    fullPage: true,
  });
  expect(
    (
      await new AxeBuilder({ page })
        .include(".connections-experience")
        .analyze()
    ).violations,
  ).toEqual([]);
  await page
    .getByRole("button", { name: "Edit settings", exact: true })
    .click();
  await expect(page.getByLabel("Bucket name", { exact: true })).toHaveValue(
    "trace-fixture-denied",
  );
  for (const width of [320, 390, 768, 1440]) {
    await page.setViewportSize({ width, height: 950 });
    await expect(page.locator("main h1")).toBeVisible();
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth),
    ).toBeLessThanOrEqual(width);
    expect(
      (
        await new AxeBuilder({ page })
          .include(".connections-experience")
          .analyze()
      ).violations,
    ).toEqual([]);
    if (width === 390)
      await page.screenshot({
        path: "../docs/connections/s3-settings-mobile.png",
        fullPage: true,
      });
  }
});
