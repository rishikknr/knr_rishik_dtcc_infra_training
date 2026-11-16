import { afterEach, describe, expect, it, vi } from "vitest";
import {
  ApiError,
  clearCsrfToken,
  clearOrganizationCache,
  request,
} from "../src/services/api";
afterEach(() => {
  clearCsrfToken();
  vi.unstubAllGlobals();
});
describe("session and tenant request boundary", () => {
  it("obtains CSRF before sending a mutation and uses same-origin cookies", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({ headerName: "X-CSRF-TOKEN", token: "masked" }),
        ),
      )
      .mockResolvedValueOnce(new Response('{"saved":true}'));
    vi.stubGlobal("fetch", fetch);
    await request("/connections", { method: "POST", body: "{}" });
    expect(fetch.mock.calls[0][0]).toBe("/api/auth/csrf");
    expect(fetch.mock.calls[1][1].headers.get("X-CSRF-TOKEN")).toBe("masked");
    expect(fetch.mock.calls[1][1].credentials).toBe("same-origin");
  });
  it("a tenant switch invalidates an old read before it can populate the UI", async () => {
    let resolve!: (r: Response) => void;
    vi.stubGlobal(
      "fetch",
      vi.fn(() => new Promise<Response>((r) => (resolve = r))),
    );
    const pending = request("/assets");
    clearOrganizationCache();
    resolve(new Response('[{"name":"Other tenant data"}]'));
    await expect(pending).rejects.toMatchObject({ name: "AbortError" });
  });
  it("a switch while CSRF loads prevents the old mutation from being sent", async () => {
    let resolve!: (r: Response) => void;
    const fetch = vi.fn(() => new Promise<Response>((r) => (resolve = r)));
    vi.stubGlobal("fetch", fetch);
    const pending = request("/assets", { method: "POST" });
    clearOrganizationCache();
    resolve(new Response('{"headerName":"X-CSRF-TOKEN","token":"old"}'));
    await expect(pending).rejects.toMatchObject({ name: "AbortError" });
    expect(fetch).toHaveBeenCalledOnce();
  });
  it("authentication expiry emits a global event and retains a safe error", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          new Response(
            '{"code":"AUTHENTICATION_REQUIRED","message":"Sign in again"}',
            { status: 401 },
          ),
        ),
    );
    const expired = vi.fn();
    window.addEventListener("trace:unauthenticated", expired, { once: true });
    await expect(request("/assets")).rejects.toMatchObject({ status: 401 });
    expect(expired).toHaveBeenCalledOnce();
  });
  it("revoked organization access clears the workspace through an event", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          new Response(
            '{"code":"ORGANIZATION_ACCESS_REVOKED","message":"Membership required"}',
            { status: 403 },
          ),
        ),
    );
    const invalid = vi.fn();
    window.addEventListener("trace:organization-invalid", invalid, {
      once: true,
    });
    await expect(request("/assets")).rejects.toBeInstanceOf(ApiError);
    expect(invalid).toHaveBeenCalledOnce();
  });
  it("unavailable transport has an actionable retry message", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockRejectedValue(new TypeError("fetch failed")),
    );
    await expect(request("/assets")).rejects.toMatchObject({
      code: "NETWORK_ERROR",
      message: "Unable to connect to the server.",
    });
  });
  it("an HTML response cannot be treated as success", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response("<html>Proxy error</html>")),
    );
    await expect(request("/assets")).rejects.toMatchObject({
      code: "INVALID_RESPONSE",
    });
  });
});

describe("CSRF recovery", () => {
  const token = (value = "fresh") =>
    new Response(JSON.stringify({ headerName: "X-CSRF-TOKEN", token: value }));
  const rejected = () =>
    new Response('{"code":"CSRF_INVALID"}', { status: 403 });

  it("shares concurrent initial token requests and preserves their session cookies", async () => {
    let resolve!: (r: Response) => void;
    const fetch = vi
      .fn()
      .mockImplementationOnce(
        () =>
          new Promise<Response>((r) => {
            resolve = r;
          }),
      )
      .mockImplementation(() => Promise.resolve(new Response('{"ok":true}')));
    vi.stubGlobal("fetch", fetch);
    const first = request("/connections", { method: "POST" });
    const second = request("/assets", { method: "PATCH" });
    expect(fetch).toHaveBeenCalledOnce();
    resolve(token());
    await Promise.all([first, second]);
    expect(fetch.mock.calls.map((c) => c[0])).toEqual([
      "/api/auth/csrf",
      "/api/connections",
      "/api/assets",
    ]);
    for (const call of fetch.mock.calls.slice(1)) {
      expect(call[1].headers.get("X-CSRF-TOKEN")).toBe("fresh");
      expect(call[1].credentials).toBe("same-origin");
    }
  });

  it("renews a rejected token and retries the same mutation exactly once", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(token("old"))
      .mockResolvedValueOnce(rejected())
      .mockResolvedValueOnce(token())
      .mockResolvedValueOnce(new Response('{"ok":true}'));
    vi.stubGlobal("fetch", fetch);
    await expect(
      request("/auth/login", { method: "POST", body: "credentials" }),
    ).resolves.toEqual({ ok: true });
    expect(fetch).toHaveBeenCalledTimes(4);
    expect(fetch.mock.calls[3][1].headers.get("X-CSRF-TOKEN")).toBe("fresh");
    expect(fetch.mock.calls[3][1].body).toBe("credentials");
  });

  it("does not loop if renewal is rejected", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(token())
      .mockResolvedValueOnce(rejected())
      .mockResolvedValueOnce(token())
      .mockResolvedValueOnce(rejected());
    vi.stubGlobal("fetch", fetch);
    await expect(
      request("/auth/login", { method: "POST" }),
    ).rejects.toMatchObject({ code: "CSRF_INVALID" });
    expect(fetch).toHaveBeenCalledTimes(4);
  });

  it("does not retry permission denials or ambiguous network failures", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(token())
      .mockResolvedValueOnce(
        new Response('{"code":"ACCESS_DENIED"}', { status: 403 }),
      );
    vi.stubGlobal("fetch", fetch);
    await expect(request("/assets", { method: "POST" })).rejects.toMatchObject({
      message: "You do not have permission to perform this action.",
    });
    expect(fetch).toHaveBeenCalledTimes(2);
    fetch
      .mockReset()
      .mockResolvedValueOnce(token())
      .mockRejectedValueOnce(new TypeError("offline"));
    await expect(request("/assets", { method: "POST" })).rejects.toMatchObject({
      code: "NETWORK_ERROR",
    });
    expect(fetch).toHaveBeenCalledTimes(2);
  });

  it("surfaces expiry after renewal and clears the authenticated workspace", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(token())
      .mockResolvedValueOnce(rejected())
      .mockResolvedValueOnce(token())
      .mockResolvedValueOnce(
        new Response('{"code":"AUTHENTICATION_REQUIRED"}', { status: 401 }),
      );
    vi.stubGlobal("fetch", fetch);
    const expired = vi.fn();
    window.addEventListener("trace:unauthenticated", expired, { once: true });
    await expect(request("/assets", { method: "POST" })).rejects.toMatchObject({
      status: 401,
      message: "Your session has expired. Please sign in again.",
    });
    expect(expired).toHaveBeenCalledOnce();
    expect(fetch).toHaveBeenCalledTimes(4);
  });

  it("fetches a fresh token after login, logout, and re-login", async () => {
    const fetch = vi
      .fn()
      .mockImplementation((path: string) =>
        Promise.resolve(
          path.endsWith("/csrf") ? token() : new Response('{"ok":true}'),
        ),
      );
    vi.stubGlobal("fetch", fetch);
    for (const path of [
      "/auth/login",
      "/assets",
      "/auth/logout",
      "/auth/login",
    ])
      await request(path, { method: "POST" });
    expect(
      fetch.mock.calls.filter((c) => c[0] === "/api/auth/csrf"),
    ).toHaveLength(4);
  });

  it("reports a token-fetch network failure without submitting the mutation", async () => {
    const fetch = vi.fn().mockRejectedValue(new TypeError("offline"));
    vi.stubGlobal("fetch", fetch);
    await expect(
      request("/auth/login", { method: "POST" }),
    ).rejects.toMatchObject({
      message: "Unable to connect to the server.",
    });
    expect(fetch).toHaveBeenCalledOnce();
  });
  it("shows the HTTPS requirement without submitting credentials", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValue(
        new Response(
          '{"code":"HTTPS_REQUIRED","message":"Open Trace using its HTTPS address to sign in securely."}',
          { status: 400 },
        ),
      );
    vi.stubGlobal("fetch", fetch);
    await expect(
      request("/auth/login", { method: "POST" }),
    ).rejects.toMatchObject({
      code: "HTTPS_REQUIRED",
      message: "Open Trace using its HTTPS address to sign in securely.",
    });
    expect(fetch).toHaveBeenCalledOnce();
  });
});
