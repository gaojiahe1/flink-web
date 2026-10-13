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
parser.add_argument('--runner')
parser.add_argument('--parser-only', action='store_true')
parser.add_argument('--validator', action='append', required=True)
parser.add_argument('--flink-classpath-file')
args = parser.parse_args()
if not args.parser_only and (not args.runner or not args.flink_classpath_file):
    parser.error('Runner 验收需要 --runner 和 --flink-classpath-file，或使用 --parser-only')

for file in [*([args.runner] if args.runner else []), *args.validator]:
    with zipfile.ZipFile(file) as jar:
        for name in jar.namelist():
            if name.endswith('.class') and not name.startswith('META-INF/versions/'):
                version = int.from_bytes(jar.read(name)[6:8], 'big')
                if version > 52:
                    raise RuntimeError(str(file) + ': ' + name + ' 字节码版本为 ' + str(version))

classpath = '' if args.parser_only else os.pathsep.join([str(Path(args.runner).resolve()),
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
        for payload, error_type in [({'statements': statements}, None),
                                    ({'statements': ['INSERT INTO sink SELECT FROM']}, 'SYNTAX'),
                                    ({'statements': []}, 'INPUT'),
                                    ({'statements': [None]}, 'INPUT'),
                                    ({'statements': ['SELECT 1'] * 101}, 'INPUT'),
                                    ({'statements': ['SELECT ' + 'a' * 32769]}, 'INPUT'),
                                    ({'statements': [' ' * 131073]}, 'INPUT'),
                                    ('{broken-json', 'INPUT')]:
            checked = subprocess.run([java, '-Xmx256m', '-jar', validator],
                                     input=(payload if isinstance(payload, str) else json.dumps(payload)).encode(), capture_output=True,
                                     check=True, timeout=15)
            result = json.loads(checked.stdout)
            assert result['valid'] is (error_type is None), result
            if error_type:
                assert result['errorType'] == error_type, result
                assert 'SELECT FROM' not in checked.stdout.decode(), result
                if error_type == 'INPUT':
                    assert 'line' not in result and 'column' not in result and 'statement' not in result, result
        print(json.dumps({'java': version, 'validator': str(validator), 'protocolCases': 8, 'passed': True}))
    if args.parser_only:
        continue
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
