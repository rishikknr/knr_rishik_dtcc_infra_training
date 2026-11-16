import { afterEach, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { OverviewPage } from "../src/app/OverviewPage";
import type { Session } from "../src/types/api";

afterEach(() => vi.unstubAllGlobals());

const session = {
  organizations: [{ id: "org", name: "NovaMart" }],
  selectedOrganization: "org",
  organizationRole: "OWNER",
  teamRoles: {},
  permissions: { permissions: [], teamPermissions: [] },
} as unknown as Session;

it.each([undefined, 2])(
  "explains an incompatible backend (%s) without requesting missing browse endpoints",
  async (integrationVersion) => {
    const fetch = vi
      .fn()
      .mockResolvedValue(new Response(JSON.stringify({ integrationVersion })));
    vi.stubGlobal("fetch", fetch);
    render(<OverviewPage session={session} canImport />);

    expect((await screen.findByRole("alert")).textContent).toContain(
      "The running Trace backend is out of date",
    );
    expect(fetch).toHaveBeenCalledOnce();
    expect(fetch.mock.calls[0][0]).toBe("/api/registry/status");
  },
);

it("loads registry totals after confirming a compatible backend", async () => {
  const fetch = vi.fn(
    async (url: string) =>
      new Response(
        JSON.stringify(
          url === "/api/registry/status"
            ? { integrationVersion: 3 }
            : { items: [], total: 42, limit: 5, offset: 0, hasMore: false },
        ),
      ),
  );
  vi.stubGlobal("fetch", fetch);
  render(<OverviewPage session={session} canImport />);

  await waitFor(() =>
    expect(
      screen.getByRole("link", { name: /Catalog assets/ }).textContent,
    ).toContain("42"),
  );
  expect(screen.queryByRole("alert")).toBeNull();
  await waitFor(() => expect(fetch).toHaveBeenCalledTimes(8));
});
