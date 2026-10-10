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
app, web = [f'fw-same-pod-{part}-{suffix}' for part in ['app', 'web']]
containers = []
try:
    env = os.environ.copy()
    env['FLINK_WEB_MASTER_KEY'] = base64.b64encode(secrets.token_bytes(32)).decode()
    env['FLINK_WEB_ADMIN_PASSWORD'] = secrets.token_urlsafe(24)
    containers.append(app)
    subprocess.check_output([
        'docker', 'run', '-d', '--name', app, '--read-only',
        '--tmpfs', '/tmp:rw,exec,uid=10001,gid=10001',
        '--tmpfs', '/app/data:rw,uid=10001,gid=10001',
        '-e', 'FLINK_WEB_MASTER_KEY', '-e', 'FLINK_WEB_ADMIN_PASSWORD',
        '-e', 'SERVER_PORT=8081', '-p', '127.0.0.1::8081', '-p', '127.0.0.1::8080',
        PREFIX + 'app:0.0.4',
    ], env=env, text=True)
    port = docker('port', app, '8081/tcp').rsplit(':', 1)[1]
    deadline = time.monotonic() + 60
    while True:
        try:
            with urllib.request.urlopen(f'http://127.0.0.1:{port}/actuator/health', timeout=3) as r:
                assert json.load(r)['status'] == 'UP'
            break
        except OSError:
            if time.monotonic() > deadline:
                raise AssertionError('Backend on 8081 did not become healthy')
            time.sleep(1)
    containers.append(web)
    docker('run', '-d', '--name', web, '--network', 'container:' + app, '--read-only',
           '--tmpfs', '/tmp:rw,uid=10001,gid=10001',
           '-e', 'FLINK_WEB_PROXY_BACKEND_HOST=127.0.0.1',
           '-e', 'FLINK_WEB_PROXY_BACKEND_PORT=8081', PREFIX + 'web:0.0.4')
    env['RELEASE_URL'] = 'http://127.0.0.1:' + docker('port', app, '8080/tcp').rsplit(':', 1)[1]
    env['RELEASE_PASSWORD'] = env['FLINK_WEB_ADMIN_PASSWORD']
    subprocess.run(['node', str(ROOT / 'test-results/release-ui-smoke.cjs')], env=env, check=True)
    print('PASS: existing images share a network namespace, backend 8081, frontend 8080, localhost proxy', flush=True)
except Exception:
    for container in containers:
        print(docker('logs', '--tail', '25', container))
    raise
finally:
    for container in reversed(containers):
        subprocess.run(['docker', 'rm', '-f', container], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
