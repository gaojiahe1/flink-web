import { test, expect, type Page } from '@playwright/test'
import { mergeDefinitions } from '../src/modules/ai/utils/definitions'
const pageErrors = new WeakMap<Page, string[]>()
test.afterEach(async ({ page }) => {
  expect(pageErrors.get(page) || []).toEqual([])
})

const model = {
  id: 'private',
  name: '团队模型',
  model: 'test-model',
  revision: 1,
  enabled: true,
  external: false,
  privateNetwork: true,
  baseUrl: 'http://model.test/v1',
  purposes: ['sql', 'definition', 'diagnosis'],
  timeoutSeconds: 90,
  maxTokens: 4096,
}
const sql =
  "CREATE TABLE source_events (id STRING) WITH ('connector'='datagen'); CREATE TABLE sink_events (id STRING) WITH ('connector'='print'); INSERT INTO sink_events SELECT id FROM source_events;"
const runtime = {
  imageId: 'flink',
  runnerArtifactId: '',
  workspaceId: 'sql-workspace',
  parallelism: 1,
  slots: 2,
  dependencyArtifactIds: [],
  flinkConfiguration: {},
  podTemplate: {},
}
const draft = { id: 'sql-draft', name: 'sql-draft', description: '', sql, runtime, revision: 1 }
const job = {
  id: 'job-one',
  name: 'orders',
  description: '',
  jobNumber: 1,
  deploymentName: 'flink-0000001',
  flinkVersion: '1.20.5',
  revision: 1,
  yaml: '',
}

async function fixture(page: Page, role = 'ROLE_ADMIN') {
  const failures: string[] = []
  pageErrors.set(page, failures)
  page.on('pageerror', (error) => failures.push(error.message))
  const submissions: any[] = [],
    saved: any[] = [],
    operations: string[] = []
  let models: any[] = [model],
    active: any = null,
    pending = false
  await page.route('**/api/**', async (route) => {
    const req = route.request(),
      url = new URL(req.url()),
      path = url.pathname
    if (!path.startsWith('/api/')) return route.continue()
    let data: any = []
    if (path === '/api/auth/me') data = { username: 'admin', roles: [role] }
    else if (path === '/api/auth/csrf') data = { headerName: 'X-CSRF-TOKEN', token: 'test' }
    else if (path === '/api/system') data = { namespace: 'team', capabilities: [] }
    else if (path === '/api/ai/models' && req.method() === 'GET') data = models
    else if (path.startsWith('/api/ai/models/') && req.method() === 'PUT') {
      const value = req.postDataJSON()
      saved.push(value)
      data = { ...value, revision: 1 }
      models.push(data)
    } else if (path.endsWith('/test')) data = { message: '模型连接和 JSON 响应正常' }
    else if (path === '/api/ai/tasks' && req.method() === 'POST') {
      const value = req.postDataJSON()
      submissions.push(value)
      active = {
        id: value.requestId,
        state: 'QUEUED',
        model: 'test-model',
        kind: value.kind,
        createdAt: new Date().toISOString(),
      }
      data = active
    } else if (path.endsWith('/cancel')) {
      active = { ...active, state: 'CANCELED' }
      data = active
    } else if (path.startsWith('/api/ai/tasks/'))
      data = pending
        ? active
        : {
            ...active,
            state: 'SUCCEEDED',
            result: {
              summary: '使用窗口聚合统计支付金额',
              steps: ['先检查源表网络', '确认配置后重新检查 SQL'],
              evidence: ['当前错误包含 Metaspace'],
              risks: ['未执行实际作业'],
              unknowns: ['仍需确认上游去重'],
              sql: sql + '\n-- AI 建议',
              env: [],
              flinkConfiguration: {},
            },
          }
    else if (path === '/api/ai/tasks') data = []
    else if (path === '/api/ai/definitions/compile')
      data = {
        valid: false,
        pending: [{ table: 'source_events', message: '缺少 brokers' }],
        sql: '',
        env: [],
      }
    else if (path === '/api/catalog/images')
      data = [
        {
          id: 'flink',
          repository: 'registry.test/flink',
          version: '1.20.5',
          flinkVersion: '1.20.5',
        },
      ]
    else if (path === '/api/catalog/datasources')
      data = [{ id: 'source', name: '测试数据', connectorType: 'datagen' }]
    else if (path === '/api/artifacts/defaults')
      data = { bucket: 'files', rootPrefix: 'artifacts/', stateRoot: 's3://state' }
    else if (path === '/api/artifacts/objects')
      data = { directories: [], objects: [], nextToken: null }
    else if (path === '/api/sql/jobs') data = [draft]
    else if (path === '/api/sql/jobs/sql-draft') data = draft
    else if (path === '/api/sql/validate')
      data = {
        valid: true,
        level: 'PARSER',
        statementCount: 3,
        insertCount: 1,
        message: '检查通过',
      }
    else if (path === '/api/jobs') data = [job]
    else if (path === '/api/jobs/refresh')
      data = {
        items: [
          {
            name: job.deploymentName,
            jobId: job.id,
            managed: true,
            application: true,
            healthState: 'ERROR',
            error: 'OutOfMemoryError: Metaspace',
            jobState: 'FAILED',
            yaml: '',
            snapshots: [],
          },
        ],
        queriedAt: new Date().toISOString(),
        stopProgress: {},
      }
    else if (path === '/api/runtime/jobs')
      data = [
        {
          name: job.deploymentName,
          jobId: job.id,
          managed: true,
          application: true,
          healthState: 'ERROR',
          error: 'OutOfMemoryError: Metaspace',
          jobState: 'FAILED',
          yaml: '',
          snapshots: [],
        },
      ]
    else if (path === '/api/development/links')
      data = { [job.id]: { type: 'sql', sqlId: 'sql-draft' } }
    else if (/\/api\/jobs\/[^/]+\/access/.test(path)) data = { canEdit: true, canOperate: true }
    else if (path.includes('/actions/')) {
      operations.push(path)
      data = {}
    }
    return route.fulfill({ json: { data, error: null } })
  })
  return {
    submissions,
    saved,
    operations,
    setPending: (value: boolean) => {
      pending = value
    },
    setModels: (value: any[]) => {
      models = value
    },
  }
}

test('模型设置可添加模型并明确安全与用途配置', async ({ page }) => {
  const f = await fixture(page)
  await page.goto('/#/settings/models')
  await page.getByLabel('配置标识', { exact: true }).fill('new-model')
  await page.getByLabel('显示名称', { exact: true }).fill('新增测试模型')
  await page.getByLabel('Base URL', { exact: false }).fill('https://model.test/v1')
  await page.getByLabel('实际模型标识', { exact: true }).fill('provider-model')
  await page.getByRole('button', { name: '保存模型', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('配置已保存')
  expect(f.saved[0]).toMatchObject({
    id: 'new-model',
    external: true,
    purposes: ['sql', 'definition', 'diagnosis'],
  })
  await page.getByRole('button', { name: '测试连接' }).first().click()
  await expect(page.getByRole('status')).toContainText('JSON 响应正常')
})

test('继续补充数据定义保留用户地址与字段，同时合入新字段', () => {
  const value = mergeDefinitions(
    [{ tableName: 'orders', brokers: 'my-broker:9092', columns: [{ name: 'id', type: 'STRING' }] }],
    [
      {
        tableName: 'orders',
        brokers: 'invented:9092',
        columns: [
          { name: 'id', type: 'BIGINT' },
          { name: 'amount', type: 'DECIMAL(18,2)' },
        ],
      },
    ],
  )
  expect(value[0]).toMatchObject({
    brokers: 'my-broker:9092',
    columns: [
      { name: 'id', type: 'STRING' },
      { name: 'amount', type: 'DECIMAL(18,2)' },
    ],
  })
})

test('SQL 助手生成差异、显式应用，随后继续沿用 SQL 检查', async ({ page }) => {
  const f = await fixture(page)
  await page.goto('/#/sql?draft=sql-draft')
  await page.getByRole('button', { name: 'AI 助手', exact: true }).click()
  const panel = page.locator('.ai-sql-panel')
  await panel.getByLabel('测试数据', { exact: false }).check()
  await panel.getByLabel('重复数据', { exact: true }).selectOption('upstream')
  await panel.getByLabel('描述业务需求', { exact: true }).fill('按类目统计支付金额')
  await panel.getByRole('button', { name: '确认并生成 SQL' }).click()
  await expect(panel.getByText('使用窗口聚合统计支付金额', { exact: true })).toBeVisible()
  await expect(panel.locator('.ai-diff')).toContainText('AI 建议')
  await panel.getByRole('button', { name: '应用到编辑器' }).click()
  await expect(page.locator('.sql-center .cm-content').first()).toContainText('AI 建议')
  expect(f.submissions[0]).toMatchObject({
    kind: 'sql',
    mode: 'generate',
    sourceIds: ['source'],
    flinkVersion: '1.20.5',
  })
  expect(f.operations).toEqual([])
})

test('SQL 编辑后旧建议禁止应用；数据定义缺参数明确提示', async ({ page }) => {
  await fixture(page)
  await page.goto('/#/sql?draft=sql-draft')
  await page.getByRole('button', { name: 'AI 助手', exact: true }).click()
  const panel = page.locator('.ai-sql-panel')
  await panel.getByLabel('重复数据', { exact: true }).selectOption('keep')
  await panel.getByLabel('描述业务需求', { exact: true }).fill('原需求')
  await panel.getByRole('button', { name: '确认并生成 SQL' }).click()
  await expect(panel.getByRole('button', { name: '应用到编辑器' })).toBeEnabled()
  await page
    .locator('.sql-center .cm-content')
    .first()
    .fill(sql + '\n-- 用户新编辑')
  await expect(panel.getByRole('button', { name: '应用到编辑器' })).toBeDisabled()
  await panel.getByRole('button', { name: '数据定义', exact: true }).last().click()
  await panel.getByRole('button', { name: '添加输入表' }).click()
  await expect(panel.getByRole('textbox', { name: '表1字段1名称', exact: true })).toHaveValue('id')
  await panel.getByRole('button', { name: '添加字段', exact: true }).click()
  await panel.getByRole('textbox', { name: '表1字段2名称', exact: true }).fill('amount')
  await panel.getByRole('textbox', { name: '表1字段2类型', exact: true }).fill('DECIMAL(18,2)')
  await panel.getByRole('button', { name: '编译定义并检查待补项' }).click()
  await expect(panel).toContainText('缺少 brokers')
  await expect(page.locator('.sql-center .cm-content').first()).toContainText('用户新编辑')
})

test('作业报错按钮主动分析并展示解决步骤，不执行运行操作', async ({ page }) => {
  const f = await fixture(page)
  await page.goto('/#/jobs')
  await page.getByRole('button', { name: 'AI 分析', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: 'AI 分析：orders' })
  await expect(drawer).toContainText('Metaspace')
  await drawer.getByRole('button', { name: '开始 AI 分析' }).click()
  await expect(drawer).toContainText('先检查源表网络')
  expect(f.submissions[0]).toMatchObject({ kind: 'diagnosis', jobId: 'job-one', includeLogs: true })
  expect(f.operations).toEqual([])
  await drawer.getByRole('button', { name: '关闭 AI 分析' }).click()
  await expect(drawer).toBeHidden()
})

test('外部模型必须明确同意，任务取消后不接受旧完成结果', async ({ page }) => {
  const f = await fixture(page)
  f.setModels([{ ...model, external: true }])
  f.setPending(true)
  await page.goto('/#/sql?draft=sql-draft')
  await page.getByRole('button', { name: 'AI 助手', exact: true }).click()
  const panel = page.locator('.ai-sql-panel')
  await panel.getByLabel('重复数据', { exact: true }).selectOption('keep')
  await panel.getByLabel('描述业务需求', { exact: true }).fill('测试取消')
  await expect(panel.getByRole('button', { name: '确认并生成 SQL' })).toBeDisabled()
  await panel.getByLabel('允许将本次脱敏', { exact: false }).check()
  await panel.getByRole('button', { name: '确认并生成 SQL' }).click()
  await panel.getByRole('button', { name: '取消任务' }).click()
  await expect(panel).toContainText('已取消')
  expect(f.submissions).toHaveLength(1)
})

test('模型与 AI 侧栏窄屏无页面横向溢出', async ({ page }) => {
  await fixture(page)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/#/settings/models')
  await expect(page.getByRole('heading', { name: '模型服务', exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
  await page.getByRole('link', { name: 'SQL 开发', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'SQL 开发', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'AI 助手', exact: true }).click()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
})

test('只读角色不能显示 SQL 助手或管理模型', async ({ page }) => {
  await fixture(page, 'ROLE_VIEWER')
  await page.goto('/#/sql?draft=sql-draft')
  await expect(page.getByRole('heading', { name: 'SQL 开发', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'AI 助手', exact: true })).toHaveCount(0)
  await page.goto('/#/settings/models')
  await expect(page.getByText('仅管理员可维护模型服务。', { exact: true })).toBeVisible()
})
