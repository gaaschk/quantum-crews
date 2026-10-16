"""Start the local foundation; Ctrl+C stops app processes and preserves the database."""
import os
import secrets
from pathlib import Path
import signal
import socket
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
processes = []

def stop(*_):
    raise KeyboardInterrupt

def wait_ready(url, process):
    for _ in range(600):
        if process.poll() is not None:
            raise RuntimeError(f"Service exited before becoming ready: {url}")
        try:
            with urllib.request.urlopen(url, timeout=2) as response:
                if response.status == 200:
                    return
        except (OSError, TimeoutError):
            pass
        time.sleep(1)
    raise RuntimeError(f"Timed out waiting for {url}; inspect work/logs")

def main():
    for port in (3100, 8100, 8200):
        with socket.socket() as sock:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind(("127.0.0.1", port))
    subprocess.run(["docker", "compose", "up", "-d", "--wait"], cwd=ROOT, check=True)
    subprocess.run(["./gradlew", "bootJar", "--no-daemon"], cwd=ROOT / "services/api", check=True)
    logs = ROOT / "work/logs"
    logs.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ, QUANTUM_RUNTIME_TOKEN=os.environ.get("QUANTUM_RUNTIME_TOKEN") or secrets.token_urlsafe(32), QUANTUM_TEST_MODE=os.environ.get("QUANTUM_TEST_MODE", "true"), NEXT_TELEMETRY_DISABLED="1", OTEL_SDK_DISABLED="true", CREWAI_TELEMETRY_DISABLED="true")
    services = [
        ("runtime", ["uv", "run", "--frozen", "uvicorn", "app:app", "--host", "127.0.0.1", "--port", "8200"], ROOT / "services/runtime", "http://127.0.0.1:8200/health"),
        ("api", ["java", "-jar", "build/libs/quantum-crews-api.jar"], ROOT / "services/api", "http://127.0.0.1:8100/api/foundation"),
        ("web", ["npm", "run", "dev", "--workspace", "apps/web"], ROOT, "http://127.0.0.1:3100"),
    ]
    for name, command, cwd, url in services:
        with (logs / f"{name}.log").open("w") as log:
            process = subprocess.Popen(command, cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        processes.append(process)
        wait_ready(url, process)
        print(f"{name} ready", flush=True)
    print("Quantum Crews: http://127.0.0.1:3100 — Ctrl+C to stop. Logs: work/logs", flush=True)
    while True:
        if any(p.poll() is not None for p in processes):
            raise RuntimeError("A service exited; inspect work/logs")
        time.sleep(1)

if __name__ == "__main__":
    signal.signal(signal.SIGTERM, stop)
    try:
        main()
    except KeyboardInterrupt:
        pass
    finally:
        signal.signal(signal.SIGINT, signal.SIG_IGN)
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        for process in reversed(processes):
            if process.poll() is None:
                try:
                    os.killpg(process.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
        for process in reversed(processes):
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
