import { expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { FormulaVersion } from "../src/features/integration/FormulaStudio";
const versions = vi.hoisted(() => [
  {
    id: "old",
    version: 1,
    state: "REGISTERED",
    expression: "SELECT 1",
    created_at: "2026-11-11T00:00:00Z",
    referenced_assets: "[]",
    referenced_columns: "[]",
  },
  {
    id: "latest",
    version: 2,
    state: "REGISTERED",
    expression: "SELECT 2",
    created_at: "2026-11-11T00:00:01Z",
    referenced_assets: "[]",
    referenced_columns: "[]",
  },
]);
vi.mock("../src/features/integration/paging", () => ({
  usePage: () => ({
    items: versions,
    offset: 0,
    total: 2,
    loading: false,
    hasMore: false,
    previous: vi.fn(),
    next: vi.fn(),
    search: vi.fn(),
    setQuery: vi.fn(),
    query: "",
    reload: vi.fn(),
  }),
}));
vi.mock("../src/services/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../src/services/api")>()),
  request: vi.fn(),
}));
import { request } from "../src/services/api";
import { VersionComparison } from "../src/features/integration/VersionComparison";
it("a slower previous version selection cannot overwrite the user's latest choice", async () => {
  let old!: (v: FormulaVersion) => void;
  vi.mocked(request).mockImplementation((path) =>
    path.endsWith("/old")
      ? new Promise((resolve) => {
          old = resolve as typeof old;
        })
      : Promise.resolve(versions[1]),
  );
  render(
    <VersionComparison
      formula="formula"
      revision={0}
      writable
      onEdit={vi.fn()}
      onUse={vi.fn()}
    />,
  );
  await userEvent.selectOptions(
    screen.getByRole("combobox", { name: "Left version" }),
    "old",
  );
  await userEvent.selectOptions(
    screen.getByRole("combobox", { name: "Left version" }),
    "latest",
  );
  await waitFor(() =>
    expect(
      (
        screen.getByRole("combobox", {
          name: "Left version",
        }) as HTMLSelectElement
      ).value,
    ).toBe("latest"),
  );
  old(versions[0] as FormulaVersion);
  await new Promise((resolve) => setTimeout(resolve, 0));
  expect(
    (
      screen.getByRole("combobox", {
        name: "Left version",
      }) as HTMLSelectElement
    ).value,
  ).toBe("latest");
});
