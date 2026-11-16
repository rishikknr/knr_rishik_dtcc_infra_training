import { loadOptions } from "./paging";
import { useEffect, useState, useRef } from "react";
import { requireCurrentBackend } from "../../services/compatibility";
import type { Asset, Connection, Team } from "../../types/api";
export function useWorkspace(
  revision: number,
  options: { teams?: boolean; connections?: boolean; assets?: boolean },
) {
  const {
    teams: needsTeams,
    connections: needsConnections,
    assets: needsAssets,
  } = options;
  const [data, setData] = useState<{
    teams: Team[];
    connections: Connection[];
    assets: Asset[];
  }>({ teams: [], connections: [], assets: [] });
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);
  useEffect(() => {
    let active = true;
    requireCurrentBackend()
      .then(async () => {
        if (!active) return;
        const [teams, connections, assets] = await Promise.all([
          needsTeams ? loadOptions<Team>("teams") : [],
          needsConnections ? loadOptions<Connection>("connections") : [],
          needsAssets ? loadOptions<Asset>("assets") : [],
        ]);
        if (active) {
          setData({ teams, connections, assets });
          setError(null);
        }
      })
      .catch((e) => {
        if (active) setError(e);
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [revision, needsTeams, needsConnections, needsAssets]);
  return { ...data, error, loading };
}
export function useAction() {
  const pending = useRef(false);
  const [busy, setBusy] = useState(false),
    [error, setError] = useState<unknown>(null),
    [notice, setNotice] = useState(""),
    [revision, setRevision] = useState(0);
  async function run(fn: () => Promise<void>) {
    if (pending.current) return;
    pending.current = true;
    setBusy(true);
    setError(null);
    setNotice("");
    try {
      await fn();
      setRevision((v) => v + 1);
    } catch (e) {
      setError(e);
    } finally {
      pending.current = false;
      setBusy(false);
    }
  }
  return { busy, error, notice, setNotice, revision, run };
}
