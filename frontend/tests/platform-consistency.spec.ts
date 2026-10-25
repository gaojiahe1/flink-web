import { test, expect, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { stringify } from 'yaml'
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
async function fixture(page: Page, role = 'ROLE_ADMIN') {
  const errors: string[] = [],
    calls: string[] = [],
    writes: any[] = []
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
    let data: any = []
    if (path === '/api/auth/me') data = { username: 'admin', roles: [role] }
    else if (path === '/api/auth/csrf') data = { headerName: 'X-CSRF-TOKEN', token: 'test' }
    else if (path === '/api/system')
      data = { namespace: 'team', version: '0.0.8', storageType: 'local' }
    else if (path === '/api/system/operator')
      data = { found: false, compatibility: { known: false, supported: [] } }
    else if (path === '/api/system/downloader')
      data = { image: 'registry/downloader:0.0.8', revision: 1 }
    else if (path === '/api/platform/network/test')
      data = { connected: true, message: '连接成功', elapsedMs: 1 }
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
    else if (path === '/api/jobs/jar') data = job
    else if (path === '/api/jobs/refresh') data = { items: [], queriedAt: new Date().toISOString() }
    else if (path === '/api/jobs/render-form') data = { yaml, flinkVersion: '1.20.5' }
    else if (path === '/api/sessions' || path === '/api/sessions/refresh') data = [session]
    else if (path.endsWith('/status')) data = session
    else if (path === '/api/platform/job-access')
      data = { jar: { canEdit: true, canOperate: true } }
    else if (path.endsWith('/access'))
      data = { canEdit: role === 'ROLE_ADMIN', canOperate: role === 'ROLE_ADMIN' }
    else if (path === '/api/artifacts/defaults') data = { bucket: 'test', rootPrefix: 'artifacts/' }
    else if (path === '/api/artifacts/objects') data = { objects: [], directories: [] }
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
  return { errors, calls, writes }
}

test('版本显示与发布镜像标签一致，审计中文动作可搜索并保留未知码', async ({ page }) => {
  const f = await fixture(page)
  const pkg = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8'))
  const manifest = readFileSync(
    new URL('../../deploy/kubernetes/application.yaml', import.meta.url),
    'utf8',
  )
  expect(manifest).toContain('flink-web/app:' + pkg.version)
  expect(manifest).toContain('flink-web/web:' + pkg.version)
  const system = readFileSync(
    new URL(
      '../../backend/src/main/java/com/flinkweb/service/system/impl/SystemServiceImpl.java',
      import.meta.url,
    ),
    'utf8',
  )
  expect(system).toContain('"' + pkg.version + '"')
  await page.goto('/#/audit')
  await expect(page.locator('.version-tag')).toHaveText('v' + pkg.version)
  await expect(page.getByRole('cell', { name: '发布 SQL 作业', exact: true })).toHaveAttribute(
    'title',
    'SQL_PUBLISH',
  )
  await expect(page.getByRole('cell', { name: '已受理', exact: true })).toBeVisible()
  await expect(page.getByRole('cell', { name: 'FUTURE_ACTION', exact: true })).toBeVisible()
  await page.getByPlaceholder('搜索记录').fill('发布 SQL')
  await expect(page.locator('tbody tr')).toHaveCount(1)
  expect(f.errors).toEqual([])
})

test('模板、镜像、Session、作业使用相同列表行高与控件高度', async ({ page }) => {
  const f = await fixture(page)
  for (const route of ['templates', 'images', 'sessions', 'jobs']) {
    await page.goto('/#/' + route)
    await expect(
      page.getByRole('heading', {
        name: (
          {
            templates: '默认模板',
            images: '镜像管理',
            sessions: 'Session 集群',
            jobs: '作业管理',
          } as Record<string, string>
        )[route],
        exact: true,
      }),
    ).toBeVisible()
    await expect(page.locator('.table-wrap tbody tr').first()).toBeVisible()
    const heights = await page
      .locator('.table-wrap tbody tr')
      .evaluateAll((rows) => rows.map((r) => r.getBoundingClientRect().height))
    for (const height of heights) expect(height, route).toBeCloseTo(64, 0)
    const controls = await page
      .locator(
        'input:visible:not([type=checkbox]):not([type=radio]), select:visible, .button:visible',
      )
      .evaluateAll((items) => items.map((i) => i.getBoundingClientRect().height))
    for (const height of controls) expect(height, route).toBeCloseTo(32, 0)
  }
  expect(f.errors).toEqual([])
})

test('SQL 历史操作并排、语法和修改高亮，短屏可操作；SQL 无数据源插入或查询', async ({ page }) => {
  const f = await fixture(page)
  await page.goto('/#/sql')
  await expect(page.locator('.sql-center > .code-editor .cm-content')).toBeVisible()
  await expect(page.getByRole('button', { name: '数据源', exact: true })).toHaveCount(0)
  expect(f.calls.some((c) => c.includes('/api/catalog/datasources'))).toBe(false)
  await page.getByRole('button', { name: '版本历史', exact: true }).click()
  const select = page.getByLabel('SQL 历史版本'),
    restore = page.getByRole('button', { name: '恢复此版本' })
  for (const viewport of [
    { width: 1920, height: 1080 },
    { width: 1280, height: 720 },
  ]) {
    await page.setViewportSize(viewport)
    await expect(restore).toBeVisible()
    const a = await select.boundingBox(),
      b = await restore.boundingBox()
    expect(Math.abs(a!.y - b!.y)).toBeLessThan(2)
    expect(a!.x + a!.width).toBeLessThanOrEqual(b!.x)
  }
  await expect(page.locator('.sql-history-diff .cm-changedText').first()).toBeVisible()
  const token = page
    .locator('.sql-history-diff .cm-content span')
    .filter({ hasText: 'CREATE' })
    .first()
  await expect(token).toBeVisible()
  expect(await token.evaluate((e) => getComputedStyle(e).color)).not.toBe('rgb(36, 50, 72)')
  expect(f.errors).toEqual([])
})

test('SQL 与 JAR 草稿栏使用同一标题、计数及草稿行格式', async ({ page }) => {
  const f = await fixture(page)
  const dimensions: number[][] = []
  for (const route of ['sql', 'jar']) {
    await page.goto('/#/' + route)
    const head = page.locator('.draft-list-heading')
    await expect(head).toContainText(route.toUpperCase() + ' 草稿')
    await expect(head.locator('.draft-count')).toHaveText('1')
    const row = page.locator(route === 'sql' ? '.sql-file' : '.jar-draft-open').first()
    await expect(row).toBeVisible()
    dimensions.push([(await head.boundingBox())!.height, (await row.boundingBox())!.height])
    await head.getByRole('button', { name: /草稿栏$/ }).click()
    await expect(head.locator('.draft-count')).toHaveCount(0)
    await head.getByRole('button', { name: /草稿栏$/ }).click()
    await expect(row).toBeVisible()
  }
  expect(dimensions[0]).toEqual(dimensions[1])
  expect(f.errors).toEqual([])
})

test('旧数据源入口转到 Catalog，JDBC 连接仅在 Catalog 管理并支持嵌套保存', async ({ page }) => {
  const f = await fixture(page)
  await page.goto('/#/datasources')
  await expect(page).toHaveURL(/#\/catalogs$/)
  await expect(page.getByText('网络端口验证', { exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: '＋ 注册 Catalog' }).click()
  const catalog = page.getByRole('dialog', { name: 'Catalog 配置' })
  await catalog.getByLabel('Catalog 标识', { exact: false }).fill('kept_draft')
  await catalog.getByRole('button', { name: '管理 JDBC 连接', exact: true }).click()
  const connection = page.getByRole('dialog', { name: 'Catalog JDBC 连接' })
  await connection.getByRole('button', { name: '新增 JDBC 连接', exact: true }).click()
  for (const [name, value] of Object.entries({
    连接标识: 'new_db',
    连接名称: '新连接',
    主机: 'mysql.team.svc',
    数据库: 'business',
    用户名: 'reader',
  }))
    await connection.getByLabel(name, { exact: true }).fill(value)
  await connection.getByLabel('密码配置引用', { exact: false }).selectOption('password')
  await connection.getByRole('button', { name: '保存连接', exact: true }).click()
  await expect(connection).toContainText('连接已保存')
  await connection.getByRole('button', { name: '返回 Catalog', exact: true }).click()
  await expect(catalog.getByLabel('Catalog 标识', { exact: false })).toHaveValue('kept_draft')
  await expect(catalog.getByLabel('JDBC 连接', { exact: false })).toContainText('新连接')
  expect(f.writes[0]).toMatchObject({ id: 'new_db', passwordRef: 'password' })
  expect(f.errors).toEqual([])
})

test('网络诊断迁至系统设置，手动执行且只向管理员开放', async ({ page }) => {
  const f = await fixture(page)
  await page.goto('/#/settings')
  await page.locator('summary').filter({ hasText: '网络端口验证' }).click()
  expect(f.calls.filter((c) => c.includes('/network/test'))).toHaveLength(0)
  await page.getByLabel('诊断主机').fill('mysql.team.svc')
  await page.getByRole('button', { name: '验证端口', exact: true }).click()
  await expect(page.getByRole('status').filter({ hasText: '连接成功' })).toBeVisible()
  expect(f.calls.filter((c) => c.includes('/network/test'))).toHaveLength(1)
  expect(f.errors).toEqual([])
})

test('只读角色没有 Catalog 连接管理或网络诊断入口', async ({ page }) => {
  await fixture(page, 'ROLE_VIEWER')
  await page.goto('/#/catalogs')
  await expect(page.getByRole('heading', { name: 'Catalog 工作台', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '管理 JDBC 连接', exact: true })).toHaveCount(0)
  await page.goto('/#/settings')
  await expect(page.getByText('网络端口验证', { exact: true })).toHaveCount(0)
})

test('JAR、SQL、Catalog 和系统设置的表单在短屏统一控件高度', async ({ page }) => {
  const f = await fixture(page)
  await page.setViewportSize({ width: 900, height: 650 })
  for (const path of ['jar/jar/edit', 'sql', 'catalogs', 'settings']) {
    await page.goto('/#/' + path)
    if (path.startsWith('jar'))
      await expect(page.getByLabel('作业名称', { exact: false }).first()).toHaveValue('test-job')
    if (path === 'sql') {
      await expect(page.getByRole('textbox', { name: 'SQL 编辑器', exact: true })).toBeVisible()
      await page.getByRole('button', { name: '部署配置', exact: true }).click()
    }
    if (path === 'catalogs')
      await page.getByRole('button', { name: '编辑配置', exact: true }).click()
    if (path === 'settings') {
      await expect(page.getByRole('heading', { name: '系统设置', exact: true })).toBeVisible()
      await page.locator('summary').filter({ hasText: '网络端口验证' }).click()
    }
    const controls = page.locator(
      'input:visible:not([type=checkbox]):not([type=radio]):not([type=file]), select:visible',
    )
    expect(await controls.count()).toBeGreaterThan(0)
    for (const height of await controls.evaluateAll((items) =>
      items.map((item) => item.getBoundingClientRect().height),
    ))
      expect(height, path).toBeCloseTo(32, 0)
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
      path,
    ).toBe(true)
  }
  expect(f.errors).toEqual([])
})
