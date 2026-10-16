# P2 — Public accounts, BYOK, reuse and collaboration

Status: P2 started. The first increment implements private encrypted model-connection management; public signup verification and credential-backed execution are not shipped yet. P1 now implements the authenticated, persisted manual/AI-proposal/team/run journey; its deterministic API journey passed approximately 80 HTTP assertions (polling affects the count). The separately approved live smart-orchestrator proposal and two-task CrewAI/smart-worker run passed on October 16, 2026; see p1-acceptance.md. This verifies the tested path, not all providers or execution modes. Reconcile any subsequent P1 changes before starting these milestones.

## Outcome and scope

A person can find saved work, reopen a team, reuse an existing team member or task, and collaborate with another person in a shared organization. Everyone can see which organization they are working in and which saved revisions a team uses. The home page stays focused on starting a job and returning to recent work; the editor remains on its own workspace page.

P2 includes public signup with verified email and recovery, BYOK for smart-ai-router or supported direct providers, organization administration and invitations, searchable tenant-local libraries, recent teams and runs, explicit revision-aware reuse, and conflict-safe collaborative editing. Collaboration means people sharing saved definitions and runs with optimistic concurrency; it does not require simultaneous cursor presence or live multi-user canvas editing.

P2 excludes external tools, new workflow kinds, schedules, dynamic teams, cross-organization copying/sharing, public links, billing, marketplace discovery, and a second runtime. Manual and AI editing of existing P1 kinds retain the same validation and approval paths. Existing Mac mini deployment is authorized; public exposure waits for the release gate below. Email delivery configuration, paid calls, and consequential repository/release actions require their specific authorization.

## Entry gate: prove P1 first

The P1 baseline supplies the following contracts; preserve and extend them rather than building replacements:

- Authenticated users, active organization membership checks, automatic Personal organization provisioning, organization switching, and server-side isolation exist.
- Registry components and immutable versions support agents, tasks, and crews with tenant-bound pinned references, validation, and optimistic concurrency.
- AI proposals carry an exact base revision, permit human editing, and bind approval to the reviewed content.
- Saved crews produce immutable snapshots, durable run status, inspectable task outputs, and truthful failure states.
- Tenant isolation and concurrency tests run against real PostgreSQL using the application role rather than the database owner.

The completed deterministic journey covers account/session behavior, private/shared organization boundaries, role restrictions, saved definitions, proposal approval, version conflicts, snapshots, simulated outputs, concurrent writes, and cancellation. P1's approved live gateway check also passed; do not assume that extends to untested provider combinations. P2 must not make a new UI appear to provide behavior that the backend does not enforce.

### Existing P1 API and schema baseline

- Identity: `POST /api/auth/register`, `/api/auth/login`, `/api/auth/logout`, and `GET /api/me`; local password authentication uses an HTTP-only session cookie. `/api/me` returns the user, organization memberships, and active organization ID. Email identity is not production-verified.
- Organizations: `POST /api/organizations`, `POST /api/organizations/active` with `organizationId`, and owner-only `POST /api/organizations/{orgId}/members` with an already registered email and `member` or `viewer` role. Personal organizations cannot receive members. P1 has owner/member/viewer roles; admin is a new P2 role.
- Registry: `GET/POST /api/components`, `PUT /api/components/{id}`, `GET /api/components/{id}/history`, and `POST /api/components/{id}/rollback`. Updates take `baseRevision`; rollback takes `baseRevision` and `targetRevision`. There is no definition-deletion API.
- Proposals: `GET/POST /api/proposals`, `POST /api/proposals/{id}/approve`, and `/reject`. Generation takes `prompt`, optional `agentId`, and `mode`; approval takes edited `proposed` content plus `baseRevision`. P1 proposes team members only.
- Runs: `GET/POST /api/runs`, `GET /api/runs/{id}`, and `POST /api/runs/{id}/cancel`. Creation takes pinned `crewId`/`crewRevision`, source `input`, and `mode`. Lists currently return at most 100 detailed runs; P2 should replace expensive list expansion with bounded summaries while retaining run detail.
- Tenant scope: registry, proposal, and run endpoints resolve the active organization from the authenticated server session. P2 keeps this convention; it does not add a second organization-prefixed registry API. Every request still checks membership and establishes transaction-local tenant scope.
- Core tables: `app_users`, `organizations`, `memberships`, `sessions`, `components`, `component_versions`, `proposals`, `approvals`, `runs`, `run_snapshots`, and `run_events`. Tenant data uses `org_id`; immutable versions and snapshots live separately from mutable current revision/run status.
- P1 definition specs are deliberately narrow: agent `{role,instructions}`, task `{instructions,agentId,agentRevision}`, and crew `{agents:[{id,revision}],tasks:[{id,revision}]}`. Metadata search for description/tags requires a versioned schema extension, because P1 rejects unknown definition fields. Do not silently add unvalidated fields.
- P1's organization switch also saves that organization as the user's default. P2 separates session switching from an explicit default preference operation, with migration/UI behavior documented before changing this contract.

## Primary journeys

### Return to work

After signing in, open the current default organization. See recent teams and recent runs with understandable status and dates. Reopen a saved team in the separate workspace; inspect a run without altering its snapshot. An empty organization offers Describe a job and Build manually, without invented sample data or operational badges.

### Find and reuse

Open Library, search by name, description, or tag, and filter by Team members, Tasks, or Teams. Preview a component before adding it. The team references a specific saved revision; adding it does not silently clone or modify the source. Show a quiet update indicator when a newer revision exists. Preview changes and explicitly adopt the newer revision, creating a new team revision. Historical runs retain their original references.

### Collaborate in a shared organization

Create a shared organization and become its owner. Invite an existing or new user with a selected role. The recipient signs in and accepts the invitation. Both users can switch between the shared organization and their own Personal organizations. The shared library and run list contain only shared-organization work. Personal remains private; P2 does not invite people into Personal organizations.

### Resolve concurrent changes

Two members open the same definition revision. The first saves. The second receives a clear conflict instead of overwriting the first change. Their unsaved work remains available, with current saved values and their changes shown side by side. They can discard or revise and resubmit against the latest revision. An AI proposal based on the old revision requires renewed review and approval.

### Administer access

An authorized owner/admin can view members, invite, revoke invitations, change permitted roles, and remove members. Owner transfer is explicit and atomic; the final owner cannot be removed or demoted. Removal immediately prevents new protected requests and invalidates cached organization context. A removed user's default organization falls back to Personal. Existing run snapshots remain historical records; access to them still requires current membership.

## Domain changes

Reuse P1 users, organizations, memberships, preferences, components, component versions, proposals, and runs. Do not create a second registry or duplicate the role model.

| Addition/change | Required behavior |
|---|---|
| Organization invitation | Organization, normalized target email, intended role, inviter, token digest, expiry, state, accepted user, and timestamps. States: pending, accepted, revoked, expired. Single-use token; no plaintext token persistence. |
| Membership administration | Unique organization/user membership; explicit owner/admin/member/viewer permissions; revision or equivalent concurrency protection for administrative changes. |
| Organization preferences | Default organization is separate from Personal ownership; only active memberships can become defaults. |
| Search metadata | Name, description, tags, kind, updated time, and current revision from the canonical registry. Start with PostgreSQL search, not a separate index service. |
| Recent work | Derive recent teams from saved updates and recent runs from run timestamps/status; no activity-tracking table is necessary initially. |
| Dependency usage | Query which current teams reference a component/revision. Use a tenant-scoped dependency table only if existing JSON queries become insufficient. |
| Administrative audit events | Actor, organization, action, affected identity, outcome, and timestamp. Redact invitation tokens and credentials. Append-only records; keep retention explicit. |

Component references remain pinned. Editing a reusable team member affects future explicitly updated teams, not every saved consumer. Creation from an existing component within the same organization can be added later as an explicit duplicate action; implicit copying is prohibited. Reject cross-organization references even when the actor belongs to both organizations.

## API changes

These extensions retain P1's active-organization API convention. Administrative routes with an explicit organization ID additionally verify that the target matches the authorized context. All routes authenticate and check active membership plus operation permission server-side.

| Operation | Proposed route/contract |
|---|---|
| List organizations | Reuse `GET /api/me` organization memberships initially; a dedicated list route is unnecessary. |
| Create shared organization | `POST /api/organizations`; owner membership is committed atomically. Personal provisioning remains an account operation. |
| Choose default | `PUT /api/me/preferences/default-organization`; requires an active membership. |
| List/search library | Extend `GET /api/components?kind=&q=&tag=&cursor=&limit=`; bounded pages and stable ordering by update time plus ID. Coordinate the existing web client when changing the bare-array response to a page envelope. |
| Recent teams/runs | Extend `GET /api/components` and `GET /api/runs` with bounded filters, cursor pagination, and summary responses; fetch full snapshots/events only from run detail. Add dashboard aggregation only if necessary. |
| Inspect reuse | `GET /api/components/{id}/usage`; list authorized current consumers and pinned revisions. |
| Adopt newer dependency | Existing crew update/proposal route with expected crew revision and new pinned dependency revision; validate and append transactionally. |
| List members | `GET /api/organizations/{orgId}/members`; limit identity fields to what authorized members need. |
| Invite | Add `POST /api/organizations/{orgId}/invitations`; validate allowed role, deduplicate pending invitations, and generate an expiring token. This replaces direct email membership assignment in the production-facing UI; retain the P1 assignment endpoint only as an explicitly gated local development/admin bootstrap operation. |
| Revoke invitation | `DELETE /api/organizations/{orgId}/invitations/{id}`; idempotent revocation. |
| Accept invitation | `POST /api/invitations/accept`; authenticated verified identity must match target email, with token in the request body. |
| Change/remove membership | `PATCH`/`DELETE /api/organizations/{orgId}/members/{userId}`; expected version, privilege checks, and last-owner protection. |

Reuse P1 conflict responses. Definition writes carry an expected revision and return a conflict with authorized current-version information. Authentication failures never disclose whether a component exists in another organization. Invitation acceptance must be transactional and safe under simultaneous acceptance/revocation. Role changes and owner transfer must be safe under simultaneous administrative requests.

For localhost P2 verification, expose a one-time invitation link to the inviting administrator for manual delivery. Do not send email automatically. If P1 only offers development identities without verified email, acceptance must stay explicitly development-only; production email-bound invitations cannot be declared complete until verified identity is available. Avoid public email lookup or user-enumeration responses.

## Permission and privacy boundaries

| Role | P2 baseline |
|---|---|
| Viewer | Read shared definitions, history, and authorized run outputs. No authoring, execution, invitation, or access administration. |
| Member | Read, author, approve definition proposals, and execute within existing policy/grants. No membership administration. |
| Admin | Member capabilities plus invite/remove members and viewers and manage their roles. Cannot create owners/admins or alter owner/admin access. |
| Owner | Admin capabilities plus appoint admins and explicitly transfer/add ownership. Last-owner invariant remains enforced. |

P1 supports owner/member/viewer; add admin through an explicit membership constraint migration and backend permission mapping before exposing it. Approval here means accepting a definition proposal, not granting tools or approving external actions. No membership role bypasses tool grants or execution budgets. AI never administers organizations or invitations.

Use composite tenant foreign keys and RLS for every new tenant-owned table. Keep invitation redemption behind a narrowly scoped backend lookup that validates the digest and identity before establishing organization membership; it must not disable RLS globally or use a database-owner application connection. Decide and test the narrow redemption mechanism during schema design.

Search queries, counts, pagination cursors, dependency usage, dashboard aggregates, and audit views must remain tenant-scoped. Cache keys include user and organization; switching organizations clears local drafts, requests, and query caches. Search excludes secrets, proposal prompt bodies, and run output content initially. Run outputs are visible only to current authorized organization members; avoid accidental cross-tenant identifiers in error text or logs.

Membership revocation blocks new protected operations. It does not rewrite completed runs. Recheck authorization before pending user-approved work is newly dispatched; document the existing P1 policy for already-running jobs rather than silently inventing cancellation semantics in P2. Organization deletion and hard deletion of components remain out of scope.

## Implementation milestones and order

### P2.1 — Public account identity

Keep the existing account and Personal-organization transaction. Add expiring, hashed, single-use email-verification and password-reset tokens, durable rate limits, generic account-recovery responses, session revocation, and verified-identity gates for invitations/model use. Do not grandfather unverified P1 identities as verified. Choose/configure an email delivery service with operator credentials; development tests capture mail locally without exposing tokens in production responses/logs. Keep manual authoring available without model credentials. Finish accessible signup, verification, resend, recovery, and expiry/error UI.

Exit: replay/expiry/concurrent redemption and enumeration tests pass; verified identity survives restart; existing sessions/accounts migrate safely; real delivery is verified before opening signup publicly.

### P2.2 — BYOK connections and execution

First increment: private connection create/list/replace-key/delete operations under the current active organization, exact owner checks, forced RLS on organization and owner, AES-256-GCM with authenticated identity/provider binding, no plaintext persistence or key readback, and a quiet Connections settings page. Support smart-router, OpenAI, and Anthropic as explicit choices. Fail closed without a valid operator encryption key. Saving does not call providers or certify compatibility.

Next increment: require an explicit authorized connection for live proposals and runs, pin non-secret provider/model configuration in snapshots, resolve secrets immediately before dispatch, and strip runtime platform-key fallback. Implement fixed endpoint adapters for router and direct providers, require planner/worker model selection for direct providers, redact provider errors, enforce budgets, and recheck revocation and user membership at dispatch. No key means manual use only. Organization-wide connection sharing is deferred until explicit grants and audit are implemented; initial keys remain private even to other organization owners.

Exit: tenant/user isolation, tampering, revocation, restart, missing-key, wrong-key, no-fallback, snapshot-redaction, and provider-double tests pass. Live provider calls require bounded authorization and are reported separately from simulated checks. Document master-key backup and rotation; loss of the key makes stored credentials unrecoverable.

### P2.3 — Contract reconciliation and read-only discovery

Recheck the baseline above against P1 migrations and tests. Define the existing-list pagination response change and update its web callers together. Add name/kind search first; description/tag search follows an explicit validated metadata schema migration. Add recent-team/run summary queries. Separate active-session switching from default preference saving. Verify no dependency can be resolved across organizations.

Exit: seeded components can be found by name/tag/kind; pages are stable; two-tenant negative tests pass, including counts and cursors.

### P2.4 — Calm home and library

Add compact organization/account controls, recent work on Home, and Library filters/search with empty/loading/error states. Opening a team navigates to the separate workspace. Omit version/tag clutter from Home and expose detail where needed. Cancel outdated requests on organization switches and show no stale content.

Exit: users find and reopen saved work after reload; switching organizations never briefly displays the previous organization's cards or results; manual creation is available during AI failure.

### P2.5 — Revision-aware reuse and conflict recovery

Add component previews, add-existing-member/task controls, dependency usage, and explicit dependency updates. Preserve unsaved edits when saves conflict; show current versus proposed fields and allow a deliberate retry. Reuse the proposal diff UI where practical, with no automatic reapproval.

Exit: two editors cannot overwrite each other; saved crew references change only through explicit saves; old snapshots remain identical after source updates.

### P2.6 — Shared organization administration

Implement shared organization creation, membership views, explicit defaults, and guarded role/removal operations. Add transactional owner transfer and administrative audit records. Test concurrent last-owner changes.

Exit: owner, admin, member, and viewer behavior matches the permission table; users retain Personal access after leaving a shared organization; forged client roles fail server-side.

### P2.7 — Invitations

Implement expiring, single-use invitation creation, acceptance, and revocation with the identity gate described above. Show a clear accepting-organization confirmation before joining. Keep token-bearing URLs out of analytics, application logs, and referrers; remove tokens from browser history after exchange. No email delivery integration in this milestone.

Exit: wrong identity, expired/revoked tokens, replay, concurrent acceptance, and privilege escalation fail safely; valid acceptance produces one membership and does not move Personal work.

### P2.8 — Integrated verification and public release gate

Run a browser journey with two users collaborating in a shared organization, creating/reusing definitions, encountering a real revision conflict, running a saved team using the P1 test gateway, and revoking access. Include a viewer and isolated Personal organizations. Use real PostgreSQL for tenancy, transactional races, and persistence. No additional paid model calls are required to prove P2 collaboration.

Exit: all acceptance checks pass; migrations are verified on a copy of the existing local database; README describes shipped behavior and limitations; deployment remains separately authorized.

P2.1 identity and P2.2 model connections precede public release. Discovery and reuse can follow independently once tenant contracts are stable. Membership administration precedes invitations. P2.8 integrates all milestones and gates public routing.

## Acceptance matrix

- **Persistence:** teams, dependency selections, defaults, and memberships survive restart; read-only lists reflect canonical saved state.
- **Isolation:** unauthorized reads/writes/search/counts/cursors/usage/invitations/runs fail, even if IDs are guessed or the caller belongs to multiple organizations.
- **Reuse:** adding an existing component pins its revision; adopting an update requires review/save; historical execution snapshots never change.
- **Concurrency:** simultaneous definition writes preserve the winner and surface the loser; concurrent role changes never remove every owner; invitation redemption is single-use.
- **Authorization:** server enforces every role; revocation applies to subsequent requests and newly dispatched work; AI and ordinary definition patches cannot change access.
- **Product clarity:** Home has no embedded team workspace; organization context appears once; common work uses Team/Team member rather than requiring knowledge of Agent/Crew.
- **Failure behavior:** offline/AI-failure states preserve manual work; conflicting drafts survive until the user resolves/discards them; errors do not masquerade as successful saves or runs.
- **Accessibility:** keyboard-operable switcher/search/invitation/conflict review, focus management on navigation and dialogs, labeled controls, and status conveyed beyond color.
- **Audit:** organization/member/invitation administrative changes have authorized actor/time records; tokens, credentials, and hidden model reasoning are never recorded.

## Decisions to resolve during P2.1

Choose a production verified-email identity path before enabling real invitation acceptance; local password authentication alone is insufficient evidence of email ownership. Define the new admin permission mapping, pagination envelopes/cursor format, description/tag metadata migration, and whether member-visible identity can be limited to display name plus role. Preserve P1's dispatch-time membership recheck and document already-running-job behavior. Separate default preference from organization switching explicitly. Prefer existing P1 primitives and small additions. These choices must not weaken Personal privacy, pinned versions, or immutable snapshots.

## Software delivery reference and subsequent roadmap

The architecture's software delivery crew is the reference for every phase. P2 supports its manual roles, shared definitions, review artifacts, and individual model connections; it does not claim autonomous software delivery.

| Phase | Reference scenario delivered |
|---|---|
| P1 (complete) | Create team members/tasks, approve proposals, save a crew, execute a bounded tool-free run, inspect immutable results. |
| P2 (started) | Public verified accounts, BYOK, reusable teams, shared organizations, conflict-safe edits, and isolated collaboration. |
| P3 | Versioned PI/sprint/story/design/QA artifacts; typed flows, conditions and validators; bounded QA→development rework; human acceptance and durable approval/resume. |
| P4 | Scoped repository tools in isolated checkouts; development creates reviewable PRs; QA attaches evidence; explicit human merge/deploy gates and tool audits. |
| P5 | Hybrid delivery flow with ephemeral specialists, registry discovery, policy/budget reservations across delegation, and promotion of reusable crews. |
| P6+ | Operational scale, stronger sandboxing, another runtime adapter, richer observability, and verified production delivery templates. |

Public release additionally requires HTTPS/secure cookies, verified email/recovery, durable anti-abuse limits, encrypted credential backup/rotation, no server-funded model fallback, redacted diagnostics, and tests proving users cannot reach another user's keys or tenant work. Publish quantum-crews.kevingaasch.com only after this gate passes. The previously prepared single-email Cloudflare Access application must not be activated as the public signup model.

## PI pull request and preview delivery requirements

Each PI, including P2, must have a branch and PR with its objective, delivered and remaining scope, acceptance checks, QA evidence, and product-owner review. Start a draft when work remains. Never push PI implementation directly to main. Smaller PR increments are allowed; retain a PI integration PR linking their results. P2's first PR covers the BYOK foundation and revised roadmap, not a claim that the entire public-account PI is finished.

Add Review PR, Download, Install preview, and Promote release to the delivery experience in P4. Download exact-SHA source and CI-built platform artifacts with checksum/provenance and licenses. Install preview only after successful checks and explicit target-specific human approval; verify the approved head has not changed. Use an isolated Mac mini environment, separate database/ports/secrets, and synthetic data. Show queued/installing/healthy/failed/removed state, installed SHA, health and logs; failed previews cannot replace production. Uninstall and rollback must be tested. Promotion follows review, human approval, merge and main CI. No public PR job receives target credentials or production secrets.

Acceptance: stale head/digest, failed checks, unauthorized user/target, replayed approval and untrusted artifacts are rejected; two previews cannot collide; preview failure/uninstall leaves production intact; downloaded packages reproduce the named revision. P2 documents this contract; P4 implements application-managed download/install-preview rather than implying it already exists.
