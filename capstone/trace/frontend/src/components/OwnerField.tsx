import { useId, useState } from "react";
import { request } from "../services/api";
import type { Member, Session } from "../types/api";
export function OwnerField({
  session,
  name,
  label,
  defaultValue = "",
}: {
  session: Session;
  name: string;
  label: string;
  defaultValue?: string;
}) {
  const id = useId();
  const [members, setMembers] = useState<Member[] | null>(null),
    [requested, setRequested] = useState(false),
    [unavailable, setUnavailable] = useState(false);
  const canRead = ["OWNER", "ADMIN"].includes(session.organizationRole || "");
  async function suggest() {
    if (!canRead || requested) return;
    setRequested(true);
    try {
      const rows = await request<Member[]>("/organization/members");
      if (Array.isArray(rows))
        setMembers(rows.filter((m) => m.active && m.status === "ACTIVE"));
      else setUnavailable(true);
    } catch {
      setUnavailable(true);
    }
  }
  return (
    <label>
      {label}
      <input
        name={name}
        defaultValue={defaultValue}
        maxLength={150}
        list={id}
        onFocus={() => void suggest()}
        placeholder="Name of the responsible person or team"
      />
      <datalist id={id}>
        {members?.map((m) => (
          <option key={m.user_id} value={m.display_name || m.email}>
            {m.email}
          </option>
        ))}
      </datalist>
      {canRead && (
        <small>
          {unavailable
            ? "Member suggestions are unavailable. Enter an owner you can verify."
            : "Choose an existing member when available, or enter a known owner. This records responsibility without changing access."}
        </small>
      )}
    </label>
  );
}
