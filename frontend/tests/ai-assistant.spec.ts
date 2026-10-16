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

test('SQL 助手生成差异、显式应用，随后继续沿用 SQL 检查', async ({ page }) => {
  const f = await fixture(page)
  await page.goto('/#/sql?draft=sql-draft')
  await page.getByRole('button', { name: 'AI 助手', exact: true }).click()
  const panel = page.locator('.ai-sql-panel')
  await selectData(page)
  await page
    .getByRole('navigation', { name: 'SQL 需求向导' })
    .getByRole('button', { name: /确认需求/ })
    .click()
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
    sourceIds: ['source', 'sink'],
    flinkVersion: '1.20.5',
  })
  expect(f.operations).toEqual([])
})

test('SQL 编辑后旧建议禁止应用；数据定义缺参数明确提示', async ({ page }) => {
  await fixture(page)
  await page.goto('/#/sql?draft=sql-draft')
  await page.getByRole('button', { name: 'AI 助手', exact: true }).click()
  const panel = page.locator('.ai-sql-panel')
  await selectData(page)
  await page
    .getByRole('navigation', { name: 'SQL 需求向导' })
    .getByRole('button', { name: /确认需求/ })
    .click()
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

test('生成成功后切换解释或修复使旧建议失效', async ({ page }) => {
  await fixture(page)
  const panel = await generate(page)
  const apply = panel.getByRole('button', { name: '应用到编辑器', exact: true })
  await expect(apply).toBeEnabled()
  await panel.getByRole('button', { name: '解释', exact: true }).click()
  await page
    .getByRole('navigation', { name: 'SQL 需求向导' })
    .getByRole('button', { name: /查看 SQL/ })
    .click()
  await expect(apply).toBeDisabled()
  await panel.getByRole('button', { name: '修复', exact: true }).click()
  await page
    .getByRole('navigation', { name: 'SQL 需求向导' })
    .getByRole('button', { name: /查看 SQL/ })
    .click()
  await expect(apply).toBeDisabled()
})

test('服务端接受后创建响应断开，按原编号查询且不重复提交', async ({ page }) => {
  const f = await fixture(page)
  let id = '',
    posts = 0,
    lookups: string[] = []
  await page.route('**/api/ai/tasks', async (route) => {
    if (route.request().method() !== 'POST') return route.fallback()
    id = route.request().postDataJSON().requestId
    posts++
    await route.abort('failed')
  })
  await page.route('**/api/ai/tasks/*', (route) => {
    lookups.push(new URL(route.request().url()).pathname.split('/').at(-1)!)
    return route.fulfill({
      json: {
        data: {
          id,
          state: 'SUCCEEDED',
          model: 'test-model',
          result: { summary: '已接受', sql: sql + '\n-- recovered' },
        },
        error: null,
      },
    })
  })
  const panel = await generate(page)
  await expect(panel).toContainText('已接受')
  expect(lookups).toEqual([id])
  expect(posts).toBe(1)
  expect(f.submissions).toHaveLength(0)
  await expect(panel.getByRole('button', { name: '应用到编辑器', exact: true })).toBeEnabled()
})

test('未知提交主动重试前先核对，确认不存在后仍使用原编号与快照', async ({ page }) => {
  await fixture(page)
  const ids: string[] = [],
    order: string[] = []
  let found = false
  await page.route('**/api/ai/tasks', async (route) => {
    if (route.request().method() !== 'POST') return route.fallback()
    ids.push(route.request().postDataJSON().requestId)
    order.push('POST')
    if (ids.length === 1) return route.abort('failed')
    found = true
    return route.fulfill({
      json: {
        data: {
          id: ids[0],
          state: 'SUCCEEDED',
          model: 'test-model',
          result: { summary: '重试已核对' },
        },
        error: null,
      },
    })
  })
  await page.route('**/api/ai/tasks/*', (route) => {
    order.push('GET')
    return route.fulfill({
      status: found ? 200 : 404,
      json: found
        ? { data: { id: ids[0], state: 'SUCCEEDED' } }
        : { error: { code: 'NOT_FOUND', message: '未找到任务' } },
    })
  })
  const panel = await generate(page)
  await expect(panel.getByRole('button', { name: '核对原任务并重试' })).toBeVisible()
  expect(ids).toHaveLength(1)
  await panel.getByRole('button', { name: '核对原任务并重试' }).click()
  await expect(panel).toContainText('重试已核对')
  expect(ids[0]).toBe(ids[1])
  expect(order).toEqual(['POST', 'GET', 'GET', 'POST'])
})

test('编译和历史的旧响应不覆盖修改后的上下文', async ({ page }) => {
  await fixture(page)
  let releaseCompile!: () => void, releaseHistory!: () => void
  const compileGate = new Promise<void>((resolve) => {
      releaseCompile = resolve
    }),
    historyGate = new Promise<void>((resolve) => {
      releaseHistory = resolve
    })
  await page.route('**/api/ai/definitions/compile', async (route) => {
    await compileGate
    await route.fulfill({ json: { data: { valid: true, pending: [], sql: '', env: [] } } })
  })
  await page.route('**/api/ai/tasks?*', async (route) => {
    await historyGate
    await route.fulfill({
      json: {
        data: [
          {
            id: 'old',
            state: 'FAILED',
            createdAt: new Date().toISOString(),
            model: '旧历史',
            message: '旧错误',
          },
        ],
      },
    })
  })
  await page.goto('/#/sql?draft=sql-draft')
  await page.getByRole('button', { name: 'AI 助手', exact: true }).click()
  const panel = page.locator('.ai-sql-panel')
  await panel
    .getByRole('navigation', { name: 'AI 产物' })
    .getByRole('button', { name: '数据定义', exact: true })
    .click()
  const compileSent = page.waitForRequest('**/api/ai/definitions/compile')
  await panel.getByRole('button', { name: '编译定义并检查待补项' }).click()
  await compileSent
  await panel.getByRole('button', { name: '添加输入表' }).click()
  const compiled = page.waitForResponse('**/api/ai/definitions/compile'),
    historied = page.waitForResponse('**/api/ai/tasks?*')
  releaseCompile()
  releaseHistory()
  await Promise.all([compiled, historied])
  await expect(panel).not.toContainText('数据定义编译通过')
  await expect(panel).not.toContainText('旧错误')
})

test('提交不明时取消失败保留原编号，重试取消不重发模型请求', async ({ page }) => {
  await fixture(page)
  let id = '',
    posts = 0,
    cancels = 0
  await page.route('**/api/ai/tasks', (route) => {
    if (route.request().method() !== 'POST') return route.fallback()
    id = route.request().postDataJSON().requestId
    posts++
    return route.abort('failed')
  })
  await page.route('**/api/ai/tasks/*', (route) =>
    route.fulfill({ status: 404, json: { error: { code: 'NOT_FOUND', message: '尚未找到' } } }),
  )
  await page.route('**/api/ai/tasks/*/cancel', (route) => {
    expect(new URL(route.request().url()).pathname).toBe('/api/ai/tasks/' + id + '/cancel')
    cancels++
    return route.fulfill({
      status: cancels === 1 ? 503 : 200,
      json:
        cancels === 1
          ? { error: { code: 'UNAVAILABLE', message: '暂时失败' } }
          : { data: { id, state: 'CANCELED', model: 'test-model' } },
    })
  })
  const panel = await generate(page)
  await expect(panel.getByRole('button', { name: '核对原任务并重试' })).toBeVisible()
  await panel.getByRole('button', { name: '取消任务' }).click()
  await expect(panel).toContainText('取消尚未确认')
  await panel.getByRole('button', { name: '核对原任务并重试' }).click()
  await expect(panel).toContainText('已取消')
  expect(posts).toBe(1)
  expect(cancels).toBe(2)
})

test('编译失败响应晚于 SQL 上下文变化时不回填旧错误', async ({ page }) => {
  await fixture(page)
  let release!: () => void
  const gate = new Promise<void>((resolve) => {
    release = resolve
  })
  await page.route('**/api/ai/definitions/compile', async (route) => {
    await gate
    await route.fulfill({
      status: 400,
      json: { error: { code: 'INVALID', message: '过期编译错误' } },
    })
  })
  await page.goto('/#/sql?draft=sql-draft')
  await page.getByRole('button', { name: 'AI 助手', exact: true }).click()
  const panel = page.locator('.ai-sql-panel')
  await panel
    .getByRole('navigation', { name: 'AI 产物' })
    .getByRole('button', { name: '数据定义', exact: true })
    .click()
  const sent = page.waitForRequest('**/api/ai/definitions/compile')
  await panel.getByRole('button', { name: '编译定义并检查待补项' }).click()
  await sent
  await page
    .locator('.sql-center .cm-content')
    .first()
    .fill(sql + '\n-- changed')
  const response = page.waitForResponse('**/api/ai/definitions/compile')
  release()
  await response
  await expect(panel).not.toContainText('过期编译错误')
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
  await selectData(page)
  await page
    .getByRole('navigation', { name: 'SQL 需求向导' })
    .getByRole('button', { name: /确认需求/ })
    .click()
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

test('需求向导选择模型后按四步生成、检查和保存，不自动发布或运行', async ({ page }) => {
  const f = await fixture(page)
  f.setModels([model, { ...model, id: 'second', name: '第二个模型' }])
  await page.goto('/#/sql?draft=sql-draft')
  await page.getByRole('button', { name: 'AI 助手', exact: true }).click()
  const panel = page.locator('.ai-sql-panel'),
    steps = page.getByRole('navigation', { name: 'SQL 需求向导' })
  await selectData(page)
  await panel.getByLabel('任务模型', { exact: true }).selectOption('second')
  await panel.getByRole('button', { name: '下一步：确认需求' }).click()
  await panel.getByLabel('描述业务需求', { exact: true }).fill('按类目统计支付金额')
  await expect(panel.getByRole('button', { name: '确认并生成 SQL' })).toBeDisabled()
  await panel.getByLabel('重复数据', { exact: true }).selectOption('keep')
  await page.screenshot({
    path: test.info().outputPath('wizard-desktop.png'),
    fullPage: true,
    animations: 'disabled',
  })
  const box = await panel.boundingBox(),
    editor = await page.locator('.sql-center').boundingBox()
  expect(box!.x).toBeLessThan(editor!.x)
  await panel.getByRole('button', { name: '确认并生成 SQL' }).click()
  await expect(panel.locator('.ai-diff')).toContainText('AI 建议')
  expect(f.submissions[0].modelId).toBe('second')
  await expect(page.locator('.sql-center .cm-content').first()).not.toContainText('AI 建议')
  await panel.getByRole('button', { name: '应用到编辑器' }).click()
  await expect(steps.getByRole('button', { name: /检查与保存/ })).toHaveAttribute(
    'aria-current',
    'step',
  )
  await panel.getByRole('button', { name: '检查当前 SQL', exact: true }).click()
  await expect(panel).toContainText('检查通过')
  expect(f.operations).toEqual([])
  await page.setViewportSize({ width: 390, height: 844 })
  await steps.getByRole('button', { name: /确认需求/ }).click()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
  await page.screenshot({
    path: test.info().outputPath('wizard-mobile.png'),
    fullPage: true,
    animations: 'disabled',
  })
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

test('向导先明确输入输出，清洗场景不携带隐藏窗口，补充和收起均保留草稿', async ({ page }) => {
  const f = await fixture(page)
  await page.goto('/#/sql?draft=sql-draft')
  await page.getByRole('button', { name: 'AI 助手', exact: true }).click()
  const panel = page.locator('.ai-sql-panel'),
    steps = page.getByRole('navigation', { name: 'SQL 需求向导' })
  await expect(steps.getByRole('button', { name: /确认需求/ })).toBeDisabled()
  await expect(steps.getByRole('button', { name: /检查与保存/ })).toBeDisabled()
  await selectData(page)
  await panel.getByRole('button', { name: '下一步：确认需求' }).click()
  await expect(panel.getByLabel('窗口（分钟）', { exact: true })).toBeHidden()
  await panel.getByLabel('描述业务需求', { exact: true }).fill('筛选有效订单并同步明细')
  await panel.getByLabel('重复数据', { exact: true }).selectOption('keep')
  await panel.getByRole('button', { name: '确认并生成 SQL' }).click()
  await expect(panel.getByRole('button', { name: '应用到编辑器' })).toBeEnabled()
  expect(f.submissions[0].spec).toMatchObject({
    workflowVersion: 2,
    pattern: 'transform',
    bindings: [
      { tableName: 'source_events', role: 'source' },
      { tableName: 'sink_events', role: 'sink' },
    ],
  })
  expect(f.submissions[0].spec).not.toHaveProperty('windowMinutes')
  await panel.getByRole('button', { name: '应用到编辑器' }).click()
  await expect(steps.getByRole('button', { name: /检查与保存/ })).toHaveAttribute(
    'aria-current',
    'step',
  )
  await panel.getByRole('button', { name: '返回查看 SQL 建议' }).click()
  await panel.getByLabel('继续补充需求', { exact: true }).fill('过滤掉金额为零的订单')
  await panel.getByRole('button', { name: '发送补充' }).click()
  expect(f.submissions).toHaveLength(1)
  await expect(steps.getByRole('button', { name: /检查与保存/ })).toBeDisabled()
  await expect(panel.getByLabel('描述业务需求', { exact: true })).toHaveValue(/过滤掉金额为零/)
  await expect(page.locator('.sql-center .cm-content').first()).toContainText('AI 建议')
  await panel.getByRole('button', { name: '收起 AI 助手' }).click()
  await page.getByRole('button', { name: 'AI 助手', exact: true }).click()
  await expect(panel.getByLabel('描述业务需求', { exact: true })).toHaveValue(/过滤掉金额为零/)
  await expect(steps.getByRole('button', { name: /检查与保存/ })).toBeDisabled()
  await panel.getByRole('button', { name: '确认并生成 SQL' }).click()
  await expect(panel.getByRole('button', { name: '应用到编辑器' })).toBeEnabled()
  await expect(steps.getByRole('button', { name: /检查与保存/ })).toBeDisabled()
  await panel.getByRole('button', { name: '应用到编辑器' }).click()
  await expect(steps.getByRole('button', { name: /检查与保存/ })).toHaveAttribute(
    'aria-current',
    'step',
  )
})

test('长 SQL 对比提示尾部修改并可完整审阅第 1001 行之后的 SQL', async ({ page }) => {
  await fixture(page)
  const original = Array.from({ length: 1005 }, (_, n) => '-- line ' + (n + 1)).join('\n')
  const suggestion = original + '\n-- 尾部独有修改'
  await page.route('**/api/sql/jobs', (route) =>
    route.fulfill({ json: { data: [{ ...draft, sql: original }], error: null } }),
  )
  await page.route('**/api/sql/jobs/sql-draft', (route) =>
    route.fulfill({ json: { data: { ...draft, sql: original }, error: null } }),
  )
  await page.route('**/api/ai/tasks/*', (route) =>
    route.fulfill({
      json: {
        data: {
          id: 'long',
          state: 'SUCCEEDED',
          result: { summary: '尾部修改', sql: suggestion, env: [], flinkConfiguration: {} },
        },
        error: null,
      },
    }),
  )
  await page.goto('/#/sql?draft=sql-draft')
  await page.getByRole('button', { name: 'AI 助手', exact: true }).click()
  await selectData(page)
  const panel = page.locator('.ai-sql-panel')
  await panel.getByRole('button', { name: '下一步：确认需求' }).click()
  await panel.getByLabel('描述业务需求', { exact: true }).fill('修改尾部')
  await panel.getByLabel('重复数据', { exact: true }).selectOption('keep')
  await panel.getByRole('button', { name: '确认并生成 SQL' }).click()
  await expect(panel).toContainText('1 行修改未展示')
  await expect(panel.locator('.ai-diff')).not.toContainText('尾部独有修改')
  await panel.getByRole('button', { name: '审阅完整 SQL', exact: true }).click()
  const complete = panel.locator('.cm-content[aria-label="建议完整 SQL"]')
  await complete.locator('..').evaluate((element) => {
    element.scrollTop = element.scrollHeight
  })
  await expect(complete).toContainText('尾部独有修改')
  await expect(complete).toHaveAttribute('contenteditable', 'false')
  await panel
    .locator('.ai-full-review')
    .screenshot({ path: test.info().outputPath('full-review.png') })
})

test('窗口场景缺少输入水位线时不能生成，应用配置冲突不越过审阅步骤', async ({ page }) => {
  const f = await fixture(page)
  await page.route('**/api/sql/jobs/sql-draft', (route) =>
    route.fulfill({
      json: {
        data: {
          ...draft,
          runtime: { ...runtime, flinkConfiguration: { 'table.local-time-zone': 'UTC' } },
        },
        error: null,
      },
    }),
  )
  await page.route('**/api/sql/jobs', (route) =>
    route.fulfill({
      json: {
        data: [
          {
            ...draft,
            runtime: { ...runtime, flinkConfiguration: { 'table.local-time-zone': 'UTC' } },
          },
        ],
        error: null,
      },
    }),
  )
  await page.route('**/api/ai/tasks/*', (route) =>
    route.fulfill({
      json: {
        data: {
          id: 'conflicting',
          state: 'SUCCEEDED',
          model: 'test-model',
          result: {
            summary: '转换建议',
            sql: sql + '\n-- 配置冲突建议',
            env: [],
            flinkConfiguration: { 'table.local-time-zone': 'Asia/Shanghai' },
          },
        },
        error: null,
      },
    }),
  )
  await selectDataAfterOpen()
  async function selectDataAfterOpen() {
    await page.goto('/#/sql?draft=sql-draft')
    await page.getByRole('button', { name: 'AI 助手', exact: true }).click()
    await selectData(page)
    await page.locator('.ai-sql-panel').getByRole('button', { name: '下一步：确认需求' }).click()
  }
  const panel = page.locator('.ai-sql-panel')
  await panel.getByLabel('处理场景', { exact: true }).selectOption('window')
  await panel.getByLabel('重复数据', { exact: true }).selectOption('keep')
  await panel.getByLabel('描述业务需求', { exact: true }).fill('每五分钟统计金额')
  await expect(panel).toContainText('请选择输入表中已定义水位线的事件时间字段')
  await expect(panel.getByRole('button', { name: '确认并生成 SQL' })).toBeDisabled()
  expect(f.submissions).toHaveLength(0)
  await panel.getByLabel('处理场景', { exact: true }).selectOption('transform')
  await panel.getByRole('button', { name: '确认并生成 SQL' }).click()
  await expect(panel.getByRole('button', { name: '应用到编辑器' })).toBeEnabled()
  await panel.getByRole('button', { name: '应用到编辑器' }).click()
  await expect(page.getByRole('alert')).toContainText('运行配置与当前值冲突')
  await expect(panel.getByRole('button', { name: '应用到编辑器' })).toBeEnabled()
  await expect(
    page
      .getByRole('navigation', { name: 'SQL 需求向导' })
      .getByRole('button', { name: /检查与保存/ }),
  ).toBeDisabled()
  await expect(page.locator('.sql-center .cm-content').first()).not.toContainText('配置冲突建议')
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
