# P1 acceptance — October 16, 2026

P1 implemented locally and verified across Next.js, Kotlin/Spring Boot, real PostgreSQL, Python/CrewAI and smart-ai-router. No deployment, remote repository creation, publishing or changes to the three protected projects occurred.

## Verified journeys

- Local account registration/login/logout; atomic Personal organization plus owner membership; separate default/Personal IDs.
- Shared organizations with owner/member/viewer access and organization switching. Cross-tenant definitions, references, proposals and runs denied by API checks and forced PostgreSQL row-level security.
- Manual persisted agent/task/crew creation, editing, history and rollback; concurrent stale edits reject with 409.
- AI creation and granular edits use the same definitions. Proposals expose changed fields, allow human changes, require approval, record content hashes and reject stale/duplicate approvals or permission changes.
- Pinned ordered task/agent versions resolve into immutable snapshots with policy, model aliases, adapter version, dependency-lock fingerprint and approval evidence.
- Durable queued/running/succeeded/failed/cancelled runs; fenced dispatch, process cancellation, truthful unconfirmed-stop errors, and no automatic retry of uncertain attempts.
- Task outputs, readable progress, exact snapshot and reported usage available in the UI.

## Evidence

- Six backend tests pass against real PostgreSQL, including non-superuser application role, append-only history privileges and absence of tenant visibility without context.
- Eleven Python runtime tests pass, including actual installed CrewAI with a mocked gateway, real CrewAI simulation with no gateway calls, limits, authentication, timeout/cancellation cleanup and lock identity.
- The deterministic API journey passes (79–80 HTTP assertions observed; polling affects the count) through real services: two users, Personal and shared organizations, viewer/member restrictions, persistence/versioning, concurrent writes, proposal approvals/conflicts, snapshots and cancellation.
- Web type checks and production build pass.
- Browser journey completed: sign in, manually save Summarizer, generate/edit/approve simulated reviewer proposal, save two assigned tasks, save an ordered team, execute, inspect two completed CrewAI outputs.
- Actual persisted agent/task/crew responses validate against the framework-neutral JSON Schema. Revoked shared membership falls back to Personal.
- Restart check preserved definitions/versions/completed runs. A disposable expired in-flight lease was failed with an uncertain-outcome message and no replay.

## Approved live check

Destination: https://smart-router.kevingaasch.com. Input contained synthetic meeting notes only. User explicitly approved one proposal and one two-task run, at most nine model calls and 28,000 tokens total.

One smart-orchestrator proposal produced “Live accuracy reviewer”; its fields were reviewed and approved under the disposable local test account. CrewAI executed the summary and review tasks through smart-worker. Run ba8e8533-a2b0-413c-b966-2e7b90e6be32 succeeded with simulated=false, two worker calls and 8,623 reported worker tokens. The summary preserved the launch target, owners and pending budget decision; the reviewer checked those details against the notes. Proposal token usage is not included in the worker figure. Dollar cost is unknown.

Live mode was disabled again after the check. The successful call validates this concrete gateway/adapter path; it does not establish universal provider reliability.

## P1 limits carried into P2

Local development accounts are not verified external identities. Shared access bootstrap adds existing local accounts; invitations and production identity are future work. Personal organizations remain owner-only. Switching organization also saves the default; P2 separates those preferences.

P1 supports only agents, tasks and sequential crews, with up to four members/tasks, no tools, autonomous delegation or external side effects. Limits are fixed policy v1 (eight worker calls, 12,000 worker tokens, 90-second runtime). A provider must honor requested token caps; unknown usage fails closed. No hard dollar budget is promised.

The canvas displays saved team members; temporary canvas positions do not control execution and are not persisted. Saved task ordering controls execution. Live run events currently mark task completion when the adapter returns; fine-grained streaming progress is future work. No automatic replay of uncertain runs. Production deployment and operational hardening remain later work.
