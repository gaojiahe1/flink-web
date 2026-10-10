"""在隔离 Docker Session 中验证 SQL Runner、动态依赖、日志及固定输出上限。"""
import argparse
import base64
import json
from pathlib import Path
import re
import subprocess
import tempfile
import time
import urllib.request
import uuid
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--image', required=True)
parser.add_argument('--runner', default='sql-runner/target/flink-web-sql-runner.jar')
args = parser.parse_args()
identity = 'fw-sql-test-' + uuid.uuid4().hex[:8]
jm, tm = identity + '-jm', identity + '-tm'
network = identity
root = Path(tempfile.mkdtemp(prefix='sql-session-', dir='test-results')).resolve()
containers = []

def docker(*arguments):
    return subprocess.check_output(['docker', *arguments], text=True, encoding='utf-8').strip()

def call(path, body=None, method=None):
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(base + path, data=data, method=method,
                                     headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)

try:
    docker('network', 'create', network)
    (root / 'rows.csv').write_text('\n'.join(str(n) for n in range(150)) + '\n')
    properties = '\n'.join([
        'jobmanager.rpc.address: ' + jm, 'taskmanager.numberOfTaskSlots: 2',
        'taskmanager.system-out.mode: LOG', 'rest.bind-address: 0.0.0.0',
        'jobmanager.memory.process.size: 1600m', 'taskmanager.memory.process.size: 1728m',
        'jobmanager.memory.jvm-metaspace.size: 384m', 'parallelism.default: 1',
        'web.refresh-interval: 1000',
    ])
    # 移除当前测试容器里的连接器，以证明它们确实通过上传的用户 JAR 分发。
    script = 'mv /opt/flink/lib/flink-csv-*.jar /tmp/; mv /opt/flink/lib/flink-connector-files-*.jar /tmp/; exec /docker-entrypoint.sh "$@"'
    for name, role in [(jm, 'jobmanager'), (tm, 'taskmanager')]:
        options = ['-p', '127.0.0.1::8081'] if name == jm else []
        docker('run', '-d', '--name', name, '--network', network,
               '-e', 'FLINK_PROPERTIES=' + properties, '-v', str(root) + ':/validation:ro',
               '-v', str(Path(args.runner).resolve()) + ':/opt/flink/usrlib/flink-web-sql-runner.jar:ro',
               *options, '--entrypoint', 'sh', args.image, '-c', script, 'sh', role)
        containers.append(name)
    port = docker('port', jm, '8081/tcp').split(':')[-1]
    base = 'http://127.0.0.1:' + port
    for attempt in range(60):
        try:
            managers = call('/taskmanagers')['taskmanagers']
            if managers:
                break
        except Exception:
            pass
        time.sleep(1)
    else:
        raise RuntimeError('Session 未就绪')
    csv = docker('exec', jm, 'sh', '-c', 'echo /tmp/flink-csv-*.jar')
    files = docker('exec', jm, 'sh', '-c', 'echo /tmp/flink-connector-files-*.jar')
    docker('cp', jm + ':' + csv, str(root / 'csv.jar'))
    docker('cp', jm + ':' + files, str(root / 'files.jar'))
    bundle = root / 'verification.jar'
    services, entries = {}, set()
    with zipfile.ZipFile(bundle, 'w', zipfile.ZIP_DEFLATED) as output:
        for jar in [Path(args.runner), root / 'csv.jar', root / 'files.jar']:
            with zipfile.ZipFile(jar) as source:
                for name in source.namelist():
                    if name.endswith('/') or name == 'META-INF/MANIFEST.MF':
                        continue
                    if name.startswith('META-INF/services/'):
                        services.setdefault(name, set()).update(source.read(name).decode().splitlines())
                    elif name not in entries:
                        output.writestr(name, source.read(name))
                        entries.add(name)
        for name, providers in services.items():
            output.writestr(name, '\n'.join(sorted(providers)) + '\n')
    boundary = 'fw' + uuid.uuid4().hex
    body = ('--' + boundary + '\r\nContent-Disposition: form-data; name="jarfile"; filename="verification.jar"\r\nContent-Type: application/java-archive\r\n\r\n').encode() + bundle.read_bytes() + ('\r\n--' + boundary + '--\r\n').encode()
    request = urllib.request.Request(base + '/jars/upload', data=body,
                                    headers={'Content-Type': 'multipart/form-data; boundary=' + boundary})
    with urllib.request.urlopen(request, timeout=30) as response:
        uploaded = json.load(response)['filename'].split('/')[-1]
    run = uuid.uuid4().hex
    statements = ["CREATE TABLE source_data (n INT) WITH ('connector'='filesystem', 'path'='file:///validation/rows.csv', 'format'='csv')",
                  "CREATE TABLE original_sink (n INT) WITH ('connector'='connector-must-not-be-used')",
                  'INSERT INTO original_sink SELECT n FROM source_data']
    program = ['--validation', run]
    for sql in statements:
        program.extend(['--statement', base64.b64encode(sql.encode()).decode()])
    submitted = call('/jars/' + uploaded + '/run', {
        'entryClass': 'com.flinkweb.sql.SqlRunner', 'programArgsList': program,
        'jobId': run, 'parallelism': 1,
        'flinkConfiguration': {'pipeline.name': 'isolated-sql-validation', 'restart-strategy.type': 'none'},
    })
    assert submitted['jobid'] == run, submitted
    for attempt in range(60):
        manager = call('/taskmanagers')['taskmanagers'][0]['id']
        request = urllib.request.Request(base + '/taskmanagers/' + manager + '/log', headers={'Range': 'bytes=-524288'})
        with urllib.request.urlopen(request, timeout=15) as response:
            log = response.read().decode()
        matches = re.findall('FWV_' + run + r'_0(?::\d+)?> \+I\[(\d+)\]', log)
        if len(matches) == 100:
            break
        state = call('/jobs/' + run)['state']
        if state == 'FAILED':
            raise RuntimeError(json.dumps(call('/jobs/' + run + '/exceptions')))
        time.sleep(1)
    assert len(matches) == 100, 'Print 输出数量：' + str(len(matches))
    assert len(set(matches)) == 100
    # 增加无关日志使文件超过响应上限，再验证 UI 日志接口的 suffix Range。
    docker('exec', tm, 'sh', '-c', 'for logfile in /opt/flink/log/*taskexecutor*.log; do yes validation-log-padding | head -c 650000 >> "$logfile"; break; done')
    time.sleep(2)
    with urllib.request.urlopen(request, timeout=15) as response:
        tail = response.read()
        range_result = {'status': response.status, 'bytes': len(tail), 'contentRange': response.headers.get('Content-Range')}
    assert len(tail) > 524288, '需要真实的大文件验证 Flink 忽略 Range 后的流式读取'
    if call('/jobs/' + run)['state'] not in ['CANCELED', 'FINISHED', 'FAILED']:
        call('/jobs/' + run + '?mode=cancel', method='PATCH')
    for attempt in range(30):
        if call('/jobs/' + run)['state'] in ['CANCELED', 'FINISHED']:
            break
        time.sleep(1)
    call('/jars/' + uploaded, method='DELETE')
    report = {'flinkImage': args.image, 'jobId': run, 'dynamicDependencies': ['filesystem connector', 'CSV format'],
              'originalSinkUsed': False, 'rows': len(matches), 'logRange': range_result}
    (root / 'report.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False))
finally:
    for name in containers:
        docker('rm', '-f', name)
    docker('network', 'rm', network)
