# Quantum Crews

Build AI teams that help you get work done. A product of Quantum Horizon Innovations.

## P1: one complete journey

Sign in or register, receive a private Personal organization, create a team member manually, review/edit/approve an AI proposal, save ordered tasks and a team, execute through CrewAI, and inspect outputs and immutable definitions. The front page stays focused on describing work; the editor lives at `/workspace`.

Users have many-to-many organization memberships. Shared organization owners can add an existing local account as member or viewer. Personal organizations are private. Organization switching also saves the default organization in P1. The API checks membership and operation permissions; a non-owner PostgreSQL application role and forced row-level security isolate registry, proposal and run data.

Agents, tasks and crews use framework-neutral definitions (see `schemas/component.schema.json`). Versions are append-only, edits use optimistic concurrency, rollback appends a revision, proposals bind a base revision, and approval records the reviewed content hash and resulting revision. Run snapshots resolve all pinned dependencies, inputs, fixed P1 policy, approval evidence, model aliases and runtime version/lock identity.

The Python adapter executes tool-free sequential CrewAI teams. A durable PostgreSQL queue and fenced attempt ID prevent duplicate dispatch; uncertain expired attempts fail rather than automatically replay. Cancellation requests terminate the runtime process group; if termination cannot be confirmed, the run records that uncertainty. Calls, tokens and execution time are bounded. Gateway dollar cost remains unknown when not reported.

P1 is local-only: authentication uses local password accounts with hashed passwords and HttpOnly/SameSite cookies. Production identity, verified-email invitations, public deployment and production secret management remain later work. Existing agent-tutorial, crewai-tutorial and smart-ai-router projects are unchanged.

## Install and start

Prerequisites: compatible Node.js 22.17+ (or newer), npm, Java 25, Docker/Compose, uv and Python 3.12. The Gradle wrapper and dependency locks are checked in; Python is isolated under `services/runtime/.venv`.

```sh
npm ci
uv sync --directory services/runtime --frozen
npm run dev
```

Open http://127.0.0.1:3100 and create an account (password minimum 12 characters). The launcher starts this project's database, builds the backend, generates a private runtime token, and waits for all services. Simulated test mode is enabled for this local launcher; the UI labels it clearly. Simulation uses the installed CrewAI engine with a deterministic LLM and makes no model calls.

| Service | Address |
|---|---|
| Web | http://127.0.0.1:3100 |
| API foundation check | http://127.0.0.1:8100/api/foundation |
| Runtime health | http://127.0.0.1:8200/health |
| PostgreSQL | 127.0.0.1:5440 |

Logs: `work/logs`. Ctrl+C stops application processes and preserves the running database. `docker compose stop` stops the database; `docker compose down` removes its container/network and preserves its volume. Avoid `--volumes` unless deliberately deleting this project's data.

The dedicated Compose volume is `quantum-crews_quantum_crews_postgres`. Database is `quantum_crews`; migrations use local development owner `quantum_crews`/`quantum_crews_local_only`, while application queries use restricted `qc_app`/`qc_app_local_only`. These are local development credentials, not production configuration. All published ports bind to loopback.

## Model gateway

Gateway: https://smart-router.kevingaasch.com. Planning/proposals use `smart-orchestrator`; worker execution uses `smart-worker`. Credentials stay in the startup environment, never the browser or registry. `.env.example` documents settings but startup does not load `.env` files.

Live calls require explicit `QUANTUM_ALLOW_LIVE=true` in both backend and runtime (the launcher passes the environment to both). To offer live mode in the UI, Next.js also requires `NEXT_PUBLIC_ALLOW_LIVE=true` at startup/build time. The default is disabled. Set `SMART_ROUTER_BASE_URL` and `SMART_ROUTER_API_KEY` in the shell before intentionally enabling it. Disabling simulation outside development uses `QUANTUM_TEST_MODE=false`.

The approved October 16, 2026 compatibility check produced one live proposal and a successful two-task CrewAI run through the gateway. The worker run reported two calls and 8,623 tokens; dollar cost was not reported. Live execution was disabled again afterward. This validates the tested path, not every provider, model or execution mode.

## Verify

```sh
python3 scripts/smoke.py
python3 scripts/p1_check.py
npm run typecheck
npm run build
uv run --directory services/runtime --frozen python -m unittest -v
cd services/api
./gradlew test --no-daemon
```

The stack must be running for smoke/API checks; backend tests require this project's PostgreSQL container. `p1_check.py` makes approximately 80 HTTP assertions (the exact count varies with run polling) using unique disposable accounts and organizations. It leaves its local test records for inspection. It checks authentication, separate Personal/shared organizations, permissions, tenant denial, revisions/rollback, concurrent edits, proposal review/conflicts, no self-granted permissions, immutable snapshots, simulated CrewAI execution and cancellation. It makes no live model calls.

Backend tests cover PostgreSQL migration, readiness failure, restricted role privileges, history immutability and missing tenant context. Runtime tests cover authentication, modes, gateway budgets, actual CrewAI translation with a mocked gateway, simulation through CrewAI, timeout cleanup, cancellation and runtime lock identity.

## Roadmap

See [architecture](docs/architecture.md), [P1 acceptance evidence](docs/p1-acceptance.md), and [detailed P2 plan](docs/p2-plan.md). P2 extends this working slice with searchable reuse, recent work, conflict recovery and organization administration/invitations. Tools, workflow types, schedules, dynamic/hybrid execution and additional adapters remain later phases.

## License and deployment

Original Quantum Crews source is [MIT licensed](LICENSE), copyright Quantum Horizon Innovations. Third-party software retains its own licenses; see [NOTICE](NOTICE).

The public GitHub repository and Mac mini pull-based container pipeline are described in [deployment operations](docs/deployment.md). Pull requests use GitHub-hosted CI; the mini deploys only exact main commits that passed CI. Deployment is initially loopback-only, with live model execution disabled.
