#!/usr/bin/env python3
"""Task-only isolated service launcher. Never targets the persistent development database."""
import json
import os
from pathlib import Path
import shutil
import socket
import sys

root = Path(__file__).resolve().parents[2]
state = Path(os.environ.get("TRACE_TEST_STATE_DIR", "/tmp/trace-final-hardening"))
env = {**os.environ, **json.loads((state / "private-env.json").read_text())}
if env.get("TRACE_DATABASE_URL") != "jdbc:postgresql://localhost:55437/trace_hardening_browser":
    raise SystemExit("Refusing any other database")
kind = sys.argv[1] if len(sys.argv) == 2 else ""
if kind not in {"backend", "processor", "migrate"}:
    raise SystemExit("Usage: start-hardening-service.py backend|processor|migrate")
if kind != "migrate":
    port = 18082 if kind == "backend" else 18002
    with socket.socket() as check:
        check.bind(("127.0.0.1", port))
if kind == "processor":
    os.chdir(root / "processor")
    executable = str(root / "processor/.venv/bin/uvicorn")
    argv = [executable, "tests.acceptance_processor:app", "--host", "127.0.0.1", "--port", "18002"]
else:
    executable = str(Path(env["JAVA_HOME"]) / "bin/java") if env.get("JAVA_HOME") else shutil.which("java")
    jar = Path((state / "release-path").read_text().strip())
    if not jar.is_absolute() or "/target/" in str(jar):
        raise SystemExit("Use an immutable absolute release JAR")
    argv = [executable, "-jar", str(jar)]
    if kind == "migrate":
        argv += ["--migrate-only"]
    else:
        argv += ["--spring.datasource.url=" + env["TRACE_DATABASE_URL"], "--spring.flyway.enabled=false",
                 "--server.address=127.0.0.1", "--server.port=18082", "--spring.profiles.active=local-dev",
                 "--trace.security.local-token-delivery=true", "--trace.security.bcrypt-strength=10",
                 "--trace.security.rate-limit-attempts=1000", "--trace.processor.url=http://127.0.0.1:18002",
                 "--trace.discovery.lease-seconds=10"]
os.execve(executable, argv, env)
