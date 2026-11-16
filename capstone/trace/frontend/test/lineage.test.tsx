import { beforeEach, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { LineagePage } from "../src/features/integration/LineagePage";
import { TransformationPage } from "../src/features/integration/TransformationPage";
import { request, mutate } from "../src/services/api";
import type { Session } from "../src/types/api";
vi.mock("../src/services/api", async (original) => ({
  ...(await original<object>()),
  request: vi.fn(),
  mutate: vi.fn(),
}));
vi.mock("../src/features/integration/hooks", async (original) => {
  const workspace = {
    assets: [
      {
        id: "root",
        name: "Orders",
        asset_type: "DATASET",
        platform: "SNOWFLAKE",
        external_key: "DB.RAW.ORDERS",
        connection_name: "Snowflake",
        owner_team_id: "team",
      },
      {
        id: "output",
        name: "Revenue",
        asset_type: "DATASET",
        platform: "SNOWFLAKE",
        external_key: "DB.ANALYTICS.REVENUE",
        connection_name: "Snowflake",
        owner_team_id: "team",
      },
    ],
    teams: [],
    loading: false,
    error: null,
  };
  return { ...(await original<object>()), useWorkspace: () => workspace };
});
vi.mock("../src/features/integration/LineageGraph", () => ({
  LineageGraph: ({
    onExplore,
  }: {
    onExplore?: (id: string, direction: string) => void;
  }) => (
    <button onClick={() => onExplore?.("output", "upstream")}>
      Explore selected graph node
    </button>
  ),
}));
const session = {
  organizationRole: "OWNER",
  teamRoles: {},
  user: { id: "user" },
} as Session;
const graph = {
  nodes: [
    {
      id: "root",
      name: "Orders",
      asset_type: "DATASET",
      external_key: "DB.RAW.ORDERS",
    },
  ],
  edges: [
    {
      id: "edge",
      source_asset_id: "root",
      target_asset_id: "output",
      relationship_type: "DATA_FLOW",
      provenance: "MANUAL_DECLARATION",
      evidence_ref: "COPY revision",
    },
  ],
  columnEdges: [],
  history: [],
  totalNodes: 250,
  totalEdges: 240,
  nextOffset: 80,
  snapshot: "revision-one",
  hasMore: true,
  truncated: true,
  label: "Recorded dependencies",
};
beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(request).mockImplementation(async (path) =>
    path.startsWith("/lineage/")
      ? graph
      : path.includes("/curation")
        ? { metadata: "{}" }
        : path.includes("/history")
          ? { items: [], total: 0, offset: 0, hasMore: false }
          : { items: [], hasMore: false, total: 0 },
  );
});
it("paginates complete reachability with snapshot continuity and no depth selector", async () => {
  render(<LineagePage session={session} initialRoot="root" />);
  expect(await screen.findByText("250 reachable nodes")).toBeTruthy();
  expect(screen.queryByLabelText("Maximum depth")).toBeNull();
  await userEvent.click(
    screen.getByRole("button", { name: "Continue exploration" }),
  );
  await waitFor(() =>
    expect(request).toHaveBeenCalledWith(
      expect.stringContaining("offset=80&limit=80&snapshot=revision-one"),
      expect.anything(),
    ),
  );
});
it("starts a fresh snapshot when traversal direction changes", async () => {
  render(<LineagePage session={session} initialRoot="root" />);
  await screen.findByText("250 reachable nodes");
  await userEvent.selectOptions(screen.getByLabelText("Direction"), "upstream");
  await waitFor(() =>
    expect(request).toHaveBeenCalledWith(
      "/lineage/root?direction=upstream&offset=0&limit=80",
      expect.anything(),
    ),
  );
});
it("reroots through the graph inspector action", async () => {
  render(<LineagePage session={session} initialRoot="root" />);
  await userEvent.click(
    await screen.findByRole("button", { name: "Explore selected graph node" }),
  );
  await waitFor(() =>
    expect(request).toHaveBeenCalledWith(
      "/lineage/output?direction=upstream&offset=0&limit=80",
      expect.anything(),
    ),
  );
});
it("surfaces traversal errors without showing stale results", async () => {
  vi.mocked(request).mockImplementation(async (path) => {
    if (path.startsWith("/lineage/")) throw new Error("Registry unavailable");
    return { items: [], hasMore: false };
  });
  render(<LineagePage session={session} initialRoot="root" />);
  expect(await screen.findByRole("alert")).toBeTruthy();
  expect(screen.queryByText("250 reachable nodes")).toBeNull();
});
it("requires analysis and explicit confirmation before saving SQL relationships", async () => {
  vi.mocked(mutate).mockResolvedValue({
    status: "PARSED",
    definition: "SELECT 1",
    definitionHash: "abc",
    dialect: "snowflake",
    references: [
      {
        identifier: "DB.RAW.ORDERS",
        status: "RESOLVED",
        matches: [{ id: "root", name: "DB.RAW.ORDERS" }],
      },
    ],
    columnMappings: [],
    rules: [],
    warnings: [],
  });
  render(<TransformationPage session={session} initialTarget="output" />);
  await waitFor(() =>
    expect(request).toHaveBeenCalledWith("/assets/output/curation"),
  );
  expect(
    screen.queryByRole("button", { name: "Confirm selected dependencies" }),
  ).toBeNull();
  await userEvent.click(screen.getByRole("button",{name:"Review new SQL",exact:true}));
  await userEvent.click(screen.getByLabelText("SQL definition"));
  await userEvent.keyboard("SELECT 1");
  await userEvent.click(
    screen.getByRole("button", { name: "Analyze dependency candidates" }),
  );
  expect(
    await screen.findByRole("button", {
      name: "Confirm selected dependencies",
    }),
  ).toBeTruthy();
  expect(mutate).toHaveBeenCalledTimes(1);
});
it("renders a persisted structured metric snapshot without crashing the detail workspace", async () => {
  const metric = {
    id: "metric",
    name: "Test metric",
    business_key: "TEST_METRIC",
    description: "Current description",
    owner_team_id: "team",
    revision: 1,
  };
  vi.mocked(request).mockImplementation(async (path) => {
    if (path === "/metrics/metric") return metric;
    if (path.startsWith("/browse/metric-versions"))
      return {
        items: [
          {
            id: "mv",
            version: 1,
            formula_version_id: "fv",
            formula_name: "Test calculation",
            formula_version: 1,
            expression: "SELECT 1",
            definition_snapshot: {
              type: "jsonb",
              value: JSON.stringify({
                description: "Captured business definition",
              }),
            },
            created_at: "2026-11-11T10:00:00Z",
          },
        ],
        hasMore: false,
        total: 1,
      };
    if (path.startsWith("/browse/metrics"))
      return { items: [metric], hasMore: false, total: 1 };
    return { items: [], hasMore: false, total: 0 };
  });
  const { MetricsPage } = await import(
    "../src/features/integration/MetricsPage"
  );
  render(<MetricsPage session={session} mappingsOnly={false} />);
  await userEvent.click(
    await screen.findByRole("button", { name: "Test metric",exact:true }),
  );
  await screen.findByRole("heading", { name: "Test metric", exact: true });
  const summary = await screen.findByText(
    "Business definition at registration",
  );
  await userEvent.click(summary);
  expect(await screen.findByText(/Captured business definition/)).toBeTruthy();
});
it("shows named column dependencies for an unregistered formula without rendering stale impact", async () => {
  vi.mocked(request).mockResolvedValue([
    {
      id: "column",
      asset_id: "root",
      asset_key: "DB.RAW.ORDERS",
      name: "NET_AMOUNT",
      data_type: "NUMBER",
    },
  ]);
  const { DefinitionDependencies } = await import(
    "../src/features/integration/DefinitionDependencies"
  );
  render(
    <DefinitionDependencies
      name="Draft calculation"
      assets={[]}
      refs={[]}
      columnRefs={["column"]}
      expression="SUM(NET_AMOUNT)"
    />,
  );
  expect(
    await screen.findByRole("link", { name: "DB.RAW.ORDERS.NET_AMOUNT" }),
  ).toBeTruthy();
  expect(screen.queryByText(/Pre-registration impact/)).toBeNull();
});
it("offers a recoverable workspace error state when a view throws", async () => {
  const errors = vi.spyOn(console, "error").mockImplementation(() => {});
  try {
    const { WorkspaceErrorBoundary } = await import(
      "../src/components/WorkspaceErrorBoundary"
    );
    function Broken() {
      throw new Error("Controlled rendering regression");
    }
    render(
      <WorkspaceErrorBoundary>
        <Broken />
      </WorkspaceErrorBoundary>,
    );
    expect(await screen.findByRole("alert")).toBeTruthy();
    expect(
      screen.getByRole("button", { name: "Reload workspace" }),
    ).toBeTruthy();
    expect(screen.getByRole("link", { name: "Open catalog" })).toBeTruthy();
  } finally {
    errors.mockRestore();
  }
});
