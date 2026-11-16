import type {
  Asset,
  AssetColumn,
  Connection,
  ImportBatch,
  RegistryStatus,
  ErrorDetail,
  RegistrationOptions,
  RegistrationRequest,
  RegistrationResponse,
} from "../types/api";

let organizationEpoch = 0;
export function clearOrganizationCache() {
  organizationEpoch++;
}
export class ApiError extends Error {
  status = 0;
  diagnosticId: string | null = null;
  code: string;
  details: ErrorDetail[];
  constructor(
    message: string,
    code = "NETWORK_ERROR",
    details: ErrorDetail[] = [],
  ) {
    super(message);
    this.name = "ApiError";
    this.code = code;
    this.details = details;
  }
}
type CsrfToken = { token: string; headerName: string };
let pendingCsrf: Promise<CsrfToken> | undefined;

// Share only an in-flight fetch. A later mutation always sees the current session's
// token, including after login/logout, a password change, or another browser tab.
export function clearCsrfToken() {
  pendingCsrf = undefined;
}
async function fetchResponse(path: string, options?: RequestInit) {
  try {
    return await fetch(path, {
      ...options,
      credentials: "same-origin",
      cache: "no-store",
    });
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError")
      throw error;
    throw new ApiError("Unable to connect to the server.");
  }
}
function csrfToken(): Promise<CsrfToken> {
  if (!pendingCsrf) {
    const pending = (async () => {
      const response = await fetchResponse("/api/auth/csrf");
      const token = await response.json().catch(() => null);
      if (!response.ok)
        throw new ApiError(
          token?.message ??
            "Unable to obtain a security token. Please try again.",
          token?.code ?? "CSRF_UNAVAILABLE",
        );
      if (!token?.token || token.headerName !== "X-CSRF-TOKEN")
        throw new ApiError(
          "The server returned an invalid security token. Please try again.",
          "INVALID_RESPONSE",
        );
      return token as CsrfToken;
    })();
    pendingCsrf = pending;
    // Do not let an old request clear a newer session's pending fetch.
    void pending
      .finally(() => {
        if (pendingCsrf === pending) pendingCsrf = undefined;
      })
      .catch(() => {});
  }
  return pendingCsrf;
}
export async function request<T>(
  path: string,
  options?: RequestInit,
): Promise<T> {
  const epoch = organizationEpoch;
  const unsafe = !["GET", "HEAD", "OPTIONS"].includes(
    (options?.method ?? "GET").toUpperCase(),
  );
  const checkOrganization = () => {
    if (epoch !== organizationEpoch)
      throw new DOMException("Organization changed", "AbortError");
  };
  for (let attempt = 0; attempt < 2; attempt++) {
    const headers = new Headers(options?.headers);
    if (unsafe) {
      const token = await csrfToken();
      headers.set(token.headerName, token.token);
      checkOrganization();
    }
    const response = await fetchResponse(`/api${path}`, {
      ...options,
      headers,
    });
    const body = await response.json().catch(() => null);
    if (path !== "/auth/registration") checkOrganization();
    if (!response.ok) {
      // CSRF_INVALID is emitted only by Spring's CSRF filter, before the action
      // runs. Never replay a network failure, permission denial, or generic 403.
      if (
        unsafe &&
        response.status === 403 &&
        body?.code === "CSRF_INVALID" &&
        attempt === 0
      ) {
        continue;
      }
      if (response.status === 401 && path !== "/auth/login") {
        clearCsrfToken();
        window.dispatchEvent(new Event("trace:unauthenticated"));
      }
      if (
        response.status === 403 &&
        ["ORGANIZATION_ACCESS_REVOKED", "ORGANIZATION_REQUIRED"].includes(
          body?.code,
        )
      )
        window.dispatchEvent(new Event("trace:organization-invalid"));
      const message =
        response.status === 401
          ? path === "/auth/login"
            ? "Incorrect email or password."
            : "Your session has expired. Please sign in again."
          : body?.code === "ACCESS_DENIED"
            ? "You do not have permission to perform this action."
            : (body?.message ??
              (response.status >= 500
                ? "Trace is temporarily unavailable. Please try again."
                : `Trace returned HTTP ${response.status}. Try again.`));
      const error = new ApiError(
        message,
        body?.code ?? "HTTP_ERROR",
        body?.errors ?? [],
      );
      error.status = response.status;
      error.diagnosticId = response.headers.get("X-Request-ID");
      throw error;
    }
    if (body === null)
      throw new ApiError(
        "Trace returned an invalid response.",
        "INVALID_RESPONSE",
      );
    if (unsafe && path.startsWith("/auth/")) clearCsrfToken();
    return body as T;
  }
  throw new ApiError(
    "Your security token could not be renewed. Please try signing in again.",
    "CSRF_INVALID",
  );
}
export const api = {
  status: () => request<RegistryStatus>("/registry/status"),
  connections: () => request<Connection[]>("/connections"),
  assets: (q: string, offset: number, signal?: AbortSignal) =>
    request<Asset[]>(
      `/assets?q=${encodeURIComponent(q)}&limit=50&offset=${offset}`,
      { signal },
    ),
  asset: (id: string, signal?: AbortSignal) =>
    request<Asset>(`/assets/${id}`, { signal }),
  columns: (id: string, signal?: AbortSignal) =>
    request<AssetColumn[]>(`/assets/${id}/columns`, { signal }),
  preview: (connection: string, file: File) => {
    const body = new FormData();
    body.append("connectionId", connection);
    body.append("file", file);
    return request<ImportBatch>("/imports/preview", { method: "POST", body });
  },
  commit: (id: string) =>
    request<ImportBatch>(`/imports/${id}/commit`, { method: "POST" }),
  batch: (id: string) => request<ImportBatch>(`/imports/${id}`),
};

export function mutate<T = { ok: boolean }>(
  path: string,
  body?: unknown,
  method = "POST",
): Promise<T> {
  return request<T>(path, {
    method,
    headers: { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

export const authApi = {
  registrationOptions: () => request<RegistrationOptions>("/auth/registration"),
  register: (body: RegistrationRequest) =>
    mutate<RegistrationResponse>("/auth/register", body),
};
