#!/usr/bin/env python3
"""Own-process fault injection on the explicitly provisioned disposable browser database.

No existing process is stopped. No schema is reset. Uses synthetic reserved adapters only.
Required: TRACE_TEST_STATE_DIR containing release-path, private-env.json and browser-fixture.json.
"""
import hashlib
import http.cookiejar
import json
import os
from pathlib import Path
import socket
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

if os.environ.get("TRACE_TEST_EXCLUSIVE_WORKERS") != "true":
    raise SystemExit("Stop other task-owned workers using this disposable database, then set TRACE_TEST_EXCLUSIVE_WORKERS=true")
root = Path(__file__).resolve().parents[2]
state = Path(os.environ.get("TRACE_TEST_STATE_DIR", "/tmp/trace-final-hardening"))
private = json.loads((state / "private-env.json").read_text())
fixture = json.loads((state / "browser-fixture.json").read_text())
url = private.get("TRACE_DATABASE_URL", "")
if url != "jdbc:postgresql://localhost:55437/trace_hardening_browser":
    raise SystemExit("Refusing fault injection outside the dedicated disposable browser database")
for port in (18083, 18003):
    with socket.socket() as check:
        check.bind(("127.0.0.1", port))
jar = Path((state / "release-path").read_text().strip())
if not jar.is_absolute() or "/target/" in str(jar):
    raise SystemExit("Use an immutable absolute release path")
java = str(Path(os.environ["JAVA_HOME"]) / "bin/java")
env = {**os.environ, **private}
backend = processor = None
logs = []
base = "http://127.0.0.1:18083"
org = "a1000000-0000-4000-8000-000000000001"
team = "a2000000-0000-4000-8000-000000000001"
result = {"database": "disposable 55437/trace_hardening_browser", "artifactSha256": hashlib.sha256(jar.read_bytes()).hexdigest(), "provider": "CONTROLLED SIMULATION; LIVE UNVERIFIED"}


def start(kind):
    log = (state / f"process-{kind}-{uuid.uuid4().hex[:8]}.log").open("w")
    logs.append(log)
    if kind == "backend":
        args = [java, "-jar", str(jar), f"--spring.datasource.url={url}",
                "--spring.flyway.enabled=false", "--server.address=127.0.0.1", "--server.port=18083",
                "--spring.profiles.active=local-dev", "--trace.security.bcrypt-strength=10",
                "--trace.processor.url=http://127.0.0.1:18003", "--trace.discovery.lease-seconds=10"]
        return subprocess.Popen(args, env=env, stdout=log, stderr=subprocess.STDOUT)
    return subprocess.Popen([str(root / "processor/.venv/bin/uvicorn"), "tests.acceptance_processor:app",
                             "--host", "127.0.0.1", "--port", "18003"], cwd=root / "processor", env=env,
                            stdout=log, stderr=subprocess.STDOUT)


def stop(process):
    if process and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()  # Only the child Popen created by this script.
            process.wait(timeout=5)


def wait_for(operation, expected, seconds=50):
    deadline = time.monotonic() + seconds
    last = None
    while time.monotonic() < deadline:
        try:
            last = operation()
            if expected(last):
                return last
        except (OSError, urllib.error.HTTPError):
            pass
        time.sleep(0.15)
    raise AssertionError(f"Timed out; last safe state: {last}")


def public_health(url):
    with urllib.request.urlopen(url, timeout=2) as response:
        return json.load(response)


def session():
    return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))


def request(client, path, body=None, method="GET", form=False):
    headers = {}
    data = None
    if method != "GET":
        token = request(client, "/auth/csrf")
        headers[token["headerName"]] = token["token"]
        headers["Content-Type"] = "application/x-www-form-urlencoded" if form else "application/json"
        data = (urllib.parse.urlencode(body) if form else json.dumps(body) if body is not None else "").encode()
    req = urllib.request.Request(base + "/api" + path, data=data, headers=headers, method=method)
    with client.open(req, timeout=5) as response:
        return json.load(response)


def login():
    client = session()
    request(client, "/auth/login", {"email": "owner@hardening.example.test", "password": fixture["password"]}, "POST", True)
    request(client, "/auth/organization", {"organizationId": org}, "POST")
    return client


try:
    processor = start("processor")
    backend = start("backend")
    wait_for(lambda: public_health(base + "/actuator/health/liveness"), lambda h: h["status"] == "UP")
    wait_for(lambda: public_health("http://127.0.0.1:18003/health"), lambda h: h["status"] == "ok")
    client = login()
    config = {"owner": "trace-fixture", "repository": "controlled-metadata", "revision": "slow-restart-" + uuid.uuid4().hex[:8]}
    source = request(client, "/connections", {"name": "Process recovery " + uuid.uuid4().hex[:8], "connectionType": "GITHUB_SQL", "ownerTeamId": team, "configuration": config}, "POST")["id"]
    request(client, f"/connections/{source}/credentials", {"token": "synthetic-reserved-fixture-token"}, "PUT")
    run = request(client, f"/connections/{source}/sync", method="POST")["id"]
    read_run = lambda: request(client, f"/connections/{source}/sync-runs/{run}")
    wait_for(read_run, lambda r: r["status"] == "RUNNING")
    original_pid = backend.pid
    stop(backend)
    backend = start("backend")
    wait_for(lambda: public_health(base + "/actuator/health/liveness"), lambda h: h["status"] == "UP")
    client = login()
    recovered = wait_for(read_run, lambda r: r["status"] in ("COMPLETED", "PARTIALLY_COMPLETED"))
    attempts = request(client, f"/browse/sync-attempts?parent={run}")["items"]
    assert recovered["id"] == run and recovered["attempt_count"] >= 2
    assert any(a["status"] == "INTERRUPTED" for a in attempts)
    result["backendRestart"] = {"runId": run, "originalPid": original_pid, "replacementPid": backend.pid,
                                "status": recovered["status"], "attempts": [a["status"] for a in attempts]}
    stop(processor)
    config["revision"] = "stable-restored"
    request(client, f"/connections/{source}", {"name": "Process recovery restored", "ownerTeamId": team, "connectionType": "GITHUB_SQL", "configuration": config}, "PATCH")
    retry = request(client, f"/connections/{source}/sync", method="POST")["id"]
    failed = wait_for(lambda: request(client, f"/connections/{source}/sync-runs/{retry}"), lambda r: r["status"] == "RETRY_WAIT")
    assert failed["error_code"] == "PROCESSOR_UNAVAILABLE"
    processor = start("processor")
    restored = wait_for(lambda: request(client, f"/connections/{source}/sync-runs/{retry}"), lambda r: r["status"] in ("COMPLETED", "PARTIALLY_COMPLETED"))
    assert restored["id"] == retry and restored["attempt_count"] == 2
    assert request(client, f"/browse/assets?source={source}")["total"] == 0
    result["processorRestart"] = {"runId": retry, "interruptedStatus": failed["status"], "errorCode": failed["error_code"], "restoredStatus": restored["status"], "attemptCount": restored["attempt_count"], "canonicalAssetsBeforeExplicitCommit": 0}
    result["result"] = "PASS"
    target = root / "docs/final-hardening/evidence/process-recovery.json"
    target.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))
finally:
    stop(backend)
    stop(processor)
    for log in logs:
        log.close()
