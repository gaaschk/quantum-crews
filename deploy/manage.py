#!/usr/bin/env python3
"""MIT. Pull and deploy only trusted main commits with a successful hosted Verify job."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import tarfile
import time
import urllib.request


def run(command, **kwargs):
    return subprocess.run(command, check=True, **kwargs)


def initialize(root, test=False):
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    path = root / 'deployment.env'
    if path.exists():
        return path
    values = {'POSTGRES_PASSWORD': secrets.token_urlsafe(32), 'DB_APP_PASSWORD': secrets.token_urlsafe(32),
              'QUANTUM_RUNTIME_TOKEN': secrets.token_urlsafe(32), 'QUANTUM_TEST_MODE': str(test).lower(),
              'QUANTUM_ALLOW_LIVE': 'false', 'PUBLIC_ORIGIN': 'http://127.0.0.1:3180', 'WEB_PORT': '3180'}
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as file:
        file.write(''.join(f'{key}={value}\n' for key, value in values.items()))
    return path


def github(repo, route, token):
    request = urllib.request.Request('https://api.github.com/repos/' + repo + route,
        headers={'Accept': 'application/vnd.github+json', **({'Authorization': 'Bearer ' + token} if token else {}),
                 'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': 'quantum-crews-deployer'})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def verified(repo, sha, token):
    data = github(repo, '/actions/workflows/ci.yml/runs?branch=main&event=push&per_page=30', token)
    return any(item['head_sha'] == sha and item['event'] == 'push' and item['head_branch'] == 'main'
               and item['status'] == 'completed' and item['conclusion'] == 'success' for item in data['workflow_runs'])


def compose(release, root, sha, *args, **kwargs):
    return run(['docker', 'compose', '--env-file', str(root / 'deployment.env'), '-f', str(release / 'deploy/compose.yaml'), *args],
               env=dict(os.environ, QC_RELEASE=sha), **kwargs)


def deploy(root, repo):
    token_path = root / 'github-token'
    if token_path.exists() and token_path.stat().st_mode & 0o077:
        raise RuntimeError('github-token must have mode 600')
    token = token_path.read_text().strip() if token_path.exists() else ''
    repository = root / 'repository'
    if not repository.exists():
        # The public repository needs no credential on the deployment host.
        run(['git', 'clone', '--no-checkout', f'https://github.com/{repo}.git', str(repository)])
    actual = subprocess.check_output(['git', '-C', str(repository), 'remote', 'get-url', 'origin'], text=True).strip()
    if actual != f'https://github.com/{repo}.git':
        raise RuntimeError('Deployment checkout origin differs from the configured repository')
    run(['git', '-C', str(repository), 'fetch', '--prune', 'origin', 'main'])
    sha = subprocess.check_output(['git', '-C', str(repository), 'rev-parse', 'origin/main'], text=True).strip()
    if not re.fullmatch('[0-9a-f]{40}', sha):
        raise RuntimeError('Invalid commit identity')
    current_path = root / 'current.json'
    current = json.loads(current_path.read_text()) if current_path.exists() else None
    if current and current['sha'] == sha:
        print('Already deployed', sha)
        return
    check_path = root / 'last-check.json'
    if check_path.exists():
        last = json.loads(check_path.read_text())
        if last['sha'] == sha and time.time() - last['time'] < 300:
            return
    check_path.write_text(json.dumps({'sha': sha, 'time': time.time()}))
    if not verified(repo, sha, token):
        print('Waiting for successful hosted CI for', sha)
        return
    release = root / 'releases' / sha
    if not release.exists():
        staging = root / 'releases' / (sha + '.partial')
        if staging.exists():
            shutil.rmtree(staging)
        staging.mkdir(parents=True)
        process = subprocess.Popen(['git', '-C', str(repository), 'archive', sha], stdout=subprocess.PIPE)
        try:
            with tarfile.open(fileobj=process.stdout, mode='r|') as archive:
                archive.extractall(staging, filter='data')
        finally:
            process.stdout.close()
        if process.wait() != 0:
            raise RuntimeError('Source archive failed')
        os.rename(staging, release)
    compose(release, root, sha, 'config', '--quiet')
    compose(release, root, sha, 'build', '--pull')
    backups = root / 'backups'
    backups.mkdir(exist_ok=True, mode=0o700)
    database = subprocess.check_output(['docker', 'ps', '--filter', 'label=com.docker.compose.project=quantum-crews-deploy',
                                      '--filter', 'label=com.docker.compose.service=postgres', '--format', '{{.ID}}'], text=True).strip()
    try:
        if database:
            compose(release, root, sha, 'stop', 'web', 'api', 'runtime')
            backup = backups / (time.strftime('%Y%m%d-%H%M%S') + '.dump')
            with backup.open('wb') as output:
                os.chmod(backup, 0o600)
                run(['docker', 'exec', database, 'pg_dump', '-U', 'quantum_crews', '-Fc', 'quantum_crews'], stdout=output)
        compose(release, root, sha, 'up', '-d', '--no-build', '--wait', '--wait-timeout', '240')
        port = next((line.split('=', 1)[1] for line in (root / 'deployment.env').read_text().splitlines() if line.startswith('WEB_PORT=')), '3180')
        with urllib.request.urlopen(f'http://127.0.0.1:{int(port)}/api/foundation', timeout=10) as response:
            if response.status != 200:
                raise RuntimeError('Release health check failed')
    except Exception:
        if current:
            previous = root / 'releases' / current['sha']
            compose(previous, root, current['sha'], 'up', '-d', '--no-build', '--wait', '--wait-timeout', '240')
            print('Restored previous application images; database was not reverted')
        raise
    state = root / 'current.tmp'
    state.write_text(json.dumps({'sha': sha, 'deployedAt': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())}))
    os.replace(state, current_path)
    print('Healthy release', sha)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('command', choices=['init', 'once'])
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--repo')
    parser.add_argument('--test', action='store_true')
    args = parser.parse_args()
    root = args.root.resolve()
    initialize(root, args.test)
    if args.command == 'once':
        if not args.repo or not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', args.repo):
            parser.error('--repo must be owner/repository')
        with (root / 'deploy.lock').open('w') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            deploy(root, args.repo)

if __name__ == '__main__':
    main()
