import {
  test,
  expect,
  type Page,
  type APIRequestContext,
} from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
const fixture = JSON.parse(
  readFileSync(
    process.env.TRACE_BROWSER_FIXTURE ??
      "/tmp/trace-final-hardening/browser-fixture.json",
    "utf8",
  ),
);
const org = "a1000000-0000-4000-8000-000000000001",
  orgB = "b1000000-0000-4000-8000-000000000001",
  team = "a2000000-0000-4000-8000-000000000001";
const evidence = process.env.TRACE_BROWSER_EVIDENCE_DIR ?? resolve(process.cwd(), "../docs/final-hardening/evidence");
async function mutation(
  api: APIRequestContext,
  path: string,
  body?: unknown,
  method = "POST",
) {
  const csrf = await (await api.get("/api/auth/csrf")).json();
  const response = await api.fetch("/api" + path, {
    method,
    headers: { [csrf.headerName]: csrf.token },
    data: body,
  });
  expect(
    response.ok(),
    `${method} ${path}: ${await response.text()}`,
  ).toBeTruthy();
  return response.json();
}
async function login(page: Page, email = "owner@hardening.example.test") {
  await page.goto("/#login");
  await page.getByLabel("Email", { exact: true }).fill(email);
  await page.getByLabel("Password", { exact: true }).fill(fixture.password);
  await page.getByRole("button", { name: "Sign In", exact: true }).click();
  await expect(page.getByLabel("Account menu", { exact: true })).toBeVisible();
  if (email !== "fresh@hardening.example.test") {
    await mutation(page.request, "/auth/organization", { organizationId: org });
    await page.goto("/#overview");
    await page.reload();
    await expect(
      page.getByRole("heading", { name: /Overview|Good/ }).first(),
    ).toBeVisible();
  }
}
async function source(page: Page, name: string) {
  return (
    await mutation(page.request, "/connections", {
      name,
      connectionType: "MANUAL",
      ownerTeamId: team,
      configuration: {},
    })
  ).id as string;
}
async function asset(
  page: Page,
  connectionId: string,
  name: string,
  type = "DATASET",
) {
  return (
    await mutation(page.request, "/assets", {
      connectionId,
      ownerTeamId: team,
      name,
      assetType: type,
      externalKey: "HARDENING." + name.replaceAll(" ", "_"),
      columns:
        type === "DATASET"
          ? [{ name: "AMOUNT", dataType: "NUMBER", nullable: false }]
          : [],
    })
  ).id as string;
}
async function savedScreenshot(page: Page, name: string) {
  await page.screenshot({ path: `${evidence}/${name}.png`, fullPage: true });
}

test("identity, signup validation and no-organization onboarding", async ({
  page,
}) => {
  await page.goto("/#signup");
  await page.getByLabel("Full Name").fill("Fresh Hardening User");
  await page
    .getByLabel("Email", { exact: true })
    .fill("fresh@hardening.example.test");
  await page.getByLabel("Set Password", { exact: true }).fill(fixture.password);
  await page
    .getByLabel("Confirm Password", { exact: true })
    .fill("mismatch-password");
  await expect(
    page.getByRole("button", { name: "Create Account" }),
  ).toBeDisabled();
  await page
    .getByLabel("Confirm Password", { exact: true })
    .fill(fixture.password);
  await page.getByRole("button", { name: "Create Account" }).click();
  await expect(
    page.getByRole("heading", { name: "Sign in to Trace" }),
  ).toBeVisible();
  await login(page, "fresh@hardening.example.test");
  await expect(
    page.getByRole("heading", { name: "No organization membership" }),
  ).toBeVisible();
  await savedScreenshot(page, "browser-new-user");
  await page.getByLabel("Account menu", { exact: true }).click();
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Sign in to Trace" }),
  ).toBeVisible();
});

test("connection create/edit, mapped CSV preview refresh, commit and reimport", async ({
  page,
}) => {
  await login(page);
  await page.goto("/#connections/new");
  await page.getByRole("button", { name: /Manual sources.*Manual source/ }).click();
  await page.getByRole("button", { name: "Continue", exact: true }).click();
  await page
    .getByLabel("Connection name", { exact: true })
    .fill("Browser manual source");
  await page
    .getByRole("combobox", { name: "Owning team", exact: true })
    .selectOption(team);
  await page
    .getByRole("button", { name: "Save and continue", exact: true })
    .click();
  await page
    .getByRole("link", { name: "Open connection", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Browser manual source", exact: true }),
  ).toBeVisible();
  const connectionId = page.url().split("connections/")[1].split("/")[0];
  await page
    .getByRole("link", { name: "Edit source settings", exact: true })
    .click();
  await page
    .getByRole("textbox", { name: "Description", exact: true })
    .fill("Edited in a real browser");
  const edited = page.waitForResponse(
    (r) =>
      r.request().method() === "PATCH" &&
      r.url().endsWith(`/api/connections/${connectionId}`),
  );
  await page
    .getByRole("button", { name: "Save and continue", exact: true })
    .click();
  expect((await edited).ok()).toBeTruthy();
  await page.reload();
  await expect(
    page.getByRole("textbox", { name: "Description", exact: true }),
  ).toHaveValue("Edited in a real browser");
  const csv =
    "db,sch,obj,field,type\nHARDENING,RAW,ORDERS,AMOUNT,NUMBER\nHARDENING,RAW,ORDERS,ID,NUMBER";
  for (let iteration = 0; iteration < 2; iteration++) {
    await page.goto(`/#import?source=${connectionId}`);
    await page
      .getByRole("combobox", { name: "Input format", exact: true })
      .selectOption("GENERIC_CSV");
    await page.getByLabel("Metadata file", { exact: true }).setInputFiles({
      name: "hardening.csv",
      mimeType: "text/csv",
      buffer: Buffer.from(csv),
    });
    if (iteration === 0) {
      for (const [header, field] of Object.entries({
        db: "database",
        sch: "schema",
        obj: "table",
        field: "column",
        type: "data_type",
      }))
        await page
          .getByLabel(`Map ${header}`, { exact: true })
          .selectOption(field);
      await page
        .getByRole("button", { name: "Save mapping profile", exact: true })
        .click();
      await page.getByLabel("Profile name").fill("Hardening columns");
      await page
        .getByRole("button", { name: "Save profile", exact: true })
        .click();
      await expect(
        page.getByRole("status").filter({ hasText: "Mapping profile saved" }),
      ).toBeVisible();
    } else {
      await page
        .getByRole("combobox", { name: "Saved mapping", exact: true })
        .selectOption({ label: "Hardening columns" });
    }
    await page
      .getByRole("button", { name: "Validate and preview", exact: true })
      .click();
    await expect(page).toHaveURL(/#import\/[a-f0-9-]+/);
    await expect(
      page.getByRole("heading", { name: "Review import changes" }),
    ).toBeVisible();
    await page.reload();
    await expect(
      page.getByText("HARDENING.RAW.ORDERS", { exact: true }).first(),
    ).toBeVisible();
    if (iteration === 0) {
      const before = await page.request.get(
        `/api/browse/assets?source=${connectionId}`,
      );
      expect((await before.json()).total).toBe(0);
      await savedScreenshot(page, "browser-import-preview");
    }
    await page
      .getByRole("button", { name: "Commit to Catalog", exact: true })
      .click();
    await expect(
      page.getByRole("heading", { name: "Import results" }),
    ).toBeVisible();
    const items = (
      await (
        await page.request.get(`/api/browse/assets?source=${connectionId}`)
      ).json()
    ).items;
    expect(items).toHaveLength(1);
    if (iteration === 0) fixture.importedAsset = items[0].id;
    else expect(items[0].id).toBe(fixture.importedAsset);
  }
  await page
    .getByRole("link", { name: "HARDENING.RAW.ORDERS", exact: true })
    .click();
  await page.getByRole("link", { name: "ORDERS", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Registered columns" }),
  ).toBeVisible();
  await expect(
    page.getByRole("cell", { name: "AMOUNT", exact: true }),
  ).toBeVisible();
  await savedScreenshot(page, "browser-catalog-imported");
});

test("manual asset, additional column visibility, ownership tags and direct edit navigation", async ({
  page,
}) => {
  await login(page);
  const c = await source(page, "Curation browser source");
  const id = await asset(page, c, "Curation One");
  const second = await asset(page, c, "Curation Two");
  await page.goto(`/#edit-asset/${id}`);
  await page.getByLabel("Technical owner", { exact: true }).fill("Engineering");
  await page.getByLabel("Business owner", { exact: true }).fill("Analytics");
  await page
    .getByRole("button", { name: "Create organization tag", exact: true })
    .click();
  await page.getByLabel("Tag name", { exact: true }).fill("hardening");
  await page.getByRole("button", { name: "Create tag", exact: true }).click();
  await page
    .getByRole("button", { name: "Save metadata", exact: true })
    .click();
  await expect(page).toHaveURL(new RegExp(`#asset/${id}$`));
  await page.goto(`/#edit-asset/${id}`);
  const form = page.locator("form").filter({
    has: page.getByRole("heading", { name: "Register an additional column" }),
  });
  await form.getByLabel("Column name", { exact: true }).fill("NEW_COLUMN");
  await form.getByLabel("Data type", { exact: true }).fill("TEXT");
  await form
    .getByRole("button", { name: "Register column", exact: true })
    .click();
  await expect(
    page.getByText("NEW_COLUMN · TEXT", { exact: true }),
  ).toBeVisible();
  await page.goto(`/#edit-asset/${second}`);
  await expect(page.getByLabel("Asset name", { exact: true })).toHaveValue(
    "Curation Two",
  );
  await page
    .getByLabel("Asset name", { exact: true })
    .fill("Curation Two Edited");
  await page
    .getByRole("button", { name: "Save metadata", exact: true })
    .click();
  await expect(page).toHaveURL(new RegExp(`#asset/${second}$`));
  await page.goto(`/#asset/${id}`);
  await page
    .getByText("Source metadata and provenance", { exact: true })
    .click();
  await expect(
    page.getByText("Analytics", { exact: true }).first(),
  ).toBeVisible();
  await savedScreenshot(page, "browser-curation");
});

test("formula draft editing, immutable versions, metric metadata, report usage and revoke", async ({
  page,
}) => {
  await login(page);
  const c = await source(page, "Governance browser source");
  const raw = await asset(page, c, "Formula Source"),
    report = await asset(page, c, "Formula Report", "REPORT");
  await page.goto("/#formulas");
  await page
    .getByLabel("Formula name", { exact: true })
    .fill("Browser Revenue Formula");
  await page
    .getByRole("combobox", { name: "Owning team", exact: true })
    .selectOption(team);
  await page
    .getByRole("combobox", {
      name: "Linked transformation or asset",
      exact: true,
    })
    .selectOption(raw);
  await page
    .getByRole("button", { name: "Create formula", exact: true })
    .click();
  const editor = page.locator(".cm-content");
  await expect(editor).toBeVisible();
  await editor.fill("SELECT AMOUNT FROM RAW.ORDERS;");
  await page
    .getByRole("combobox", { name: "Save state", exact: true })
    .selectOption("REGISTERED");
  await page
    .getByRole("listbox", {
      name: "Referenced assets (explicit declarations)",
      exact: true,
    })
    .selectOption([raw]);
  await page
    .getByRole("button", { name: "Validate expression", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Validation result" }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "Save new version", exact: true })
    .click();
  await page
    .getByRole("button", {
      name: "Version history and comparison",
      exact: true,
    })
    .click();
  await page.locator("summary").filter({ hasText: "Version 1" }).click();
  await page
    .getByRole("button", { name: "Use as next version", exact: true })
    .click();
  await editor.fill("SELECT AMOUNT - 1 FROM RAW.ORDERS;");
  await page
    .getByRole("combobox", { name: "Save state", exact: true })
    .selectOption("DRAFT");
  await page
    .getByRole("button", { name: "Save new version", exact: true })
    .click();
  await page
    .getByRole("button", {
      name: "Version history and comparison",
      exact: true,
    })
    .click();
  await page.locator("summary").filter({ hasText: "Version 2" }).click();
  await page
    .getByRole("button", { name: "Edit draft version 2", exact: true })
    .click();
  await editor.fill("SELECT AMOUNT - 2 FROM RAW.ORDERS;");
  await page.getByRole("link", { name: "Catalog", exact: true }).click();
  await expect(
    page.getByRole("dialog", { name: "Discard unsaved changes?" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Cancel", exact: true }).click();
  await expect(editor).toContainText("AMOUNT - 2");
  await page
    .getByRole("combobox", { name: "Save state", exact: true })
    .selectOption("REGISTERED");
  await page
    .getByRole("button", { name: "Save draft changes", exact: true })
    .click();
  await page
    .getByRole("button", {
      name: "Version history and comparison",
      exact: true,
    })
    .click();
  await page
    .getByRole("combobox", { name: "Left version", exact: true })
    .selectOption({ label: "Version 1 · REGISTERED" });
  await page
    .getByRole("combobox", { name: "Right version", exact: true })
    .selectOption({ label: "Version 2 · REGISTERED" });
  await expect(
    page.getByLabel("Right expression", { exact: true }),
  ).toContainText("AMOUNT - 2");
  await savedScreenshot(page, "browser-formula-comparison");
  await page.goto("/#metrics");
  await page
    .getByLabel("Business name", { exact: true })
    .fill("Browser Revenue");
  await page
    .getByLabel("Business key", { exact: true })
    .fill("BROWSER_REVENUE");
  await page
    .getByRole("combobox", { name: "Owning team", exact: true })
    .selectOption(team);
  await page
    .getByRole("button", { name: "Create metric", exact: true })
    .click();
  await page
    .getByRole("combobox", { name: "Formula", exact: true })
    .selectOption({ label: "Browser Revenue Formula" });
  await page
    .getByRole("combobox", { name: "Registered formula version", exact: true })
    .selectOption({ label: "Version 2 · SELECT AMOUNT - 2 FROM RAW.ORDERS;" });
  await page
    .getByRole("button", { name: "Register version", exact: true })
    .click();
  await page
    .getByRole("combobox", { name: "Metric version", exact: true })
    .selectOption({ label: "Version 1" });
  await page
    .getByRole("combobox", {
      name: "Report / semantic model / measure / visual",
      exact: true,
    })
    .selectOption(report);
  await page
    .getByLabel("Evidence / declared usage", { exact: true })
    .fill("Browser declared usage");
  await page
    .getByRole("button", { name: "Save report mapping", exact: true })
    .click();
  await page.getByText("Edit metric metadata", { exact: true }).click();
  await page
    .getByLabel("Business name", { exact: true })
    .fill("Browser Revenue Edited");
  await page
    .getByRole("button", { name: "Save metric metadata", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Browser Revenue Edited", exact: true }),
  ).toBeVisible();
  await page.goto("/#report-mappings");
  const row = page
    .getByRole("row")
    .filter({ hasText: "Browser declared usage" });
  await expect(row).toBeVisible();
  await row.getByRole("button", { name: "Revoke usage mapping" }).click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Revoke mapping", exact: true })
    .click();
  await expect(row.getByText("Revoked", { exact: true })).toBeVisible();
  await savedScreenshot(page, "browser-report-mapping-revoked");
});

test("manual dataset and column mappings persist and feed the lineage graph", async ({
  page,
}) => {
  await login(page);
  const c = await source(page, "Lineage browser source");
  const raw = await asset(page, c, "Lineage Raw"),
    target = await asset(page, c, "Lineage Target");
  await page.goto(`/#lineage/${raw}`);
  await page
    .getByRole("link", { name: "Declare mapping", exact: true })
    .click();
  await page
    .getByRole("combobox", { name: "Upstream asset", exact: true })
    .selectOption(raw);
  await page
    .getByRole("combobox", { name: "Downstream asset", exact: true })
    .selectOption(target);
  await page
    .getByRole("combobox", { name: "Relationship type", exact: true })
    .selectOption("DATA_FLOW");
  await page
    .getByLabel("Evidence / source reference", { exact: true })
    .fill("Declared ingestion contract");
  await page
    .getByRole("button", { name: "Save manual mapping", exact: true })
    .click();
  await expect(
    page.getByText("Manual relationship stored with provenance.", {
      exact: true,
    }),
  ).toBeVisible();
  await page
    .getByRole("combobox", { name: "Relationship type", exact: true })
    .selectOption("COLUMN_DERIVATION");
  const from = (
    await (await page.request.get(`/api/assets/${raw}/columns`)).json()
  )[0].id;
  const to = (
    await (await page.request.get(`/api/assets/${target}/columns`)).json()
  )[0].id;
  await page
    .getByRole("combobox", { name: "Source column", exact: true })
    .selectOption(from);
  await page
    .getByRole("combobox", { name: "Target column", exact: true })
    .selectOption(to);
  const stored = page.waitForResponse(
    (r) =>
      r.request().method() === "POST" &&
      r.url().endsWith("/api/lineage/mappings"),
  );
  await page
    .getByRole("button", { name: "Save manual mapping", exact: true })
    .click();
  expect((await stored).ok()).toBeTruthy();
  await page.reload();
  await expect(
    page.getByRole("button", { name: "Lineage Target, Dataset", exact: true }),
  ).toBeVisible();
  await savedScreenshot(page, "browser-lineage");
});

test("viewer denials and organization switching clear prior tenant data", async ({
  page,
  browser,
}) => {
  await login(page);
  const c = await source(page, "Tenant private browser source");
  const id = await asset(page, c, "Tenant Private Asset");
  await page
    .getByRole("combobox", { name: "Active organization", exact: true })
    .selectOption(orgB);
  await expect(
    page.getByRole("heading", { name: "Overview", exact: true }),
  ).toBeVisible();
  await page.goto(`/#asset/${id}`);
  await expect(page.getByRole("alert")).toContainText(/not found/i);
  await expect(
    page.getByRole("heading", { name: "Tenant Private Asset", exact: true }),
  ).toHaveCount(0);
  const context = await browser.newContext();
  const viewer = await context.newPage();
  await login(viewer, "viewer@hardening.example.test");
  await viewer.goto(`/#connections/${c}`);
  await expect(
    viewer.getByRole("heading", {
      name: "Tenant private browser source",
      exact: true,
    }),
  ).toBeVisible();
  await expect(
    viewer.getByRole("button", { name: "Save connection", exact: true }),
  ).toHaveCount(0);
  await viewer.goto("/#register");
  await expect(
    viewer.getByRole("heading", { name: "No writable team", exact: true }),
  ).toBeVisible();
  const csrf = await (await viewer.request.get("/api/auth/csrf")).json();
  const denied = await viewer.request.post(`/api/connections/${c}/sync`, {
    headers: { [csrf.headerName]: csrf.token },
  });
  expect(denied.status()).toBe(403);
  await savedScreenshot(viewer, "browser-viewer-denied");
  await context.close();
});

test("platform provisioning, invitations and assisted password recovery", async ({
  page,
  browser,
}) => {
  await login(page, "platform@hardening.example.test");
  await page.goto("/#platform");
  await expect(
    page.getByRole("heading", { name: "Platform Management", exact: true }),
  ).toBeVisible();
  // API mutations use the same real cookie session and preserve UI verification separately.
  const created = await mutation(page.request, "/platform/organizations", {
    name: "Browser Provisioned Organization",
    slug: "browser-provisioned",
  });
  expect(created.id).toBeTruthy();
  await page.reload();
  await expect(
    page.getByText("Browser Provisioned Organization", { exact: true }).first(),
  ).toBeVisible();
  await mutation(page.request, "/auth/organization", { organizationId: org });
  const invitation = await mutation(page.request, "/organization/invitations", {
    email: "invited@hardening.example.test",
    role: "MEMBER",
  });
  const inviteContext = await browser.newContext();
  const invite = await inviteContext.newPage();
  await invite.goto(
    `/${new URL(invitation.link, "http://127.0.0.1:15174").hash}`,
  );
  await expect(
    invite.getByRole("heading", {
      name: "Join your organization",
      exact: true,
    }),
  ).toBeVisible();
  await invite
    .getByLabel("Display name", { exact: true })
    .fill("Invited Synthetic User");
  await invite
    .getByLabel("Create password", { exact: true })
    .fill(fixture.password);
  await invite
    .getByRole("button", { name: "Accept invitation", exact: true })
    .click();
  await expect(
    invite.getByRole("heading", {
      name: "Select an organization",
      exact: true,
    }),
  ).toBeVisible();
  await mutation(invite.request, "/auth/organization", { organizationId: org });
  await invite.goto("/#overview");
  await invite.reload();
  await expect(
    invite.getByRole("heading", { name: "Overview", exact: true }),
  ).toBeVisible();
  const target = fixture.recoveryUserId;
  const delivery = await mutation(
    page.request,
    `/platform/users/${target}/password-recovery`,
  );
  const resetContext = await browser.newContext();
  const reset = await resetContext.newPage();
  await reset.goto(`/${new URL(delivery.link, "http://127.0.0.1:15174").hash}`);
  await expect(
    reset.getByRole("heading", { name: "Reset password", exact: true }),
  ).toBeVisible();
  await reset
    .getByLabel("New password", { exact: true })
    .fill(fixture.password + "-reset");
  await reset
    .getByRole("button", { name: "Reset password", exact: true })
    .click();
  await expect(
    reset.getByText("Password changed. Previous sessions have been revoked.", {
      exact: true,
    }),
  ).toBeVisible();
  await inviteContext.close();
  await resetContext.close();
});

test("discovery enqueue, attempts, cancellation and retry use the durable real queue", async ({
  page,
}) => {
  await login(page);
  const saved = await mutation(page.request, "/connections", {
    name: "Controlled discovery browser source",
    connectionType: "GITHUB_SQL",
    ownerTeamId: team,
    configuration: {
      owner: "trace-fixture",
      repository: "controlled-metadata",
      revision: "slow-browser",
    },
  });
  await mutation(
    page.request,
    `/connections/${saved.id}/credentials`,
    { token: "synthetic-provider-token" },
    "PUT",
  );
  // Queue through the real API, then exercise durable cancellation/retry in history.
  await mutation(page.request, `/connections/${saved.id}/sync`);
  await page.goto(`/#connections/${saved.id}/history`);
  await expect(
    page.getByRole("button", { name: "Cancel discovery", exact: true }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "Cancel discovery", exact: true })
    .click();
  await expect(
    page.getByText("Cancelled", { exact: true }).first(),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "Retry discovery", exact: true })
    .click();
  await expect(
    page.getByRole("link", { name: "Review preview", exact: true }),
  ).toBeVisible({ timeout: 30000 });
  const detail = page
    .locator("details")
    .filter({
      has: page
        .locator("summary")
        .getByText("Attempt history", { exact: true }),
    })
    .first();
  await detail.locator("summary").click();
  await expect(detail).toContainText("COMPLETED");
  await savedScreenshot(page, "browser-discovery");
});

test("responsive workspace, keyboard modal behavior and automated accessibility", async ({
  page,
}) => {
  await login(page);
  const sources = (
    await (
      await page.request.get(
        "/api/browse/connections?q=Browser%20manual%20source",
      )
    ).json()
  ).items;
  const assets = (
    await (await page.request.get("/api/browse/assets?q=Lineage%20Raw")).json()
  ).items;
  const routes = [
    "overview",
    "connections",
    "connections/new",
    "import",
    "catalog",
    "register",
    "lineage",
    "formulas",
    "metrics",
    "report-mappings",
    "teams",
    "members",
    "organization",
    "audit",
    "profile",
    `connections/${sources[0].id}`,
    `asset/${assets[0].id}`,
    `edit-asset/${assets[0].id}`,
    `lineage/${assets[0].id}`,
    "formula-comparison",
    "search-dialog",
  ];
  const records = [];
  for (const width of [320, 390, 768, 1024, 1280, 1440]) {
    await page.setViewportSize({ width, height: 1000 });
    for (const route of routes) {
      if (route === "formula-comparison") await openComparison(page);
      else
        await page.goto(`/#${route === "search-dialog" ? "overview" : route}`);
      if (route === "search-dialog") {
        await page
          .getByRole("button", { name: "Search catalog", exact: true })
          .click();
        await expect(
          page.getByRole("dialog", { name: "Search catalog", exact: true }),
        ).toBeVisible();
      }
      if (route === "import") {
        await page
          .getByRole("combobox", { name: "Source connection", exact: true })
          .selectOption(sources[0].id);
        await page
          .getByRole("combobox", { name: "Input format", exact: true })
          .selectOption("GENERIC_CSV");
        await page
          .getByLabel("Metadata file", { exact: true })
          .setInputFiles({
            name: "layout.csv",
            mimeType: "text/csv",
            buffer: Buffer.from(
              "external_key,name,column,data_type\nLAYOUT,Layout,ID,NUMBER",
            ),
          });
        await expect(
          page.getByRole("region", { name: "CSV field mapping", exact: true }),
        ).toBeVisible();
      }
      if (route !== "search-dialog")
        await expect(page.locator("main h1").first()).toBeVisible();
      await expect(page.locator(".loading-state")).toHaveCount(0);
      await expect(page.getByRole("alert")).toHaveCount(0);
      const measurement = await page.evaluate(() => ({
        scroll: document.documentElement.scrollWidth,
        viewport: innerWidth,
      }));
      expect(measurement.scroll, `${route} at ${width}`).toBeLessThanOrEqual(
        width,
      );
      records.push({ route, width, ...measurement });
      if (route === "search-dialog") await page.keyboard.press("Escape");
    }
  }
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/#overview");
  const results = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
    .analyze();
  expect(results.violations).toEqual([]);
  await page.setViewportSize({ width: 390, height: 844 });
  await page
    .getByRole("button", { name: "Open navigation", exact: true })
    .click();
  await expect(
    page.getByRole("dialog", { name: "Navigation", exact: true }),
  ).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await savedScreenshot(page, "browser-responsive-mobile");
  console.log("RESPONSIVE", JSON.stringify(records));
});

test("team administration creates, edits and persists the responsible owner", async ({
  page,
}) => {
  await login(page);
  await page.goto("/#teams");
  const create = page.locator("form").filter({
    has: page.getByRole("button", { name: "Create team", exact: true }),
  });
  await create
    .getByLabel("Name", { exact: true })
    .fill("Browser Governance Team");
  await create.getByLabel("Slug", { exact: true }).fill("browser-governance");
  await create
    .getByRole("button", { name: "Create team", exact: true })
    .click();
  await page.getByRole("button", { name: /Browser Governance Team/ }).click();
  await page
    .getByLabel("Team name", { exact: true })
    .fill("Browser Governance Team Edited");
  await page
    .getByRole("textbox", { name: "Description", exact: true })
    .fill("Responsible synthetic owner retained");
  const saved = page.waitForResponse(
    (r) => r.request().method() === "PATCH" && r.url().includes("/api/teams/"),
  );
  await page.getByRole("button", { name: "Save team", exact: true }).click();
  expect((await saved).ok()).toBeTruthy();
  await page.reload();
  await page
    .getByRole("button", { name: /Browser Governance Team Edited/ })
    .click();
  await expect(
    page.getByRole("textbox", { name: "Description", exact: true }),
  ).toHaveValue("Responsible synthetic owner retained");
  await expect(
    page.getByRole("combobox", {
      name: "Team role for owner@hardening.example.test",
      exact: true,
    }),
  ).toHaveValue("OWNER");
  await savedScreenshot(page, "browser-team-administration");
});

test("accessibility across loaded forms, graph and version comparison", async ({
  page,
}) => {
  await login(page);
  const checks = [];
  const sources = (
    await (
      await page.request.get(
        "/api/browse/connections?q=Browser%20manual%20source",
      )
    ).json()
  ).items;
  const assets = (
    await (await page.request.get("/api/browse/assets?q=Lineage%20Raw")).json()
  ).items;
  const routes = [
    "connections",
    "import",
    "catalog",
    "register",
    "formulas",
    "metrics",
    "report-mappings",
    "teams",
    "members",
    "organization",
    "audit",
    "profile",
    `connections/${sources[0].id}`,
    `asset/${assets[0].id}`,
    `edit-asset/${assets[0].id}`,
    `lineage/${assets[0].id}`,
  ];
  for (const route of routes) {
    await page.goto(`/#${route}`);
    await expect(page.locator("main h1").first()).toBeVisible();
    await expect(page.locator(".loading-state")).toHaveCount(0);
    await expect(page.getByRole("alert")).toHaveCount(0);
    const result = await new AxeBuilder({ page })
      .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
      .analyze();
    checks.push({
      route,
      violations: result.violations.map((v) => ({
        id: v.id,
        impact: v.impact,
        nodes: v.nodes.map((n) => n.target),
      })),
    });
  }
  await openComparison(page);
  const comparison = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
    .analyze();
  checks.push({
    route: "formulas/registered-comparison",
    violations: comparison.violations.map((v) => ({
      id: v.id,
      impact: v.impact,
      nodes: v.nodes.map((n) => n.target),
    })),
  });
  console.log("ACCESSIBILITY", JSON.stringify(checks));
  expect(checks.flatMap((c) => c.violations)).toEqual([]);
});

test("browser pagination beyond 100 stays bounded and preserves final record access", async ({
  page,
}) => {
  await login(page);
  for (let first = 0; first < 125; first += 10)
    await Promise.all(
      Array.from({ length: Math.min(10, 125 - first) }, (_, offset) =>
        source(
          page,
          `Browser paging ${String(first + offset).padStart(3, "0")}`,
        ),
      ),
    );
  await page.goto("/#connections");
  await page
    .getByRole("searchbox", { name: "Filter connections", exact: true })
    .fill("Browser paging");
  await page.getByRole("button", { name: "Search", exact: true }).click();
  await expect(
    page.getByRole("status").filter({ hasText: "1–25 of 125 connections" }),
  ).toBeVisible();
  for (let n = 0; n < 4; n++) {
    await page
      .getByRole("button", { name: "Next connections", exact: true })
      .click();
    await expect(
      page.getByRole("status").filter({
        hasText: `${26 + n * 25}–${50 + n * 25} of 125 connections`,
      }),
    ).toBeVisible();
  }
  await expect(
    page.getByRole("button", { name: "Next connections", exact: true }),
  ).toBeDisabled();
  await page
    .getByRole("link", {
      name: "Browser paging 124",
      exact: true,
    })
    .click();
  await expect(
    page.getByRole("heading", { name: "Browser paging 124", exact: true }),
  ).toBeVisible();
  await savedScreenshot(page, "browser-pagination-last-page");
});

test("authentication layouts at six widths and measured lazy editor loading", async ({
  page,
  context,
}) => {
  const records = [];
  for (const width of [320, 390, 768, 1024, 1280, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    for (const route of ["login", "signup"]) {
      await page.goto(`/#${route}`);
      await expect(page.locator("h1").first()).toBeVisible();
      expect(
        await page.evaluate(() => document.documentElement.scrollWidth),
      ).toBeLessThanOrEqual(width);
      records.push({ route, width });
    }
  }
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/#login");
  const initial = await page.evaluate(() =>
    performance.getEntriesByType("resource").map((r) => r.name),
  );
  expect(
    initial.some(
      (name) => name.includes("CodeEditor") || name.includes("codemirror"),
    ),
  ).toBe(false);
  await login(page);
  await page.goto("/#formulas");
  await page.getByRole("button", { name: /Browser Revenue Formula/ }).click();
  await expect(
    page.getByRole("textbox", { name: "Expression", exact: true }),
  ).toBeVisible();
  const metrics = await context.newCDPSession(page);
  await metrics.send("Performance.enable");
  const sample = await metrics.send("Performance.getMetrics");
  console.log(
    "BROWSER_METRICS",
    JSON.stringify({
      authenticationLayouts: records,
      metrics: sample.metrics.filter((m) =>
        ["JSHeapUsedSize", "JSHeapTotalSize", "Nodes", "Documents"].includes(
          m.name,
        ),
      ),
    }),
  );
  await metrics.detach();
});

async function openComparison(page: Page) {
  await page.goto("/#formulas");
  await page.getByRole("button", { name: /Browser Revenue Formula/ }).click();
  await page
    .getByRole("button", {
      name: "Version history and comparison",
      exact: true,
    })
    .click();
  await page
    .getByRole("combobox", { name: "Left version", exact: true })
    .selectOption({ label: "Version 1 · REGISTERED" });
  await page
    .getByRole("combobox", { name: "Right version", exact: true })
    .selectOption({ label: "Version 2 · REGISTERED" });
  await expect(
    page.getByLabel("Right expression", { exact: true }),
  ).toBeVisible();
}
