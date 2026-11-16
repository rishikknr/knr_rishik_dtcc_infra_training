import type { ReactNode } from "react";
import { Feedback } from "../../components/Feedback";
import { Icon } from "../../components/Design";
import { usePage } from "./paging";
export type { Page } from "./paging";

type PageModel = ReturnType<typeof usePage<unknown>>;
export function PageNavigation({
  page,
  label,
}: {
  page: PageModel;
  label: string;
}) {
  return (
    <div className="pagination">
      <span role="status">
        {page.loading
          ? `Loading ${label}…`
          : page.error
            ? "Page unavailable"
            : page.items.length === 0
              ? `0 ${label} on this page`
              : `${page.offset + 1}–${page.offset + page.items.length} of ${page.total} ${label}`}
      </span>
      <button
        type="button"
        aria-label={`Previous ${label}`}
        disabled={page.loading || page.offset === 0}
        onClick={page.previous}
      >
        Previous
      </button>
      <button
        type="button"
        aria-label={`Next ${label}`}
        disabled={page.loading || !page.hasMore}
        onClick={page.next}
      >
        Next
      </button>
    </div>
  );
}
export function Pager({
  page,
  label = "records",
  separateNavigation = false,
  filters,
}: {
  page: PageModel;
  label?: string;
  separateNavigation?: boolean;
  filters?: ReactNode;
}) {
  return (
    <div className="page-controls">
      <form
        className="inline-form"
        onSubmit={(event) => {
          event.preventDefault();
          page.search();
        }}
      >
        <label>
          <span className="sr-only">Filter {label}</span>
          <input
            type="search"
            placeholder={`Search ${label}`}
            value={page.query}
            onChange={(event) => page.setQuery(event.target.value)}
            maxLength={255}
          />
        </label>
        {filters}
        <button>Search</button>
        <button
          type="button"
          className="icon-button"
          title={`Refresh ${label}`}
          aria-label={`Refresh ${label}`}
          onClick={page.reload}
        >
          <Icon name="history" size={16} />
        </button>
      </form>
      <Feedback error={page.error} />
      {!separateNavigation && <PageNavigation page={page} label={label} />}
    </div>
  );
}
