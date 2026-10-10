import subprocess, time, urllib.request, uuid
from pathlib import Path
root = Path(__file__).resolve().parent.parent
image = '192.168.68.1:5000/flink-web/downloader:0.0.4'
suffix = uuid.uuid4().hex[:10]
network, server = 'fw-s3-compat-net-' + suffix, 'fw-s3-compat-store-' + suffix
script = '''
import sys, os, json
sys.path.insert(0, '/app')
import download
import boto3
from botocore.config import Config
from pathlib import Path
client = boto3.client('s3', endpoint_url=os.environ['S3_ENDPOINT'], region_name=os.environ['AWS_REGION'], config=Config(signature_version='s3v4', request_checksum_calculation='when_required', response_checksum_validation='when_required', s3={'addressing_style': 'path'}))
client.create_bucket(Bucket='compat-test', CreateBucketConfiguration={'LocationConstraint': 'cn-north-4'})
client.put_object(Bucket='compat-test', Key='path/job.jar', Body=b'compatible-payload')
os.environ['ARTIFACT_MANIFEST'] = json.dumps([{'uri': 's3://compat-test/path/job.jar', 'filename': 'job.jar'}])
download.main()
assert Path('/artifacts/job.jar').read_bytes() == b'compatible-payload'
assert os.getuid() == 9999
client.delete_object(Bucket='compat-test', Key='path/job.jar')
client.delete_bucket(Bucket='compat-test')
print('PASS: real boto3 S3v4 download with configured region cn-north-4, UID 9999 and read-only root')
'''
subprocess.run(['docker', 'network', 'create', network], check=True, stdout=subprocess.DEVNULL)
try:
    subprocess.run(['docker','run','-d','--name',server,'--network',network,'--network-alias','compat-store','-p','127.0.0.1::9000',
        '-e','MINIO_ROOT_USER=local-test','-e','MINIO_ROOT_PASSWORD=local-test-only','-e','MINIO_REGION_NAME=cn-north-4',
        '--tmpfs','/data:rw','cgr.dev/chainguard/minio:latest','server','/data'],check=True,stdout=subprocess.DEVNULL)
    port = subprocess.check_output(['docker','port',server,'9000/tcp'],text=True).strip().rsplit(':',1)[1]
    for attempt in range(40):
        try:
            urllib.request.urlopen('http://127.0.0.1:'+port+'/minio/health/live', timeout=2).close()
            break
        except OSError: time.sleep(1)
    else: raise RuntimeError('Temporary S3 service did not become healthy')
    subprocess.run(['docker','run','--rm','--read-only','--network',network,'--tmpfs','/artifacts:rw,uid=9999,gid=9999',
        '-e','AWS_ACCESS_KEY_ID=local-test','-e','AWS_SECRET_ACCESS_KEY=local-test-only','-e','AWS_REGION=cn-north-4',
        '-e','S3_ENDPOINT=http://compat-store:9000','--entrypoint','/opt/python/bin/python3',image,'-c',script],check=True)
    subprocess.run(['docker','run','--rm','--read-only','--tmpfs','/tmp:rw,uid=9999',
        '-v',str(root/'downloader')+':/tests:ro','--entrypoint','/opt/python/bin/python3',image,
        '-m','unittest','discover','-s','/tests','-p','test_*.py'],check=True)
finally:
    subprocess.run(['docker','rm','-f',server],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    subprocess.run(['docker','network','rm',network],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
