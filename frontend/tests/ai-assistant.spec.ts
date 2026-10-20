import { test, expect, type Page } from '@playwright/test'
import { mergeDefinitions, validateDefinitions } from '../src/modules/ai/utils/definitions'
import { wizardDataIssues } from '../src/modules/ai/utils/wizard'
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
  const jobConversation: any = {
    id: 'job-conversation',
    revision: 0,
    contentVersion: 0,
    summary: '',
    unknowns: [],
    messages: [],
    compressedMessages: 0,
    canEdit: true,
    canOperate: true,
  }
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
    else if (path === '/api/ai/jobs/job-one/conversation') data = jobConversation
    else if (path === '/api/ai/jobs/job-one/messages') {
      const value = req.postDataJSON()
      jobConversation.revision++
      jobConversation.contentVersion++
      jobConversation.messages.push(
        { id: value.messageId, role: 'user', content: value.content },
        { id: 'reply', role: 'assistant', content: '先检查源表网络，确认配置后重新检查 SQL' },
      )
      jobConversation.summary = '综合当前作业报错和用户问题'
      data = jobConversation
    } else if (path.startsWith('/api/ai/models/') && req.method() === 'PUT') {
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
      data = [
        {
          id: 'source',
          name: '测试数据',
          tableName: 'source_events',
          connectorType: 'datagen',
          rowsPerSecond: 1,
          columns: [{ name: 'id', type: 'STRING' }],
        },
        {
          id: 'sink',
          name: '测试输出',
          tableName: 'sink_events',
          connectorType: 'print',
          columns: [{ name: 'id', type: 'STRING' }],
        },
      ]
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
  await expect(page.locator('.model-test-result')).toContainText('JSON 响应正常')
})

test('继续补充数据定义保留用户地址与字段，同时合入新字段', () => {
  const value = mergeDefinitions(
    [
      {
        tableName: 'orders',
        connectorType: 'kafka',
        brokers: 'my-broker:9092',
        columns: [{ name: 'id', type: 'STRING' }],
      },
    ],
    [
      {
        tableName: 'orders',
        connectorType: 'kafka',
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

async function selectData(page: Page) {
  const panel = page.locator('.ai-sql-panel')
  const navigation = page.getByRole('navigation', { name: 'SQL 需求向导' })
  await navigation.getByRole('button', { name: /选择数据/ }).click()
  await panel.getByRole('checkbox', { name: /^测试数据/ }).check()
  await panel.getByRole('checkbox', { name: /^测试输出/ }).check()
}

test('异常定义及列结构均拒绝，合入失败保留原草稿', () => {
  const current = [
    { tableName: 'orders', connectorType: 'kafka', columns: [{ name: 'id', type: 'STRING' }] },
  ]
  const source = JSON.stringify(current)
  for (const value of [
    [null],
    [1],
    ['text'],
    [{ tableName: 't', connectorType: 'kafka', columns: null }],
    [{ tableName: 't', connectorType: 'kafka', columns: [null] }],
    [{ tableName: 't', connectorType: 'kafka', columns: [{ name: 'x', type: 1 }] }],
  ]) {
    expect(() => validateDefinitions(value)).toThrow('数据定义')
    expect(() => mergeDefinitions(current, value as any)).toThrow('数据定义')
    expect(JSON.stringify(current)).toBe(source)
  }
})

async function generate(page: Page) {
  await page.goto('/#/sql?draft=sql-draft')
  await page.getByRole('button', { name: 'AI 助手', exact: true }).click()
  const panel = page.locator('.ai-sql-panel')
  await selectData(page)
  await page
    .getByRole('navigation', { name: 'SQL 需求向导' })
    .getByRole('button', { name: /确认需求/ })
    .click()
  await panel.getByLabel('重复数据', { exact: true }).selectOption('keep')
  await panel.getByLabel('描述业务需求', { exact: true }).fill('测试请求可靠性')
  await panel.getByRole('button', { name: '确认并生成 SQL', exact: true }).click()
  return panel
}

test('作业报错按钮主动分析并展示解决步骤，不执行运行操作', async ({ page }) => {
  const f = await fixture(page)
  await page.goto('/#/jobs')
  await page.getByRole('button', { name: 'AI 分析', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: 'AI 作业助手：orders' })
  await expect(drawer).toContainText('Metaspace')
  await drawer.getByRole('button', { name: '开始 AI 分析' }).first().click()
  await expect(drawer).toContainText('先检查源表网络')
  await expect.poll(() => f.submissions.length).toBe(1)
  expect(f.submissions[0]).toMatchObject({ kind: 'diagnosis', jobId: 'job-one', includeLogs: true })
  expect(f.operations).toEqual([])
  await drawer.getByRole('button', { name: '关闭 AI 助手' }).click()
  await expect(drawer).toBeHidden()
})

test('只读角色不能显示 SQL 助手或管理模型', async ({ page }) => {
  await fixture(page, 'ROLE_VIEWER')
  await page.goto('/#/sql?draft=sql-draft')
  await expect(page.getByRole('heading', { name: 'SQL 开发', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'AI 助手', exact: true })).toHaveCount(0)
  await page.goto('/#/settings/models')
  await expect(page.getByText('仅管理员可维护模型服务。', { exact: true })).toBeVisible()
})

test('模型服务预设不带密钥，连接失败明确展示账号邮箱验证原因', async ({ page }) => {
  await fixture(page)
  await page.route('**/api/ai/models/*/test', (route) =>
    route.fulfill({
      status: 502,
      json: {
        data: null,
        error: {
          code: 'AI_MODEL_ACCOUNT_VERIFICATION_REQUIRED',
          message: 'ModelScope 账号尚未完成邮箱验证，请在账号设置中完成验证后重试（HTTP 403）',
        },
      },
    }),
  )
  await page.goto('/#/settings/models')
  await page.getByRole('button', { name: /ModelScope/ }).click()
  await expect(page.getByLabel('Base URL', { exact: false })).toHaveValue(
    'https://api-inference.modelscope.cn/v1',
  )
  await expect(page.getByLabel('实际模型标识', { exact: true })).toHaveValue('Qwen/Qwen3.5-27B')
  await expect(page.getByLabel('API Key', { exact: true })).toHaveValue('')
  await page.getByRole('button', { name: '测试连接', exact: true }).first().click()
  await expect(page.getByRole('alert')).toContainText('邮箱验证')
  await expect(page.locator('.model-test-result.failed')).toContainText('HTTP 403')
  await page.screenshot({
    path: test.info().outputPath('models-desktop.png'),
    fullPage: true,
    animations: 'disabled',
  })
  await page.setViewportSize({ width: 390, height: 844 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
  await page.screenshot({
    path: test.info().outputPath('models-mobile.png'),
    fullPage: true,
    animations: 'disabled',
  })
})

test('本地定义角色限制与大小写重复表名能解释原因', () => {
  expect(
    wizardDataIssues(
      [
        { tableName: 'SRC', connectorType: 'datagen' },
        { tableName: 'src', connectorType: 'print' },
      ],
      {},
    ),
  ).toContain('请补全表名并移除重复表定义')
  expect(
    wizardDataIssues(
      [
        { tableName: 'src', connectorType: 'datagen' },
        { tableName: 'dst', connectorType: 'print' },
      ],
      { src: 'sink', dst: 'source' },
    ),
  ).toEqual(expect.arrayContaining(['Print 只能作为输出表', 'DataGen 只能作为输入表']))
})
