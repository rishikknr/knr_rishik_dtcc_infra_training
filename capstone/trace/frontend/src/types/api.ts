export interface RegistryStatus {
  application: string;
  database: string;
  registered_assets: number;
  organization: string;
}
export interface Connection {
  connection_type: string;
  description: string;
  configuration: Record<string, unknown>;
  status: string;
  reason_code: string | null;
  has_credentials: boolean;
  last_tested_at: string | null;
  last_successful_test_at?: string | null;
  last_test_diagnostic_id?: string | null;
  discovered_asset_count?: number | null;
  registered_asset_count?: number;
  recent_run_error_code?: string | null;
  last_successful_sync_at: string | null;
  id: string;
  name: string;
  platform: string;
  ingestion_mode: string;
  owner_team_id: string;
  owner_team_name?: string | null;
  recent_run_status?: string | null;
}
export interface Asset {
  object_type?: string | null;
  id: string;
  name: string;
  asset_type: string;
  platform: string;
  external_key: string;
  connection_id: string;
  connection_name: string;
  owner_team_id: string;
  owner_team_name: string | null;
  owner: string | null;
  business_owner?: string | null;
  inventory_state?: string;
  tags?: string[];
  last_seen_at: string | null;
  created_at: string;
  column_count: number;
}
export interface AssetColumn {
  inventory_state?: string;
  nullable: boolean | null;
  ordinal_position: number | null;
  id: string;
  asset_id: string;
  name: string;
  data_type: string;
  description: string | null;
}
export interface PreviewColumn {
  name: string;
  dataType: string;
  description: string | null;
}
export interface Dataset {
  assetType?: string;
  metadata?: Record<string, unknown>;
  externalKey: string;
  name: string;
  columns: PreviewColumn[];
}
export interface Statistics {
  datasets: number;
  columns: number;
  newDatasets: number;
  updatedDatasets: number;
  unchangedDatasets: number;
  newColumns: number;
  updatedColumns: number;
  unchangedColumns: number;
}
export interface ImportBatch {
  id: string;
  connectionId: string;
  status: "PREVIEW_READY" | "COMMITTED" | "EXPIRED";
  sourceType: string;
  format: string;
  preview: { datasets: Dataset[]; warnings: string[] };
  datasetChanges: {
    externalKey: string;
    status: string;
    newColumns: number;
    updatedColumns: number;
    unchangedColumns: number;
  }[];
  comparison: Statistics | null;
  results: Statistics | null;
  filename: string;
  fileSize: number;
  createdAt: string;
  committedAt: string | null;
  expiresAt: string;
}
export interface ErrorDetail {
  row: number | null;
  field: string;
  message: string;
}

export interface Organization {
  id: string;
  name: string;
  slug: string;
  role: "OWNER" | "ADMIN" | "MEMBER" | "VIEWER";
}
export interface Session {
  user: {
    id: string;
    email: string;
    displayName: string;
    active: boolean;
    platformRole: "PLATFORM_ADMIN" | "USER";
  };
  organizations: Organization[];
  selectedOrganization: string | null;
  organizationRole: Organization["role"] | null;
  teamRoles: Record<string, "OWNER" | "EDITOR" | "VIEWER">;
  permissions: {
    permissions: string[];
    teamPermissions: { teamId: string; role: string; permissions: string[] }[];
  };
}
export interface Member {
  user_id: string;
  email: string;
  display_name: string;
  role: string;
  status: string;
  active: boolean;
}
export interface Team {
  id: string;
  name: string;
  slug: string;
  description: string;
  active: boolean;
}
export interface TeamDetail extends Team {
  members: Member[];
  connections: { id: string; name: string }[];
  assets: { id: string; name: string }[];
}
export interface Invitation {
  id: string;
  email: string;
  role: string;
  status: string;
  expires_at: string;
}
export interface Delivery {
  id: string;
  link: string;
  expiresAt: string;
}

export interface RegistrationOptions {
  enabled: boolean;
  minimumPasswordLength: number;
  maximumPasswordBytes: number;
}
export interface RegistrationRequest {
  displayName: string;
  email: string;
  password: string;
  confirmPassword: string;
}
export interface RegistrationResponse {
  id: string;
  displayName: string;
  email: string;
  message: string;
}
