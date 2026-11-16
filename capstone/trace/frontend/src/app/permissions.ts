import type { Session } from "../types/api";

export function allowed(session: Session, team: string) {
  return (
    ["OWNER", "ADMIN"].includes(session.organizationRole ?? "") ||
    (session.organizationRole === "MEMBER" &&
      ["OWNER", "EDITOR"].includes(session.teamRoles[team] ?? ""))
  );
}
