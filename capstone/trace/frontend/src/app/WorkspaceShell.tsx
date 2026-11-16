import { useEffect, useRef, useState, type ReactNode } from "react";
import { Brand, Dialog, Icon, type IconName } from "../components/Design";
import { humanize } from "../components/format";
import type { Session } from "../types/api";

type Destination = [string, string, IconName];
const groups: [string, Destination[]][] = [
  [
    "Workspace",
    [
      ["overview", "Overview", "overview"],
      ["connections", "Sources", "connections"],
      ["catalog", "Catalog", "catalog"],
      ["lineage", "Data Flow", "lineage"],
    ],
  ],
  [
    "Define & measure",
    [
      ["transformations", "Transformations", "code"],
      ["formulas", "Formula Studio", "formula"],
      ["metrics", "Metrics", "metrics"],
      ["report-mappings", "Reporting", "mapping"],
    ],
  ],
  [
    "Change & review",
    [
      ["impact", "Impact Analysis", "lineage"],
      ["governance", "Governance", "shield"],
    ],
  ],
  [
    "Source tools",
    [
      ["data-flow", "Source overview", "database"],
      ["import", "Import Center", "upload"],
      ["discovery", "Discovery History", "history"],
    ],
  ],
  [
    "Workspace settings",
    [
      ["teams", "Teams", "teams"],
      ["members", "Members", "user"],
      ["organization", "Organization Settings", "settings"],
      ["audit", "Audit History", "history"],
      ["platform", "Platform Management", "shield"],
    ],
  ],
];

function routeSection(route: string) {
  const base = route.split(/[/?]/)[0];
  if (route.startsWith("connections/") && route.endsWith("/history"))
    return "discovery";
  if (base === "login") return "overview";
  if (["asset", "register", "edit-asset"].includes(base)) return "catalog";
  return base;
}

export function WorkspaceShell({
  session,
  route,
  busy,
  canImport,
  onSelect,
  onSignOut,
  children,
}: {
  session: Session;
  route: string;
  busy: boolean;
  canImport: boolean;
  onSelect: (id: string) => void;
  onSignOut: () => void;
  children: ReactNode;
}) {
  const [compact, setCompact] = useState(false);
  const [dialog, setDialog] = useState<"nav" | "search" | null>(null);
  const [query, setQuery] = useState("");
  const account = useRef<HTMLDetailsElement>(null);
  const main = useRef<HTMLElement>(null);
  const previousRoute = useRef(route);
  const current = routeSection(route);
  const admin =
    session.organizationRole === "OWNER" ||
    session.organizationRole === "ADMIN";
  const org = session.organizations.find(
    (item) => item.id === session.selectedOrganization,
  );
  const visibleGroups = groups
    .map(([label, links]) => ({
      label,
      links: links.filter(([id]) => {
        if (id === "platform")
          return session.user.platformRole === "PLATFORM_ADMIN";
        if (!org) return false;
        if (id === "import") return canImport;
        if (["members", "organization", "audit"].includes(id)) return admin;
        return true;
      }),
    }))
    .filter(({ links }) => links.length > 0);
  const title =
    visibleGroups
      .flatMap(({ links }) => links)
      .find(([id]) => id === current)?.[1] ??
    (current === "profile" ? "Account settings" : "Workspace");
  const initials =
    session.user.displayName
      .trim()
      .split(/\s+/)
      .slice(0, 2)
      .map((part) => part[0])
      .join("")
      .toUpperCase() || "T";

  useEffect(() => {
    if (previousRoute.current === route) return;
    previousRoute.current = route;
    main.current?.focus({ preventScroll: true });
    window.scrollTo({ top: 0, left: 0, behavior: "instant" });
  }, [route]);

  useEffect(() => {
    function key(event: KeyboardEvent) {
      if (event.key === "Escape" && account.current?.open) {
        account.current.open = false;
        account.current.querySelector("summary")?.focus();
      }
      if (
        org &&
        !busy &&
        (event.metaKey || event.ctrlKey) &&
        event.key.toLowerCase() === "k"
      ) {
        event.preventDefault();
        setDialog("search");
      }
    }
    function outside(event: PointerEvent) {
      if (
        account.current?.open &&
        event.target instanceof Node &&
        !account.current.contains(event.target)
      )
        account.current.open = false;
    }
    window.addEventListener("keydown", key);
    window.addEventListener("pointerdown", outside);
    return () => {
      window.removeEventListener("keydown", key);
      window.removeEventListener("pointerdown", outside);
    };
  }, [busy, org]);

  const navigation = (close?: () => void) => (
    <nav className="workspace-nav" aria-label="Workspace navigation">
      {visibleGroups.map(({ label, links }) => {
        const entries = links.map(([id, name, icon]) => (
          <a
            key={id}
            className="nav-link"
            href={`#${id}`}
            title={name}
            aria-label={name}
            aria-current={id === current ? "page" : undefined}
            onClick={close}
          >
            <Icon name={icon} size={16} />
            <span>{name}</span>
          </a>
        ));
        return ["Source tools", "Workspace settings"].includes(label) ? (
          <details
            className="nav-disclosure"
            key={label}
            open={links.some(([id]) => id === current) || undefined}
          >
            <summary>
              <Icon
                name={label === "Source tools" ? "connections" : "settings"}
                size={16}
              />
              <span>{label}</span>
            </summary>
            {entries}
          </details>
        ) : (
          <div key={label}>
            <p className="nav-label">{label}</p>
            {entries}
          </div>
        );
      })}
    </nav>
  );

  return (
    <div className={`workspace${compact ? " compact" : ""}`}>
      <button
        type="button"
        className="button skip-link"
        onClick={() => main.current?.focus()}
      >
        Skip to content
      </button>
      <aside className="sidebar">
        <div className="rail-brand">
          <Brand linked />
          <button
            type="button"
            className="icon-button"
            title={compact ? "Expand sidebar" : "Collapse sidebar"}
            aria-label={compact ? "Expand sidebar" : "Collapse sidebar"}
            onClick={() => setCompact((value) => !value)}
          >
            <Icon name="panel" size={17} />
          </button>
        </div>
        {navigation()}
        <div className="rail-footer">Metadata registry</div>
      </aside>
      <div className="workspace-body">
        <header className="workspace-header">
          <button
            type="button"
            className="icon-button mobile-nav"
            aria-label="Open navigation"
            onClick={() => setDialog("nav")}
          >
            <Icon name="menu" />
          </button>
          <label className="org-switch">
            <Icon name="building" size={16} />
            <span className="sr-only">Active organization</span>
            <select
              disabled={busy || !session.organizations.length}
              value={session.selectedOrganization ?? ""}
              onChange={(event) => onSelect(event.target.value)}
            >
              <option value="" disabled>
                {session.organizations.length
                  ? "Select organization"
                  : "No organization"}
              </option>
              {session.organizations.map((item) => (
                <option key={item.id} value={item.id}>
                  {item.name}
                  {item.id !== org?.id ? ` · ${humanize(item.role)}` : ""}
                </option>
              ))}
            </select>
          </label>
          <nav aria-label="Breadcrumb" className="breadcrumb">
            <Icon name="chevron" size={13} />
            <span aria-current="page">{title}</span>
          </nav>
          <div className="header-tools">
            {org && (
              <button
                type="button"
                className="search-entry"
                aria-label="Search catalog"
                disabled={busy}
                onClick={() => setDialog("search")}
              >
                <Icon name="search" size={16} />
                <span>Search catalog</span>
                <kbd aria-hidden="true">⌘ K</kbd>
              </button>
            )}
            <details className="account-menu" ref={account}>
              <summary
                aria-label="Account menu"
                title={session.user.displayName}
              >
                <span className="avatar" aria-hidden="true">
                  {initials}
                </span>
                <Icon name="down" size={13} />
              </summary>
              <div className="account-popover">
                <div>
                  <strong>{session.user.displayName}</strong>
                  <small>
                    {org
                      ? `${humanize(org.role)} · ${org.name}`
                      : session.user.email}
                  </small>
                </div>
                <a
                  className="button"
                  href="#profile"
                  onClick={() => {
                    if (account.current) account.current.open = false;
                  }}
                >
                  <Icon name="user" size={16} />
                  Account settings
                </a>
                <button
                  type="button"
                  disabled={busy}
                  onClick={() => {
                    if (account.current) account.current.open = false;
                    onSignOut();
                  }}
                >
                  <Icon name="logout" size={16} />
                  Sign out
                </button>
              </div>
            </details>
          </div>
        </header>
        <main ref={main} className="workspace-main" tabIndex={-1}>
          {children}
        </main>
      </div>
      {dialog === "nav" && (
        <Dialog
          title="Navigation"
          className="navigation-dialog"
          onClose={() => setDialog(null)}
        >
          {navigation(() => setDialog(null))}
        </Dialog>
      )}
      {dialog === "search" && (
        <Dialog
          title="Search catalog"
          className="search-dialog"
          onClose={() => setDialog(null)}
        >
          <div className="dialog-body">
            <form
              onSubmit={(event) => {
                event.preventDefault();
                const value = query.trim();
                window.location.hash = value
                  ? `catalog?${new URLSearchParams({ q: value })}`
                  : "catalog";
                setDialog(null);
                setQuery("");
              }}
            >
              <label className="sr-only" htmlFor="global-search">
                Asset name or qualified identifier
              </label>
              <input
                id="global-search"
                autoFocus
                type="search"
                placeholder="Asset name or qualified identifier"
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                maxLength={255}
              />
              <button className="primary">Search</button>
            </form>
          </div>
        </Dialog>
      )}
    </div>
  );
}

export function AuthShell({ children }: { children: ReactNode }) {
  return (
    <div className="auth-shell">
      <aside className="auth-identity">
        <Brand />
        <div>
          <p className="eyebrow">Data lineage &amp; metric registry</p>
          <strong>Metadata governance workspace</strong>
          <p>
            Trace the sources, transformations, and definitions behind your
            organization’s data.
          </p>
          <svg
            className="auth-schematic"
            viewBox="0 0 360 166"
            aria-hidden="true"
          >
            <defs>
              <pattern
                id="auth-grid"
                width="16"
                height="16"
                patternUnits="userSpaceOnUse"
              >
                <circle cx="1" cy="1" r=".8" fill="#bfd5ee" />
              </pattern>
            </defs>
            <rect width="360" height="166" fill="url(#auth-grid)" />
            <g fill="none" stroke="#86aadb" strokeWidth="1.3">
              <path d="M91 49h26a13 13 0 0 1 13 13v6a13 13 0 0 0 13 13h8 M91 115h26a13 13 0 0 0 13-13v-8a13 13 0 0 1 13-13h8 M233 81h18a13 13 0 0 0 13-13v-4a13 13 0 0 1 13-13h8 M233 81h18a13 13 0 0 1 13 13v4a13 13 0 0 0 13 13h8" />
            </g>
            <g stroke="#99bde8" fill="#eaf4ff">
              <rect x="9" y="34" width="82" height="30" rx="4" />
              <rect x="9" y="100" width="82" height="30" rx="4" />
              <rect
                x="151"
                y="65"
                width="82"
                height="32"
                rx="4"
                fill="#dbeaff"
              />
              <rect x="285" y="36" width="67" height="30" rx="4" />
              <rect x="285" y="96" width="67" height="30" rx="4" />
            </g>
            <g
              fill="#23456f"
              fontSize="10"
              fontFamily="monospace"
              textAnchor="middle"
            >
              <text x="50" y="53">
                SOURCE
              </text>
              <text x="50" y="119">
                SOURCE
              </text>
              <text x="192" y="85">
                DEFINITION
              </text>
              <text x="318" y="55">
                METRIC
              </text>
              <text x="318" y="115">
                REPORT
              </text>
            </g>
          </svg>
        </div>
      </aside>
      <main className="auth-main">{children}</main>
    </div>
  );
}
