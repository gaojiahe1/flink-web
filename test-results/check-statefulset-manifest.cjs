const fs = require('node:fs');
const assert = require('node:assert/strict');
const yaml = require('../frontend/node_modules/yaml');
const docs = yaml.parseAllDocuments(fs.readFileSync('deploy/kubernetes/application.yaml', 'utf8'));
for (const doc of docs) assert.equal(doc.errors.length, 0);
const resources = docs.map(doc => doc.toJSON());
const workloads = resources.filter(doc => ['Deployment', 'StatefulSet'].includes(doc.kind));
assert.equal(workloads.length, 1);
const sts = workloads[0];
assert.equal(sts.kind, 'StatefulSet');
assert.equal(sts.apiVersion, 'apps/v1');
assert.equal(sts.metadata.name, 'flink-web');
assert.equal(sts.spec.replicas, 1);
assert.equal(sts.spec.updateStrategy.type, 'RollingUpdate');
assert.equal(sts.spec.strategy, undefined);
assert.deepEqual(sts.spec.selector.matchLabels, sts.spec.template.metadata.labels);
assert.equal(sts.spec.volumeClaimTemplates, undefined);
const pod = sts.spec.template.spec;
assert.equal(pod.containers.length, 2);
const backend = pod.containers.find(c => c.name === 'backend');
const frontend = pod.containers.find(c => c.name === 'frontend');
assert.equal(backend.env.find(e => e.name === 'SERVER_PORT').value, '8081');
assert.equal(frontend.env.find(e => e.name === 'FLINK_WEB_PROXY_BACKEND_HOST').value, '127.0.0.1');
assert.equal(frontend.env.find(e => e.name === 'FLINK_WEB_PROXY_BACKEND_PORT').value, '8081');
assert.equal(new Set(pod.containers.flatMap(c => c.ports.map(p => p.containerPort))).size, 2);
assert.equal(pod.automountServiceAccountToken, false);
const token = pod.volumes.find(v => v.name === 'kube-api-access');
assert.equal(token.projected.sources[0].serviceAccountToken.path, 'token');
assert.ok(backend.volumeMounts.some(m => m.name === token.name));
assert.ok(!(frontend.volumeMounts || []).some(m => m.name === token.name));
assert.equal(pod.volumes.find(v => v.name === 'data').persistentVolumeClaim.claimName, 'flink-web-data');
const services = resources.filter(doc => doc.kind === 'Service');
const headless = services.find(doc => doc.metadata.name === sts.spec.serviceName);
assert.equal(headless.spec.clusterIP, 'None');
const nodeport = services.find(doc => doc.metadata.name === 'flink-web');
assert.equal(nodeport.spec.ports[0].nodePort, 30080);
for (const service of services) {
  assert.deepEqual(service.spec.selector, sts.spec.template.metadata.labels);
  for (const port of service.spec.ports) {
    assert.ok(pod.containers.some(c => c.ports.some(p => p.name === port.targetPort)));
  }
}
for (const container of pod.containers) {
  assert.equal(container.imagePullPolicy, 'Always');
  assert.ok(container.ports.some(port => port.name === container.readinessProbe.httpGet.port));
}
console.log('PASS: one StatefulSet, two containers, distinct ports, service selectors, original PVC and backend-only API token');
