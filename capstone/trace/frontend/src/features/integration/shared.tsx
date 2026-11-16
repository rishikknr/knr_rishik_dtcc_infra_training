import { useState } from "react";
import { EmptyState } from "../../components/Design";
import type { Asset, Session, Team } from "../../types/api";
import { allowed } from "../../app/permissions";
export function TeamSelect({
  teams,
  session,
  name = "ownerTeamId",
  value,
}: {
  teams: Team[];
  session: Session;
  name?: string;
  value?: string;
}) {
  return (
    <label>
      Owning team
      <select name={name} defaultValue={value} required>
        <option value="">Select team</option>
        {teams
          .filter((t) => t.active && allowed(session, t.id))
          .map((t) => (
            <option key={t.id} value={t.id}>
              {t.name}
            </option>
          ))}
      </select>
    </label>
  );
}
export function AssetSelect({
  assets,
  name,
  label,
  value,
  onChange,
  optional = false,
  searchable = assets.length > 12,
}: {
  assets: Asset[];
  name: string;
  label: string;
  value?: string;
  onChange?: (value: string) => void;
  optional?: boolean;
  searchable?: boolean;
}) {
  const [query, setQuery] = useState("");
  const [local, setLocal] = useState(value ?? "");
  const selected = onChange ? (value ?? "") : local;
  const search = query.trim().toLowerCase();
  const options = search
    ? assets.filter(
        (asset) =>
          asset.id === selected ||
          `${asset.external_key} ${asset.asset_type} ${asset.connection_name} ${asset.name}`
            .toLowerCase()
            .includes(search),
      )
    : assets;
  return (
    <div className="resource-picker">
      {searchable && (
        <label>
          <span className="sr-only">Filter {label}</span>
          <input
            type="search"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="Filter assets by name or identifier"
            maxLength={255}
          />
        </label>
      )}
      <label>
        {label}
        <select
          name={name}
          value={selected}
          onChange={(event) => {
            if (onChange) onChange(event.target.value);
            else setLocal(event.target.value);
          }}
          required={!optional}
        >
          <option value="">
            {optional ? "No linked asset" : "Select asset"}
          </option>
          {options.map((asset) => (
            <option key={asset.id} value={asset.id}>
              {asset.external_key} · {asset.asset_type} ·{" "}
              {asset.connection_name}
            </option>
          ))}
        </select>
      </label>
      {searchable && search && !options.length && (
        <small role="status">No assets match this search.</small>
      )}
    </div>
  );
}
export function NoTeams() {
  return (
    <section className="panel">
      <EmptyState
        icon="teams"
        title="No writable team"
        description="A team you can edit is required to register connections or metadata. Team roles are listed in Teams."
        actions={
          <a className="button" href="#teams">
            Open Teams
          </a>
        }
      />
    </section>
  );
}
export function JsonDetails({ value }: { value: unknown }) {
  return <pre className="metadata-json">{JSON.stringify(value, null, 2)}</pre>;
}
