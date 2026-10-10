import json, hashlib, subprocess, urllib.request
from pathlib import Path
results = {}
for component in ['app', 'web', 'downloader']:
    repository = '192.168.68.1:5000/flink-web/' + component
    local = json.loads(subprocess.check_output(['docker', 'image', 'inspect', repository + ':0.0.4'], text=True))[0]
    expected = [d.split('@', 1)[1] for d in local['RepoDigests'] if d.startswith(repository + '@')]
    request = urllib.request.Request('http://192.168.68.1:5000/v2/flink-web/' + component + '/manifests/0.0.4', headers={'Accept': 'application/vnd.docker.distribution.manifest.v2+json'})
    with urllib.request.urlopen(request, timeout=30) as response:
        body = response.read()
        digest = response.headers['Docker-Content-Digest']
    assert digest in expected, (component, digest, expected)
    assert 'sha256:' + hashlib.sha256(body).hexdigest() == digest
    manifest = json.loads(body)
    assert manifest['config']['digest'] == local['Id']
    results[component] = {'digest': digest, 'imageId': local['Id'], 'platform': local['Os'] + '/' + local['Architecture'], 'user': local['Config']['User']}
Path('test-results/operator-pod-image-digests.json').write_text(json.dumps(results, indent=2), encoding='utf-8')
print(json.dumps(results, indent=2))
