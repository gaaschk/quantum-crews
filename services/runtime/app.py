"""Private execution adapter; registry and authorization remain in the backend."""
import hmac
import hashlib
import json
import os
import signal
import subprocess
import sys
import threading
import time
from importlib.metadata import version
from pathlib import Path
from typing import Literal
from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel, ConfigDict, Field

app = FastAPI(title="Quantum Crews runtime", docs_url=None, redoc_url=None, openapi_url=None)

class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")

active = {}
cancelled = {}
active_lock = threading.Lock()

class Cancel(StrictModel):
    runId: str
    attemptId: str

class AgentDefinition(StrictModel):
    id: str
    revision: int
    name: str = Field(min_length=1, max_length=200)
    role: str = Field(min_length=1, max_length=2000)
    instructions: str = Field(min_length=1, max_length=20000)

class TaskDefinition(StrictModel):
    id: str
    revision: int
    name: str = Field(min_length=1, max_length=200)
    instructions: str = Field(min_length=1, max_length=20000)
    agentId: str

class Snapshot(StrictModel):
    agents: list[AgentDefinition] = Field(min_length=1, max_length=10)
    tasks: list[TaskDefinition] = Field(min_length=1, max_length=20)
    input: str = Field(max_length=100000)

class Limits(StrictModel):
    maxCalls: int = Field(ge=1, le=100)
    maxTokens: int = Field(ge=1, le=1000000)
    timeoutSeconds: int = Field(ge=1, le=300)

class ModelConnection(StrictModel):
    connectionId: str
    provider: Literal["smart-router", "openai", "anthropic"]
    keyRevision: int = Field(ge=1)
    plannerModel: str = Field(min_length=1, max_length=120)
    workerModel: str = Field(min_length=1, max_length=120)
    apiKey: str = Field(min_length=8, max_length=4096, repr=False)

class Execution(StrictModel):
    runId: str
    attemptId: str
    snapshot: Snapshot
    limits: Limits
    mode: Literal["test", "live"]
    connection: ModelConnection | None = None

class Proposal(StrictModel):
    prompt: str = Field(min_length=1, max_length=20000)
    base: dict | None = None
    mode: Literal["test", "live"]
    connection: ModelConnection | None = None


def authorize(authorization: str | None = Header(default=None)):
    token = os.getenv("QUANTUM_RUNTIME_TOKEN", "")
    if not token or not hmac.compare_digest(authorization or "", "Bearer " + token):
        raise HTTPException(401, "Runtime authentication required")


def invoke(kind: str, payload: dict, timeout: int):
    if payload["mode"] == "test" and os.getenv("QUANTUM_TEST_MODE") != "true":
        raise HTTPException(403, "Simulation is disabled")
    if payload["mode"] == "live" and os.getenv("QUANTUM_ALLOW_LIVE") != "true":
        raise HTTPException(403, "Live model calls are disabled")
    if payload["mode"] == "live" and not payload.get("connection"):
        raise HTTPException(403, "A user-owned model connection is required")
    key = (payload.get("runId"), payload.get("attemptId")) if kind == "execute" else None
    process = subprocess.Popen([sys.executable, str(Path(__file__).with_name("worker.py")), kind],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, start_new_session=True,
        env={key: value for key, value in os.environ.items() if not key.endswith("API_KEY") and key != "QUANTUM_RUNTIME_TOKEN"})
    with active_lock:
        now = time.monotonic()
        for old_key in list(cancelled):
            if cancelled[old_key] < now:
                cancelled.pop(old_key, None)
        if key in cancelled:
            process.kill()
            process.wait()
            raise HTTPException(409, "Attempt was cancelled")
        if key in active:
            process.kill()
            process.wait()
            raise HTTPException(409, "Attempt is already executing")
        if key:
            active[key] = process
    try:
        stdout, _ = process.communicate(json.dumps(payload), timeout=timeout)
        result = json.loads(stdout)
        if process.returncode or "error" in result:
            raise HTTPException(502, result.get("error", "Runtime failed"))
        return result
    except subprocess.TimeoutExpired:
        raise HTTPException(504, "Execution time limit exceeded")
    except (ValueError, OSError):
        raise HTTPException(502, "Runtime failed")
    finally:
        # Kill the process group even if its leader exited, so descendants cannot survive cancellation.
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait()
        with active_lock:
            if key and active.get(key) is process:
                active.pop(key, None)

@app.post("/cancel", dependencies=[Depends(authorize)])
def cancel(body: Cancel):
    with active_lock:
        key = (body.runId, body.attemptId)
        cancelled[key] = time.monotonic() + 360
        process = active.get(key)
        if process:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
    return {"cancelled": True}

@app.get("/health")
def health():
    return {"status": "ready", "adapter": "crewai", "version": version("crewai"), "lockSha256": hashlib.sha256(Path(__file__).with_name("uv.lock").read_bytes()).hexdigest(), "executionEnabled": bool(os.getenv("QUANTUM_RUNTIME_TOKEN"))}

@app.post("/execute", dependencies=[Depends(authorize)])
def execute(body: Execution):
    ids = [agent.id for agent in body.snapshot.agents]
    task_ids = [task.id for task in body.snapshot.tasks]
    if len(set(ids)) != len(ids) or len(set(task_ids)) != len(task_ids) or any(task.agentId not in ids for task in body.snapshot.tasks):
        raise HTTPException(422, "Invalid snapshot references")
    return invoke("execute", body.model_dump(), body.limits.timeoutSeconds)

@app.post("/propose", dependencies=[Depends(authorize)])
def propose(body: Proposal):
    return invoke("propose", body.model_dump(), 60)
