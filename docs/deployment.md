# GitHub → Mac mini deployment

## Topology

Public source: `gaaschk/quantum-crews`. All original project source is MIT, copyright Quantum Horizon Innovations. Third-party packages and the Gradle wrapper retain their original licenses; see LICENSE, NOTICE and the wrapper license.

GitHub-hosted CI (`.github/workflows/ci.yml`) runs on pull requests and main pushes. It checks deployment-controller tests, TypeScript, the web production build, Kotlin/PostgreSQL tests, Python/CrewAI tests, container builds, and the complete simulated API journey against the containerized stack. No production credentials or model calls are used. Action dependencies are pinned to commit SHAs.

A dedicated LaunchAgent on the mini fetches the public main branch and checks the CI result for that exact commit and a main push event. It never executes pull-request revisions and needs no GitHub credential. Failed, pending or missing CI results prevent deployment. Polling runs every minute, with GitHub status requests throttled to five minutes for an unchanged candidate. Public pull-based deployment avoids an internet-facing SSH service and avoids giving PR workflows a runner on the mini.

The mini builds native ARM64 container images tagged with the source commit, backs up PostgreSQL before application replacement, starts the candidate, and checks the full web → API → database/runtime path. A healthy release is recorded atomically in current.json. On replacement failure, previous application images are restored if available. Database migration reversal is not automatic: migrations must remain compatible with the preceding release. Database restoration requires a deliberate operator decision because it can discard newer data.

## Host installation

The mini needs its existing Docker engine, Git, and Python 3.12+; Java, Node and uv run inside containers. The source repositories and services for other projects are not modified.

After creating/pushing the public repository, copy `deploy/manage.py` and `deploy/install-mini.py` to a temporary folder on the mini and run:

```sh
python3 install-mini.py \
  --root "$HOME/Library/Application Support/Quantum Crews" \
  --repo gaaschk/quantum-crews
```

On a headless mini, the installer falls back to the user launch domain if no GUI domain exists. This starts the pipeline now. Cold-boot startup without a login requires the separately generated system service, installed once with `deploy/enable-boot.sh` under administrator authority. The service runs as the normal deployment user, not root.

The installer creates `~/Library/LaunchAgents/com.quantumhorizon.quantum-crews.plist`, the deployment root, random database/runtime credentials and a stable controller copy. Updating the controller itself requires reinstalling it; application changes arrive through trusted main revisions. Root and secret-file permissions are private. The Git clone, immutable release folders, backups and logs are inside the deployment root.

The service uses Compose project `quantum-crews-deploy` and its own database volume. Only the web port is published, on `127.0.0.1:3180`. API, runtime and PostgreSQL are on the internal Docker network. Live execution and simulation are disabled by default in this deployment. Public DNS/tunnel routing is a separate step requiring an explicit hostname decision and HTTPS settings.

For local access from another computer:

```sh
ssh -L 3180:127.0.0.1:3180 mini
```

Then open http://127.0.0.1:3180. Do not run the local container test stack on port 3180 at the same time as that tunnel.

## Configuration

`deployment.env` stays outside Git. It contains POSTGRES_PASSWORD, DB_APP_PASSWORD and QUANTUM_RUNTIME_TOKEN generated at installation. Backend migrations use the database owner; normal queries use the restricted qc_app role. The configured application password is applied after migration without changing migration checksums. Retain the initial PostgreSQL owner password for an existing volume; changing an environment variable does not rotate the database owner's stored password.

For a future HTTPS hostname, set PUBLIC_ORIGIN to the exact HTTPS origin and QUANTUM_SECURE_COOKIES=true before routing traffic. Keep API/runtime/database ports internal. Set QUANTUM_PUBLIC_MODE=true only after configuring and verifying account email delivery and the release checks below. Verified-email invitations remain separate P2 collaboration work.

SMART_ROUTER_BASE_URL defaults to https://smart-router.kevingaasch.com. Server model keys are not used. Each live operation requires the verified user’s explicitly selected BYOK connection. The pipeline never copies credentials from another repository. QUANTUM_ALLOW_LIVE remains false unless explicitly changed; simulation requires QUANTUM_TEST_MODE=true. Rebuild/redeploy after configuration changes. The UI reads live/simulation availability from authenticated server capabilities; no rebuild-time flag can grant model authority.

## Operations

```sh
launchctl print gui/$(id -u)/com.quantumhorizon.quantum-crews
python3 "$HOME/Library/Application Support/Quantum Crews/manage.py" once \
  --root "$HOME/Library/Application Support/Quantum Crews" --repo gaaschk/quantum-crews
curl --fail http://127.0.0.1:3180/api/foundation
```

Inspect deploy.log/deploy.err and current.json in the deployment root. Backups are custom-format pg_dump files under backups, with private permissions. No retention deletion is automated in this first pipeline; monitor disk usage and add an explicit retention policy later. Test a restore before relying on backups for production recovery.

Application replacement has brief downtime. In-flight jobs can become uncertain and are failed after their lease expires, rather than replayed. Prefer deploying when no runs are active; graceful drain/zero-downtime migration are later improvements.

GitHub branch protection should require `Verify` before merging to main. The pull controller independently checks CI for the exact main SHA even if branch protection is unavailable. Disable the LaunchAgent to pause automatic deployment; do not delete the volume or backup files.

On macOS, the controller uses a separate Docker configuration for anonymous public-image pulls, preserving the selected daemon endpoint. It does not access the interactive login keychain or alter your normal Docker credentials.

## P2 model connection storage (first increment)

Set `QUANTUM_CREDENTIAL_KEY` in the private deployment environment to a base64-encoded 32-byte random key before saving connections. Keep it outside Git and the database, back it up separately, and preserve it across releases. The API fails closed if it is missing or malformed; the application and manual building still start normally. Losing or replacing it makes existing connection ciphertext unreadable. Key rotation requires decrypt/re-encrypt under controlled maintenance and is not implemented yet. Never print this environment or copy it into an execution snapshot.

Connections are private to their creator within the active organization. The settings page stores, replaces, and removes keys. Live proposals/runs select a connection and explicit planner/worker models for direct providers; smart-router uses smart-orchestrator/smart-worker. Email verification is mandatory for live operations. Removal or replacement before dispatch rejects the queued operation. Keys are never copied into snapshots, outputs or run events. Live execution remains operator-disabled until intentionally enabled. Provider compatibility tests use doubles; do not treat them as live-provider certification.

## Public signup release configuration

The application now supports signup verification, resend, password recovery, one-time expiring token redemption, session revocation on password reset, durable PostgreSQL rate limits, bounded request bodies, and connection-backed model dispatch. Existing accounts are NOT silently marked verified. Manual building remains available after sign-in without a model key. Direct membership assignment is available only in simulation/development; public collaboration must use the later verified invitation flow.

Before public routing, privately configure:

- PUBLIC_ORIGIN=https://quantum-crews.kevingaasch.com and QUANTUM_SECURE_COOKIES=true.
- QUANTUM_PUBLIC_MODE=true and QUANTUM_TEST_MODE=false. Public mode fails startup with incomplete security configuration.
- QUANTUM_CREDENTIAL_KEY: a persistent base64-encoded random 32-byte master key, backed up independently of PostgreSQL. Do not replace it casually.
- QUANTUM_MAIL_KEY: a Resend sending API key; QUANTUM_MAIL_FROM: an address on your verified sending domain. The integration posts only account emails to the fixed Resend endpoint. Configure a different delivery adapter before use if Resend is not chosen.
- QUANTUM_ALLOW_LIVE=true only when intentionally enabling user-funded model calls. No operator/provider key is mounted or inherited as a fallback. SMART_ROUTER_BASE_URL is operator-controlled and must use HTTPS; users cannot supply arbitrary URLs.

Merge/deploy through main CI, verify an actual verification email and recovery email to an operator-owned account, verify replay/expiry and session revocation, create a BYOK connection, and separately authorize any bounded real model compatibility checks. Then publish DNS/tunnel routing. Do not publish the route while these operator checks are pending. The application guard proves configuration shape, not deliverability, provider compatibility, external penetration testing or compliance.

Email links carry tokens in fragments, and the account page immediately removes the fragment from browser history. Tokens are hashed in PostgreSQL and never returned by signup/recovery APIs. Public signup returns the same message for new/existing addresses; recovery is also generic. Mail submission is bounded and asynchronous to avoid a provider round trip in account-enumeration responses. Failed/process-interrupted delivery requires a fresh resend; a durable mail outbox is not implemented. Sender and API-key errors are not disclosed to users.

Limits are conservative: 10 email/action attempts and 300 aggregate connection-address/action attempts per 15 minutes, 3 verification resends/user, 20 model submissions/user, 120 authenticated authoring writes/user, and 10 active runs/organization. The backend intentionally does not trust arbitrary forwarding headers; behind the Next proxy the address bucket may be shared. Edge rate limits and bot challenges should supplement this before opening signup to broad traffic. Old buckets/tokens/sessions are pruned. Passwords require 12–256 characters with existing PBKDF2 hashing. Backup ciphertext may retain removed keys until backup retention expires; at dispatch a removed connection is unavailable. Key removal cannot recall an already-dispatched provider request.

Implementation references: [Resend send-email API](https://resend.com/docs/api-reference/emails/send-email), [OpenAI Chat Completions](https://developers.openai.com/api/reference/resources/chat), [Anthropic Messages](https://platform.claude.com/docs/en/api/messages). No paid provider calls or real account emails are part of CI.
