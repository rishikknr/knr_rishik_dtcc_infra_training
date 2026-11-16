import { ApiError, request } from "./api";

export async function requireCurrentBackend(signal?: AbortSignal) {
  const status = await request<{ integrationVersion?: number }>(
    "/registry/status",
    { signal },
  );
  if (status.integrationVersion !== 3)
    throw new ApiError(
      "The running Trace backend is out of date. Start the matching backend after completing the database upgrade through V18, then retry.",
      "BACKEND_UPGRADE_REQUIRED",
    );
}
