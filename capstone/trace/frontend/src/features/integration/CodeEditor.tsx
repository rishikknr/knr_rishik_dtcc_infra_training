import { useEffect, useRef, useState } from "react";
import { EditorView, basicSetup } from "codemirror";
import {
  Annotation,
  Compartment,
  EditorState,
  Transaction,
} from "@codemirror/state";
import {
  autocompletion,
  startCompletion,
  type CompletionContext,
} from "@codemirror/autocomplete";
import {
  sql,
  PostgreSQL,
  MySQL,
  MSSQL,
  SQLite,
  type SQLNamespace,
} from "@codemirror/lang-sql";
import { loadOptions } from "./paging";
import type { Asset, AssetColumn } from "../../types/api";
import {
  sqlFunctions,
  resolveEditorReference,
  type EditorDataset,
} from "./editorMetadata";
import { Feedback } from "../../components/Feedback";

const syncedValue = Annotation.define<boolean>();
const noAssets: Asset[] = [];
export function CodeEditor({
  value,
  language,
  readOnly = false,
  onChange,
  assets = noAssets,
  dialect = "snowflake",
  label = "Expression",
}: {
  value: string;
  language: string;
  readOnly?: boolean;
  onChange: (value: string) => void;
  assets?: Asset[];
  dialect?: string;
  label?: string;
}) {
  const host = useRef<HTMLDivElement>(null),
    editor = useRef<EditorView | null>(null),
    change = useRef(onChange);
  const settings = useRef(new Compartment());
  const [datasets, setDatasets] = useState<EditorDataset[]>([]),
    [query, setQuery] = useState(""),
    [token, setToken] = useState("");
  const [error, setError] = useState<unknown>(null);
  const assetKey = assets.map((a) => a.id).join(",");
  // Fetch fields only for referenced datasets or the selected browser item, never the entire warehouse.
  const [browseId, setBrowseId] = useState("");
  const mentioned = assets
    .filter(
      (a) =>
        ["DATASET", "TABLE", "VIEW"].includes(a.asset_type) &&
        (a.id === browseId ||
          value.toLowerCase().includes(a.external_key.toLowerCase()) ||
          new RegExp(
            `\\b${a.external_key
              .split(".")
              .at(-1)
              ?.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}\\b`,
            "i",
          ).test(value)),
    )
    .slice(0, 30);
  const fieldKey = mentioned
    .map((a) => a.id)
    .sort()
    .join(",");
  useEffect(() => {
    const controller = new AbortController();
    void Promise.all(
      mentioned.map(async (asset) => ({
        asset,
        columns: await loadOptions<AssetColumn>("columns", {
          parent: asset.id,
        }),
      })),
    )
      .then((rows) => {
        if (!controller.signal.aborted) {
          setDatasets(rows);
          setError(null);
        }
      })
      .catch((e) => {
        if (!controller.signal.aborted) setError(e);
      });
    return () => controller.abort();
    // fieldKey captures the exact selected identities.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fieldKey, assetKey]);
  useEffect(() => {
    change.current = onChange;
  }, [onChange]);
  useEffect(() => {
    if (!host.current) return;
    const view = new EditorView({
      parent: host.current,
      state: EditorState.create({
        extensions: [
          basicSetup,
          settings.current.of([]),
          EditorView.lineWrapping,
          EditorView.contentAttributes.of({
            "aria-label": label,
            "aria-multiline": "true",
          }),
          EditorState.transactionFilter.of((tr) =>
            !tr.annotation(syncedValue) && tr.newDoc.length > 100000 ? [] : tr,
          ),
          EditorView.updateListener.of((update) => {
            if (
              update.docChanged &&
              !update.transactions.some((tr) => tr.annotation(syncedValue))
            )
              change.current(update.state.doc.toString());
            if (update.selectionSet || update.docChanged) {
              const doc = update.state.doc.toString(),
                pos = update.state.selection.main.head;
              const before = doc.slice(0, pos).match(/[\w.$"]+$/)?.[0] || "";
              const after = doc.slice(pos).match(/^[\w.$"]+/)?.[0] || "";
              setToken(before + after);
            }
          }),
          EditorView.domEventHandlers({
            click: () => {
              if (!view.state.readOnly) startCompletion(view);
            },
          }),
        ],
      }),
    });
    editor.current = view;
    return () => {
      view.destroy();
      editor.current = null;
    };
  }, [label]);
  useEffect(() => {
    const schema: Record<string, SQLNamespace> = {};
    for (const asset of assets.filter((a) =>
      ["DATASET", "VIEW", "TABLE"].includes(a.asset_type),
    )) {
      const parts = asset.external_key.split(".");
      let branch = schema;
      for (const part of parts.slice(0, -1)) {
        if (!branch[part]) branch[part] = {};
        branch = branch[part] as Record<string, SQLNamespace>;
      }
      branch[parts.at(-1)!] =
        datasets
          .find((d) => d.asset.id === asset.id)
          ?.columns.map((c) => ({
            label: c.name,
            type: "property",
            detail: c.data_type,
            info: c.description || `${asset.external_key} · discovered column`,
          })) || [];
    }
    const functionSource = (context: CompletionContext) => {
      const word = context.matchBefore(/\w*/);
      if (
        !word ||
        (!word.text && !context.explicit) ||
        context.state.sliceDoc(Math.max(0, word.from - 1), word.from) === "."
      )
        return null;
      return {
        from: word.from,
        options: sqlFunctions.map((f) => ({
          ...f,
          type: "function",
          apply: `${f.label}()`,
        })),
        validFor: /\w*/,
      };
    };
    editor.current?.dispatch({
      effects: settings.current.reconfigure([
        ...(language === "SQL"
          ? [
              sql({
                schema,
                upperCaseKeywords: true,
                dialect: (
                  {
                    postgres: PostgreSQL,
                    mysql: MySQL,
                    tsql: MSSQL,
                    sqlite: SQLite,
                  } as Record<string, typeof PostgreSQL>
                )[dialect],
              }),
              EditorState.languageData.of(() => [
                { autocomplete: functionSource },
              ]),
            ]
          : []),
        autocompletion({ activateOnTyping: true }),
        EditorState.readOnly.of(readOnly),
        EditorView.editable.of(!readOnly),
      ]),
    });
  }, [language, readOnly, assets, datasets, dialect]);
  useEffect(() => {
    const view = editor.current;
    if (view && view.state.doc.toString() !== value)
      view.dispatch({
        changes: { from: 0, to: view.state.doc.length, insert: value },
        annotations: [syncedValue.of(true), Transaction.addToHistory.of(false)],
      });
  }, [value]);
  const resolved = resolveEditorReference(
    token.replaceAll('"', ""),
    value,
    datasets,
  );
  function insert(text: string) {
    const view = editor.current;
    if (!view || readOnly) return;
    const { from, to } = view.state.selection.main;
    view.dispatch({
      changes: { from, to, insert: text },
      selection: { anchor: from + text.length },
    });
    view.focus();
  }
  const filtered = assets.filter(
    (a) =>
      ["DATASET", "TABLE", "VIEW"].includes(a.asset_type) &&
      `${a.name} ${a.external_key}`.toLowerCase().includes(query.toLowerCase()),
  );
  return (
    <div>
      <span className="field-label">{label}</span>
      <div className="code-editor">
        {resolved && (
          <div className="editor-reference">
            <span>
              {resolved.column ? "Column reference" : "Dataset reference"}
            </span>
            <a href={`#asset/${resolved.asset.id}`}>
              {resolved.asset.external_key}
              {resolved.column ? `.${resolved.column.name}` : ""}
            </a>
            {resolved.column && <span>{resolved.column.data_type}</span>}
          </div>
        )}
        <div ref={host} />
      </div>
      <small>
        {readOnly
          ? "Select a reference to inspect its registered metadata. This is a stored definition."
          : language === "SQL"
            ? "Type to see tables, fields and functions. Click a reference to inspect it. Ctrl+Space opens suggestions; ↑ ↓ and Enter select. SQL is analyzed as metadata."
            : "Stored expression text. Validation does not execute this calculation."}
      </small>
      <Feedback error={error} />
      {!readOnly && assets.length > 0 && (
        <details className="reference-browser">
          <summary>Browse datasets and insert fields</summary>
          <input
            aria-label="Search editor datasets"
            type="search"
            placeholder="Find a dataset by name or schema…"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
          <div className="reference-results">
            {filtered.slice(0, 30).map((a) => (
              <div key={a.id}>
                <button
                  type="button"
                  className="reference-result"
                  onClick={() => setBrowseId(a.id)}
                  aria-expanded={browseId === a.id}
                >
                  {a.external_key}
                  <small>{a.column_count} fields</small>
                </button>
                {browseId === a.id && (
                  <>
                    <button
                      type="button"
                      className="reference-result"
                      onClick={() => insert(a.external_key)}
                    >
                      Insert dataset reference →
                    </button>
                    {datasets
                      .find((d) => d.asset.id === a.id)
                      ?.columns.map((c) => (
                        <button
                          type="button"
                          className="reference-result"
                          key={c.id}
                          onClick={() => insert(`${a.external_key}.${c.name}`)}
                        >
                          {c.name}
                          <small>{c.data_type} · insert</small>
                        </button>
                      ))}
                  </>
                )}
              </div>
            ))}
          </div>
          <small>
            Only registered metadata is offered. References still need
            validation and explicit dependency review.
          </small>
        </details>
      )}
    </div>
  );
}
