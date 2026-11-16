#!/usr/bin/env python3
"""Operator-approved V5 -> V15 repair for the existing loopback development stack.

Run from a terminal. Existing role passwords are read without echo and are passed
only in child environments/stdin, never written to disk or command arguments.
"""

import base64
import datetime
import getpass
import hashlib
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
STATE = ROOT / ".trace-local"
JAR = STATE / "releases/trace-v15.jar"
SHA256 = "56f9abe20cab28499c6ee2163ef9437615e6b6f4764603dd28f20b8966746524"
DB_URL = "jdbc:postgresql://localhost:5433/trace"
COMPOSE = ["docker", "compose", "--project-directory", str(ROOT)]


def run(args, **kwargs):
    return subprocess.run(args, check=True, **kwargs)


def sql(query):
    return subprocess.check_output(
        COMPOSE + ["exec", "-T", "postgres", "sh", "-ec",
                   'exec psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'],
        input=query.encode(),
    )


def listening(port):
    with socket.socket() as connection:
        return connection.connect_ex(("127.0.0.1", port)) == 0


def health(url, process):
    for _ in range(60):
        if process.poll() is not None:
            raise RuntimeError(f"Service exited; inspect logs in {STATE}")
        try:
            with urllib.request.urlopen(url, timeout=2) as response:
                if response.status == 200:
                    return
        except (OSError, TimeoutError):
            pass
        time.sleep(1)
    raise RuntimeError(f"Service did not become healthy: {url}. Inspect {STATE}")


def launch(args, name, env, cwd=ROOT):
    with (STATE / f"{name}.log").open("ab") as log:
        child = subprocess.Popen(args, cwd=cwd, env=env, stdin=subprocess.DEVNULL,
                                 stdout=log, stderr=log, start_new_session=True)
    (STATE / f"{name}.pid").write_text(str(child.pid) + "\n")
    return child


def main():
    if "--help" in sys.argv:
        print(__doc__)
        return
    os.umask(0o077)
    if not sys.stdin.isatty():
        raise RuntimeError("Run this command in your terminal for private password prompts")
    if not JAR.is_file() or hashlib.sha256(JAR.read_bytes()).hexdigest() != SHA256:
        raise RuntimeError(f"Missing or changed verified release: {JAR}")
    published = subprocess.check_output(COMPOSE + ["port", "postgres", "5432"], text=True).strip()
    if published != "127.0.0.1:5433":
        raise RuntimeError("Refusing a database other than the existing loopback 5433/trace")
    versions = sql("BEGIN READ ONLY; SELECT version FROM flyway_schema_history ORDER BY installed_rank; COMMIT;").decode().splitlines()
    if versions != [str(n) for n in range(1, 6)]:
        raise RuntimeError("This repair requires the inspected V1-V5 baseline; no changes made")
    if listening(8000):
        raise RuntimeError("Port 8000 is occupied; inspect its processor before this repair")

    service_env = os.environ.copy()
    for key, label in [("TRACE_MIGRATION_PASSWORD", "Existing trace_migrator password: "),
                       ("TRACE_RUNTIME_PASSWORD", "Existing trace_runtime password: ")]:
        password = service_env.get(key) or getpass.getpass(label)
        if not password or "\n" in password or "\r" in password:
            raise RuntimeError("A nonempty existing role password is required")
        role = "trace_migrator" if "MIGRATION" in key else "trace_runtime"
        # Authenticate before stopping the old service. Read the password over stdin.
        run(COMPOSE + ["exec", "-T", "postgres", "sh", "-ec",
                       'IFS= read -r PGPASSWORD; export PGPASSWORD; exec psql -X -w -h 127.0.0.1 -U "$1" -d trace -qAtc "SELECT 1"',
                       "sh", role], input=(password + "\n").encode(), stdout=subprocess.DEVNULL)
        service_env[key] = password
    if service_env["TRACE_RUNTIME_PASSWORD"] == service_env["TRACE_MIGRATION_PASSWORD"]:
        raise RuntimeError("The existing runtime and migration passwords must differ")

    # Capture exactly the baseline columns; later additive fields cannot mask changes.
    projections = sql("""BEGIN READ ONLY;
SELECT table_name || '|' || string_agg(quote_ident(column_name), ',' ORDER BY ordinal_position)
FROM information_schema.columns WHERE table_schema='public' AND table_name <> 'flyway_schema_history'
AND table_name IN (SELECT tablename FROM pg_tables WHERE schemaname='public') GROUP BY table_name ORDER BY table_name;
COMMIT;""").decode().splitlines()
    fingerprint = "BEGIN READ ONLY; SET LOCAL TIME ZONE 'UTC'; SET LOCAL DateStyle TO 'ISO, YMD'; SET LOCAL bytea_output TO 'hex';\n"
    for projection in projections:
        table, columns = projection.split("|", 1)
        fingerprint += f"SELECT '{table}',count(*),md5(coalesce(string_agg(row_json,'' ORDER BY row_json),'')) FROM (SELECT row_to_json(r)::text row_json FROM (SELECT {columns} FROM \"{table}\") r) rows;\n"
    fingerprint += "COMMIT;"
    duplicates = sql("""BEGIN READ ONLY; SELECT
(SELECT count(*) FROM (SELECT 1 FROM lineage_edges GROUP BY organization_id,source_asset_id,target_asset_id,relationship_type HAVING count(*)>1) d) +
(SELECT count(*) FROM (SELECT 1 FROM column_lineage GROUP BY organization_id,source_column_id,target_column_id HAVING count(*)>1) d); COMMIT;""").strip()
    if duplicates != b"0":
        raise RuntimeError("Duplicate lineage requires separate review; no changes made")

    if listening(8081):
        pid = subprocess.check_output(["lsof", "-t", "-nP", "-iTCP:8081", "-sTCP:LISTEN"], text=True).strip()
        if not pid.isdecimal():
            raise RuntimeError("Could not identify one backend on port 8081")
        command = subprocess.check_output(["ps", "-p", pid, "-o", "command="], text=True)
        cwd = subprocess.check_output(["lsof", "-a", "-p", pid, "-d", "cwd", "-Fn"], text=True)
        if "java -jar backend/target/backend-0.0.1-SNAPSHOT.jar" not in command or f"n{ROOT}\n" not in cwd:
            raise RuntimeError("Port 8081 is not the inspected development backend; no changes made")
        os.kill(int(pid), signal.SIGTERM)
        for _ in range(30):
            if not listening(8081):
                break
            time.sleep(1)
        else:
            raise RuntimeError("Old backend did not stop; no migration attempted")

    if sql("BEGIN READ ONLY; SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND usename='trace_runtime'; COMMIT;").strip() != b"0":
        raise RuntimeError("Other database writers are connected; stop them before retrying")
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    backup = STATE / "backups" / stamp
    backup.mkdir(parents=True)
    (backup / "fingerprint.sql").write_text(fingerprint)
    before = sql(fingerprint)
    (backup / "data-before.tsv").write_bytes(before)
    dump = backup / "trace-v5.dump"
    with dump.open("wb") as stream:
        run(COMPOSE + ["exec", "-T", "postgres", "sh", "-ec",
                       'exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom'], stdout=stream)
    with dump.open("rb") as stream:
        run(COMPOSE + ["exec", "-T", "postgres", "pg_restore", "--exit-on-error", "--file=/dev/null"], stdin=stream)
    (backup / "trace-v5.dump.sha256").write_text(hashlib.sha256(dump.read_bytes()).hexdigest() + "\n")
    print(f"Verified backup: {backup}", flush=True)

    java_home = service_env.get("JAVA_HOME") or subprocess.check_output(["/usr/libexec/java_home", "-v", "21"], text=True).strip()
    java = str(Path(java_home) / "bin/java")
    service_env["TRACE_DATABASE_URL"] = DB_URL
    with (backup / "migration.log").open("wb") as log:
        run([java, "-jar", str(JAR), "--migrate-only"], env=service_env, stdout=log, stderr=log)
    after = sql(fingerprint)
    (backup / "data-after.tsv").write_bytes(after)
    if before != after:
        raise RuntimeError(f"Baseline preservation check failed. Services remain stopped; inspect {backup}")
    if sql("BEGIN READ ONLY; SELECT trace_schema_version(); COMMIT;").strip() != b"15":
        raise RuntimeError("Schema contract is not V15; services remain stopped")
    print("V15 applied; all original table projections preserved.", flush=True)

    # V5 has no stored connector secrets. Persist the newly initialized key privately.
    security_file = STATE / "security.json"
    if security_file.exists():
        security = json.loads(security_file.read_text())
    else:
        security = {"TRACE_PROCESSOR_TOKEN": service_env.get("TRACE_PROCESSOR_TOKEN") or os.urandom(32).hex(),
                    "TRACE_CONNECTOR_ENCRYPTION_KEY": service_env.get("TRACE_CONNECTOR_ENCRYPTION_KEY") or base64.b64encode(os.urandom(32)).decode()}
        security_file.write_text(json.dumps(security))
    service_env.update(security)
    # Python receives only its shared token, never database or connector secrets.
    processor_env = {key: value for key, value in os.environ.items() if not key.startswith("TRACE_")}
    processor_env["TRACE_PROCESSOR_TOKEN"] = security["TRACE_PROCESSOR_TOKEN"]
    processor = launch([str(ROOT / "processor/.venv/bin/uvicorn"), "app:app", "--host", "127.0.0.1", "--port", "8000"],
                       "processor", processor_env, ROOT / "processor")
    health("http://127.0.0.1:8000/health", processor)
    service_env.pop("TRACE_MIGRATION_PASSWORD", None)
    backend = launch([java, "-jar", str(JAR), "--spring.datasource.url=" + DB_URL,
                      "--spring.flyway.enabled=false", "--spring.profiles.active=local-dev",
                      "--server.address=127.0.0.1", "--server.port=8081",
                      "--trace.processor.url=http://127.0.0.1:8000"], "backend", service_env)
    health("http://127.0.0.1:8081/actuator/health", backend)
    if not listening(5173):
        frontend_env = {**processor_env, "TRACE_BACKEND_URL": "http://127.0.0.1:8081"}
        frontend_env.pop("TRACE_PROCESSOR_TOKEN", None)
        frontend = launch(["npm", "run", "dev", "--", "--port", "5173"], "frontend", frontend_env, ROOT / "frontend")
        health("http://127.0.0.1:5173/", frontend)
    with urllib.request.urlopen("http://127.0.0.1:5173/api/auth/registration", timeout=5) as response:
        json.load(response)
    print("Trace is ready at http://127.0.0.1:5173/#overview. Sign in again with your existing account.")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, subprocess.CalledProcessError, KeyboardInterrupt) as error:
        print(f"Stopped: {error}", file=sys.stderr)
        sys.exit(1)
