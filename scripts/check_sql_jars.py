"""验证 SQL JAR 的 Java 8 字节码及指定 JDK 下的真实 Parser/Runner 行为。"""
import argparse
import base64
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--java', action='append', required=True)
parser.add_argument('--runner', required=True)
parser.add_argument('--validator', action='append', required=True)
parser.add_argument('--flink-classpath-file', required=True)
args = parser.parse_args()

for file in [args.runner, *args.validator]:
    with zipfile.ZipFile(file) as jar:
        for name in jar.namelist():
            if name.endswith('.class') and not name.startswith('META-INF/versions/'):
                version = int.from_bytes(jar.read(name)[6:8], 'big')
                if version > 52:
                    raise RuntimeError(str(file) + ': ' + name + ' 字节码版本为 ' + str(version))

classpath = os.pathsep.join([str(Path(args.runner).resolve()),
                           Path(args.flink_classpath_file).read_text(encoding='utf-8').strip()])
statements = [
    "CREATE TEMPORARY TABLE source_events (id BIGINT, payload STRING) WITH ('connector'='datagen', 'number-of-rows'='3')",
    "CREATE TEMPORARY TABLE sink_events (id BIGINT, payload STRING) WITH ('connector'='print')",
    'INSERT INTO sink_events SELECT id, payload FROM source_events',
]
program = []
for sql in statements:
    program.extend(['--statement', base64.b64encode(sql.encode()).decode()])

for java in args.java:
    version = subprocess.run([java, '-version'], capture_output=True, text=True, check=True).stderr.splitlines()[0]
    for validator in args.validator:
        for payload, valid in [({'statements': statements}, True),
                               ({'statements': ['INSERT INTO sink SELECT FROM']}, False),
                               ({'statements': []}, False),
                               ({'statements': [None]}, False),
                               ({'statements': ['SELECT 1'] * 101}, False),
                               ({'statements': [' ' * 131073]}, False)]:
            checked = subprocess.run([java, '-Xmx256m', '-jar', validator],
                                     input=json.dumps(payload).encode(), capture_output=True,
                                     check=True, timeout=15)
            result = json.loads(checked.stdout)
            assert result['valid'] is valid, result
        print(json.dumps({'java': version, 'validator': str(validator), 'protocolCases': 6, 'passed': True}))
    with tempfile.TemporaryDirectory(prefix='sql-jdk-') as directory:
        Path(directory, 'flink-conf.yaml').write_text('parallelism.default: 1\n', encoding='utf-8')
        environment = dict(os.environ, FLINK_CONF_DIR=directory)
        run = subprocess.run([java, '-Xmx768m', '-cp', classpath, 'com.flinkweb.sql.SqlRunner', *program],
                             env=environment, capture_output=True, timeout=90)
        output = run.stdout.decode('utf-8', errors='replace')
        if run.returncode != 0:
            raise RuntimeError(run.stderr.decode('utf-8', errors='replace')[-8000:])
        rows = len(re.findall(r'\+I\[', output))
        assert rows == 3, output[-4000:]
        print(json.dumps({'java': version, 'runnerRows': rows, 'passed': True}))
