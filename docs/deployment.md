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

For a future HTTPS hostname, set PUBLIC_ORIGIN to the exact HTTPS origin and QUANTUM_SECURE_COOKIES=true before routing traffic. Keep API/runtime/database ports internal. Production identity and verified-email invitations remain future work; the current local-account bootstrap is not an unrestricted public-service launch.

SMART_ROUTER_BASE_URL defaults to https://smart-router.kevingaasch.com. SMART_ROUTER_API_KEY can be added privately on the mini when live execution is intentionally enabled. The pipeline never copies credentials from another repository. QUANTUM_ALLOW_LIVE remains false unless explicitly changed; simulation requires QUANTUM_TEST_MODE=true. Rebuild/redeploy after configuration changes. The UI's live selector remains disabled in the production image by default.

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
