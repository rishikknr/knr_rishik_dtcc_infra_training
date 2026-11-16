import { ApiError } from "../services/api";
import { Icon } from "./Design";
export function Feedback({ error }: { error: unknown }) {
  if (!error) return null;
  return (
    <div className="notice error" role="alert">
      <strong>
        {error instanceof Error
          ? error.message
          : "Something went wrong. Try again."}
      </strong>
      {error instanceof ApiError && error.details.length > 0 && (
        <ul>
          {error.details.map((detail, index) => (
            <li key={index}>
              {detail.row ? `Row ${detail.row} · ` : ""}
              {detail.field}: {detail.message}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
export function Loading({ children = "Loading…" }: { children?: string }) {
  return (
    <div className="notice loading-state" role="status">
      {children}
      <span aria-hidden="true" />
      <span aria-hidden="true" />
    </div>
  );
}
export function Success({ children }: { children: string }) {
  return (
    <p className="notice success" role="status">
      <Icon name="check" size={14} /> {children}
    </p>
  );
}
