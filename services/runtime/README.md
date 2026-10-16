# Private BYOK runtime

The backend calls `/execute`, `/propose`, and `/cancel` with `Authorization: Bearer <QUANTUM_RUNTIME_TOKEN>`. An unset token denies all requests. `/health` remains public. `QUANTUM_TEST_MODE=true` permits explicitly labeled simulated responses; it never implies live execution. Live calls require `QUANTUM_ALLOW_LIVE=true` and a transient backend-authorized connection carrying the user-owned key, provider, and explicit planner/worker models. A missing connection fails before a worker starts. No environment model key funds calls; the child process strips environment API keys and the runtime service token. Router uses the operator HTTPS base URL; OpenAI and Anthropic endpoints are fixed.

Each attempt runs in a fresh process group. Timeout or cancellation kills the group including descendants. Cancellation tombstones prevent late dispatch for six minutes. Backend owns durable idempotency, run status, organization authorization, and recovery after process/service loss.

Live CrewAI uses a custom BaseLLM and a shared call/token budget, no provider retries, tools, delegation, memory, planning, or cache. Every gateway request reserves a conservative UTF-8 input bound plus framing allowance and caps output; unknown usage closes the budget and fails the run. Gateway usage is measured; dollar cost is unknown (`null`). Prompts exceeding the reservation fail before making a model call. A provider violating its token cap may consume tokens before the adapter detects and rejects the result.

`/propose` is limited to one selected planner call (smart-orchestrator for router connections), 16,000 tokens and 60 seconds. It returns only name/role/instructions plus the simulation marker. The backend must validate and present the proposal for approval; the adapter does not persist or grant permissions.

Run `.venv/bin/python -m unittest -v`. Tests exercise authentication, fail-closed modes and limits, deterministic simulation, and the installed CrewAI engine with a mocked gateway. The P1 approved router smoke check remains historical evidence only. Current BYOK/direct-provider tests use doubles; no new live model calls were made.
