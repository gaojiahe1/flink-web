"""在隔离 Flink Application 中执行初始 SQL，回归磁盘 local URI 与提交上下文的兼容性。"""
import argparse
import base64
import json
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request
import uuid

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--image', required=True)
parser.add_argument('--runner', default='sql-runner/target/flink-web-sql-runner.jar')
args = parser.parse_args()
identity = 'fw-sql-application-' + uuid.uuid4().hex[:8]
jm, tm = identity + '-jm', identity + '-tm'
containers = []
network_created = False


def docker(*arguments):
    return subprocess.check_output(['docker', *arguments], text=True, encoding='utf-8').strip()


def call(path):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(base + path, timeout=15) as response:
        return json.load(response)


try:
    docker('network', 'create', identity)
    network_created = True
    statements = [
        "CREATE TEMPORARY TABLE source_events (id BIGINT, payload STRING) WITH ('connector'='datagen', 'rows-per-second'='1')",
        "CREATE TEMPORARY TABLE sink_events (id BIGINT, payload STRING) WITH ('connector'='print')",
        'INSERT INTO sink_events SELECT id, payload FROM source_events',
    ]
    program = []
    for sql in statements:
        program.extend(['--statement', base64.b64encode(sql.encode()).decode()])
    properties = '\n'.join([
        'jobmanager.rpc.address: ' + jm,
        'taskmanager.numberOfTaskSlots: 1', 'parallelism.default: 1',
        'jobmanager.memory.process.size: 1600m', 'taskmanager.memory.process.size: 1728m',
        'jobmanager.memory.jvm-metaspace.size: 384m', 'rest.bind-address: 0.0.0.0',
        'pipeline.name: initial-sql-application',
        'pipeline.jars: local:///opt/flink/usrlib/flink-web-sql-runner.jar',
        'restart-strategy.type: none',
    ])
    for name, role in [(jm, 'standalone-job'), (tm, 'taskmanager')]:
        ports = ['-p', '127.0.0.1::8081'] if name == jm else []
        command = [role, '--job-classname', 'com.flinkweb.sql.SqlRunner', *program] if name == jm else [role]
        docker('run', '-d', '--name', name, '--network', identity,
               '-e', 'FLINK_PROPERTIES=' + properties,
               '-v', str(Path(args.runner).resolve()) + ':/opt/flink/usrlib/flink-web-sql-runner.jar:ro',
               *ports, args.image, *command)
        containers.append(name)
    base = 'http://127.0.0.1:' + docker('port', jm, '8081/tcp').split(':')[-1]
    for attempt in range(90):
        try:
            jobs = call('/jobs/overview')['jobs']
            if any(job['state'] == 'FAILED' for job in jobs):
                raise RuntimeError('SQL Application 失败')
            if any(job['state'] == 'RUNNING' for job in jobs):
                output = docker('logs', tm)
                if '+I[' in output:
                    print(json.dumps({'flinkImage': args.image, 'mode': 'Application',
                                      'state': 'RUNNING', 'initialSqlPrint': True,
                                      'diskJarUri': 'local:///opt/flink/usrlib/flink-web-sql-runner.jar'}))
                    break
        except (OSError, urllib.error.URLError, json.JSONDecodeError):
            pass
        time.sleep(1)
    else:
        raise RuntimeError('Application 未就绪或初始 SQL 未输出')
except Exception:
    for name in containers:
        print(docker('logs', name))
    raise
finally:
    for name in containers:
        docker('rm', '-f', name)
    if network_created:
        docker('network', 'rm', identity)
