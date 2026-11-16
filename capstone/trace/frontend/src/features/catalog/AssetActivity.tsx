import { EmptyState, Timestamp } from "../../components/Design";
import { humanize } from "../../components/format";
import { usePage } from "../integration/paging";
import { Pager } from "../integration/pagination";
import { JsonDetails } from "../integration/shared";

type Event = {
  id: string;
  event_type: string;
  created_at: string;
  previous_value?: unknown;
  new_value?: unknown;
};
export function AssetActivity({
  id,
  revision = 0,
  sourceHistory = false,
}: {
  id: string;
  revision?: number;
  sourceHistory?: boolean;
}) {
  const page = usePage<Event>(
    sourceHistory ? "metadata-history" : "asset-history",
    revision,
    { parent: id },
  );
  return (
    <section className="panel">
      <h2>
        {sourceHistory
          ? "Source and column change history"
          : "Curated ownership history"}
      </h2>
      <Pager
        page={page}
        label={sourceHistory ? "metadata changes" : "ownership changes"}
      />
      {!page.loading && !page.error && !page.items.length && (
        <EmptyState
          icon="history"
          title={
            sourceHistory
              ? "No recorded metadata changes"
              : "No curation activity"
          }
          description={
            sourceHistory
              ? "Changes observed after the hardening release appear here with their import and actor. Earlier states are not reconstructed."
              : "Ownership, description, and tag changes appear here after they are saved."
          }
        />
      )}
      {page.items.map((event) => (
        <details key={event.id}>
          <summary>
            {humanize(event.event_type)} ·{" "}
            <Timestamp value={event.created_at} />
          </summary>
          <JsonDetails value={event} />
        </details>
      ))}
    </section>
  );
}
