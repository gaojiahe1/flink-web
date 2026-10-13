"""在隔离 Flink Session 中检查固定验证日志、四种变更事件和一千条取消边界。"""
import argparse
import base64
import json
from pathlib import Path
import re
import subprocess
import time
import urllib.error
import urllib.request
import uuid

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--image', required=True)
parser.add_argument('--runner', default='sql-runner/target/flink-web-sql-runner.jar')
parser.add_argument('--log-configuration', required=True, help='实际 Session 配置中的 log4j-console.properties')
parser.add_argument('--directory', required=True, help='本任务独占的临时目录')
args = parser.parse_args()
directory = Path(args.directory).resolve()
directory.mkdir(parents=True, exist_ok=True)
identity = 'fw-sql-validation-' + uuid.uuid4().hex[:8]
jm, tm = identity + '-jm', identity + '-tm'
containers = []
network = False
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def docker(*arguments):
    return subprocess.check_output(['docker', *arguments], text=True, encoding='utf-8').strip()


def call(method, path, body=None, raw=False):
    request = urllib.request.Request(base + path, method=method, data=body)
    if body is not None:
        request.add_header('Content-Type', 'application/json')
    with opener.open(request, timeout=20) as response:
        data = response.read().decode('utf-8')
        return data if raw else json.loads(data or '{}')


def submit(statements):
    validation = uuid.uuid4().hex
    program = ['--validation', validation]
    for sql in statements:
        program.extend(['--statement', base64.b64encode(sql.encode()).decode()])
    result = call('POST', '/jars/' + jar + '/run', json.dumps({'entryClass': 'com.flinkweb.sql.SqlRunner', 'jobId': validation, 'parallelism': 1, 'programArgsList': program}).encode())
    assert result['jobid'] == validation
    return validation


def events(validation):
    result = []
    available = {file['name'] for file in call('GET', '/taskmanagers/' + manager + '/logs')['logs']}
    for filename in ['sql-validation.log.1', 'sql-validation.log']:
        if filename not in available:
            continue
        try:
            text = call('GET', '/taskmanagers/' + manager + '/logs/' + filename, raw=True)
        except urllib.error.HTTPError as error:
            if error.code == 404:
                continue
            raise
        for match in re.finditer(r'^FWV_EVENT\|(\d+)\|([^\r\n]*)\r?\n', text, re.MULTILINE):
            message = json.loads('"' + match[2] + '"')
            row = re.match(r'^FWV_' + validation + r'_(\d+)(?::\d+)?>\s*(\+I|-U|\+U|-D)\[(.*)]$', message, re.DOTALL)
            if row:
                result.append((int(match[1]), row[2], row[3]))
    return sorted({r[0]: r for r in result}.values())


try:
    cdc = directory / 'cdc.json'
    cdc.write_text('\n'.join(json.dumps(event, ensure_ascii=False) for event in [
        {'before': None, 'after': {'id': 1, 'payload': '中文\n一'}, 'op': 'c'},
        {'before': {'id': 1, 'payload': '中文\n一'}, 'after': {'id': 1, 'payload': '更新'}, 'op': 'u'},
        {'before': {'id': 1, 'payload': '更新'}, 'after': None, 'op': 'd'},
    ]) + '\n', encoding='utf-8')
    docker('network', 'create', identity)
    network = True
    properties = '\n'.join([
        'jobmanager.rpc.address: ' + jm,
        'taskmanager.numberOfTaskSlots: 1', 'parallelism.default: 1',
        'jobmanager.memory.process.size: 1600m', 'taskmanager.memory.process.size: 1728m',
        'jobmanager.memory.jvm-metaspace.size: 384m', 'rest.bind-address: 0.0.0.0',
        'taskmanager.system-out.mode: LOG', 'taskmanager.system-out.log.thread-name.enabled: false',
        'taskmanager.system-out.log.cache-upper-size: 1 mb', 'env.log.dir: /opt/flink/log',
        'web.refresh-interval: 1000', 'restart-strategy.type: none',
    ])
    for name, role in [(jm, 'jobmanager'), (tm, 'taskmanager')]:
        ports = ['-p', '127.0.0.1::8081'] if name == jm else []
        docker('run', '-d', '--name', name, '--network', identity,
               '-e', 'FLINK_PROPERTIES=' + properties,
               '-v', str(Path(args.log_configuration).resolve()) + ':/opt/flink/conf/log4j-console.properties:ro',
               '-v', str(cdc) + ':/data/cdc.json:ro',
               '-v', str(Path(args.runner).resolve()) + ':/opt/flink/usrlib/runner.jar:ro',
               *ports, args.image, role)
        containers.append(name)
    base = 'http://127.0.0.1:' + docker('port', jm, '8081/tcp').split(':')[-1]
    for attempt in range(75):
        try:
            managers = call('GET', '/taskmanagers')['taskmanagers']
            if managers:
                manager = managers[0]['id']
                break
        except (OSError, urllib.error.URLError, json.JSONDecodeError):
            pass
        time.sleep(1)
    else:
        raise RuntimeError('Session 未就绪')
    # 在容器内上传同一经过构建验证的 Runner，避免宿主 multipart 工具依赖。
    uploaded = docker('exec', jm, 'curl', '-fsS', '-F', 'jarfile=@/opt/flink/usrlib/runner.jar', 'http://localhost:8081/jars/upload')
    jar = json.loads(uploaded)['filename'].split('/')[-1]
    validation = submit([
        "CREATE TABLE source_cdc (id INT, payload STRING) WITH ('connector'='filesystem', 'path'='file:///data/cdc.json', 'format'='debezium-json')",
        "CREATE TABLE original_sink (id INT, payload STRING) WITH ('connector'='unavailable-original-sink')",
        'INSERT INTO original_sink SELECT * FROM source_cdc',
    ])
    for attempt in range(45):
        state = call('GET', '/jobs/' + validation)['state']
        if state == 'FAILED':
            raise RuntimeError(call('GET', '/jobs/' + validation + '/exceptions'))
        rows = events(validation)
        if len(rows) == 4:
            assert [r[1] for r in rows] == ['+I', '-U', '+U', '-D'], rows
            assert '中文\n一' in rows[0][2], rows
            break
        time.sleep(1)
    else:
        raise RuntimeError('固定文件未读取到四种变更：' + str(rows))
    validation = submit([
        "CREATE TABLE source_events (id BIGINT) WITH ('connector'='datagen','rows-per-second'='100','fields.id.kind'='sequence','fields.id.start'='1','fields.id.end'='100000')",
        "CREATE TABLE original_sink (id BIGINT) WITH ('connector'='unavailable-original-sink')",
        'INSERT INTO original_sink SELECT * FROM source_events',
    ])
    for attempt in range(45):
        rows = events(validation)
        if len(rows) >= 1000:
            displayed = rows[:1000]
            assert len(displayed) == 1000
            call('PATCH', '/jobs/' + validation + '?mode=cancel')
            break
        if call('GET', '/jobs/' + validation)['state'] == 'FAILED':
            raise RuntimeError(call('GET', '/jobs/' + validation + '/exceptions'))
        time.sleep(1)
    else:
        raise RuntimeError('未达到一千条数据')
    for attempt in range(20):
        if call('GET', '/jobs/' + validation)['state'] == 'CANCELED':
            break
        time.sleep(1)
    else:
        raise RuntimeError('取消未完成')
    print(json.dumps({'flinkImage': args.image, 'fixedLog': True, 'rowKinds': ['+I', '-U', '+U', '-D'], 'multiline': True, 'displayed': 1000, 'state': 'CANCELED'}))
finally:
    for name in reversed(containers):
        docker('rm', '-f', name)
    if network:
        docker('network', 'rm', identity)
