import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {
  ConnectionWizard,
  ConnectionFailure,
} from "../src/features/integration/ConnectionWizard";
import { MetadataInventory } from "../src/features/integration/MetadataInventory";
import {
  awsPolicy,
  validateSource,
} from "../src/features/integration/connectionModel";
import { ApiError, mutate, request, api } from "../src/services/api";
import type { Connection, Session, Team } from "../src/types/api";
vi.mock("../src/services/api", async (original) => ({
  ...(await original<object>()),
  mutate: vi.fn(),
  request: vi.fn(),
  api: { commit: vi.fn() },
}));
const session = {
  organizationRole: "OWNER",
  teamRoles: {},
  user: { id: "user" },
} as Session;
const teams = [{ id: "team", name: "Data platform", active: true }] as Team[];
const initial = {
  id: "source",
  name: "S3 baseline",
  platform: "AMAZON_S3",
  owner_team_id: "team",
  configuration: {
    region: "ap-south-1",
    bucket: "novamart-trace-test-space",
    prefix: "novamart/baseline/",
    maxObjects: 1000,
  },
  has_credentials: true,
  status: "NOT_TESTED",
  reason_code: null,
  last_tested_at: null,
  last_successful_sync_at: null,
} as Connection;
const props = { session, teams, onChanged: vi.fn() };
const run = {
  id: "run-1",
  status: "COMPLETED",
  phase: "COMPLETED",
  initiated_by: "user",
  attempt_count: 1,
  records_discovered: 1,
  import_id: "preview-1",
  warnings: "[]",
  started_at: "2026-11-11T10:00:00Z",
  finished_at: "2026-11-11T10:00:01Z",
  error_code: null,
};
const batch = {
  id: "preview-1",
  connectionId: "source",
  status: "PREVIEW_READY",
  preview: {
    datasets: [
      {
        name: "orders.csv",
        externalKey:
          "s3://novamart-trace-test-space/novamart/baseline/orders.csv",
        assetType: "FILE",
        columns: [],
        metadata: {
          size: 1234,
          lastModified: "2026-11-11T10:00:00Z",
          etag: "opaque-etag",
        },
      },
    ],
    warnings: [],
  },
  datasetChanges: [
    {
      externalKey:
        "s3://novamart-trace-test-space/novamart/baseline/orders.csv",
      status: "NEW",
    },
  ],
};
beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(request).mockResolvedValue(initial);
});
async function goToTest() {
  await userEvent.click(
    screen.getByRole("button", { name: "Use stored credentials" }),
  );
  await userEvent.click(
    screen.getByRole("button", { name: /Continue to test/ }),
  );
}
describe("guided connections", () => {
  it("accepts NovaMart settings but rejects the actual ten-million-object failure", () => {
    const v = {
      region: "ap-south-1",
      bucket: "novamart-trace-test-space",
      prefix: "novamart/baseline/",
      maxObjects: "10000000",
    };
    expect(validateSource("AMAZON_S3", v).maxObjects).toContain("1 and 1000");
    expect(validateSource("AMAZON_S3", { ...v, maxObjects: "1000" })).toEqual(
      {},
    );
  });
  it("generates a prefix-scoped inventory policy without object read permissions", () => {
    const p = JSON.parse(
      awsPolicy(
        "novamart-trace-test-space",
        "novamart/baseline/",
        "ap-south-1",
      ),
    );
    expect(p.Statement[1].Condition.StringEquals["s3:prefix"]).toBe(
      "novamart/baseline/",
    );
    expect(p.Statement[1].Resource).toBe(
      "arn:aws:s3:::novamart-trace-test-space",
    );
    expect(JSON.stringify(p)).not.toContain("GetObject");
    expect(awsPolicy("test-bucket", "", "cn-north-1")).toContain(
      "arn:aws-cn:s3",
    );
  });
  it("shows provider-specific scope and blocks invalid settings before API persistence", async () => {
    render(<ConnectionWizard {...props} />);
    await userEvent.click(screen.getByRole("button", { name: /Amazon S3/ }));
    await userEvent.click(screen.getByRole("button", { name: "Continue" }));
    expect(
      screen.getByLabelText("Maximum objects", { exact: false }),
    ).toHaveProperty("value", "1000");
    await userEvent.click(
      screen.getByRole("button", { name: "Save and continue" }),
    );
    expect(screen.getByText("Enter a connection name.")).toBeTruthy();
    expect(mutate).not.toHaveBeenCalled();
    expect(
      screen.getByRole("heading", { name: "Source settings" }),
    ).toBeTruthy();
  });
  it("never reads back stored credentials and clears newly saved credentials", async () => {
    vi.mocked(mutate).mockResolvedValue({ saved: true });
    render(<ConnectionWizard {...props} initial={initial} />);
    expect(screen.getByLabelText(/Secret Access Key/)).toHaveProperty(
      "value",
      "",
    );
    expect(screen.getByLabelText(/Secret Access Key/)).toHaveProperty(
      "type",
      "password",
    );
    await userEvent.type(
      screen.getByLabelText(/Access Key ID/),
      "controlled-key",
    );
    await userEvent.type(
      screen.getByLabelText(/Secret Access Key/),
      "controlled-secret",
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Save credentials and continue" }),
    );
    await waitFor(() =>
      expect(
        screen.getByRole("heading", { name: "Access guide" }),
      ).toBeTruthy(),
    );
    expect(mutate).toHaveBeenCalledWith(
      "/connections/source/credentials",
      { accessKeyId: "controlled-key", secretAccessKey: "controlled-secret" },
      "PUT",
    );
    await userEvent.click(
      screen.getByRole("button", { name: /Authentication/ }),
    );
    expect(screen.getByLabelText(/Secret Access Key/)).toHaveProperty(
      "value",
      "",
    );
    expect(screen.queryByText("controlled-secret")).toBeNull();
  });
  it("rejects credential whitespace without sending it", async () => {
    render(<ConnectionWizard {...props} initial={initial} />);
    await userEvent.type(
      screen.getByLabelText(/Access Key ID/),
      "controlled-key",
    );
    await userEvent.type(
      screen.getByLabelText(/Secret Access Key/),
      "secret with spaces",
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Save credentials and continue" }),
    );
    expect(screen.getByRole("alert").textContent).toContain(
      "must not contain spaces",
    );
    expect(mutate).not.toHaveBeenCalled();
  });
  it("rejects public-key bodies before saving Snowflake credentials", async () => {
    render(
      <ConnectionWizard
        {...props}
        initial={{ ...initial, platform: "SNOWFLAKE" }}
      />,
    );
    await userEvent.type(
      screen.getByLabelText(/RSA private key/),
      "synthetic-public-key-body%",
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Save credentials and continue" }),
    );
    expect(screen.getByRole("alert").textContent).toContain(
      "complete private-key PEM",
    );
    expect(mutate).not.toHaveBeenCalled();
  });
  it("identifies the schema allowlist copied into the warehouse field", () => {
    expect(
      validateSource("SNOWFLAKE", {
        account: "YJUJFLN-CW27089",
        username: "TRACE_METADATA_READER",
        database: "NOVAMART_DB",
        warehouse: "RAW, STAGING, CURATED, ANALYTICS",
        schemas: "RAW, STAGING, CURATED, ANALYTICS",
      }).warehouse,
    ).toContain("one warehouse");
  });
  it("uses provider-specific key-pair authentication for Snowflake", () => {
    render(
      <ConnectionWizard
        {...props}
        initial={{ ...initial, platform: "SNOWFLAKE" }}
      />,
    );
    expect(screen.getByLabelText(/RSA private key/)).toHaveProperty(
      "value",
      "",
    );
    expect(screen.getByLabelText(/RSA private key/).className).toBe(
      "secret-masked",
    );
    expect(
      screen.getByText(/S3 stage credentials cannot sign in/),
    ).toBeTruthy();
    expect(screen.queryByLabelText(/Access Key ID/)).toBeNull();
  });
  it("shows pending state, safe failure evidence and a retry without recreating the source", async () => {
    let resolve!: (c: Connection) => void;
    vi.mocked(mutate).mockImplementationOnce(
      () =>
        new Promise((r) => {
          resolve = r;
        }),
    );
    render(<ConnectionWizard {...props} initial={initial} />);
    await goToTest();
    await userEvent.click(
      screen.getByRole("button", { name: "Test Connection", exact: true }),
    );
    expect(screen.getByRole("button", { name: "Testing…" })).toHaveProperty(
      "disabled",
      true,
    );
    resolve({
      ...initial,
      status: "PERMISSION_DENIED",
      reason_code: "S3_LIST_ACCESS_DENIED",
      last_test_diagnostic_id: "safe-reference",
    });
    await waitFor(() =>
      expect(screen.getByRole("alert").textContent).toContain("s3:ListBucket"),
    );
    await userEvent.click(screen.getByText("Technical details"));
    expect(screen.getByText("safe-reference")).toBeTruthy();
    expect(
      screen.getByRole("button", { name: /Continue to discovery/ }),
    ).toHaveProperty("disabled", true);
    vi.mocked(mutate).mockResolvedValueOnce({
      ...initial,
      status: "CONNECTED",
      reason_code: "CONNECTED",
    });
    await userEvent.click(
      screen.getByRole("button", { name: "Test Connection again" }),
    );
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: /Continue to discovery/ }),
      ).toHaveProperty("disabled", false),
    );
    expect(mutate).toHaveBeenCalledTimes(2);
    expect(
      vi
        .mocked(mutate)
        .mock.calls.every(([path]) => path === "/connections/source/test"),
    ).toBe(true);
  });
  it("keeps service errors actionable and displays request correlation", () => {
    render(
      <ConnectionFailure
        code="PROCESSOR_UNAVAILABLE"
        reference="request-123"
      />,
    );
    expect(screen.getByRole("alert").textContent).toContain("processor URL");
    expect(screen.getByText("request-123")).toBeTruthy();
  });
  it("discovers real API inventory and requires explicit commit before catalog navigation", async () => {
    vi.mocked(mutate).mockImplementation(async (path) =>
      path.endsWith("/test")
        ? { ...initial, status: "CONNECTED", reason_code: "CONNECTED" }
        : run,
    );
    vi.mocked(request).mockImplementation(async (path) =>
      path.startsWith("/imports/") ? batch : path.startsWith("/mapping-suggestions") ? {items:[],hasMore:false,basis:"Exact name"} : initial,
    );
    vi.mocked(api.commit).mockResolvedValue({
      ...batch,
      status: "COMMITTED",
    } as never);
    render(<ConnectionWizard {...props} initial={initial} />);
    await goToTest();
    await userEvent.click(
      screen.getByRole("button", { name: "Test Connection", exact: true }),
    );
    await userEvent.click(
      await screen.findByRole("button", { name: /Continue to discovery/ }),
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Discover Metadata" }),
    );
    await userEvent.click(
      await screen.findByRole("button", { name: /Review metadata preview/ }),
    );
    expect(await screen.findByText("orders.csv")).toBeTruthy();
    expect(screen.getByText("opaque-etag")).toBeTruthy();
    expect(api.commit).not.toHaveBeenCalled();
    await userEvent.click(
      screen.getByRole("button", { name: "Commit to Catalog" }),
    );
    await waitFor(() => expect(api.commit).toHaveBeenCalledWith("preview-1"));
    expect(
      (
        await screen.findByRole("link", { name: /Inspect registered assets/ })
      ).getAttribute("href"),
    ).toBe("#catalog?source=source");
  });
  it("does not present a queued timestamp as an actual discovery start", async () => {
    vi.mocked(mutate).mockImplementation(async (path) =>
      path.endsWith("/test")
        ? { ...initial, status: "CONNECTED", reason_code: "CONNECTED" }
        : {
            ...run,
            status: "PENDING",
            phase: "QUEUED",
            attempt_count: 0,
            started_at: "2026-11-11T10:00:00Z",
            finished_at: null,
            import_id: null,
            records_discovered: 0,
          },
    );
    render(<ConnectionWizard {...props} initial={initial} />);
    await goToTest();
    await userEvent.click(
      screen.getByRole("button", { name: "Test Connection", exact: true }),
    );
    await userEvent.click(
      await screen.findByRole("button", { name: /Continue to discovery/ }),
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Discover Metadata", exact: true }),
    );
    expect(await screen.findByText("Queued", { exact: true })).toBeTruthy();
    expect(
      screen.getByRole("button", { name: "Cancel discovery", exact: true }),
    ).toBeTruthy();
  });
  it("preserves the connection when encryption configuration is unavailable", async () => {
    vi.mocked(mutate).mockRejectedValue(
      new ApiError("Encryption unavailable", "ENCRYPTION_UNAVAILABLE"),
    );
    render(<ConnectionWizard {...props} initial={initial} />);
    await userEvent.type(
      screen.getByLabelText(/Access Key ID/),
      "controlled-key",
    );
    await userEvent.type(
      screen.getByLabelText(/Secret Access Key/),
      "controlled-secret",
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Save credentials and continue" }),
    );
    expect((await screen.findByRole("alert")).textContent).toContain(
      "TRACE_CONNECTOR_ENCRYPTION_KEY",
    );
    expect(
      screen.getByRole("button", { name: "Save credentials and continue" }),
    ).toBeTruthy();
  });
  it("searches and filters returned object metadata", async () => {
    render(
      <MetadataInventory
        datasets={batch.preview.datasets}
        changes={new Map(batch.datasetChanges.map((c) => [c.externalKey, c]))}
        connectionId="source"
      />,
    );
    await userEvent.type(screen.getByLabelText("Search inventory"), "missing");
    expect(screen.getByRole("status").textContent).toBe("0 of 1 assets");
    await userEvent.clear(screen.getByLabelText("Search inventory"));
    await userEvent.selectOptions(
      screen.getByLabelText("Change filter"),
      "NEW",
    );
    expect(screen.getByText("orders.csv")).toBeTruthy();
  });
});
