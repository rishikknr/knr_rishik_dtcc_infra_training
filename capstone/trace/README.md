# Current product-experience release — November 12, 2026

The current implementation adds a minimal light-blue workspace, browse-first formula/metric/transformation libraries, guided creation and report mapping, registered-metadata SQL suggestions, catalog sections and filters, explainable change impact, source-mapping suggestions and independent governance reviews. **Schema V18 is required**; existing V1–V17 migrations remain unchanged. Integration API 3 remains compatible, with additive product endpoints.

Open [TRACE](http://127.0.0.1:5173/#overview). Use Catalog to understand an existing dataset, Transformations / Formula Studio / Metrics to browse existing definitions, Reporting to inspect usage, and Impact Analysis / Governance to plan and review changes. See the [product-experience verification report](docs/verification/product-experience/README.md) for implementation details, exact changes, preservation results, executed tests, real screenshots and remaining verification limits. The [previous lineage report](docs/verification/lineage-overhaul/README.md) remains the historical baseline for the graph engine and NovaMart evidence.

The V17→V18 local rollout took a database backup and verified every existing table fingerprint, roles and encryption-key file. It did not reset the database, rotate credentials, deploy SQL, or modify cloud data. Browser writes for approval, creation and mapping acceptance used a separate disposable database.

The NovaMart SQL and synthetic dataset folder was published to [infta_training_novamart](https://github.com/rishikknr/infta_training_novamart/tree/cbbe4b742f3978fc4ecc41dd560a5c5fddc01234), with credential values replaced by placeholders in the published copy. Existing local files were preserved.

The sections below retain the earlier foundation and rollout history. Prefer the current product-experience report for release requirements; the sections below describe earlier releases.

---

# Trace — authenticated metadata registry

Trace catalogs datasets, columns, source connections, and metadata imports within
independent organizations. Step 09 adds Spring Security email/password login,
cookie sessions, CSRF, organization switching, team permissions, local Sign Up, invitations,
password recovery, and immutable security audit records.

See [local Sign Up and Sign In](docs/local-signup.md) for the Step 09 amendment,
its configuration, verification, and V6 operator rollout.

See [authentication and authorization](docs/authentication-and-authorization.md)
for architecture, diagrams, the complete API reference, permission matrix,
verification results, acceptance checklist, and deployment limitations.
See [tenant isolation](docs/tenancy.md) for the database trust boundary.

## Step 10 metadata integration

The source now includes real connector adapters, secure connection lifecycle,
generic/file imports, manual assets and columns, lineage mapping, formula versions,
metrics and report usage. Discovery runs in a durable PostgreSQL queue.
Registry pages expose counts and filters, and Formula Studio supports SQL highlighting,
editable drafts and version comparison. See the [implementation and verification report](docs/step10/verification-report.md),
[connector/import guide](docs/step10/connectors-and-imports.md), [API reference](docs/step10/api.md)
and [operator rollout](docs/step10/operator-rollout.md).

The initial development inspection was V1–V5. The final read-only observation
shows V15 after concurrent operator activity; verify the remaining rollout gate
in the current release report before treating development as accepted. Do not overwrite a
running backend JAR. Generic parsing/provider operations require a private shared
TRACE_PROCESSOR_TOKEN; stored provider credentials additionally require a persistent
TRACE_CONNECTOR_ENCRYPTION_KEY. No live vendor verification is claimed without
provided authorized credentials. The report lists the remaining broad-scope gaps;
Step 11 is not started.

## Final hardening release

The current source requires schema **V15** and integration API **3**. See the
[release report](docs/final-hardening/13-release-readiness.md),
[execution evidence](docs/final-hardening/10-end-to-end-test-results.md), and
[operator-controlled rollout](docs/final-hardening/11-operator-rollout.md).
The development database was initially **V5** and was observed at **V15** after
concurrent operator activity. This hardening task did not perform that rollout;
Gate G remains **PENDING VERIFICATION** of backup/restore, login and preserved-data
smoke evidence. Existing acceptance on 55433 was observed at **V10** and was not
reset here. The active unassigned legacy organization still needs an explicit
operator quarantine/adoption decision. All four providers remain **LIVE UNVERIFIED**.

Build with `backend/ops/build-release.sh` and start with
`backend/ops/start-release.sh`; these use immutable artifacts outside `target/`.
Never run a reset-based suite against 5433 or 55433. New destructive tests require
`TRACE_TEST_ALLOW_RESET=true` and a separate disposable database.

## Prerequisites

Java 21, included Maven wrapper, Python 3.12, Node compatible with Vite, Docker,
and a PostgreSQL client. Spring Boot **4.1.2** manages Spring Security **7.1.2**.
The existing development database remains **localhost:5433/trace**.
No Redis, external identity service, JWT, or browser authentication storage is used.

## Prepare the development database

Keep the existing `.env`, volume, UUIDs, and V1–V5 migrations. Do not reset the
schema on port 5433. Historical November 10 inspection found V1–V5; the final
November 11 read-only observation found V15 after concurrent operator activity.
This task did not apply development migrations or change its administrator.
Inspect current history before following any historical setup instructions.

Start the existing database and export your existing role secrets privately:

```zsh
docker compose up -d --wait postgres
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
read -s 'TRACE_MIGRATION_PASSWORD?Migration-role password: '
export TRACE_MIGRATION_PASSWORD
read -s 'TRACE_RUNTIME_PASSWORD?Runtime-role password: '
export TRACE_RUNTIME_PASSWORD
```

For a new installation only, provision distinct, restricted roles with
`backend/ops/bootstrap-roles.sql` as the database administrator before migrating.
Existing installations with the roles already provisioned do not need to rotate
their passwords. The runtime is always `trace_runtime`; Flyway is `trace_migrator`.

Build and migrate with no HTTP server:

```sh
backend/ops/build-release.sh
# Set this to the exact immutable path returned by the build:
export TRACE_RELEASE_JAR=/absolute/path/to/trace-v15-SHA256.jar
export TRACE_DATABASE_URL=jdbc:postgresql://localhost:5433/trace
java -jar "$TRACE_RELEASE_JAR" --migrate-only
```

The operator command uses the two exported secrets, applies versioned migrations,
and verifies the restricted runtime role. `TRACE_DATABASE_URL` optionally changes
its database URL; its default is `jdbc:postgresql://localhost:5433/trace`.
Normal server startup also runs Flyway with separate migration credentials.
Maven skips the PostgreSQL suite unless the disposable test URL is supplied.

## Provision the first administrator

For a new installation without a platform administrator, run this deliberately in
an interactive terminal after migrations. **Skip this if your administrator already
exists; do not change its account or password.**

```sh
java -jar "$TRACE_RELEASE_JAR" --bootstrap-admin
```

Enter an explicit email, display name, new administrator password, and the
migration-role database password at the prompts. Password entry is hidden.
The command hashes the password with BCrypt, writes the bootstrap audit event,
opens no HTTP listener, and refuses a second bootstrap. There is no default
password, public bootstrap endpoint, or automatic fixture login. It connects as
`trace_migrator`; runtime cannot invoke the bootstrap function.

Legacy fixture users retain their UUIDs and have **no new passwords assigned**.
The new platform administrator can issue a one-time recovery link to a legacy
identity, including an identity with no local credentials. This is a deliberate,
audited activation; existing memberships remain unchanged.
See the architecture document for the exact legacy-account procedure.

## Start the application

Use separate terminals; do not expose the loopback development services publicly.

```sh
# Terminal 1: stateless parser, no database credentials
cd processor
python3.12 -m venv .venv  # only if absent
.venv/bin/pip install -r requirements.txt
.venv/bin/uvicorn app:app --host 127.0.0.1 --port 8000
```

```sh
# Terminal 2: restricted runtime, with the role secrets exported above
export TRACE_LOCAL_TOKEN_DELIVERY=true
export TRACE_LOCAL_SIGNUP_ENABLED=true
export TRACE_DATABASE_URL=jdbc:postgresql://localhost:5433/trace
export TRACE_SERVER_PORT=8081
backend/ops/start-release.sh --spring.profiles.active=local-dev
```

```sh
# Terminal 3: same-origin frontend proxy
cd frontend
npm ci
npm run dev
```

Open [Trace](http://127.0.0.1:5173). Sign in with the provisioned administrator.
Local users can also use **Sign Up** to create a normal account, then sign in and
join through an invitation. Signup grants no memberships or administrative role.
Use **Platform** to create organizations; creation explicitly assigns the creator
an organization OWNER membership. Select an organization, create teams, invite
users in **Settings**, and assign activated organization members to teams.
Use **Connections** to create a Snowflake source with an owning team, then import.
The organization switcher shows only current active memberships.

Simple signup defaults to enabled only with the exclusive loopback `local-dev`
profile. Set `TRACE_LOCAL_SIGNUP_ENABLED=false` to disable it there. Outside that
profile it is disabled; forcing the signup property on production, mixed profiles,
or an external listener fails startup. This local flow does not verify email
ownership and must not be exposed publicly. No OTP or email verification was added.
Forgot Password directs users to the existing administrator-issued recovery link.

`TRACE_LOCAL_TOKEN_DELIVERY=true` is optional and only valid with the exclusive
`local-dev` profile and loopback binding. It returns an invitation/recovery link
once to the authorized administrator. It does not authenticate users or bypass
permissions. No delivery provider is configured in other environments: issuing
links returns 503 until a delivery channel is implemented.

If 8080 is occupied, leave the existing process alone and start with
`--server.port=8081`; use `TRACE_BACKEND_URL=http://127.0.0.1:8081 npm run dev`.
`TRACE_PROCESSOR_URL` changes the parser URL. Backend URL configuration can use
`--spring.datasource.url=...`; the operator commands use `TRACE_DATABASE_URL`.
In HTTPS environments set `TRACE_SECURE_COOKIES=true`. Idle timeout defaults to
30 minutes and can be configured with `TRACE_SESSION_TIMEOUT`.

## Verification

Use the [current verification commands](docs/final-hardening/10-end-to-end-test-results.md)
and [guarded browser setup](docs/final-hardening/11-operator-rollout.md#reproduce-isolated-browser-acceptance).
They establish a separate disposable PostgreSQL 16 server on **55437**. The existing
55433 acceptance fixtures and persistent 5433 database are protected by the test
preflight and must not be reset. Do not substitute either URL.

The independently rerun release checks include 96 Java tests, 111 Python tests,
37 frontend tests, a real Chrome suite, real Java/processor restart recovery,
forced-RLS/tenant and authorization matrices, historical upgrades and an owner
race reproduction. See the linked machine-readable evidence for exact current
outcomes and the distinction between automated scope, operator rollout, live
provider verification and pending manual assistive-technology checks.

## CSV format and semantics

```csv
database,schema,table,column,data_type
NOVAMART,RAW,ORDERS,ORDER_ID,NUMBER
NOVAMART,RAW,ORDERS,GROSS_AMOUNT,NUMBER
```

Required headers are case-insensitive: `database,schema,table,column,data_type`.
Optional `description` is a column description (maximum 4,000 characters).
Ordinary identifiers are trimmed and normalized to uppercase; data types are
trimmed, whitespace-normalized, and uppercased. Unsupported quoted/case-sensitive
or compound identifiers in this format produce a row-specific validation error.
CSV quoting for delimiters, descriptions, and data types such as `"NUMBER(10,2)"`
is supported. UTF-8 BOM is accepted.

Backwards compatibility: the exact legacy header family
`DATABASE_NAME,SCHEMA_NAME,TABLE_NAME,COLUMN_NAME,DATA_TYPE[,DESCRIPTION]` retains
literal identifier spelling, including mixed case and embedded dots, and escapes
qualified keys to avoid collisions. These fields contain literal identifier
values, not SQL expressions or Snowflake SQL quoting syntax. Do not interchange
legacy literal exports and ordinary exports for case-sensitive datasets.

Limits: 5 MiB, 20,000 data rows (including duplicates), 1,000 datasets,
2,000 columns per dataset, identifier length 255, qualified key length 500,
display name length 255, type length 100. Exact duplicate normalized definitions
are deduplicated with a warning; conflicting definitions are rejected. Invalid
uploads create no batch or canonical records. No SQL is executed by the parser.
SQLGlot is used by Step 10 SQL parsing, not by CSV import.

Preview persists only a tenant/creator-owned batch with validated metadata,
warnings, file information, per-dataset changes, and comparison counts. It does
not create a connection, dataset, or column. Status is `PREVIEW_READY` until
committed or expired. Expiry is derived from the stored deadline; the database
retains `PREVIEW_READY`, and GET returns `EXPIRED` after 24 hours. Expired commits
return 409. Old committed batches from before V4 may have null result statistics.

Commit locks the batch and source connection, revalidates connection/team access,
recomputes actual changes, upserts assets/columns, stores results, and marks the
batch `COMMITTED` in one verified tenant transaction. Retries return the original
results and timestamp. Failed commits roll back all changes and retain the preview
for retry. Connection locking serializes competing imports for accurate counts.
Existing UUIDs, team ownership, human descriptions, and columns omitted from partial
uploads are preserved. Imported descriptions fill only an empty description.
`last_import_id` records the most recent successful import that saw each object.

Statistics distinguish new, updated, and unchanged datasets and columns. An
existing dataset is updated when its discovered name or any supplied column changes;
last-seen/provenance refresh alone counts as unchanged. Preview counts are a snapshot;
commit counts reflect the state at commit time. No columns are automatically removed.


## Structure and next milestones

```text
backend/src/main/java/com/trace/backend/
  controller/        HTTP endpoints and typed contracts
  dto/               Request and response records
  entity/            Identity and registry records
  mapper/            Typed JDBC/registry mapping
  service/           Identity, organizations, teams, imports, resource ownership
  repository/        Parameterized SQL and tenant transaction guards
  security/          Spring Security principal, policies, passwords, rate limits
  tenancy/           Session selection, fresh roles, LOCAL RLS context, runtime guard
  config/            Filter chain, operator migration command
backend/src/main/resources/db/migration/   V1–V15
backend/ops/         Existing role bootstrap and historical local fixtures
frontend/src/        Real login, onboarding, profile, organization/team management,
                     platform administration, connections, catalog and imports
processor/           Bounded parsers and private provider acquisition
```

Sessions and rate limits are process-local; restarting the backend signs users
out. Horizontal deployment needs shared session storage and coordinated abuse
controls. Email delivery, MFA/SSO, metric approval workflows, automatic resolved
SQL lineage, scheduled discovery, and production hardening remain later work.
This implementation is a tested single-instance foundation, not a production
readiness certification. No formal project execution log has been created.
