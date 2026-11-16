import { describe, it, expect, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {
  ConfirmDialog,
  EmptyState,
  Status,
  TableRegion,
} from "../src/components/Design";
import { Feedback, Loading } from "../src/components/Feedback";
import { ApiError } from "../src/services/api";
import { TeamSelect, AssetSelect } from "../src/features/integration/shared";
import { PageNavigation } from "../src/features/integration/pagination";
import { PasswordField } from "../src/features/auth/PasswordField";
import type { Asset, Session, Team } from "../src/types/api";
const session = {
  organizationRole: "MEMBER",
  teamRoles: { edit: "EDITOR", view: "VIEWER" },
} as Session;
const teams = [
  { id: "edit", name: "Editing", active: true },
  { id: "view", name: "Viewing", active: true },
  { id: "inactive", name: "Inactive", active: false },
] as Team[];
const assets = [
  {
    id: "one",
    external_key: "DB.ONE",
    name: "One",
    asset_type: "DATASET",
    connection_name: "Source A",
  },
  {
    id: "two",
    external_key: "DB.TWO",
    name: "Two",
    asset_type: "REPORT",
    connection_name: "Source B",
  },
] as Asset[];
describe("accessible shared controls", () => {
  it("password visibility never submits its containing form", async () => {
    const submit = vi.fn();
    render(
      <form onSubmit={submit}>
        <PasswordField
          label="Password"
          name="password"
          autoComplete="current-password"
        />
      </form>,
    );
    await userEvent.type(screen.getByLabelText("Password"), "Sample password!");
    await userEvent.click(
      screen.getByRole("button", { name: "Show password" }),
    );
    expect((screen.getByLabelText("Password") as HTMLInputElement).type).toBe(
      "text",
    );
    expect(submit).not.toHaveBeenCalled();
    await userEvent.click(
      screen.getByRole("button", { name: "Hide password" }),
    );
    expect((screen.getByLabelText("Password") as HTMLInputElement).type).toBe(
      "password",
    );
  });
  it("validation details identify the field and CSV row", () => {
    render(
      <Feedback
        error={
          new ApiError("Invalid import", "INVALID_METADATA", [
            { row: 7, field: "column", message: "Conflicting definition" },
          ])
        }
      />,
    );
    expect(screen.getByRole("alert").textContent).toContain(
      "Row 7 · column: Conflicting definition",
    );
  });
  it("loading and status indicators expose their text", () => {
    render(
      <>
        <Loading>Loading sources…</Loading>
        <Status value="NOT_TESTED" />
      </>,
    );
    expect(screen.getByRole("status").textContent).toContain("Loading sources");
    expect(screen.getByText("Not tested")).toBeTruthy();
  });
  it("empty states retain an actionable destination", () => {
    render(
      <EmptyState
        title="No sources"
        description="Create a source"
        actions={<a href="#connections/new">Add source</a>}
      />,
    );
    expect(screen.getByRole("link").getAttribute("href")).toBe(
      "#connections/new",
    );
  });
  it("wide tables are keyboard-scrollable named regions", () => {
    render(
      <TableRegion label="Columns">
        <table>
          <tbody>
            <tr>
              <td>ID</td>
            </tr>
          </tbody>
        </table>
      </TableRegion>,
    );
    expect(screen.getByRole("region", { name: "Columns" }).tabIndex).toBe(0);
  });
  it("cancelling a confirmation never invokes the mutation", async () => {
    const close = vi.fn(),
      confirm = vi.fn();
    render(
      <ConfirmDialog
        title="Revoke?"
        description="Retain history"
        action="Revoke"
        onClose={close}
        onConfirm={confirm}
      />,
    );
    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(close).toHaveBeenCalledOnce();
    expect(confirm).not.toHaveBeenCalled();
  });
  it("native cancel dismissal follows the same safe path", () => {
    const close = vi.fn(),
      confirm = vi.fn();
    render(
      <ConfirmDialog
        title="Revoke?"
        description="Retain history"
        action="Revoke"
        onClose={close}
        onConfirm={confirm}
      />,
    );
    fireEvent(
      screen.getByRole("dialog"),
      new Event("cancel", { bubbles: true, cancelable: true }),
    );
    expect(close).toHaveBeenCalledOnce();
    expect(confirm).not.toHaveBeenCalled();
  });
  it("confirmation invokes the mutation once after closing", async () => {
    const calls: string[] = [];
    render(
      <ConfirmDialog
        title="Revoke?"
        description="Retain history"
        action="Revoke"
        onClose={() => calls.push("close")}
        onConfirm={() => calls.push("confirm")}
      />,
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Revoke", exact: true }),
    );
    expect(calls).toEqual(["close", "confirm"]);
  });
});
describe("permission-aware resource selectors", () => {
  it("team members see only active writable team choices", () => {
    render(<TeamSelect teams={teams} session={session} />);
    expect(screen.getByRole("option", { name: "Editing" })).toBeTruthy();
    expect(screen.queryByRole("option", { name: "Viewing" })).toBeNull();
    expect(screen.queryByRole("option", { name: "Inactive" })).toBeNull();
  });
  it("organization viewers cannot use stale team editor privileges", () => {
    render(
      <TeamSelect
        teams={teams}
        session={{ ...session, organizationRole: "VIEWER" }}
      />,
    );
    expect(screen.getAllByRole("option")).toHaveLength(1);
  });
  it("organization administrators can choose all active teams", () => {
    render(
      <TeamSelect
        teams={teams}
        session={{ ...session, organizationRole: "ADMIN" }}
      />,
    );
    expect(screen.getAllByRole("option")).toHaveLength(3);
  });
  it("asset search preserves the selected resource and source context", async () => {
    render(
      <AssetSelect
        assets={assets}
        name="asset"
        label="Reporting asset"
        value="one"
        optional
        searchable
      />,
    );
    await userEvent.type(screen.getByRole("searchbox"), "Source B");
    expect(screen.getAllByRole("option")).toHaveLength(3);
    expect(
      screen.getByRole("option", { name: /DB.TWO.*Source B/ }),
    ).toBeTruthy();
  });
  it("asset selection is submitted through the named form control", async () => {
    render(
      <form data-testid="form">
        <AssetSelect assets={assets} name="assetId" label="Asset" />
      </form>,
    );
    await userEvent.selectOptions(screen.getByLabelText("Asset"), "two");
    expect(
      new FormData(screen.getByTestId("form") as HTMLFormElement).get(
        "assetId",
      ),
    ).toBe("two");
  });
  it("controlled selectors emit the selected UUID", async () => {
    const change = vi.fn();
    render(
      <AssetSelect
        assets={assets}
        name="asset"
        label="Asset"
        value=""
        onChange={change}
      />,
    );
    await userEvent.selectOptions(screen.getByLabelText("Asset"), "one");
    expect(change).toHaveBeenCalledWith("one");
  });
  it("unmatched searches display an explicit empty result", async () => {
    render(
      <AssetSelect assets={assets} name="asset" label="Asset" searchable />,
    );
    await userEvent.type(screen.getByRole("searchbox"), "missing");
    expect(screen.getByRole("status").textContent).toContain("No assets match");
  });
});
describe("pagination", () => {
  const page = {
    items: [{}],
    offset: 0,
    total: 26,
    loading: false,
    hasMore: true,
    previous: vi.fn(),
    next: vi.fn(),
  } as unknown as Parameters<typeof PageNavigation>[0]["page"];
  it("first page disables previous and reports the total", () => {
    render(<PageNavigation page={page} label="assets" />);
    expect(
      (
        screen.getByRole("button", {
          name: "Previous assets",
        }) as HTMLButtonElement
      ).disabled,
    ).toBe(true);
    expect(screen.getByRole("status").textContent).toBe("1–1 of 26 assets");
  });
  it("next page invokes the paging handler", async () => {
    render(<PageNavigation page={page} label="assets" />);
    await userEvent.click(screen.getByRole("button", { name: "Next assets" }));
    expect(page.next).toHaveBeenCalled();
  });
  it("final page cannot advance", () => {
    render(
      <PageNavigation
        page={{ ...page, offset: 25, hasMore: false }}
        label="assets"
      />,
    );
    expect(
      (screen.getByRole("button", { name: "Next assets" }) as HTMLButtonElement)
        .disabled,
    ).toBe(true);
  });
  it("loading blocks navigation", () => {
    render(<PageNavigation page={{ ...page, loading: true }} label="assets" />);
    expect(
      screen
        .getAllByRole("button")
        .every((b) => (b as HTMLButtonElement).disabled),
    ).toBe(true);
  });
});
