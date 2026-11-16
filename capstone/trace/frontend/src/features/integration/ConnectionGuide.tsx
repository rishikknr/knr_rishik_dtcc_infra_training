import { useState } from "react";
import { awsPolicy } from "./connectionModel";
function CopyCode({ text, label }: { text: string; label: string }) {
  const [message, setMessage] = useState("");
  return (
    <div className="setup-code">
      <div className="section-title">
        <strong>{label}</strong>
        <button
          type="button"
          onClick={() => {
            void navigator.clipboard.writeText(text).then(
              () => setMessage("Copied to clipboard."),
              () =>
                setMessage(
                  "Clipboard unavailable. Select and copy the template below.",
                ),
            );
          }}
        >
          Copy template
        </button>
      </div>
      <pre tabIndex={0} aria-label={label}>
        {text}
      </pre>
      <small role="status">{message}</small>
    </div>
  );
}
const identifier = (value: string) => `"${value.replaceAll('"', '""')}"`;
export function ConnectionGuide({
  provider,
  values,
}: {
  provider: string;
  values: Record<string, string>;
}) {
  if (provider === "AMAZON_S3")
    return (
      <details className="setup-guide" open>
        <summary>How to configure AWS access</summary>
        <p>
          TRACE lists object metadata using <code>ListObjectsV2</code>. It does
          not read CSV contents, versions or object bodies.
        </p>
        <ol>
          <li>
            Open the{" "}
            <a
              href="https://console.aws.amazon.com/iam/"
              target="_blank"
              rel="noreferrer"
            >
              AWS IAM console
            </a>{" "}
            in the bucket’s account.
          </li>
          <li>
            Under Policies, choose Create policy → JSON. Paste the policy below,
            review the exact bucket and prefix, and name the policy.
          </li>
          <li>
            Under Users, create a dedicated TRACE user without console access.
            Attach only this scoped policy.
          </li>
          <li>
            Open the user’s Security credentials → Access keys → Create access
            key. Select the applicable use case, then securely retain the Access
            Key ID and Secret Access Key.
          </li>
          <li>
            Enter both values in Authentication. Temporary credentials also need
            their matching Session Token.
          </li>
          <li>
            Save credentials, then run Test Connection. Keep AWS Block Public
            Access enabled.
          </li>
        </ol>
        <CopyCode
          label="Scoped S3 inventory policy"
          text={awsPolicy(
            values.bucket ?? "",
            values.prefix ?? "",
            values.region ?? "",
          )}
        />
        <p className="hint">
          The prefix condition matches the exact prefix entered in TRACE.
          GetBucketLocation is scoped to the bucket; ListBucket is scoped by
          prefix. No GetObject or GetObjectVersion permission is needed by this
          adapter. An explicit deny in another policy can still block access.
        </p>
        <p className="hint">
          Long-lived IAM keys require secure storage and periodic rotation.
          Prefer short-lived session credentials where available; refresh all
          three values together before expiry. Automatic role assumption is a
          future enhancement.
        </p>
        <a
          href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListObjectsV2.html"
          target="_blank"
          rel="noreferrer"
        >
          AWS API and permissions reference ↗
        </a>
      </details>
    );
  if (provider === "SNOWFLAKE") {
    const db = identifier(values.database || "YOUR_DATABASE"),
      role = identifier(values.role || "TRACE_METADATA_READER"),
      user = identifier(values.username || "TRACE_USER");
    const schemas = (values.schemas || "YOUR_SCHEMA")
      .split(",")
      .map((s) => s.trim())
      .filter(Boolean);
    const sql = [
      "-- Run as an administrator authorized to grant these privileges.",
      `CREATE ROLE IF NOT EXISTS ${role};`,
      `GRANT USAGE ON DATABASE ${db} TO ROLE ${role};`,
      ...schemas.flatMap((schema) => [
        `GRANT USAGE ON SCHEMA ${db}.${identifier(schema)} TO ROLE ${role};`,
        `GRANT REFERENCES ON ALL TABLES IN SCHEMA ${db}.${identifier(schema)} TO ROLE ${role};`,
        `GRANT SELECT ON ALL VIEWS IN SCHEMA ${db}.${identifier(schema)} TO ROLE ${role};`,
      ]),
      `GRANT USAGE ON WAREHOUSE ${identifier(values.warehouse || "YOUR_WAREHOUSE")} TO ROLE ${role};`,
      `GRANT ROLE ${role} TO USER ${user};`,
      `-- Assign only the public key, without PEM headers, to this user.`,
      `ALTER USER ${user} SET RSA_PUBLIC_KEY='YOUR_PUBLIC_KEY_BODY';`,
    ].join("\n");
    return (
      <details className="setup-guide" open>
        <summary>How to configure Snowflake key-pair access</summary>
        <ol>
          <li>
            Find your account identifier in Snowflake account details. Use
            organization-account, without a URL.
          </li>
          <li>
            Create a dedicated RSA key pair of at least 2,048 bits. Keep the
            private key secure; assign only the public key to your Snowflake
            user.
          </li>
          <li>
            Ask an administrator to review the scoped grants below. Supply a
            warehouse usable by this role for INFORMATION_SCHEMA queries.
          </li>
          <li>
            Enter the account, user, role, database and schema allowlist in
            Source settings. Paste the PEM private key in Authentication;
            include the passphrase if the key is encrypted.
          </li>
          <li>
            Test metadata access, then discover and review the inventory before
            committing.
          </li>
        </ol>
        <CopyCode label="Snowflake scoped setup SQL" text={sql} />
        <p className="hint">
          Table REFERENCES permits metadata access; SELECT on views also permits
          reading view data. Review these grants with your administrator. TRACE
          queries INFORMATION_SCHEMA, not table contents. Future objects need
          new grants; the template does not grant account-wide or future access.
        </p>
        <p className="hint">
          Snowflake only returns objects visible to the configured role. A
          successful query does not prove every expected object is visible. View
          definitions are optional and may remain unavailable.
        </p>
        <p className="notice warning">
          AWS credentials for an S3 external stage cannot authenticate TRACE to
          Snowflake. This connection needs the Snowflake user’s RSA private key.
        </p>
        <a
          href="https://docs.snowflake.com/en/user-guide/key-pair-auth"
          target="_blank"
          rel="noreferrer"
        >
          Snowflake key-pair setup reference ↗
        </a>
      </details>
    );
  }
  return (
    <div className="setup-guide">
      <h3>Prepare provider access</h3>
      <p>
        {provider === "GITHUB_SQL"
          ? "Create a token with read access to repository contents. Confirm it can access the selected repository and revision. TRACE discovers bounded SQL files and records their resolved commit."
          : "Enable service-principal access in Power BI tenant settings and grant the application access to the selected workspaces. OAuth token issuance alone does not establish metadata access; TRACE tests reports and datasets in each workspace."}
      </p>
    </div>
  );
}
