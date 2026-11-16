import type { IconName } from "../../components/Design";
export const providers: Record<
  string,
  { name: string; category: string; icon: IconName; description: string }
> = {
  SNOWFLAKE: {
    name: "Snowflake",
    category: "Databases & warehouses",
    icon: "snow",
    description:
      "Accessible tables, views and columns in one database. Authenticate with an RSA key pair.",
  },
  AMAZON_S3: {
    name: "Amazon S3",
    category: "Cloud storage",
    icon: "cloud",
    description:
      "Object paths, sizes, modification times and ETags. Inventory only; file contents are never downloaded.",
  },
  GITHUB_SQL: {
    name: "GitHub SQL",
    category: "Code repositories",
    icon: "code",
    description:
      "SQL files and their pinned repository revision. Parsed SQL remains separate from confirmed lineage.",
  },
  POWER_BI: {
    name: "Power BI",
    category: "Analytics",
    icon: "metrics",
    description:
      "Accessible reports, semantic models and observed report-to-model references. Visuals and DAX require manual metadata.",
  },
  FILE_IMPORT: {
    name: "File import",
    category: "Manual sources",
    icon: "file",
    description:
      "Upload exported metadata, review proposed changes and explicitly commit to the catalog.",
  },
  MANUAL: {
    name: "Manual source",
    category: "Manual sources",
    icon: "database",
    description:
      "Register assets and explicitly declared relationships with supporting evidence.",
  },
};
export type Field = {
  key: string;
  label: string;
  hint: string;
  placeholder?: string;
  required?: boolean;
  type?: string;
  max?: number;
};
export const sourceFields: Record<string, Field[]> = {
  AMAZON_S3: [
    {
      key: "region",
      label: "AWS Region",
      hint: "Use the bucket’s Region identifier.",
      placeholder: "ap-south-1",
      required: true,
    },
    {
      key: "bucket",
      label: "Bucket name",
      hint: "Name only, without s3:// or a folder path.",
      placeholder: "novamart-trace-test-space",
      required: true,
    },
    {
      key: "prefix",
      label: "Folder prefix",
      hint: "Case sensitive. A trailing / limits discovery to this folder. Leave empty for the whole bucket.",
      placeholder: "novamart/baseline/",
    },
    {
      key: "maxObjects",
      label: "Maximum objects",
      hint: "1–1,000 per discovery. Narrow the prefix to inventory larger sources. A bounded result is marked partial.",
      type: "number",
      max: 1000,
      required: true,
    },
  ],
  SNOWFLAKE: [
    {
      key: "account",
      label: "Account identifier",
      hint: "Organization-account or account locator, without https:// or snowflakecomputing.com.",
      placeholder: "organization-account",
      required: true,
    },
    {
      key: "username",
      label: "Username",
      hint: "The Snowflake user whose public key matches your private key.",
      required: true,
    },
    {
      key: "role",
      label: "Role",
      hint: "Use a dedicated role with access to the intended database and schemas.",
      placeholder: "TRACE_METADATA_READER",
    },
    {
      key: "database",
      label: "Database",
      hint: "One database per connection. Preserve identifier case.",
      placeholder: "NOVAMART_DB",
      required: true,
    },
    {
      key: "warehouse",
      label: "Warehouse",
      hint: "A usable warehouse for INFORMATION_SCHEMA queries; USAGE permission is required.",
      placeholder: "TRACE_METADATA_WH",
    },
    {
      key: "schemas",
      label: "Schema allowlist",
      hint: "Comma separated. Empty means all visible schemas; preserve identifier case.",
      placeholder: "RAW, STAGING, CURATED, ANALYTICS",
    },
  ],
  GITHUB_SQL: [
    {
      key: "owner",
      label: "Repository owner",
      hint: "GitHub organization or username.",
      required: true,
    },
    {
      key: "repository",
      label: "Repository",
      hint: "Repository name, without a URL.",
      required: true,
    },
    {
      key: "revision",
      label: "Revision",
      hint: "Branch, tag or commit. Discovery pins the resolved commit.",
      placeholder: "main",
      required: true,
    },
    {
      key: "prefix",
      label: "Folder prefix",
      hint: "Optional relative folder containing SQL files.",
    },
    {
      key: "dialect",
      label: "SQL dialect",
      hint: "For example snowflake, postgres or tsql.",
      placeholder: "snowflake",
    },
    {
      key: "maxFiles",
      label: "Maximum SQL files",
      hint: "1–100. Large files are skipped with a warning.",
      type: "number",
      max: 100,
      required: true,
    },
  ],
  POWER_BI: [
    {
      key: "tenantId",
      label: "Entra tenant ID",
      hint: "Directory tenant UUID.",
      required: true,
    },
    {
      key: "clientId",
      label: "Application client ID",
      hint: "Application registration UUID.",
      required: true,
    },
    {
      key: "workspaceIds",
      label: "Workspace IDs",
      hint: "1–10 comma separated workspace UUIDs accessible to the service principal.",
      required: true,
    },
  ],
  MANUAL: [],
  FILE_IMPORT: [],
};
export const credentialFields: Record<string, Field[]> = {
  AMAZON_S3: [
    {
      key: "accessKeyId",
      label: "Access Key ID",
      hint: "Dedicated IAM identity with read-only inventory access.",
      required: true,
    },
    {
      key: "secretAccessKey",
      label: "Secret Access Key",
      hint: "Paste the complete value without spaces or line breaks.",
      required: true,
    },
    {
      key: "sessionToken",
      label: "Session Token",
      hint: "Optional for IAM user keys. Required with temporary session credentials.",
    },
  ],
  SNOWFLAKE: [
    {
      key: "privateKey",
      label: "RSA private key (PEM)",
      hint: "Paste the complete private PEM, including BEGIN / END lines. A public key (often beginning MIIBIj…) cannot authenticate TRACE. The matching public key belongs on the Snowflake user.",
      required: true,
    },
    {
      key: "privateKeyPassphrase",
      label: "Private-key passphrase",
      hint: "The encryption passphrase for this private-key file, not your Snowflake sign-in password. Leave empty for an unencrypted private key.",
    },
  ],
  GITHUB_SQL: [
    {
      key: "token",
      label: "Repository access token",
      hint: "Use a narrowly scoped token with read access to repository contents.",
      required: true,
    },
  ],
  POWER_BI: [
    {
      key: "clientSecret",
      label: "Application client secret",
      hint: "Use an Entra application allowed to read the selected workspaces.",
      required: true,
    },
  ],
};
const outcomes: Record<string, [string, string]> = {
  SNOWFLAKE_PUBLIC_KEY_PROVIDED: [
    "Public key entered instead of private key",
    "The saved credential is a public key. Paste the matching complete private-key PEM into Authentication. A public key and Snowflake login password cannot replace that private key.",
  ],
  SNOWFLAKE_PRIVATE_KEY_FORMAT: [
    "Complete private-key PEM required",
    "Paste the entire private key, including BEGIN and END lines. Remove terminal prompt characters such as a trailing %. Do not paste the public-key body.",
  ],
  SNOWFLAKE_KEY_PASSPHRASE_REQUIRED: [
    "Private-key passphrase required",
    "This PEM is encrypted. Enter the passphrase used when that private-key file was generated; this is separate from the Snowflake sign-in password.",
  ],
  SNOWFLAKE_KEY_PASSPHRASE_UNEXPECTED: [
    "Unencrypted key does not need a passphrase",
    "This private key is not encrypted. Clear Private-key passphrase and save the complete credentials again. Do not enter your Snowflake sign-in password here.",
  ],
  SNOWFLAKE_PRIVATE_KEY_INVALID: [
    "Private key could not be opened",
    "TRACE could not parse or decrypt this private key. Check the complete PEM and its encryption passphrase. No Snowflake login was attempted.",
  ],
  SNOWFLAKE_WAREHOUSE_IS_SCHEMA_LIST: [
    "Warehouse contains schema names",
    "Enter one actual Snowflake warehouse name in Warehouse. RAW, STAGING, CURATED and ANALYTICS belong in Schema allowlist. Leave Warehouse empty only if the Snowflake user has a usable default warehouse.",
  ],
  INVALID_CONFIGURATION: [
    "Configuration error",
    "Check the highlighted source fields, save corrections and test again.",
  ],
  CONNECTED: [
    "Connected successfully",
    "The provider accepted a real metadata-access request. Discovery and catalog commitment are separate steps.",
  ],
  S3_LIST_ACCESS_DENIED: [
    "Permission denied",
    "TRACE reached Amazon S3, but these credentials cannot list the selected bucket and prefix. Verify s3:ListBucket on the bucket ARN with an s3:prefix condition. Check bucket policies, permission boundaries and explicit denies. AWS did not identify which policy blocked access.",
  ],
  S3_INVALID_CREDENTIALS: [
    "Invalid AWS credentials",
    "AWS rejected the supplied credentials. Verify the Access Key ID and Secret Access Key, and include the Session Token for temporary credentials.",
  ],
  S3_SIGNATURE_MISMATCH: [
    "AWS signature rejected",
    "Verify the complete Secret Access Key and matching Access Key ID. Remove accidental whitespace and check the processor host’s clock.",
  ],
  S3_SESSION_EXPIRED: [
    "Session credentials expired",
    "Rotate the Access Key ID, Secret Access Key and Session Token together, then test again.",
  ],
  S3_REGION_MISMATCH: [
    "AWS Region mismatch",
    "AWS rejected the selected Region or regional endpoint. Check the bucket’s Region, update AWS Region and retry.",
  ],
  S3_BUCKET_NOT_FOUND: [
    "Bucket not found",
    "AWS reported that the bucket does not exist. Verify the exact bucket name and AWS account.",
  ],
  S3_CLOCK_SKEW: [
    "Processor clock is out of sync",
    "Synchronize the processor host’s system clock and retry the signed AWS request.",
  ],
  DISCOVERY_LIMIT_INVALID: [
    "Discovery limit is out of range",
    "Maximum objects must be 1–1,000. Save a smaller limit before testing. Use narrower prefixes for larger inventories.",
  ],
  CREDENTIAL_WHITESPACE: [
    "Credentials contain whitespace",
    "Paste each complete AWS credential without spaces or line breaks, save credentials and test again.",
  ],
  CREDENTIALS_REQUIRED: [
    "Credentials required",
    "Enter and save all required credentials before testing or discovering metadata.",
  ],
  PROCESSOR_UNAVAILABLE: [
    "Metadata processor unavailable",
    "TRACE could not contact its metadata processor. Check the processor service and backend processor URL, then retry.",
  ],
  PROCESSOR_AUTH_UNAVAILABLE: [
    "Processor authentication unavailable",
    "Ask an operator to configure the same private TRACE_PROCESSOR_TOKEN in the backend and processor.",
  ],
  PROCESSOR_ERROR: [
    "Processor request failed",
    "Check processor availability and matching private service tokens. Retry after the operator repairs the service.",
  ],
  ENCRYPTION_UNAVAILABLE: [
    "Credential encryption unavailable",
    "Ask an operator to configure TRACE_CONNECTOR_ENCRYPTION_KEY in the backend. Retain the existing key when credentials are already stored.",
  ],
  CREDENTIAL_UNAVAILABLE: [
    "Stored credentials unavailable",
    "The configured encryption key cannot open these credentials. Restore the original backend encryption key; do not replace it with a newly generated key.",
  ],
  NETWORK_TIMEOUT: [
    "Provider request timed out",
    "Check processor network access to the provider and retry. No partial inventory from a failed request is committed.",
  ],
  S3_THROTTLED: [
    "Amazon S3 throttled the request",
    "Wait briefly and retry. Discovery retries transient failures automatically, within its attempt limit.",
  ],
  CONNECTION_RATE_LIMITED: [
    "Acquisition limit reached",
    "Wait one minute before testing or discovering this connection again. TRACE allows at most 20 acquisition requests per connection per minute.",
  ],
  RATE_LIMITED: [
    "Provider rate limit reached",
    "Wait briefly before retrying. Discovery retries transient failures automatically.",
  ],
  AUTHENTICATION_FAILED: [
    "Authentication failed",
    "The provider rejected authentication. Verify or rotate the provider-specific credentials and retry.",
  ],
  PERMISSION_DENIED: [
    "Permission denied",
    "The provider rejected metadata access. Check the identity’s permissions on the selected source.",
  ],
  CONFIGURATION_ERROR: [
    "Configuration error",
    "Check the source settings and credential format. Save corrections and test again.",
  ],
  CONFIGURATION_CHANGED: [
    "Connection changed",
    "Settings or credentials changed during this operation. Test the current configuration and start a new discovery.",
  ],
  DRIVER_UNAVAILABLE: [
    "Connector driver unavailable",
    "Ask an operator to install the processor requirements and restart the processor.",
  ],
  ACCESS_REVOKED: [
    "Access no longer available",
    "Check your active organization membership and owning-team permissions.",
  ],
  FORBIDDEN: [
    "Team authorization required",
    "Select the appropriate organization and ask a team owner for write access.",
  ],
};
export function outcome(code: string | null | undefined): [string, string] {
  return (
    outcomes[code ?? ""] ?? [
      "Service unavailable",
      "The operation could not be completed. Retry, or share the safe diagnostic reference with an operator.",
    ]
  );
}
export function awsPolicy(bucket: string, prefix: string, region: string) {
  const partition = region.startsWith("cn-")
    ? "aws-cn"
    : region.startsWith("us-gov-")
      ? "aws-us-gov"
      : "aws";
  return JSON.stringify(
    {
      Version: "2012-10-17",
      Statement: [
        {
          Sid: "BucketRegion",
          Effect: "Allow",
          Action: "s3:GetBucketLocation",
          Resource: `arn:${partition}:s3:::${bucket || "YOUR_BUCKET"}`,
        },
        {
          Sid: "ScopedInventory",
          Effect: "Allow",
          Action: "s3:ListBucket",
          Resource: `arn:${partition}:s3:::${bucket || "YOUR_BUCKET"}`,
          Condition: { StringEquals: { "s3:prefix": prefix } },
        },
      ],
    },
    null,
    2,
  );
}
export function validateSource(
  provider: string,
  values: Record<string, string>,
): Record<string, string> {
  const errors: Record<string, string> = {};
  for (const field of sourceFields[provider] ?? []) {
    const v = values[field.key] ?? "";
    if (field.required && !v.trim())
      errors[field.key] = "This field is required.";
    else if (
      field.type === "number" &&
      (!/^\d+$/.test(v) || +v < 1 || +v > field.max!)
    )
      errors[field.key] = `Enter a whole number between 1 and ${field.max}.`;
  }
  if (provider === "AMAZON_S3") {
    if (!/^[a-z]{2}(?:-[a-z]+)+-\d+$/.test(values.region ?? ""))
      errors.region = "Use a Region identifier such as ap-south-1.";
    const bucket = values.bucket ?? "";
    if (
      !/^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$/.test(bucket) ||
      bucket.includes("..") ||
      /^[0-9.]+$/.test(bucket)
    )
      errors.bucket = "Enter the bucket name without a URL or path.";
  }
  if (provider === "SNOWFLAKE") {
    const schemas = (values.schemas ?? "")
      .split(",")
      .map((v) => v.trim())
      .filter(Boolean);
    const warehouse = (values.warehouse ?? "").split(",").map((v) => v.trim());
    if (
      schemas.length > 1 &&
      JSON.stringify(schemas) === JSON.stringify(warehouse)
    )
      errors.warehouse =
        "Enter one warehouse name here. Put this list in Schema allowlist.";
  }
  const prefix = values.prefix ?? "";
  if (
    prefix.startsWith("/") ||
    prefix.includes("\\") ||
    prefix.split("/").includes("..")
  )
    errors.prefix =
      "Use a relative prefix without a leading slash or parent path.";
  return errors;
}
