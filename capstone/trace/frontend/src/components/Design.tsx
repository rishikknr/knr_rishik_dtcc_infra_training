import { createPortal } from "react-dom";
import { humanize } from "./format";
import { useEffect, useRef, type ReactNode } from "react";

const icons = {
  overview: "M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z",
  catalog: "M3 5h18v14H3z M3 10h18 M9 10v9",
  lineage: "M3 9h6v6H3z M15 3h6v6h-6z M15 15h6v6h-6z M9 12h3V6h3 M12 12v6h3",
  connections: "M8 3v4 M16 3v4 M6 7h12v4a6 6 0 0 1-6 6v4 M6 11a6 6 0 0 0 6 6",
  upload: "M12 16V3 M7 8l5-5 5 5 M4 15v6h16v-6",
  history: "M3 12a9 9 0 1 1 2.7 6.4 M3 5v7h7 M12 7v5l3 2",
  code: "m8 6-6 6 6 6 m8-12 6 6-6 6 m-2-15-4 18",
  formula: "M17 4h-3a3 3 0 0 0-3 3l-2 10a3 3 0 0 1-3 3H4 M6 10h11",
  metrics: "M4 19V5 M4 19h16 M8 15v-3 M13 15V8 M18 15V5",
  mapping: "M9 7h12 M9 17h12 M3 5h3v4H3z M3 15h3v4H3z",
  teams:
    "M16 21v-2a4 4 0 0 0-4-4H7a4 4 0 0 0-4 4v2 M21 21v-2a4 4 0 0 0-3-4 M13.5 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0 M17 3a4 4 0 0 1 0 8",
  user: "M20 21v-2a5 5 0 0 0-5-5H9a5 5 0 0 0-5 5v2 M16 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0",
  settings: "M4 7h16 M4 17h16 M8 4v6 M16 14v6",
  shield: "M12 22s8-3 8-10V5l-8-3-8 3v7c0 7 8 10 8 10z M9 12l2 2 4-4",
  building:
    "M4 21V7l8-4 8 4v14H4 M9 9h.1 M15 9h.1 M9 13h.1 M15 13h.1 M10 21v-4h4v4",
  panel: "M3 4h18v16H3z M9 4v16",
  menu: "M4 6h16 M4 12h16 M4 18h16",
  search: "M16.5 16.5 21 21 M18 11a7 7 0 1 1-14 0 7 7 0 0 1 14 0",
  chevron: "m9 18 6-6-6-6",
  down: "m6 9 6 6 6-6",
  arrow: "M4 12h15 m-6-6 6 6-6 6",
  plus: "M12 5v14 M5 12h14",
  minus: "M5 12h14",
  close: "m6 6 12 12 M18 6 6 18",
  check: "m5 12 4 4L19 6",
  alert: "M12 3 2 21h20L12 3z M12 9v5 M12 17h.01",
  info: "M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0 M12 11v6 M12 7h.01",
  file: "M5 3h9l5 5v13H5z M14 3v6h5 M8 13h8 M8 17h6",
  logout: "M10 3H4v18h6 M9 12h12 m-4-4 4 4-4 4",
  database:
    "M20 6c0 2-3.6 3-8 3S4 8 4 6s3.6-3 8-3 8 1 8 3z M4 6v12c0 2 3.6 3 8 3s8-1 8-3V6 M4 12c0 2 3.6 3 8 3s8-1 8-3",
  snow: "M12 2v20 M3 7l18 10 M3 17 21 7 M9 3l3 3 3-3 M9 21l3-3 3 3",
  cloud: "M6 18h12a4 4 0 0 0 0-8 6 6 0 0 0-11.5-1A4.5 4.5 0 0 0 6 18z",
} as const;

export type IconName = keyof typeof icons;
export function Icon({ name, size = 18 }: { name: IconName; size?: number }) {
  return (
    <svg
      aria-hidden="true"
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.65"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d={icons[name]} />
    </svg>
  );
}

export function Brand({ linked = false }: { linked?: boolean }) {
  const wordmark = (
    <>
      <span className="brand-mark">
        <Icon name="lineage" size={20} />
      </span>
      <span className="brand-word">TRACE</span>
    </>
  );
  return linked ? (
    <a className="brand" href="#overview" aria-label="Trace overview">
      {wordmark}
    </a>
  ) : (
    <div className="brand">{wordmark}</div>
  );
}

export function PageHeader({
  area,
  title,
  description,
  actions,
}: {
  area: string;
  title: string;
  description?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <header className="page-heading">
      <div>
        <p className="eyebrow">{area}</p>
        <h1>{title}</h1>
        {description && <p className="page-description">{description}</p>}
      </div>
      {actions && <div className="heading-actions">{actions}</div>}
    </header>
  );
}

export function EmptyState({
  icon = "catalog",
  title,
  description,
  actions,
}: {
  icon?: IconName;
  title: string;
  description: string;
  actions?: ReactNode;
}) {
  return (
    <div className="empty-state">
      <span className="empty-icon">
        <Icon name={icon} size={21} />
      </span>
      <h2>{title}</h2>
      <p>{description}</p>
      {actions && <div className="heading-actions">{actions}</div>}
    </div>
  );
}

const tones: Record<string, string> = {
  SUBMITTED: "neutral",
  UNDER_REVIEW: "neutral",
  CHANGES_REQUESTED: "warning",
  APPROVED: "success",
  REJECTED: "error",
  PARSED: "success",
  INVALID: "error",
  UNRESOLVED: "warning",
  UNSUPPORTED: "warning",
  PARTIALLY_COMPLETED: "warning",
  CURRENT: "success",
  NOT_SEEN: "warning",
  HEALTHY: "success",
  CONNECTED: "success",
  ACTIVE: "success",
  COMMITTED: "success",
  REGISTERED: "success",
  SUCCEEDED: "success",
  COMPLETED: "success",
  PREVIEW_READY: "warning",
  PENDING: "warning",
  RUNNING: "warning",
  RETRY_WAIT: "warning",
  AUTHENTICATION_FAILED: "error",
  CONFIGURATION_ERROR: "error",
  PERMISSION_DENIED: "error",
  UNAVAILABLE: "error",
  FAILED: "error",
};
export function Status({ value }: { value: string }) {
  return (
    <span className={`badge status ${tones[value] ?? ""}`}>
      <span className="status-dot" />
      {humanize(value)}
    </span>
  );
}
export function Timestamp({
  value,
  fallback = "—",
}: {
  value?: string | null;
  fallback?: string;
}) {
  return value ? (
    <time dateTime={value} title={new Date(value).toLocaleString()}>
      {new Date(value).toLocaleDateString(undefined, {
        month: "short",
        day: "numeric",
        year: "numeric",
      })}
    </time>
  ) : (
    <span className="muted">{fallback}</span>
  );
}

export function TableRegion({
  label,
  children,
}: {
  label: string;
  children: ReactNode;
}) {
  return (
    <div className="table-wrap" tabIndex={0} role="region" aria-label={label}>
      {children}
    </div>
  );
}

export function Dialog({
  title,
  children,
  onClose,
  className = "",
}: {
  title: string;
  children: ReactNode;
  onClose: () => void;
  className?: string;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const dialog = ref.current;
    dialog?.showModal();
    return () => dialog?.close();
  }, []);
  return createPortal(
    <dialog
      ref={ref}
      className={className}
      aria-label={title}
      onCancel={(event) => {
        event.preventDefault();
        onClose();
      }}
    >
      <header className="dialog-header">
        <h2>{title}</h2>
        <button
          className="icon-button"
          type="button"
          aria-label="Close dialog"
          onClick={onClose}
        >
          <Icon name="close" />
        </button>
      </header>
      {children}
    </dialog>,
    document.body,
  );
}

export function ConfirmDialog({
  title,
  description,
  action,
  onConfirm,
  onClose,
}: {
  title: string;
  description: string;
  action: string;
  onConfirm: () => void;
  onClose: () => void;
}) {
  return (
    <Dialog title={title} onClose={onClose}>
      <div className="dialog-body">
        <p>{description}</p>
        <div className="heading-actions">
          <button type="button" autoFocus onClick={onClose}>
            Cancel
          </button>
          <button
            type="button"
            className="danger"
            onClick={() => {
              onClose();
              onConfirm();
            }}
          >
            {action}
          </button>
        </div>
      </div>
    </Dialog>
  );
}
