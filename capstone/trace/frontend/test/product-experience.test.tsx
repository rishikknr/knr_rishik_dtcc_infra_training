import { AssetRelationships } from "../src/features/catalog/AssetRelationships";
import { beforeEach, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { FormulaStudio } from "../src/features/integration/FormulaStudio";
import { MetricsPage } from "../src/features/integration/MetricsPage";
import { SuggestedMappings } from "../src/features/integration/SuggestedMappings";
import { GovernancePage } from "../src/features/governance/GovernancePage";
import { LineagePage } from "../src/features/integration/LineagePage";
import { request, mutate } from "../src/services/api";
import { resolveEditorToken } from "../src/features/integration/editorMetadata";
import type { Asset, Session } from "../src/types/api";
vi.mock("../src/services/api", async (original) => ({
  ...(await original<object>()),
  request: vi.fn(),
  mutate: vi.fn(),
}));
vi.mock("../src/features/integration/hooks", async (original) => ({
  ...(await original<object>()),
  useWorkspace: () => ({
    teams: [{ id: "team", name: "Finance", active: true }],
    assets: [
      {
        id: "orders",
        name: "Orders",
        external_key: "DB.RAW.ORDERS",
        asset_type: "DATASET",
        platform: "SNOWFLAKE",
        owner_team_id: "team",
        column_count: 2,
      },
    ],
    connections: [],
    loading: false,
    error: null,
  }),
}));
vi.mock("../src/features/integration/LineageGraph", () => ({
  LineageGraph: () => <div>Graph canvas</div>,
}));
const session = {
  organizationRole: "OWNER",
  teamRoles: {},
  user: { id: "author", displayName: "Author" },
  permissions: { permissions: [], teamPermissions: [] },
} as unknown as Session;
const formula = {
  id: "formula",
  name: "Net revenue",
  description: "Recognized revenue less processed refunds",
  category: "BUSINESS_METRIC",
  language: "SQL",
  owner_team_id: "team",
  latest_version: 1,
  latest_state: "REGISTERED",
  version_count: 1,
  versions: [
    {
      id: "fv",
      version: 1,
      state: "REGISTERED",
      expression: "SELECT SUM(NET_AMOUNT) FROM DB.RAW.ORDERS",
      referenced_assets: '["orders"]',
      referenced_columns: "[]",
      dialect: "snowflake",
    },
  ],
};
const candidate = {
  source_id: "file",
  source_name: "orders.csv",
  source_key: "s3://bucket/orders.csv",
  source_team_id: "team",
  target_id: "orders",
  target_name: "RAW.ORDERS",
  target_key: "DB.RAW.ORDERS",
  target_team_id: "team",
  source_matches: 1,
  target_matches: 1,
  matching_column_names: 2,
};
beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(request).mockImplementation(async (path) => {
    if (path.startsWith("/browse/formulas"))
      return { items: [formula], total: 1, hasMore: false };
    if (path === "/formulas/formula") return formula;
    if (path.startsWith("/mapping-suggestions"))
      return { items: [candidate], hasMore: false, basis: "Exact name" };
    if (path.startsWith("/governance/reviews"))
      return { items: [], total: 0, hasMore: false };
    if (path.startsWith("/lineage/"))
      return {
        nodes: [],
        edges: [],
        totalNodes: 0,
        totalEdges: 0,
        hasMore: false,
      };
    if (path.startsWith("/browse/columns"))
      return {
        items: [
          {
            id: "net",
            name: "NET_AMOUNT",
            data_type: "NUMBER",
            asset_id: "orders",
          },
        ],
        total: 1,
        hasMore: false,
      };
    return { items: [], total: 0, hasMore: false };
  });
  vi.mocked(mutate).mockResolvedValue({ id: "saved" });
});
it("opens the existing formula library without requesting a creation form or mutating records", async () => {
  render(<FormulaStudio session={session} />);
  expect(
    await screen.findByRole("button", { name: "Net revenue", exact: true }),
  ).toBeTruthy();
  expect(screen.queryByLabelText("Formula name")).toBeNull();
  expect(mutate).not.toHaveBeenCalled();
  expect(request).not.toHaveBeenCalledWith("/formulas/formula");
});
it("opens formula details before an explicitly chosen edit workspace", async () => {
  render(<FormulaStudio session={session} />);
  await userEvent.click(
    await screen.findByRole("button", { name: "Net revenue", exact: true }),
  );
  expect(
    await screen.findByRole("heading", { name: "Net revenue" }),
  ).toBeTruthy();
  expect(screen.getByText(/Latest recorded definition/)).toBeTruthy();
  expect(
    screen
      .getByRole("button", { name: "Overview & dependencies" })
      .getAttribute("aria-pressed"),
  ).toBe("true");
  expect(mutate).not.toHaveBeenCalled();
});
it("guides formula purpose and ownership before creating its metadata entry", async () => {
  render(<FormulaStudio session={session} />);
  await userEvent.click(
    screen.getByRole("button", { name: "Create formula", exact: true }),
  );
  await userEvent.type(screen.getByLabelText("Formula name"), "Monthly orders");
  expect(
    screen.queryByRole("button", { name: "Create formula & continue" }),
  ).toBeNull();
  await userEvent.click(screen.getByRole("button", { name: /Continue →/ }));
  expect(
    screen.getByRole("heading", { name: "Ownership & output" }),
  ).toBeTruthy();
  await userEvent.selectOptions(screen.getByLabelText("Owning team"), "team");
  expect(mutate).not.toHaveBeenCalled();
});
it("metrics default to browsing instead of the create form", async () => {
  vi.mocked(request).mockImplementation(async (path) =>
    path.startsWith("/browse/metrics")
      ? {
          items: [
            {
              id: "m",
              name: "Revenue",
              business_key: "NET_REVENUE",
              description: "Monthly total",
              owner_team_id: "team",
              revision: 1,
              latest_version: 1,
              formula_name: "Net revenue",
              consumer_count: 0,
            },
          ],
          total: 1,
          hasMore: false,
        }
      : { items: [], total: 0, hasMore: false },
  );
  render(<MetricsPage session={session} mappingsOnly={false} />);
  expect(
    await screen.findByRole("button", { name: "Revenue", exact: true }),
  ).toBeTruthy();
  expect(screen.queryByLabelText("Business name")).toBeNull();
  expect(mutate).not.toHaveBeenCalled();
});
it("reporting defaults to recorded usage and an honest unconnected state", async () => {
  render(<MetricsPage session={session} mappingsOnly />);
  expect(
    await screen.findByRole("heading", { name: "Recorded metric usage" }),
  ).toBeTruthy();
  expect(screen.getByText(/Power BI is not connected/)).toBeTruthy();
  expect(
    screen.queryByRole("heading", { name: "Choose the metric being used" }),
  ).toBeNull();
});
it("name-match suggestions do not create edges until explicit confirmation", async () => {
  render(<SuggestedMappings session={session} />);
  await screen.findByRole("button", { name: "Confirm declaration" });
  expect(mutate).not.toHaveBeenCalled();
  await userEvent.click(
    screen.getByRole("button", { name: "Confirm declaration" }),
  );
  await waitFor(() =>
    expect(mutate).toHaveBeenCalledWith("/mapping-suggestions/confirm", {
      sourceId: "file",
      targetId: "orders",
    }),
  );
  expect(await screen.findByText(/Declaration saved:/)).toBeTruthy();
});
it("ambiguous name matches are visible but cannot be confirmed in one click", async () => {
  vi.mocked(request).mockResolvedValue({
    items: [{ ...candidate, target_matches: 2 }],
    hasMore: false,
  });
  render(<SuggestedMappings session={session} />);
  expect(
    await screen.findByRole("button", { name: "Confirm declaration" }),
  ).toHaveProperty("disabled", true);
  expect(screen.getByText(/Ambiguous:/)).toBeTruthy();
  expect(mutate).not.toHaveBeenCalled();
});
it("viewer cannot confirm a mapping and dismissal does not mutate the registry", async () => {
  render(
    <SuggestedMappings session={{ ...session, organizationRole: "VIEWER" }} />,
  );
  expect(
    await screen.findByRole("button", { name: "Confirm declaration" }),
  ).toHaveProperty("disabled", true);
  await userEvent.click(
    screen.getByRole("button", { name: "Dismiss for now" }),
  );
  expect(
    screen.queryByRole("button", { name: "Confirm declaration" }),
  ).toBeNull();
  expect(mutate).not.toHaveBeenCalled();
});
it("governance empty queue offers existing definitions without fabricated decisions", async () => {
  render(<GovernancePage session={session} />);
  expect(
    await screen.findByRole("heading", {
      name: "No review requests in this queue",
    }),
  ).toBeTruthy();
  expect(screen.getByRole("link", { name: "Browse formulas" })).toBeTruthy();
  expect(mutate).not.toHaveBeenCalled();
});
it("impact starts with an explicit change and does not traverse before choosing a resource", async () => {
  render(<LineagePage session={session} impact />);
  expect(
    screen.getByRole("heading", { name: "What are you planning to change?" }),
  ).toBeTruthy();
  expect(
    screen.getByRole("button", { name: "Analyze recorded impact →" }),
  ).toHaveProperty("disabled", true);
  expect(
    vi
      .mocked(request)
      .mock.calls.some(([path]) => path.startsWith("/lineage/")),
  ).toBe(false);
  await userEvent.click(
    screen.getByRole("button", { name: /Remove a column/ }),
  );
  await userEvent.selectOptions(
    screen.getByLabelText("Dataset or reporting resource"),
    "orders",
  );
  await userEvent.selectOptions(await screen.findByLabelText("Column"), "net");
  expect(
    screen.getByRole("button", { name: "Analyze recorded impact →" }),
  ).toHaveProperty("disabled", false);
});
it("reference inspection resolves exact qualified fields and refuses ambiguous short table names", () => {
  const asset = {
    id: "a",
    name: "Orders",
    external_key: "DB.RAW.ORDERS",
  } as Asset;
  const ds = [
    {
      asset,
      columns: [
        {
          id: "c",
          name: "NET_AMOUNT",
          data_type: "NUMBER",
          asset_id: "a",
          nullable: true,
          description: null,
          ordinal_position: 1,
        },
      ],
    },
  ];
  expect(resolveEditorToken("DB.RAW.ORDERS.NET_AMOUNT", ds)?.column?.id).toBe(
    "c",
  );
  expect(
    resolveEditorToken("ORDERS", [
      ...ds,
      {
        asset: { ...asset, id: "b", external_key: "DB.OTHER.ORDERS" },
        columns: [],
      },
    ]),
  ).toBeNull();
  expect(resolveEditorToken("invented", ds)).toBeNull();
});

it("catalog consumers exclude siblings that merely share an upstream source", async () => {
  vi.mocked(request).mockResolvedValue({
    nodes: [
      { id: "selected", name: "Selected", asset_type: "DATASET" },
      { id: "source", name: "Shared source", asset_type: "DATASET" },
      { id: "actual", name: "Actual consumer", asset_type: "REPORT" },
      { id: "sibling", name: "Sibling report", asset_type: "REPORT" },
    ],
    edges: [
      { id: "a", source_asset_id: "source", target_asset_id: "selected" },
      { id: "b", source_asset_id: "selected", target_asset_id: "actual" },
      { id: "c", source_asset_id: "source", target_asset_id: "sibling" },
    ],
    totalNodes: 4,
    totalEdges: 3,
    hasMore: false,
  });
  render(<AssetRelationships id="selected" section="consumers" />);
  expect(
    await screen.findByRole("link", { name: "Actual consumer", exact: true }),
  ).toBeTruthy();
  expect(
    screen.queryByRole("link", { name: "Sibling report", exact: true }),
  ).toBeNull();
});

it("a reviewer waiting for clarification is not offered an invalid decision transition", async () => {
  vi.mocked(request).mockImplementation(async (path) =>
    path === "/governance/reviews/review"
      ? {
          id: "review",
          title: "Revenue v2",
          status: "CHANGES_REQUESTED",
          target_type: "FORMULA_VERSION",
          target_id: "fv",
          revision: 2,
          canReview: true,
          canResubmit: false,
          snapshot: "{}",
          events: [],
        }
      : path.startsWith("/governance/reviews")
        ? {
            items: [
              {
                id: "review",
                title: "Revenue v2",
                status: "CHANGES_REQUESTED",
                target_type: "FORMULA_VERSION",
                target_id: "fv",
                revision: 2,
                requester_name: "Test requester",
                owner_team_name: "Test team",
                updated_at: "2026-11-12T00:00:00Z",
              },
            ],
            total: 1,
            hasMore: false,
          }
        : { items: [], total: 0, hasMore: false },
  );
  render(<GovernancePage session={session} />);
  await userEvent.click(
    await screen.findByRole("button", { name: "Revenue v2", exact: true }),
  );
  expect(
    await screen.findByText(/The requester must resubmit a comment/),
  ).toBeTruthy();
  expect(screen.queryByRole("button", { name: "Record decision" })).toBeNull();
});
