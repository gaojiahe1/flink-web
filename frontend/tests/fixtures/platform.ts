import { test, expect, type Page } from '@playwright/test'
import { stringify } from 'yaml'
const unexpectedByPage = new WeakMap<Page, string[]>()
test.afterEach(({ page }) => {
  expect(unexpectedByPage.get(page) || [], 'Undeclared fixture requests').toEqual([])
})
const runtime = {
  imageId: 'flink',
  runnerArtifactId: '',
  workspaceId: 'w',
  parallelism: 1,
  slots: 1,
  jmCpu: 1,
  tmCpu: 1,
  jmMemory: '2g',
  tmMemory: '2g',
  flinkConfiguration: {},
  podTemplate: {},
  dependencyArtifactIds: [],
}
const sql =
  "CREATE TABLE source_events (id BIGINT) WITH ('connector'='datagen');\nSELECT id + 2 FROM source_events;"
const yaml = stringify({
  apiVersion: 'flink.apache.org/v1beta1',
  kind: 'FlinkDeployment',
  metadata: { name: 'flink-0000001', labels: { 'flink-web/editor': 'jar-v1' } },
  spec: {
    image: 'test/flink:1.20.5',
    flinkVersion: 'v1_20',
    serviceAccount: 'team-flink',
    flinkConfiguration: {},
    jobManager: { resource: { cpu: 1, memory: '2g' } },
    taskManager: { resource: { cpu: 1, memory: '2g' } },
    job: { jarURI: 'local:///opt/flink/usrlib/a.jar', parallelism: 1, upgradeMode: 'stateless' },
  },
})
export async function platformFixture(page: Page, role = 'ROLE_ADMIN') {
  const errors: string[] = [],
    calls: string[] = [],
    writes: any[] = [],
    sqlWrites: { method: string; path: string; body: unknown }[] = [],
    unexpectedRequests: string[] = []
  unexpectedByPage.set(page, unexpectedRequests)
  const job = {
    id: 'jar',
    jobNumber: 1,
    deploymentName: 'flink-0000001',
    name: 'test-job',
    revision: 1,
    flinkVersion: '1.20.5',
    description: '',
    yaml,
  }
  const draft = { id: 's', name: 'sql-job', description: '', revision: 2, sql, runtime }
  const session = {
    id: 'session',
    name: 'sql-check',
    revision: 1,
    deploymentName: 'fw-session',
    validation: true,
    desiredState: 'stopped',
    state: 'STOPPED',
    message: 'Session 资源不存在',
    runtime,
  }
  const connections: any[] = [
    {
      id: 'db',
      name: '业务库',
      connectorType: 'mysql',
      host: 'mysql',
      port: '3306',
      database: 'business',
      username: 'reader',
      passwordRef: 'password',
      tableName: 'orders',
      table: 'orders',
      columns: [{ name: 'id', type: 'BIGINT' }],
      revision: 1,
    },
  ]
  page.on('pageerror', (e) => errors.push(e.message))
  await page.route('**/api/**', async (route) => {
    const req = route.request(),
      path = new URL(req.url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    calls.push(req.method() + ' ' + path)
    const methods: Record<string, string[]> = {
      '/api/auth/me': ['GET'],
      '/api/auth/csrf': ['GET'],
      '/api/system': ['GET'],
      '/api/system/operator': ['GET'],
      '/api/system/downloader': ['GET'],
      '/api/platform/network/test': ['POST'],
      '/api/users': ['GET'],
      '/api/platform/groups': ['GET'],
      '/api/ai/models': ['GET'],
      '/api/catalog/images': ['GET'],
      '/api/catalog/templates': ['GET'],
      '/api/catalog/configs': ['GET'],
      '/api/catalog/datasources': ['GET'],
      '/api/sql/catalogs': ['GET'],
      '/api/sql/jobs': ['GET'],
      '/api/sql/jobs/s': ['GET', 'PUT'],
      '/api/sql/jobs/s/versions': ['GET'],
      '/api/jobs': ['GET'],
      '/api/development/jar/jobs': ['GET'],
      '/api/development/links': ['GET'],
      '/api/development/jar/jobs/jar/open': ['POST'],
      '/api/jobs/jar': ['GET'],
      '/api/jobs/jar/versions': ['GET'],
      '/api/jobs/refresh': ['POST'],
      '/api/jobs/render-form': ['POST'],
      '/api/sessions': ['GET'],
      '/api/sessions/refresh': ['POST'],
      '/api/sessions/session/status': ['GET'],
      '/api/platform/job-access': ['GET'],
      '/api/jobs/jar/access': ['GET'],
      '/api/sql/jobs/s/access': ['GET'],
      '/api/sessions/session/access': ['GET'],
      '/api/artifacts/defaults': ['GET'],
      '/api/artifacts/objects': ['GET'],
      '/api/audit': ['GET'],
    }
    const allowed =
      methods[path] || (/^\/api\/catalog\/datasources\/[a-zA-Z0-9_-]+$/.test(path) ? ['PUT'] : [])
    if (!allowed.includes(req.method())) {
      unexpectedRequests.push(req.method() + ' ' + path)
      return route.fulfill({
        status: 501,
        json: { error: { code: 'UNDECLARED_FIXTURE_REQUEST', message: req.method() + ' ' + path } },
      })
    }
    if (path === '/api/sql/jobs/s' && req.method() === 'PUT') {
      const body = req.postDataJSON()
      writes.push(body)
      sqlWrites.push({ method: req.method(), path, body })
      Object.assign(draft, body, { revision: draft.revision + 1 })
    }
    let data: any = []
    if (path === '/api/auth/me') data = { username: 'admin', roles: [role] }
    else if (path === '/api/auth/csrf') data = { headerName: 'X-CSRF-TOKEN', token: 'test' }
    else if (path === '/api/system')
      data = { namespace: 'team', version: '0.0.9', storageType: 'local' }
    else if (path === '/api/system/operator')
      data = {
        found: false,
        namespace: 'team',
        deployment: 'flink-operator',
        queriedAt: new Date().toISOString(),
        compatibility: { known: false, supported: [] },
      }
    else if (path === '/api/system/downloader')
      data = { image: 'registry/downloader:0.0.9', revision: 1 }
    else if (path === '/api/platform/network/test')
      data = { connected: true, message: '连接成功', elapsedMs: 1 }
    else if (path === '/api/users')
      data = [{ id: 'admin', name: '管理员', role: 'ADMIN', enabled: true, revision: 1 }]
    else if (path === '/api/platform/groups')
      data = [{ id: 'ops', name: '运维组', members: ['admin'], revision: 1 }]
    else if (path === '/api/ai/models')
      data = [
        {
          id: 'private-ai',
          name: '私有 SQL 助手',
          model: 'test-model',
          baseUrl: 'https://model.example.com/v1',
          enabled: true,
          purposes: ['sql', 'diagnosis'],
          revision: 1,
          timeoutSeconds: 90,
          maxTokens: 4096,
          apiKeySet: true,
          external: false,
          privateNetwork: false,
        },
      ]
    else if (path === '/api/catalog/images')
      data = [
        {
          id: 'flink',
          name: 'Flink',
          repository: 'test/flink',
          version: '1.20.5',
          flinkVersion: '1.20.5',
          revision: 1,
        },
        {
          id: 'old',
          name: '旧镜像',
          repository: 'test/flink',
          version: '1.20.3',
          flinkVersion: '1.20.3',
          revision: 2,
        },
      ]
    else if (path === '/api/catalog/templates')
      data = [
        { id: 'basic', name: '基础模板', description: '独立作业基础模板', revision: 1, yaml: '' },
        {
          id: 'pod',
          name: 'Pod 模板',
          description: '增加 Pod 标签和环境变量',
          revision: 1,
          yaml: '',
        },
      ]
    else if (path === '/api/catalog/configs')
      data = [{ id: 'password', name: '数据库密码', kind: 'secret', revision: 1 }]
    else if (path === '/api/catalog/datasources') data = connections
    else if (path.startsWith('/api/catalog/datasources/')) {
      writes.push(req.postDataJSON())
      data = { ...req.postDataJSON(), revision: 1 }
      connections.push(data)
    } else if (path === '/api/sql/catalogs')
      data = [
        {
          id: 'business',
          name: '业务目录',
          type: 'jdbc',
          defaultDatabase: 'business',
          dataSourceId: 'db',
          revision: 1,
          dependencyArtifactIds: [],
        },
      ]
    else if (path === '/api/development/links') data = { jar: { type: 'jar' } }
    else if (path === '/api/sql/jobs') data = [draft]
    else if (path.endsWith('/versions'))
      data = [
        {
          id: 'v1',
          sql: sql.replace('+ 2', '+ 1'),
          actor: 'admin',
          createdAt: '2026-10-25T06:00:00Z',
        },
      ]
    else if (path === '/api/sql/jobs/s') data = draft
    else if (path === '/api/jobs' || path === '/api/development/jar/jobs') data = [job]
    else if (path === '/api/jobs/jar' || path === '/api/development/jar/jobs/jar/open') data = job
    else if (path === '/api/jobs/refresh') data = { items: [], queriedAt: new Date().toISOString() }
    else if (path === '/api/jobs/render-form') data = { yaml, flinkVersion: '1.20.5' }
    else if (path === '/api/sessions' || path === '/api/sessions/refresh') data = [session]
    else if (path.endsWith('/status')) data = session
    else if (path === '/api/platform/job-access')
      data = { jar: { canEdit: true, canOperate: true } }
    else if (path.endsWith('/access'))
      data = { canEdit: role === 'ROLE_ADMIN', canOperate: role === 'ROLE_ADMIN' }
    else if (path === '/api/artifacts/defaults') data = { bucket: 'test', rootPrefix: 'artifacts/' }
    else if (path === '/api/artifacts/objects')
      data = {
        bucket: 'test',
        managedPrefix: 'artifacts/',
        rootPrefix: 'artifacts/',
        prefix: 'artifacts/',
        objects: [],
        directories: [],
      }
    else if (path === '/api/audit')
      data = [
        {
          id: 'a',
          time: '2026-10-25T06:00:00Z',
          action: 'SQL_PUBLISH',
          actor: 'admin',
          target: 's',
          outcome: 'ACCEPTED',
        },
        {
          id: 'b',
          time: '2026-10-25T05:00:00Z',
          action: 'FUTURE_ACTION',
          actor: 'admin',
          target: 's',
          outcome: 'NEW_STATE',
        },
      ]
    await route.fulfill({ json: { data, error: null } })
  })
  return { errors, calls, writes, sqlWrites, unexpectedRequests }
}
