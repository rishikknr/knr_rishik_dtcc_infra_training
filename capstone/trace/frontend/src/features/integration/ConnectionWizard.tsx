import { SuggestedMappings } from "./SuggestedMappings";
import { useEffect, useRef, useState } from "react";
import type { Connection, ImportBatch, Session, Team } from "../../types/api";
import { allowed } from "../../app/permissions";
import { ApiError, api, mutate, request } from "../../services/api";
import { Feedback, Loading } from "../../components/Feedback";
import { Icon, Status, Timestamp } from "../../components/Design";
import { useAction } from "./hooks";
import {
  providers,
  sourceFields,
  credentialFields,
  outcome,
  validateSource,
} from "./connectionModel";
import { ConnectionGuide } from "./ConnectionGuide";
import { MetadataInventory } from "./MetadataInventory";
const steps = [
  "Provider",
  "Source settings",
  "Authentication",
  "Access guide",
  "Test connection",
  "Discover metadata",
  "Review & commit",
];
export type DiscoveryRun = {
  id: string;
  status: string;
  phase: string;
  started_at: string | null;
  finished_at: string | null;
  queued_at: string;
  records_discovered: number;
  error_code: string | null;
  import_id: string | null;
  attempt_count: number;
  cancel_requested_at: string | null;
  warnings: string;
  next_attempt_at: string;
  initiated_by: string;
};
const active = (run: DiscoveryRun) =>
  ["PENDING", "RUNNING", "RETRY_WAIT"].includes(run.status);
export function ConnectionFailure({
  code,
  reference,
}: {
  code: string;
  reference?: string | null;
}) {
  const [title, message] = outcome(code);
  return (
    <div className="connection-failure" role="alert">
      <Icon name="alert" />
      <div>
        <strong>{title}</strong>
        <p>{message}</p>
        <details>
          <summary>Technical details</summary>
          <p>
            Category: <code>{code}</code>
          </p>
          {reference && (
            <p>
              Diagnostic reference: <code>{reference}</code>
            </p>
          )}
          <p>
            Share this reference with your operator. Credential values and raw
            provider responses are never included here.
          </p>
        </details>
      </div>
    </div>
  );
}
export function ConnectionWizard({
  initial,
  session,
  teams,
  onChanged,
}: {
  initial?: Connection;
  session: Session;
  teams: Team[];
  onChanged: () => void;
}) {
  const [connection, setConnection] = useState<Connection | undefined>(initial);
  const [provider, setProvider] = useState(initial?.platform ?? "");
  const [step, setStep] = useState(
    initial ? (credentialFields[initial.platform] ? 2 : 1) : 0,
  );
  const [furthest, setFurthest] = useState(initial ? 2 : 0);
  const [values, setValues] = useState<Record<string, string>>(() => ({
    name: initial?.name ?? "",
    description: initial?.description ?? "",
    ownerTeamId: initial?.owner_team_id ?? "",
    maxObjects: "1000",
    maxFiles: "50",
    ...Object.fromEntries(
      Object.entries(initial?.configuration ?? {}).map(([k, v]) => [
        k,
        Array.isArray(v) ? v.join(", ") : String(v),
      ]),
    ),
  }));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [reveal, setReveal] = useState(false);
  const [run, setRun] = useState<DiscoveryRun | null>(null);
  const [pollError, setPollError] = useState<unknown>(null);
  const [pollRetry, setPollRetry] = useState(0);
  const [batch, setBatch] = useState<ImportBatch | null>(null);
  const [testing, setTesting] = useState(false);
  const a = useAction();
  const heading = useRef<HTMLHeadingElement>(null);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  useEffect(() => {
    heading.current?.focus();
  }, [step]);
  const go = (next: number) => {
    setStep(next);
    setFurthest((v) => Math.max(v, next));
  };
  const live = !!credentialFields[provider];
  useEffect(() => {
    if (!connection || !run || !active(run) || pollError) return;
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      request<DiscoveryRun>(
        `/connections/${connection.id}/sync-runs/${run.id}`,
        { signal: controller.signal },
      )
        .then((data) => {
          if (!controller.signal.aborted) setRun(data);
        })
        .catch((e) => {
          if (!controller.signal.aborted) setPollError(e);
        });
    }, 1500);
    return () => {
      controller.abort();
      window.clearTimeout(timer);
    };
  }, [connection, run, pollError, pollRetry]);
  const liveFailure =
    connection?.reason_code && !["CONNECTED"].includes(connection.reason_code);
  const previewId = run?.import_id;
  useEffect(() => {
    if (!previewId || step !== 6) return;
    const controller = new AbortController();
    request<ImportBatch>(`/imports/${previewId}`, { signal: controller.signal })
      .then((data) => {
        if (!controller.signal.aborted) setBatch(data);
      })
      .catch((e) => {
        if (!controller.signal.aborted) setPollError(e);
      });
    return () => controller.abort();
  }, [previewId, step, pollRetry]);
  const update = (key: string, value: string) => {
    setValues((v) => ({ ...v, [key]: value }));
    setErrors((e) => ({ ...e, [key]: "" }));
  };
  const field = (f: {
    key: string;
    label: string;
    hint: string;
    placeholder?: string;
    required?: boolean;
    type?: string;
    max?: number;
  }) => (
    <label key={f.key} htmlFor={`source-${f.key}`}>
      <span>
        {f.label}
        {f.required && <span className="required-mark"> *</span>}
      </span>
      <input
        id={`source-${f.key}`}
        name={f.key}
        aria-label={f.label}
        disabled={a.busy}
        type={f.type ?? "text"}
        value={values[f.key] ?? ""}
        onChange={(e) => update(f.key, e.target.value)}
        placeholder={f.placeholder}
        required={f.required}
        min={f.type === "number" ? 1 : undefined}
        max={f.max}
        maxLength={500}
        aria-invalid={!!errors[f.key]}
        aria-describedby={`hint-${f.key}${errors[f.key] ? ` error-${f.key}` : ""}`}
      />
      <small id={`hint-${f.key}`}>{f.hint}</small>
      {errors[f.key] && (
        <small className="field-error" id={`error-${f.key}`}>
          {errors[f.key]}
        </small>
      )}
    </label>
  );
  return (
    <div className="connection-wizard">
      <nav className="wizard-nav" aria-label="Connection setup steps">
        <p className="eyebrow">SOURCE SETUP</p>
        <ol>
          {steps.map((label, i) => (
            <li key={label}>
              <button
                type="button"
                disabled={i > furthest || a.busy || (!live && i > 1 && i < 6)}
                aria-current={step === i ? "step" : undefined}
                onClick={() => setStep(i)}
              >
                <span className="step-number">
                  {i < step ? <Icon name="check" size={13} /> : i + 1}
                </span>
                {label}
              </button>
            </li>
          ))}
        </ol>
        <div className="wizard-security">
          <Icon name="shield" />
          <p>
            Encrypted credentials.
            <br />
            Scoped access.
            <br />
            Explicit catalog commitment.
          </p>
        </div>
      </nav>
      <section className="wizard-content" aria-busy={a.busy}>
        <p className="eyebrow">
          STEP {step + 1} OF {steps.length}
          {provider ? ` · ${providers[provider]?.name}` : ""}
        </p>
        <h2 ref={heading} tabIndex={-1}>
          {steps[step]}
        </h2>
        {a.error instanceof ApiError ? (
          <ConnectionFailure
            code={a.error.code}
            reference={a.error.diagnosticId}
          />
        ) : (
          <Feedback error={a.error} />
        )}
        {step === 0 && (
          <>
            <p className="muted">
              Choose where your metadata lives. Each source keeps its own
              provenance and owning team.
            </p>
            <div className="provider-options">
              {Object.entries(providers).map(([key, p]) => (
                <button
                  className={`provider-option ${provider === key ? "selected" : ""}`}
                  key={key}
                  type="button"
                  disabled={!!connection && key !== provider}
                  aria-pressed={provider === key}
                  onClick={() => {
                    setProvider(key);
                    setErrors({});
                  }}
                >
                  <span
                    className={`provider-emblem provider-${key.toLowerCase()}`}
                  >
                    <Icon name={p.icon} size={24} />
                  </span>
                  <span>
                    <small>{p.category}</small>
                    <strong>{p.name}</strong>
                    <span>{p.description}</span>
                  </span>
                  {provider === key && <Icon name="check" />}
                </button>
              ))}
            </div>
            <div className="wizard-footer">
              <span>Select a provider to continue.</span>
              <button
                className="primary"
                disabled={!provider}
                onClick={() => go(1)}
              >
                Continue <Icon name="arrow" size={16} />
              </button>
            </div>
          </>
        )}
        {step === 1 && (
          <form
            noValidate
            onSubmit={(e) => {
              e.preventDefault();
              const err = validateSource(provider, values);
              if (!values.name.trim()) err.name = "Enter a connection name.";
              if (!values.ownerTeamId)
                err.ownerTeamId = "Choose a team you can edit.";
              setErrors(err);
              if (Object.keys(err).length) {
                window.setTimeout(
                  () =>
                    document
                      .querySelector<HTMLInputElement>('[aria-invalid="true"]')
                      ?.focus(),
                  0,
                );
                return;
              }
              void a.run(async () => {
                const configuration: Record<string, unknown> = {};
                for (const f of sourceFields[provider]) {
                  const value = (values[f.key] ?? "").trim();
                  if (value)
                    configuration[f.key] = ["schemas", "workspaceIds"].includes(
                      f.key,
                    )
                      ? value
                          .split(",")
                          .map((v) => v.trim())
                          .filter(Boolean)
                      : f.type === "number"
                        ? Number(value)
                        : value;
                }
                if (
                  provider === "SNOWFLAKE" &&
                  initial?.configuration.includeViewDefinitions !== undefined
                )
                  configuration.includeViewDefinitions =
                    initial.configuration.includeViewDefinitions;
                const body = {
                  name: values.name.trim(),
                  ownerTeamId: values.ownerTeamId,
                  description: values.description ?? "",
                  connectionType: provider,
                  configuration,
                };
                const saved = connection
                  ? await mutate<Connection>(
                      `/connections/${connection.id}`,
                      body,
                      "PATCH",
                    )
                  : await mutate<{ id: string }>("/connections", body);
                const next = await request<Connection>(
                  `/connections/${saved.id}`,
                );
                if (!mounted.current) return;
                setConnection(next);
                onChanged();
                go(live ? 2 : 6);
                if (!connection && live)
                  window.location.hash = `connections/${saved.id}/setup`;
              });
            }}
          >
            <p className="muted">
              Name this source and define the exact discovery scope. Fields
              marked * are required.
            </p>
            <div className="source-fields">
              {field({
                key: "name",
                label: "Connection name",
                hint: "A recognizable name for your team.",
                placeholder: `${providers[provider]?.name} · baseline`,
                required: true,
              })}
              <label htmlFor="source-ownerTeamId">
                Owning team *
                <select
                  id="source-ownerTeamId"
                  aria-label="Owning team"
                  value={values.ownerTeamId}
                  onChange={(e) => update("ownerTeamId", e.target.value)}
                  required
                  disabled={!!initial}
                  aria-invalid={!!errors.ownerTeamId}
                  aria-describedby="team-hint"
                >
                  <option value="">Select team</option>
                  {teams
                    .filter((t) => t.active && allowed(session, t.id))
                    .map((t) => (
                      <option key={t.id} value={t.id}>
                        {t.name}
                      </option>
                    ))}
                </select>
                <small id="team-hint">
                  {initial
                    ? "Use Transfer ownership on the connection to change teams."
                    : "Choose an active team you can edit."}
                </small>
                {errors.ownerTeamId && (
                  <small className="field-error">{errors.ownerTeamId}</small>
                )}
              </label>
              {(sourceFields[provider] ?? []).map(field)}
              <label>
                Description
                <textarea
                  aria-label="Description"
                  name="description"
                  value={values.description ?? ""}
                  onChange={(e) => update("description", e.target.value)}
                  maxLength={4000}
                  disabled={a.busy}
                />
                <small>
                  Optional context for other members of your organization.
                </small>
              </label>
            </div>
            <div className="wizard-footer">
              <button
                type="button"
                disabled={a.busy}
                onClick={() => setStep(0)}
              >
                Back
              </button>
              <button className="primary" disabled={a.busy}>
                {a.busy ? "Saving settings…" : "Save and continue"}
              </button>
            </div>
          </form>
        )}
        {step === 2 && connection && (
          <>
            <p className="muted">
              {provider === "SNOWFLAKE"
                ? "Use Snowflake RSA key-pair authentication. S3 stage credentials cannot sign in to Snowflake."
                : provider === "AMAZON_S3"
                  ? "Use explicit AWS IAM credentials. TRACE never falls back to the processor machine’s AWS identity."
                  : "Use a dedicated identity with access only to this source."}
            </p>
            {connection.has_credentials && (
              <p className="notice success">
                Credentials are stored securely. Existing values are never
                returned. Save a complete replacement to rotate; omitted
                optional fields are cleared.
              </p>
            )}
            <form
              onSubmit={(e) => {
                e.preventDefault();
                const form = e.currentTarget;
                const d = new FormData(form);
                const credentials = Object.fromEntries(
                  (credentialFields[provider] ?? [])
                    .map((f) => [f.key, String(d.get(f.key) ?? "")])
                    .filter(([, v]) => v !== ""),
                );
                if (provider === "SNOWFLAKE") {
                  const key = String(credentials.privateKey ?? "").trim();
                  if (
                    !/^-----BEGIN (?:ENCRYPTED |RSA |EC )?PRIVATE KEY-----/.test(
                      key,
                    ) ||
                    key.endsWith("%")
                  ) {
                    setErrors({
                      credentials:
                        "Enter the complete private-key PEM, including BEGIN / END lines. Public-key text and trailing terminal prompt characters cannot be used here.",
                    });
                    return;
                  }
                }
                if (
                  provider === "AMAZON_S3" &&
                  Object.values(credentials).some((v) => /\s/.test(String(v)))
                ) {
                  setErrors({
                    credentials:
                      "AWS credentials must not contain spaces or line breaks.",
                  });
                  return;
                }
                void a.run(async () => {
                  await mutate(
                    `/connections/${connection.id}/credentials`,
                    credentials,
                    "PUT",
                  );
                  form.reset();
                  setReveal(false);
                  setErrors({});
                  const next = await request<Connection>(
                    `/connections/${connection.id}`,
                  );
                  if (!mounted.current) return;
                  setConnection(next);
                  onChanged();
                  go(3);
                });
              }}
            >
              {(credentialFields[provider] ?? []).map((f) => (
                <label key={f.key} htmlFor={`secret-${f.key}`}>
                  <span>
                    {f.label}
                    {f.required ? " *" : " (optional)"}
                  </span>
                  {f.key === "privateKey" ? (
                    <textarea
                      id={`secret-${f.key}`}
                      name={f.key}
                      required={f.required}
                      rows={7}
                      className={reveal ? "" : "secret-masked"}
                      autoComplete="off"
                      spellCheck={false}
                      maxLength={32000}
                      aria-describedby={`secret-hint-${f.key}`}
                    />
                  ) : (
                    <input
                      id={`secret-${f.key}`}
                      name={f.key}
                      required={f.required}
                      type={reveal ? "text" : "password"}
                      autoComplete="new-password"
                      spellCheck={false}
                      maxLength={32000}
                      aria-describedby={`secret-hint-${f.key}`}
                    />
                  )}
                  <small id={`secret-hint-${f.key}`}>{f.hint}</small>
                </label>
              ))}
              <label className="check-label">
                <input
                  type="checkbox"
                  checked={reveal}
                  onChange={(e) => setReveal(e.target.checked)}
                />
                Show entered credentials
              </label>
              {errors.credentials && (
                <p role="alert" className="field-error">
                  {errors.credentials}
                </p>
              )}
              <p className="security-note">
                <Icon name="shield" size={16} />
                Credentials are encrypted server-side and never included in
                ordinary source configuration responses.
              </p>
              <div className="wizard-footer">
                <button
                  type="button"
                  disabled={a.busy}
                  onClick={() => setStep(1)}
                >
                  Back
                </button>
                <div className="action-row">
                  {connection.has_credentials && (
                    <button
                      type="button"
                      disabled={a.busy}
                      onClick={() => go(3)}
                    >
                      Use stored credentials
                    </button>
                  )}
                  <button className="primary" disabled={a.busy}>
                    {a.busy ? "Encrypting…" : "Save credentials and continue"}
                  </button>
                </div>
              </div>
            </form>
          </>
        )}
        {step === 3 && (
          <>
            <p className="muted">
              Review the source’s permissions before testing. Templates use the
              settings you entered.
            </p>
            <ConnectionGuide provider={provider} values={values} />
            <div className="wizard-footer">
              <button disabled={a.busy} onClick={() => setStep(2)}>
                Back
              </button>
              <button className="primary" onClick={() => go(4)}>
                Continue to test <Icon name="arrow" size={16} />
              </button>
            </div>
          </>
        )}
        {step === 4 && connection && (
          <>
            <p className="muted">
              Saving credentials does not verify provider access. Run a real
              metadata request before discovery.
            </p>
            <div
              className={`connection-test ${connection.status === "CONNECTED" ? "verified" : ""}`}
            >
              <span className="test-emblem">
                <Icon
                  name={
                    testing
                      ? "history"
                      : connection.status === "CONNECTED"
                        ? "check"
                        : "connections"
                  }
                  size={25}
                />
              </span>
              <h3>
                {testing
                  ? "Testing provider access…"
                  : connection.status === "CONNECTED"
                    ? "Connected successfully"
                    : connection.status === "NOT_TESTED"
                      ? "Not tested"
                      : outcome(connection.reason_code)[0]}
              </h3>
              <p>
                {provider === "AMAZON_S3"
                  ? `Test: ListObjectsV2 · bucket ${values.bucket} · prefix ${values.prefix || "(whole bucket)"}. Verifies listing permission, not object-body access.`
                  : provider === "SNOWFLAKE"
                    ? "Test: key-pair sign-in and an INFORMATION_SCHEMA tables/columns query in the selected database. Results are limited by role visibility."
                    : "Test: real provider metadata access for the selected scope."}
              </p>
              <button
                className="primary"
                disabled={a.busy || connection.status === "DISABLED"}
                onClick={() => {
                  setTesting(true);
                  void a.run(async () => {
                    try {
                      const c = await mutate<Connection>(
                        `/connections/${connection.id}/test`,
                      );
                      if (!mounted.current) return;
                      setConnection(c);
                      onChanged();
                    } finally {
                      if (mounted.current) setTesting(false);
                    }
                  });
                }}
              >
                {testing
                  ? "Testing…"
                  : connection.status === "NOT_TESTED"
                    ? "Test Connection"
                    : "Test Connection again"}
              </button>
            </div>
            {!testing && liveFailure && (
              <ConnectionFailure
                code={connection.reason_code!}
                reference={connection.last_test_diagnostic_id}
              />
            )}
            <p className="hint">
              Last test attempt:{" "}
              <Timestamp
                value={connection.last_tested_at}
                fallback="Never tested"
              />{" "}
              · Last successful test:{" "}
              <Timestamp
                value={connection.last_successful_test_at ?? null}
                fallback="No recorded success"
              />
            </p>
            <div className="wizard-footer">
              <button disabled={a.busy} onClick={() => setStep(1)}>
                Edit settings
              </button>
              <button
                className="primary"
                disabled={a.busy || connection.status !== "CONNECTED"}
                onClick={() => go(5)}
              >
                Continue to discovery <Icon name="arrow" size={16} />
              </button>
            </div>
          </>
        )}
        {step === 5 && connection && (
          <>
            <p className="muted">
              Discovery creates an inventory preview. Assets enter the catalog
              only after your explicit commitment.
            </p>
            <div className="discovery-launch">
              <Icon name="search" size={28} />
              <div>
                <h3>{providers[provider]?.name} inventory</h3>
                <p>Bounded metadata access for the saved source scope.</p>
              </div>
              <button
                className="primary"
                disabled={
                  a.busy ||
                  !!(run && active(run)) ||
                  connection.status !== "CONNECTED"
                }
                onClick={() =>
                  void a.run(async () => {
                    setBatch(null);
                    setPollError(null);
                    setRun(
                      await mutate<DiscoveryRun>(
                        `/connections/${connection.id}/sync`,
                      ),
                    );
                    onChanged();
                  })
                }
              >
                {a.busy ? "Queuing…" : "Discover Metadata"}
              </button>
            </div>
            {run && (
              <div className="run-evidence" aria-live="polite">
                <div className="section-title">
                  <h3>Discovery run</h3>
                  <Status value={run.status} />
                </div>
                <dl className="detail-grid">
                  <div>
                    <dt>Run ID</dt>
                    <dd>
                      <code>{run.id}</code>
                    </dd>
                  </div>
                  <div>
                    <dt>Phase / attempts</dt>
                    <dd>
                      {run.phase} · {run.attempt_count}/3
                    </dd>
                  </div>
                  <div>
                    <dt>Started</dt>
                    <dd>
                      <Timestamp
                        value={run.attempt_count > 0 ? run.started_at : null}
                        fallback="Queued"
                      />
                    </dd>
                  </div>
                  <div>
                    <dt>Completed</dt>
                    <dd>
                      <Timestamp
                        value={run.finished_at}
                        fallback="Not completed"
                      />
                    </dd>
                  </div>
                  <div>
                    <dt>Discovered assets</dt>
                    <dd>{run.records_discovered}</dd>
                  </div>
                </dl>
                {active(run) && (
                  <p role="status">
                    {run.status === "RETRY_WAIT"
                      ? `Waiting to retry at ${run.next_attempt_at}.`
                      : "Discovery is in progress. The provider does not expose percentage progress."}
                  </p>
                )}
                {run.error_code && (
                  <ConnectionFailure code={run.error_code} reference={run.id} />
                )}
                <RunWarnings value={run.warnings} />
                <div className="action-row">
                  {active(run) && (
                    <button
                      disabled={a.busy || !!run.cancel_requested_at}
                      onClick={() =>
                        void a.run(async () =>
                          setRun(
                            await mutate<DiscoveryRun>(
                              `/connections/${connection.id}/sync-runs/${run.id}/cancel`,
                            ),
                          ),
                        )
                      }
                    >
                      {run.cancel_requested_at
                        ? "Cancellation requested"
                        : "Cancel discovery"}
                    </button>
                  )}
                  {["FAILED", "CANCELLED"].includes(run.status) && (
                    <button
                      disabled={a.busy}
                      onClick={() =>
                        void a.run(async () => {
                          setPollError(null);
                          setRun(
                            await mutate<DiscoveryRun>(
                              `/connections/${connection.id}/sync-runs/${run.id}/retry`,
                            ),
                          );
                        })
                      }
                    >
                      Retry discovery
                    </button>
                  )}
                  {run.import_id && (
                    <button className="primary" onClick={() => go(6)}>
                      Review metadata preview <Icon name="arrow" size={16} />
                    </button>
                  )}
                </div>
              </div>
            )}
            <Feedback error={pollError} />
            {pollError && (
              <button
                onClick={() => {
                  setPollError(null);
                  setPollRetry((v) => v + 1);
                }}
              >
                Retry progress check
              </button>
            )}
            <div className="wizard-footer">
              <button disabled={a.busy} onClick={() => setStep(4)}>
                Back to test
              </button>
              <a href={`#connections/${connection.id}/history`}>
                Full discovery history ↗
              </a>
            </div>
          </>
        )}
        {step === 6 &&
          connection &&
          (!live ? (
            <>
              <p>
                <a href={`#connections/${connection.id}`}>Open connection</a>{" "}
                Your source is ready.{" "}
                {provider === "FILE_IMPORT"
                  ? "Upload an export and review the proposed changes before committing."
                  : "Register assets with their evidence and owning team."}
              </p>
              <a
                className="button primary"
                href={
                  provider === "FILE_IMPORT"
                    ? `#import?source=${connection.id}`
                    : `#register?source=${connection.id}`
                }
              >
                {provider === "FILE_IMPORT"
                  ? "Import metadata"
                  : "Register an asset"}
              </a>
            </>
          ) : (
            <>
              <p className="muted">
                Review the actual returned inventory. No cross-source lineage is
                inferred from matching names.
              </p>
              <Feedback error={pollError} />
              {pollError && (
                <button
                  onClick={() => {
                    setPollError(null);
                    setPollRetry((v) => v + 1);
                  }}
                >
                  Retry preview
                </button>
              )}
              {!batch && !pollError && (
                <Loading>Loading discovery preview…</Loading>
              )}
              {batch && (
                <>
                  <div className="section-title">
                    <h3>{batch.preview.datasets.length} discovered assets</h3>
                    <Status value={batch.status} />
                  </div>
                  {batch.preview.warnings.map((w, i) => (
                    <p className="notice warning" key={i}>
                      {w}
                    </p>
                  ))}
                  <MetadataInventory
                    datasets={batch.preview.datasets}
                    changes={
                      new Map(
                        batch.datasetChanges.map((c) => [c.externalKey, c]),
                      )
                    }
                    committed={batch.status === "COMMITTED"}
                    connectionId={connection.id}
                  />
                  <div className="wizard-footer">
                    <a href={`#connections/${connection.id}/history`}>
                      Discovery history
                    </a>
                    {batch.status === "PREVIEW_READY" ? (
                      <button
                        className="primary"
                        disabled={a.busy}
                        onClick={() =>
                          void a.run(async () => {
                            setBatch(await api.commit(batch.id));
                            onChanged();
                          })
                        }
                      >
                        {a.busy ? "Committing…" : "Commit to Catalog"}
                      </button>
                    ) : batch.status === "COMMITTED" ? (
                      <a
                        className="button primary"
                        href={`#catalog?${new URLSearchParams({ source: connection.id })}`}
                      >
                        Inspect registered assets{" "}
                        <Icon name="arrow" size={16} />
                      </a>
                    ) : (
                      <p>Preview expired. Run discovery again.</p>
                    )}
                  </div>
                  {batch.status === "COMMITTED" && <SuggestedMappings session={session} source={connection.id}/>}
                </>
              )}
            </>
          ))}
      </section>
    </div>
  );
}
export function RunWarnings({ value }: { value: string }) {
  let warnings: unknown;
  try {
    warnings = JSON.parse(value ?? "[]");
  } catch {
    warnings = [];
  }
  return (
    <>
      {Array.isArray(warnings) &&
        warnings.map((w, i) => (
          <p className="notice warning" key={i}>
            {String(w)}
          </p>
        ))}
    </>
  );
}
