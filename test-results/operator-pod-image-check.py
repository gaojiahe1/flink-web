import base64
import json
import os
from pathlib import Path
import secrets
import subprocess
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parent.parent
PREFIX = '192.168.68.1:5000/flink-web/'

def docker(*args):
    return subprocess.check_output(['docker', *args], text=True).strip()

suffix = uuid.uuid4().hex[:10]
network, app, web = [f'fw-release-{part}-{suffix}' for part in ['net', 'app', 'web']]
containers = []
docker('network', 'create', network)
try:
    env = os.environ.copy()
    env['FLINK_WEB_MASTER_KEY'] = base64.b64encode(secrets.token_bytes(32)).decode()
    env['FLINK_WEB_ADMIN_PASSWORD'] = secrets.token_urlsafe(24)
    containers.append(app)
    subprocess.check_output([
        'docker', 'run', '-d', '--name', app, '--network', network,
        '--network-alias', 'release-backend', '--read-only',
        '--tmpfs', '/tmp:rw,exec,uid=10001,gid=10001',
        '--tmpfs', '/app/data:rw,uid=10001,gid=10001',
        '-e', 'FLINK_WEB_MASTER_KEY', '-e', 'FLINK_WEB_ADMIN_PASSWORD',
        '-p', '127.0.0.1::8080', PREFIX + 'app:0.0.4',
    ], env=env, text=True)
    port = docker('port', app, '8080/tcp').rsplit(':', 1)[1]
    deadline = time.monotonic() + 60
    while True:
        try:
            with urllib.request.urlopen(f'http://127.0.0.1:{port}/actuator/health', timeout=3) as r:
                assert json.load(r)['status'] == 'UP'
            break
        except OSError:
            if time.monotonic() > deadline:
                raise AssertionError('Backend did not become healthy')
            time.sleep(1)
    assert docker('exec', app, 'id', '-u') == '10001'
    assert '1.20.3' in docker('exec', app, 'ls', '/opt/sql-validators')
    print('PASS: backend starts with new SQLite metadata on read-only root, UID 10001; health UP', flush=True)
    containers.append(web)
    docker('run', '-d', '--name', web, '--network', network, '--read-only',
           '--tmpfs', '/tmp:rw,uid=10001,gid=10001',
           '-e', 'FLINK_WEB_PROXY_BACKEND_HOST=release-backend',
           '-p', '127.0.0.1::8080', PREFIX + 'web:0.0.4')
    web_port = docker('port', web, '8080/tcp').rsplit(':', 1)[1]
    env['RELEASE_URL'] = f'http://127.0.0.1:{web_port}'
    env['RELEASE_PASSWORD'] = env['FLINK_WEB_ADMIN_PASSWORD']
    subprocess.run(['node', str(ROOT / 'test-results/release-ui-smoke.cjs')], env=env, check=True)
    assert 'Kylin' in docker('exec', app, 'cat', '/etc/os-release')
    assert 'Kylin' in docker('exec', web, 'cat', '/etc/os-release')
    runtime_logs = docker('logs', app)
    assert 'Kubernetes request failed method=GET resource=' in runtime_logs
    assert env['FLINK_WEB_ADMIN_PASSWORD'] not in runtime_logs
    assert env['FLINK_WEB_MASTER_KEY'] not in runtime_logs
    env['E2E_BASE_URL'] = env['RELEASE_URL']
    env['E2E_BROWSER'] = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
    subprocess.run(['node', str(ROOT / 'frontend/node_modules/@playwright/test/cli.js'), 'test', 'tests/pod-logs.spec.ts', '--output=test-results/image-pod-check'], cwd=ROOT / 'frontend', env=env, check=True)
    print('PASS: Kylin images, safe Kubernetes failure logs and built Pod/log UI regressions', flush=True)
except Exception:
    for container in containers:
        print(docker('logs', '--tail', '35', container))
    raise
finally:
    for container in reversed(containers):
        subprocess.run(['docker', 'rm', '-f', container], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.run(['docker', 'network', 'rm', network], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
