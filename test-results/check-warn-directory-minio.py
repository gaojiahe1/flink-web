import os, subprocess, time, urllib.request, uuid
from pathlib import Path
root = Path(__file__).resolve().parent.parent
name = 'fw-directory-minio-' + uuid.uuid4().hex[:10]
try:
    subprocess.run(['docker', 'run', '-d', '--name', name, '-p', '127.0.0.1::9000',
        '-e', 'MINIO_ROOT_USER=local-test', '-e', 'MINIO_ROOT_PASSWORD=local-test-only',
        '--tmpfs', '/data:rw', 'cgr.dev/chainguard/minio:latest', 'server', '/data'], check=True, stdout=subprocess.DEVNULL)
    port = subprocess.check_output(['docker', 'port', name, '9000/tcp'], text=True).strip().rsplit(':',1)[1]
    endpoint = 'http://127.0.0.1:' + port
    for attempt in range(40):
        try:
            urllib.request.urlopen(endpoint + '/minio/health/live', timeout=2).close()
            break
        except OSError: time.sleep(1)
    else: raise RuntimeError('Temporary MinIO failed to start')
    env = os.environ.copy()
    env['JAVA_HOME'] = 'D:/MySoft/jdk-17.0.10'
    env['PATH'] = env['JAVA_HOME'] + '/bin;' + env['PATH']
    env['FLINK_TEST_MINIO'] = endpoint
    env['TEMP'] = env['TMP'] = str(root / 'test-results/warn-directory-tmp')
    subprocess.run(['mvn.cmd', '-o', '-f', 'backend/pom.xml', '-Djava.io.tmpdir=' + env['TEMP'],
        '-Dflink.test.validator=' + str(root / 'sql-validator/target/flink-web-sql-validator.jar'), 'package'], cwd=root, env=env, check=True)
    print('PASS: isolated MinIO directory markers, nested files, adjacent-prefix protection and repeat deletion')
finally:
    subprocess.run(['docker', 'rm', '-f', name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
