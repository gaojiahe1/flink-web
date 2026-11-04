import { confirmJarName } from './draft-name'
import { encryptWithChallenge } from '../src/shared/api/secretTransport'
import { test, expect, type Page } from '@playwright/test'
async function login(page: Page) {
  await page.goto('/')
  await page.getByLabel('用户名').fill(process.env.E2E_USERNAME!)
  await page.getByLabel('密码', { exact: false }).first().fill(process.env.E2E_PASSWORD!)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('heading', { name: '作业管理', exact: true })).toBeVisible()
}
async function write(page: Page, url: string, data: object) {
  if (url.startsWith('/api/catalog/configs/') && (data as any).kind === 'secret') {
    const target = decodeURIComponent(url.split('/').pop()!)
    const challenge = (
      await (
        await page.request.get('/api/auth/transport-key', {
          params: { purpose: 'config-secret', target },
        })
      ).json()
    ).data
    data = { ...data, value: encryptWithChallenge((data as any).value, challenge) }
  }
  const csrf = (await (await page.request.get('/api/auth/csrf')).json()).data
  const response = await page.request.put(url, { data, headers: { [csrf.headerName]: csrf.token } })
  expect(response.ok(), await response.text()).toBeTruthy()
  return (await response.json()).data
}
test('navigation collapses and YAML edits retain comments while synchronizing fields', async ({
  page,
}) => {
  await login(page)
  await page.getByRole('button', { name: '收起导航' }).click()
  await expect(page.locator('.sidebar')).toHaveCSS('width', '68px')
  await page.getByRole('button', { name: '展开导航' }).click()
  await page.goto('/#/jar')
  await page.getByRole('link', { name: '＋ 新建 JAR 作业' }).click()
  await confirmJarName(page, 'sync-test')
  await page.getByRole('button', { name: 'YAML 编辑', exact: true }).click()
  await expect(page.getByLabel('作业名称', { exact: false })).toBeDisabled()
  await page.getByLabel('默认模板', { exact: false }).selectOption('application-basic')
  const image = 'sync-image-' + Date.now()
  await write(page, '/api/catalog/images/' + image, {
    name: '同步镜像',
    repository: 'registry.test/flink',
    version: '2.0-test',
    flinkVersion: '2.0',
    revision: 0,
  })
  await page.reload()
  await confirmJarName(page, 'sync-test')
  await page.getByRole('button', { name: 'YAML 编辑', exact: true }).click()
  await expect(page.getByLabel('作业名称', { exact: false })).toBeDisabled()
  await page.getByLabel('默认模板', { exact: false }).selectOption('application-basic')
  await page.getByLabel('镜像版本', { exact: false }).selectOption(image)
  const editor = page.getByRole('textbox', { name: 'YAML 编辑器', exact: true })
  await expect(editor).toContainText('name: sync-test')
  await expect(editor).toContainText('flinkVersion: v2_0')
  await expect(editor).toContainText('image: registry.test/flink:2.0-test')
  await editor.fill('# keep this comment\n' + (await editor.innerText()))
  await expect(page.getByLabel('作业名称', { exact: false })).toHaveValue('sync-test')
  await expect(editor).toContainText('# keep this comment')
  await expect(editor).toContainText('name: sync-test')
  await editor.fill('kind: FlinkDeployment\nkind: Duplicate')
  await expect(page.locator('.editor-errors')).toContainText('unique')
})
test('retained Kafka and MySQL source API generates SQL with secret references, not plaintext', async ({
  page,
}) => {
  await login(page)
  const suffix = Date.now(),
    secret = 'sql-secret-' + suffix,
    mysql = 'mysql_' + suffix,
    kafka = 'kafka-' + suffix
  await write(page, '/api/catalog/configs/' + secret, {
    name: '数据库密码',
    kind: 'secret',
    value: 'Do-not-render-this-password',
    revision: 0,
  })
  await write(page, '/api/catalog/datasources/' + kafka, {
    name: kafka,
    connectorType: 'kafka',
    groupId: 'regression',
    tableName: 'kafka_orders',
    brokers: 'broker:9092',
    topic: 'orders',
    format: 'json',
    columns: [{ name: 'id', type: 'BIGINT' }],
    revision: 0,
  })
  await write(page, '/api/catalog/datasources/' + mysql, {
    name: mysql,
    connectorType: 'mysql',
    tableName: 'mysql_orders',
    host: 'mysql.internal',
    port: '3306',
    database: 'orders',
    table: 'orders',
    username: 'reader',
    passwordRef: secret,
    columns: [{ name: 'id', type: 'BIGINT' }],
    revision: 0,
  })
  for (const [id, expected] of [
    [kafka, "'connector' = 'kafka'"],
    [mysql, 'jdbc:mysql://mysql.internal:3306/orders'],
  ]) {
    const response = await page.request.get('/api/sql/datasources/' + id + '/snippet')
    expect(response.ok()).toBe(true)
    const data = (await response.json()).data
    expect(data.sql).toContain(expected)
    expect(JSON.stringify(data)).not.toContain('Do-not-render-this-password')
    if (id === mysql) expect(JSON.stringify(data)).toContain(secret)
  }
  await write(page, '/api/sql/catalogs/' + mysql, {
    name: mysql,
    type: 'jdbc',
    dataSourceId: mysql,
    defaultDatabase: 'orders',
    revision: 0,
  })
  await page.goto('/#/catalogs')
  await page
    .getByLabel('Catalog 目录列表')
    .getByRole('button', { name: new RegExp(mysql) })
    .click()
  await page.getByRole('button', { name: '初始化 SQL', exact: true }).click()
  await expect(page.getByRole('textbox', { name: 'Catalog 初始化 SQL' })).toContainText(
    'jdbc:mysql://mysql.internal:3306',
  )
  await expect(page.locator('body')).not.toContainText('Do-not-render-this-password')
})

test('multiple dependency uploads keep every filename and lock submission until finished', async ({
  page,
}) => {
  await login(page)
  const seen: string[] = []
  await page.route('**/api/artifacts/upload', async (route) => {
    const body = route.request().postDataBuffer()!.toString()
    const filename = body.match(/filename="([^"]+)"/)![1]!
    seen.push(filename)
    await new Promise((resolve) => setTimeout(resolve, 250))
    await route.fulfill({
      json: {
        data: {
          id: filename,
          name: filename,
          filename,
          category: 'dependency',
          uri: 's3://test/dependency/' + filename,
          sha256: 'a'.repeat(64),
        },
      },
    })
  })
  await page.goto('/#/jar')
  await page.getByRole('link', { name: '＋ 新建 JAR 作业' }).click()
  await confirmJarName(page, 'sync-test')
  await page.getByLabel('上传依赖文件', { exact: true }).setInputFiles([
    { name: 'connector.jar', mimeType: 'application/java-archive', buffer: Buffer.from('jar') },
    { name: 'app.properties', mimeType: 'text/plain', buffer: Buffer.from('key=value') },
  ])
  await expect(page.getByRole('button', { name: '保存草稿', exact: true })).toBeDisabled()
  await expect(page.locator('.file-chip').filter({ hasText: 'connector.jar' })).toBeVisible()
  await expect(page.locator('.file-chip').filter({ hasText: 'app.properties' })).toBeVisible()
  expect(seen).toEqual(['connector.jar', 'app.properties'])
  await expect(page.getByRole('button', { name: '保存草稿', exact: true })).toBeEnabled()
  await page.getByLabel('JobManager 内存单位', { exact: true }).selectOption('g')
  await page.getByLabel('JobManager 内存', { exact: true }).fill('2')
  await expect(page.getByLabel('JobManager 内存单位', { exact: true })).toHaveValue('g')
  await expect(page.getByText('PodTemplate 扩展', { exact: true })).toHaveCount(0)
})
test('batch actions show partial failures and restore selection includes snapshot time', async ({
  page,
}) => {
  const jobs = [
    {
      id: 'ui-job-a',
      jobNumber: 1,
      deploymentName: 'orders-stream',
      name: 'orders-stream',
      description: '订单实时处理',
      flinkVersion: '1.20',
      revision: 1,
    },
    {
      id: 'ui-job-b',
      jobNumber: 2,
      deploymentName: 'inventory-stream',
      name: 'inventory-stream',
      description: '库存实时同步',
      flinkVersion: '1.20',
      revision: 1,
    },
  ]
  const states = jobs.map((j) => ({
    name: j.name,
    jobId: j.id,
    managed: true,
    jobState: 'RUNNING',
    desiredState: 'running',
    savepointInfo: {
      lastSavepoint: { location: 's3://state/' + j.id + '/savepoint', timeStamp: 1790150400000 },
    },
    snapshots: [],
  }))
  let refreshes = 0
  const commands: { url: string; body: any }[] = []
  await page.route('**/api/jobs', (route) => route.fulfill({ json: { data: jobs } }))
  await page.route('**/api/jobs/refresh', (route) => {
    refreshes++
    return route.fulfill({ json: { data: { items: states, queriedAt: new Date().toISOString() } } })
  })
  await page.route('**/api/jobs/*/actions/*', (route) => {
    commands.push({ url: route.request().url(), body: route.request().postDataJSON() })
    return route.fulfill({
      json: {
        data: {
          state: route.request().url().includes('ui-job-b') ? 'REJECTED' : 'ACCEPTED',
          message: route.request().url().includes('ui-job-b') ? '测试部分失败' : 'API 已接受',
        },
      },
    })
  })
  await login(page)
  await page.getByLabel('选择当前页', { exact: true }).check()
  await page
    .locator('.jobs-panel .panel-toolbar')
    .getByRole('button', { name: 'Savepoint', exact: true })
    .click()
  await page.getByRole('button', { name: '确认Savepoint', exact: true }).click()
  await expect(page.getByRole('dialog')).toContainText('测试部分失败')
  expect(commands).toHaveLength(2)
  await page.getByRole('button', { name: '关闭', exact: true }).click()
  states[0]!.jobState = 'SUSPENDED'
  states[0]!.desiredState = 'suspended'
  await expect(page.getByRole('button', { name: '↻ 刷新状态', exact: true })).toBeEnabled({
    timeout: 15000,
  })
  await page.getByRole('button', { name: '↻ 刷新状态', exact: true }).click()
  await page
    .getByRole('row')
    .filter({ hasText: 'orders-stream' })
    .getByRole('button', { name: '启动', exact: true })
    .click()
  await expect(page.getByRole('button', { name: '↻ 刷新状态', exact: true })).toBeEnabled({
    timeout: 15000,
  })
  await page.getByLabel('从 Savepoint 恢复', { exact: true }).check()
  await expect.poll(() => refreshes).toBe(3)
  const point = page.getByLabel('orders-stream 恢复快照', { exact: true })
  await expect(point.locator('option').last()).toContainText('2026')
  await point.selectOption('s3://state/ui-job-a/savepoint')
  await page.getByRole('button', { name: '确认启动', exact: true }).click()
  await expect.poll(() => commands.length).toBe(3)
  expect(commands[2]!.url).toContain('/actions/restore')
  expect(commands[2]!.body.savepointPath).toBe('s3://state/ui-job-a/savepoint')
  await page.getByRole('button', { name: '关闭', exact: true }).click()
  await page.getByPlaceholder('搜索作业名称 / ID / 描述').fill('orders')
  await expect(page.locator('.jobs-panel tbody tr')).toHaveCount(1)
  await page.getByRole('button', { name: '重置', exact: true }).click()
  await page.setViewportSize({ width: 1920, height: 1080 })
  await page.screenshot({ path: 'test-results/console-jobs-1920.png', fullPage: true })
})
