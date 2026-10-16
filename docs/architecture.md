# Quantum Crews — architecture and implementation plan

Status: P1 implemented and locally verified, including the approved smart-ai-router/CrewAI live check. See docs/p1-acceptance.md for evidence and limits; docs/p2-plan.md for the next iteration.
Working repository name: quantum-crews. Local-only, no remote or deployment.

## Product direction

Quantum Crews is a product of Quantum Horizon Innovations. Its audience includes people with no knowledge of AI agents or orchestration. Start with the outcome: “What would you like help with?” Offer guided setup and reusable examples alongside manual building. AI assistance is optional; every generated element remains visible and manually editable.

Use plain product language: team member (agent), team (crew), task, workflow (flow), connection (tool), rule (condition), start (trigger), and quality check (validator). Validate these labels with users; they are working vocabulary rather than immutable branding. Technical terms can appear in advanced settings and developer documentation. Keep framework-neutral internal names and schemas unchanged.

The first slice must include a guided path: describe the job, configure team members with plain-language fields, review suggested changes, arrange tasks, run the team, and inspect its work. Keep model aliases, runtime adapter configuration, API credentials and schema details in administrator/developer settings. Explain permissions as specific actions and budgets as understandable limits. Show clear progress, useful errors and the next available action. Do not imply that a run succeeded when it failed or is waiting for approval.

Later templates and onboarding must use concrete jobs rather than requiring users to pick an architecture or execution mode. Advanced builders retain full manual control. Static/dynamic/hybrid remain execution concepts, surfaced only when they help users make a meaningful choice.

## Invariants

Manual creation and editing are foundational for agents, tools, tasks, crews, flows, conditions, triggers, and validators. AI proposes changes to the same framework-neutral definitions. It never writes directly to the registry or grants permissions. No dependencies, generated code, configuration changes, or database migrations in agent-tutorial, crewai-tutorial, or smart-ai-router.

## Users, organizations and tenant isolation

Multi-user, multi-tenant support is foundational, including the first vertical slice. Organization is the tenant boundary. Users and organizations have a many-to-many relationship through OrganizationMembership, with roles and permissions scoped to each membership rather than globally to the user.

Each user receives a Personal organization during account provisioning and an owner membership in it, created atomically and idempotently. Use “Personal” as the initial UI label. Track personalOrganizationId separately from defaultOrganizationId: the personal organization always belongs to its user; the default selects the organization opened on sign-in and may later be another organization with an active membership. Initial default is Personal. The personal organization is private to its owner in the initial implementation; collaboration takes place in shared organizations. This is a working product choice, not a restriction on the underlying many-to-many model. The personal organization cannot be independently deleted while its user account remains active. A default selection must reference an active membership; leaving or losing access to a shared default falls back to Personal.

Core entities: User, Organization (personal/shared, personal owner where applicable), OrganizationMembership (userId, organizationId, role, status), and user organization preferences. Enforce unique user/organization membership and one personal organization per user. Initial roles: owner, admin, member and viewer, mapped to explicit permissions for editing, execution, approvals and membership administration. Roles never override tool permissions or execution budgets. Protect the last owner of a shared organization from removal without ownership transfer.

All tenant-owned records carry organizationId: components and versions, proposals, approvals, policies and grants, connections and secret references, runs and snapshots, events, jobs and artifacts. Composite foreign keys prevent cross-organization references; unique revision keys include organizationId. Author, requesting user and approving user are audit identities, not ownership or isolation boundaries. Registry discovery, AI retrieval context, runtime resolution, caches, artifacts and observability are organization-scoped. No implicit sharing between a user's organizations, including Personal. Explicit cross-organization sharing/import is a later feature requiring its own authorization model.

Every request uses an authenticated user and an explicit active organization whose membership and required permission are checked server-side. Never trust an organization ID merely because the client supplied it. Use PostgreSQL row-level security as defense in depth with a non-owner application role and transaction-local tenant context; background workers establish tenant scope from validated leased jobs. Worker credentials, snapshots and callback authorization bind organization, run and attempt. No tenant-secret access from the browser. Recheck authority at approval and execution boundaries; revoked access cannot authorize future actions.

UI includes an organization switcher with Personal available immediately and a clear active organization indicator. Switching organizations clears tenant-specific client state and loads only the selected organization's library and runs. Shared organization creation, invitations, role changes and membership removal are explicit user/admin operations. AI cannot create memberships, change roles, change organization ownership or grant itself cross-tenant access.

## Architecture

Next.js/React + @xyflow/react -> Kotlin/Spring Boot API -> PostgreSQL canonical registry and durable run queue -> isolated Python CrewAI adapter -> smart-ai-router.

The backend owns validation, authorization, proposal approval, version creation, dependency resolution, execution policy and durable run state. The UI owns forms, canvas layout, visual diffs and user intent; canvas positions are presentation metadata rather than execution semantics. The Python adapter translates immutable execution snapshots into CrewAI agents/tasks/crews and emits framework-neutral events. It cannot alter the registry. Secrets stay server-side and are referenced by identifier only.

Use smart-orchestrator for AI authoring, planning and delegation; smart-worker for worker agents. Model gateway aliases are not capability guarantees. Validate the installed CrewAI version and actual gateway endpoint behavior before declaring compatibility. No direct provider fallback. Disable framework telemetry by default.

Start with REST plus polling for runs. PostgreSQL leased jobs avoid an extra broker; retries require attempt IDs and fencing tokens. Do not automatically retry uncertain external side effects. A runtime adapter contract is sufficient now; a plugin marketplace is unnecessary.

## Domain model

Every definition has organizationId, id, kind, schemaVersion, revision, name, description, tags, capability labels, spec, author, createdAt and contentHash. References pin id and revision. Semantic validation checks references, cycles, supported operations and authority in addition to JSON Schema validation.

| Kind | Core definition |
|---|---|
| Agent | Role, goal, instructions, capability labels, model role, tool references, delegation configuration |
| Tool | Input/output schemas, registered implementation reference, required permission scopes, side-effect class |
| Task | Instructions, input/output contracts, assigned agent, dependencies, validator references |
| Crew | Agent/task references, sequential or delegated strategy, execution mode, policy reference |
| Flow | Typed nodes, edges, condition references, inputs/outputs, bounded iteration |
| Condition | Declarative expression over typed inputs; no arbitrary code evaluation |
| Trigger | Manual, schedule or event configuration; target reference; enabled state |
| Validator | Output schema or registered check reference; failure behavior |

Additional entities: Policy, PermissionGrant, Proposal, Approval, Run, RunSnapshot, RunEvent and Artifact. Policies and grants have separate authorization paths; definition editing cannot mutate them.

PostgreSQL tables: users, organizations, organization_memberships, user_preferences, components (identity and current revision), component_versions (append-only JSONB), proposals (base revision, patch, rationale, status), approvals (actor, bound content hash and scope), policies/grants, runs, run_snapshots, run_events, run_jobs, artifacts. Migrations use Flyway. Use unique component/revision keys and transactional optimistic locking. Rollback appends a revision reproducing an older definition; history never changes.

## AI proposal lifecycle

Select existing components by tags/capabilities and pinned revision before proposing new ones. Authoring produces a constrained create proposal or RFC 6902 JSON Patch with baseRevision. Allow only definition fields; reject permission, identity, secret and execution-authority changes. Validate paths and resulting document. Display changed fields and graph changes. User can edit the proposal, reject it or approve it. Approval binds the exact resulting content hash. Approval and new revision commit in one transaction; a stale base revision returns conflict and requires renewed review. Applying a patch never executes it.

## Execution and security

Static: resolve an approved crew and all dependencies into an immutable snapshot.
Dynamic: planner selects only eligible registry revisions; new assembly lives within the run, with explicit promotion to the library. Any new generated definition remains subject to proposal review.
Hybrid: a fixed flow contains bounded dynamic assembly steps.

Snapshot includes resolved definitions, policy revision, approval evidence, inputs, model aliases, adapter version and dependency-lock identity. Subsequent edits cannot affect a run. Reproduction is best effort because model responses and external data can change.

Effective authority is the intersection of active organization membership permissions, user grants, organization policy, component requirements and run policy. Enforce tool authorization at each invocation through a policy boundary, never through prompts alone. AI cannot approve its own actions. Human approvals bind action, inputs and expiry. Resume revalidates policy. Enforce bounded model calls, tokens, wall time, concurrency and tool invocations. Dollar caps require trustworthy cost reservations before calls; otherwise fail closed for monetary guarantees rather than presenting an estimate as a hard cap. A runtime timeout terminates the worker process and its descendants.

First slice is localhost-only and tool-free, delegation disabled, with bounded calls and output sizes. Reject unsupported execution features explicitly. Authentication, organization membership and tenant isolation are part of the first slice, even when running locally. Sandboxed side-effect tools and deployment hardening are required before enabling external actions or exposing the service beyond the local development environment.

Runs: queued -> running -> succeeded/failed/cancelled; later waiting_for_approval. Append sequenced task/model/tool/approval/error events with correlation IDs, redacted inputs, token usage and costs when available. Never store hidden reasoning. Run detail shows snapshot revisions, task outputs, errors and usage; distinguish measured and estimated cost.

## Phased MVP and acceptance

1. Vertical slice: authenticated users, automatic Personal organization provisioning, many-to-many organization memberships, organization switching and enforced tenant isolation; manual agent form; PostgreSQL create/read/update and history/rollback; AI agent creation proposal using smart-orchestrator; field diff and editable approval; XYFlow crew assembly with manual tasks; sequential tool-free CrewAI execution through smart-worker; immutable snapshot and inspectable run. Include granular agent edit proposals and stale revision rejection. Manual authoring must remain usable when AI is unavailable.
2. Complete authoring: manual and AI edit parity for tools, conditions, validators, triggers and flows; typed graph validation; dependency discovery/reuse; registered safe tools and policy enforcement; durable human approval/resume.
3. Dynamic/hybrid: bounded registry selection, ephemeral assemblies, inspect/save promotion, policy and budget enforcement across planner and workers.
4. Operational readiness: production identity integration and tenant isolation hardening, distributed leases/cancellation, sandboxed adapters, richer observability and another adapter proving neutral schemas.

First-slice demonstration: create Analyst manually, save v1, edit to v2, inspect history; request Reviewer with AI, inspect/edit proposed fields, approve; place Analyst and Reviewer on canvas, assign two ordered tasks, save crew, execute, inspect per-task outputs and pinned revisions; edit agent while run is pending and verify snapshot unchanged; rollback agent and verify a new revision. Failures must remain visible rather than fabricated successes.

Tests: two users with separate Personal organizations and a shared organization; unique/idempotent provisioning; role restrictions; revoked membership/default fallback; cross-tenant read/write/reference denial including jobs, snapshots, AI discovery and artifacts; real PostgreSQL row-level security under the application role; real PostgreSQL persistence/revision/rollback and concurrent edits; proposal tampering/stale base/no self-grants; adapter translation and failure propagation with deterministic gateway doubles; browser journey through forms/diff/canvas/run; separate bounded live gateway smoke test only after approval for external model calls. Mocked gateway success never establishes live compatibility.

## Environment observed on October 15, 2026

macOS ARM64; Node 22.17.0, npm 10.9.2 (initial inspection; the implementation shell later selected Node 25.9.0 and npm 11.12.1), Java 25.0.2, Gradle 9.5.0, uv 0.11.9; Docker daemon 29.2.1 responds. Python 3.12.13 and 3.13.12 are installed via uv; default Python is 3.14.4. Use an isolated Python 3.12 environment because CrewAI documentation requires >=3.10,<3.14. PostgreSQL client is installed. Existing containers occupy ports 5433, 4552, 4553 and 4554. Allocate a new database/volume and check free ports before starting services.

The router checkout is clean. The two tutorial directories do not contain Git repositories at those paths. Read existing code only, without executing tutorials or reading their secret files. Existing CrewAI examples configure custom_openai=True and api=responses with gateway base URL/key from the environment; this is an integration candidate, not live compatibility evidence.

## Sources and remaining decisions

https://docs.crewai.com/en/installation
https://docs.crewai.com/en/concepts/llms
https://reactflow.dev/learn
https://docs.spring.io/spring-boot/system-requirements.html

Pin application dependency versions during scaffolding after checking compatibility. The working project name is local and has not been checked for trademark/domain availability. Confirm before creating a remote repository, publishing, deploying, transferring sensitive data, enabling consequential tools or making paid live model requests. Local scaffolding, installation and deterministic testing are within the requested scope.
