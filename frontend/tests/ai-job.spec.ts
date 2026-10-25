import { test, expect, type Page } from '@playwright/test'

async function fixture(page: Page, role = 'ROLE_ADMIN', external = false) {
  const tasks: any[] = [],
    applications: any[] = [],
    errors: string[] = []
  page.on('pageerror', (e) => errors.push(e.message))
  const job = {
    id: 'job-ai',
    name: 'orders',
    description: '',
    flinkVersion: '1.20.5',
    deploymentName: 'flink-0000001',
    jobNumber: 1,
    revision: 1,
    yaml: 'metadata:\n  name: orders\n',
  }
  const model = {
    id: 'diagnosis',
    name: '诊断模型',
    model: 'test-model',
    enabled: true,
    purposes: ['diagnosis'],
    external,
    revision: 1,
  }
  const memory: any = {
    current: { baseline: { jobRevision: 1, sqlRevision: 1 }, draftYaml: 'metadata:\n  name: orders\nspec:\n  serviceAccount: "{{config:job-sa}}"', sql: 'SELECT 1', publishedSql: 'SELECT 1', referenceNote: '{{secret:...}} 为平台合法引用，脱敏标记不能写回配置。' },
    id: 'conversation',
    revision: 0,
    contentVersion: 0,
    summary: '',
    unknowns: [],
    messages: [],
    compressedMessages: 0,
    canEdit: role === 'ROLE_ADMIN',
    canOperate: true,
  }
  let currentTask: any,
    latestApplication: any,
    loseResponse = false
  let autoPlan = false
  const evidenceQueries: string[] = []
  await page.route('**/api/**', async (route) => {
    const req = route.request(),
      path = new URL(req.url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    let data: any = []
    if (path === '/api/auth/me') data = { username: 'admin', roles: [role] }
    else if (path === '/api/auth/csrf') data = { headerName: 'X-CSRF-TOKEN', token: 'test' }
    else if (path === '/api/system') data = { namespace: 'team', capabilities: [] }
    else if (path === '/api/jobs') data = [job]
    else if (path.endsWith('/access')) data = { canEdit: true, canOperate: true }
    else if (path === '/api/development/links') data = { 'job-ai': { type: 'jar' } }
    else if (path === '/api/jobs/refresh')
      data = {
        items: [
          {
            name: job.deploymentName,
            managed: true,
            jobId: job.id,
            healthState: 'ERROR',
            jobState: 'FAILED',
            error: 'OOMKilled',
            snapshots: [],
            yaml: '',
          },
        ],
        stopProgress: {},
        queriedAt: new Date().toISOString(),
      }
    else if (path === '/api/runtime/jobs') data = []
    else if (path === '/api/ai/models') data = [model]
    else if (path === '/api/ai/jobs/job-ai/conversation') data = memory
    else if (path === '/api/ai/jobs/job-ai/evidence') {
      const category = req.postDataJSON().category
      evidenceQueries.push(category)
      data = memory.evidence = { queriedAt: new Date().toISOString(), category, baseline: { jobRevision: 1 }, unavailable: category === 'podMetrics' ? ['metrics-server不可用，不能视为0'] : [], documents: memory.current, diagnostics: { pods: [{ name: 'tm-one' }], events: [] }, logs: [], podDetails: category === 'pods' ? [{ name: 'tm-one', yaml: 'kind: Pod\nmetadata:\n  name: tm-one' }] : [] }
    }
    else if (path === '/api/ai/jobs/job-ai/messages') {
      const input = req.postDataJSON()
      memory.messages.push({
        id: input.messageId,
        role: 'user',
        content: input.content,
        mode: input.mode,
      })
      memory.revision++
      memory.contentVersion++
      if (memory.plan) memory.plan.canApply = false
      data = memory
    } else if (path === '/api/ai/jobs/job-ai/summary') {
      memory.summary = req.postDataJSON().summary
      memory.revision++
      memory.contentVersion++
      if (memory.plan) memory.plan.canApply = false
      data = memory
    } else if (path === '/api/ai/tasks' && req.method() === 'POST') {
      const input = req.postDataJSON()
      tasks.push(input)
      currentTask = { id: input.requestId, kind: 'diagnosis', state: 'QUEUED', model: 'test-model' }
      data = currentTask
    } else if (path.startsWith('/api/ai/tasks/') && path.endsWith('/cancel'))
      data = { ...currentTask, state: 'CANCELED' }
    else if (path.startsWith('/api/ai/tasks/')) {
      if (currentTask.state !== 'SUCCEEDED') {
        memory.summary = '保留当前业务逻辑；已确认容器被 OOMKilled，待核对内存峰值。'
        memory.messages.push({
          id: currentTask.id,
          role: 'assistant',
          content: '引用 Pod 终止原因与部署内存配置，建议先核对资源配额。',
        })
        memory.revision++
        memory.evidence = {
          runtimeError: '本次重新取到的运行异常',
          queriedAt: new Date().toISOString(),
          baseline: { jobRevision: 1 },
          resourceExists: true,
          unavailable: [],
          logs: [
            {
              pod: 'tm-one',
              container: 'flink-main-container',
              previous: true,
              text: 'memory pressure',
            },
          ],
        }
        if (tasks.at(-1).mode === 'repair' || autoPlan)
          memory.plan = {
            id: currentTask.id,
            canApply: true,
            contentVersion: memory.contentVersion,
            beforeYaml: 'taskManager:\n  resource:\n    memory: 1024m',
            afterYaml: 'taskManager:\n  resource:\n    memory: 2048m',
            sqlChanged: false,
            yamlChanged: true,
            check: 'YAML 本地结构检查通过',
            baseline: { jobRevision: 1 },
            changes: [{ path: '/spec/taskManager/resource/memory', value: '2048m' }],
          }
        if (autoPlan) Object.assign(memory.plan, { sqlChanged: true, beforeSql: 'SELECT 1', sql: 'SELECT 2' })
        currentTask = { ...currentTask, state: 'SUCCEEDED', result: { summary: memory.summary } }
      }
      data = currentTask
    } else if (path.includes('/plans/') && path.endsWith('/apply')) {
      const input = req.postDataJSON()
      applications.push(input)
      latestApplication = {
        id: input.requestId,
        mode: input.mode,
        state: input.mode === 'save' ? 'SAVED' : 'ACCEPTED',
        message:
          input.mode === 'save'
            ? '草稿已保存，当前运行实例未变更'
            : 'API 已接受，请手动刷新确认实际运行状态',
        request: input,
      }
      memory.application = latestApplication
      memory.plan.applicationId = latestApplication.id
      memory.plan.canApply = false
      if (input.mode === 'restart') {
        memory.messages = []
        delete memory.plan
        delete memory.evidence
        delete memory.application
        memory.revision++
        memory.contentVersion++
        memory.round = { roundId: 'new-round', startedAt: new Date().toISOString(), reason: '作业已重新提交，已开始新一轮排障；历史总结仅作参考。' }
      }
      if (loseResponse) {
        loseResponse = false
        return route.abort('failed')
      }
      data = latestApplication
    } else if (path.includes('/applications/')) data = latestApplication
    return route.fulfill({ json: { data, error: null } })
  })
  await page.goto('/#/jobs')
  await page.getByRole('button', { name: 'AI 分析', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: 'AI 作业助手：orders' })
  await expect(drawer.getByLabel('任务模型')).toHaveValue('diagnosis')
  return {
    drawer,
    memory,
    tasks,
    applications,
    errors,
    evidenceQueries,
    automaticPlan: () => { autoPlan = true },
    loseResponse: () => {
      loseResponse = true
    },
  }
}

async function generate(f: Awaited<ReturnType<typeof fixture>>) {
  await f.drawer.getByRole('button', { name: '开始 AI 分析', exact: true }).first().click()
  await expect(f.drawer).toContainText('AI 已回答')
  await expect(f.drawer).toContainText('本轮尚未生成可应用的 SQL / 配置候选')
  await f.drawer.getByRole('button', { name: '生成修复方案', exact: true }).click()
  await expect(f.drawer.getByRole('heading', { name: '修复方案与完整对比' })).toBeVisible()
}

test('恢复记忆不自动调用模型，持续对话与完整对比在确认后仅保存', async ({ page }) => {
  const f = await fixture(page)
  expect(f.tasks).toHaveLength(0)
  await generate(f)
  expect(f.tasks).toHaveLength(2)
  expect(f.applications).toHaveLength(0)
  const apply = f.drawer.getByRole('button', { name: '确认仅保存草稿', exact: true })
  await expect(apply).toBeDisabled()
  await f.drawer.getByLabel('我已审阅完整修改、影响和前置检查').check()
  await apply.click()
  await expect(f.drawer).toContainText('草稿已保存，当前运行实例未变更')
  expect(f.applications).toHaveLength(1)
  expect(f.applications[0].mode).toBe('save')
  await f.drawer.getByRole('button', { name: '关闭 AI 助手' }).click()
  await page.getByRole('button', { name: 'AI 分析', exact: true }).click()
  await expect(page.getByRole('dialog')).toContainText('草稿已保存')
  expect(f.tasks).toHaveLength(2)
  expect(f.errors).toEqual([])
})

test('重启需要恢复方式、影响和完整变更确认，受理不显示运行成功', async ({ page }) => {
  const f = await fixture(page)
  await generate(f)
  await f.drawer.getByLabel('保存、发布并启动 / 重启作业').check()
  const apply = f.drawer.getByRole('button', { name: '确认保存并启动 / 重启', exact: true })
  await expect(apply).toBeDisabled()
  await f.drawer.getByLabel('恢复方式', { exact: true }).selectOption('stateless')
  await f.drawer.getByLabel('已核对恢复方式，接受作业中断及可能的处理进度变化').check()
  await f.drawer.getByLabel('我已审阅完整修改、影响和前置检查').check()
  await apply.click()
  await expect(f.drawer).toHaveCount(0)
  await expect(page.getByText(/再次打开助手将开始新一轮排障/)).toBeVisible()
  expect(f.applications[0]).toMatchObject({
    mode: 'restart',
    restoreMode: 'stateless',
    confirmed: true,
    acceptImpact: true,
  })
  expect(f.errors).toEqual([])
})

test('重启已受理但列表刷新失败时仍关闭助手并保留明确回执', async ({ page }) => {
  const f = await fixture(page)
  await generate(f)
  await f.drawer.getByLabel('保存、发布并启动 / 重启作业').check()
  await f.drawer.getByLabel('恢复方式', { exact: true }).selectOption('stateless')
  await f.drawer.getByLabel('已核对恢复方式，接受作业中断及可能的处理进度变化').check()
  await f.drawer.getByLabel('我已审阅完整修改、影响和前置检查').check()
  await page.route('**/api/jobs', route => route.abort('failed'))
  await f.drawer.getByRole('button', { name: '确认保存并启动 / 重启', exact: true }).click()
  await expect(f.drawer).toHaveCount(0)
  await expect(page.getByRole('alert').filter({ hasText: '列表暂时刷新失败' }).first()).toBeVisible()
  expect(f.applications).toHaveLength(1)
  expect(f.errors).toEqual([])
})

test('人工总结更新使旧方案失效，外部模型每轮需明确授权', async ({ page }) => {
  const f = await fixture(page, 'ROLE_ADMIN', true)
  const consent = f.drawer.getByRole('checkbox', { name: /允许本次/ })
  await f.drawer.getByRole('button', { name: '开始 AI 分析', exact: true }).first().click()
  await expect(f.drawer.getByRole('group', { name: '确认本轮外部发送' })).toBeVisible()
  expect(f.tasks).toHaveLength(0)
  expect(f.memory.messages).toHaveLength(0)
  await f.drawer.getByRole('button', { name: '取消发送', exact: true }).click()
  await consent.check()
  await f.drawer.getByRole('button', { name: '开始 AI 分析', exact: true }).first().click()
  await expect(f.drawer).toContainText('AI 已回答')
  await expect(consent).not.toBeChecked()
  await consent.check()
  await f.drawer.getByRole('button', { name: '生成修复方案', exact: true }).click()
  await expect(f.drawer.getByRole('heading', { name: '修复方案与完整对比' })).toBeVisible()
  await f.drawer.getByRole('button', { name: /当前总结/ }).click()
  const summary = page.getByRole('dialog', { name: '作业总结', exact: true })
  await summary.getByRole('button', { name: '修正总结' }).click()
  await summary.getByLabel('核对后的总结').fill('保持当前内存，不再采用旧建议')
  await summary.getByRole('button', { name: '保存总结', exact: true }).click()
  await summary.getByRole('button', { name: '关闭大视图' }).click()
  await expect(f.drawer).toContainText('旧方案仅供查看')
  await expect(f.drawer.getByRole('button', { name: '确认仅保存草稿' })).toBeDisabled()
  expect(f.errors).toEqual([])
})

test('应用断响应核对原号，不再次保存；窄屏输入与对比可操作', async ({ page }) => {
  const f = await fixture(page)
  await generate(f)
  await page.setViewportSize({ width: 390, height: 844 })
  await expect
    .poll(() => page.evaluate(() => document.documentElement.scrollWidth))
    .toBeLessThanOrEqual(390)
  await f.drawer.getByLabel('我已审阅完整修改、影响和前置检查').check()
  f.loseResponse()
  await f.drawer.getByRole('button', { name: '确认仅保存草稿' }).click()
  await expect(f.drawer).toContainText('草稿已保存，当前运行实例未变更')
  expect(f.applications).toHaveLength(1)
  await page.screenshot({ path: test.info().outputPath('ai-job-mobile.png'), fullPage: true })
  expect(f.errors).toEqual([])
})

test('运维可以持续排障和审阅，不能直接应用建议', async ({ page }) => {
  const f = await fixture(page, 'ROLE_OPERATOR')
  await generate(f)
  await expect(f.drawer).toContainText('保存与发布需要开发角色及作业编辑权限')
  await expect(f.drawer.getByRole('button', { name: '确认仅保存草稿' })).toHaveCount(0)
  expect(f.applications).toHaveLength(0)
  expect(f.errors).toEqual([])
})

test('平台重启后保存中的应用沿用原请求继续完成', async ({ page }) => {
  const f = await fixture(page)
  await generate(f)
  const request = {
    requestId: '00000000-0000-4000-8000-000000000001',
    mode: 'save',
    confirmed: true,
  }
  f.memory.plan.applicationId = request.requestId
  f.memory.plan.canApply = false
  f.memory.application = {
    id: request.requestId,
    state: 'SAVING',
    mode: 'save',
    request,
    message: '保存结果待核对',
  }
  await f.drawer.getByRole('button', { name: '关闭 AI 助手' }).click()
  await page.getByRole('button', { name: 'AI 分析', exact: true }).click()
  await f.drawer.getByRole('button', { name: '核对并继续原应用', exact: true }).click()
  await expect(f.drawer).toContainText('草稿已保存，当前运行实例未变更')
  expect(f.applications).toEqual([request])
  expect(f.tasks).toHaveLength(2)
  expect(f.errors).toEqual([])
})

test('重启受理后关闭助手并定位作业，仍失败时新轮次保留总结并重新取证', async ({ page }) => {
  const f = await fixture(page, 'ROLE_ADMIN', true)
  const consent = f.drawer.getByRole('checkbox', { name: /允许本次/ })
  await consent.check()
  await f.drawer.getByRole('button', { name: '开始 AI 分析', exact: true }).first().click()
  await expect(f.drawer).toContainText('AI 已回答')
  await consent.check()
  await f.drawer.getByRole('button', { name: '生成修复方案', exact: true }).click()
  await expect(f.drawer.getByRole('heading', { name: '修复方案与完整对比' })).toBeVisible()
  await f.drawer.getByLabel('保存、发布并启动 / 重启作业').check()
  await f.drawer.getByLabel('恢复方式', { exact: true }).selectOption('stateless')
  await f.drawer.getByLabel('已核对恢复方式，接受作业中断及可能的处理进度变化').check()
  await f.drawer.getByLabel('我已审阅完整修改、影响和前置检查').check()
  await f.drawer.getByRole('button', { name: '确认保存并启动 / 重启', exact: true }).click()
  await expect(f.drawer).toHaveCount(0)
  await page.getByRole('button', { name: 'AI 分析', exact: true }).click()
  await expect(f.drawer.locator('.ai-job-message')).toHaveCount(0)
  await expect(f.drawer).toContainText('已开始新一轮排障')
  await f.drawer.getByLabel('问题或补充说明', { exact: true }).fill('重启后仍然报错，请根据最新证据继续分析')
  await f.drawer.getByRole('button', { name: '开始 AI 分析', exact: true }).last().click()
  expect(f.tasks).toHaveLength(2)
  await expect(f.drawer.getByLabel('问题或补充说明', { exact: true })).toHaveValue('重启后仍然报错，请根据最新证据继续分析')
  await f.drawer.getByRole('button', { name: '允许并发送本轮', exact: true }).click()
  await expect(f.drawer.locator('.ai-job-message')).toHaveCount(2)
  expect(f.tasks).toHaveLength(3)
  expect(f.tasks.at(-1)).toMatchObject({ allowExternal: true, includeLogs: true, mode: 'chat' })
  await expect(consent).not.toBeChecked()
  await f.drawer.getByLabel('问题或补充说明', { exact: true }).fill('继续核对配置')
  await f.drawer.getByRole('button', { name: '发送并更新证据', exact: true }).click()
  await f.drawer.getByRole('button', { name: '取消发送', exact: true }).click()
  expect(f.tasks).toHaveLength(3)
  expect(f.memory.messages).toHaveLength(2)
  expect(f.errors).toEqual([])
})

test('普通分析自动给出明确的SQL修改候选，分类取证可以独立刷新而不调用模型', async ({ page }) => {
  const f = await fixture(page)
  f.automaticPlan()
  await f.drawer.getByRole('button', { name: '开始 AI 分析', exact: true }).first().click()
  await expect(f.drawer).toContainText('已生成 SQL 修改建议')
  await expect(f.drawer.locator('.ai-job-current-error')).toContainText('本次重新取到的运行异常')
  await expect(f.drawer).toContainText('待你审阅确认，尚未保存或重启')
  expect(f.tasks).toHaveLength(1)
  expect(f.applications).toHaveLength(0)
  await f.drawer.getByRole('button', { name: /本轮证据/ }).click()
  const evidence = page.getByRole('dialog', { name: '本轮证据详情' })
  await evidence.getByRole('button', { name: 'Pod 配置', exact: true }).click()
  await expect(evidence).toContainText('本快照未采集此类别')
  await evidence.getByRole('button', { name: '按需采集此类证据', exact: true }).click()
  await expect(evidence.locator('pre')).toContainText('kind: Pod')
  await evidence.getByRole('button', { name: '部署配置', exact: true }).click()
  await expect(evidence).toContainText('{{config:job-sa}}')
  await evidence.getByRole('button', { name: 'Pod 资源用量', exact: true }).click()
  await evidence.getByRole('button', { name: '按需采集此类证据', exact: true }).click()
  await expect(evidence).toContainText('metrics-server不可用，不能视为0')
  expect(f.evidenceQueries).toEqual(['pods', 'podMetrics'])
  expect(f.tasks).toHaveLength(1)
  expect(f.applications).toHaveLength(0)
  expect(f.errors).toEqual([])
})

test('加宽助手、可拖动中轴，证据和格式化总结可在大视图完整阅读', async ({ page }) => {
  const f = await fixture(page)
  await generate(f)
  const width = (await f.drawer.boundingBox())!.width
  expect(width).toBe(1180)
  const edge = f.drawer.getByRole('separator', { name: '调整 AI 助手宽度', exact: true })
  const edgeRect = (await edge.boundingBox())!
  await page.mouse.move(edgeRect.x + 3, edgeRect.y + 200)
  await page.mouse.down()
  await page.mouse.move(0, edgeRect.y + 200)
  await page.mouse.up()
  const sidebar = (await page.locator('.sidebar').boundingBox())!
  expect((await f.drawer.boundingBox())!.x).toBeCloseTo(sidebar.x + sidebar.width, 0)
  await edge.press('ArrowRight')
  expect((await f.drawer.boundingBox())!.width).toBeLessThan(1920 - sidebar.width)
  const separator = f.drawer.getByRole('separator', { name: '调整对话与修复区域宽度' })
  await separator.focus()
  await separator.press('ArrowRight')
  await expect(separator).toHaveAttribute('aria-valuenow', '58')
  const rect = (await separator.boundingBox())!
  await page.mouse.move(rect.x + rect.width / 2, rect.y + 50)
  await page.mouse.down()
  await page.mouse.move(rect.x + 180, rect.y + 50)
  await page.mouse.up()
  expect(Number(await separator.getAttribute('aria-valuenow'))).toBeGreaterThan(58)
  f.memory.summary =
    '**核对结果**\n\n- 保留原业务逻辑\n- 已修复资源配置\n\n`SELECT n + 1`\n\n<img src=x onerror=alert(1)>'
  await f.drawer.getByRole('button', { name: '关闭 AI 助手' }).click()
  await page.getByRole('button', { name: 'AI 分析', exact: true }).click()
  await f.drawer.getByRole('button', { name: /当前总结/ }).click()
  const summary = page.getByRole('dialog', { name: '作业总结', exact: true })
  await expect(summary.locator('strong')).toHaveText('核对结果')
  await expect(summary.locator('li')).toHaveCount(2)
  await expect(summary.locator('img')).toHaveCount(0)
  await summary.getByRole('button', { name: '关闭大视图' }).click()
  await f.drawer.getByRole('button', { name: /本轮证据/ }).click()
  const evidence = page.getByRole('dialog', { name: '本轮证据详情' })
  expect((await evidence.boundingBox())!.width).toBeGreaterThan(1400)
  await evidence.getByRole('button', { name: '容器日志', exact: true }).click()
  await expect(evidence.locator('pre')).toContainText('memory pressure')
  await evidence.getByRole('button', { name: '关闭大视图' }).click()
  await f.drawer.getByRole('button', { name: '大视图对比 ↗', exact: true }).click()
  const comparison = page.getByRole('dialog', { name: '完整修改对比' })
  await expect(comparison.locator('pre').nth(1)).toContainText('2048m')
  await comparison.getByRole('button', { name: '切换上下' }).click()
  await expect(comparison.locator('.ai-job-large-comparison')).toHaveClass(/stacked/)
  expect(f.errors).toEqual([])
})

test('短屏长异常、外发确认和模型失败均不遮挡输入，失败后可继续对话', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 720 })
  const f = await fixture(page, 'ROLE_ADMIN', true)
  f.memory.evidence = { queriedAt: new Date().toISOString(), runtimeError: 'ValidationException: invalid configuration\n'.repeat(200) }
  await f.drawer.getByRole('button', { name: '关闭 AI 助手' }).click()
  await page.getByRole('button', { name: 'AI 分析', exact: true }).click()
  const question = f.drawer.getByLabel('问题或补充说明', { exact: true })
  await question.fill('重新检查当前报错')
  await f.drawer.getByRole('button', { name: '开始 AI 分析', exact: true }).last().click()
  const consent = f.drawer.getByRole('group', { name: '确认本轮外部发送' })
  const footer = f.drawer.locator('.ai-job-footer')
  const input = f.drawer.locator('.ai-job-input')
  const box = (locator: typeof input) => locator.boundingBox().then(r => r!)
  expect((await box(input)).y + (await box(input)).height).toBeLessThanOrEqual((await box(footer)).y)
  expect((await box(question)).height).toBeGreaterThan(45)
  expect((await box(consent)).y).toBeGreaterThanOrEqual((await box(question)).y + (await box(question)).height)
  await page.screenshot({ path: test.info().outputPath('job-consent-short.png') })
  await page.route('**/api/ai/tasks/*', route => route.fulfill({ json: { data: { id: f.tasks.at(-1)?.requestId, state: 'FAILED', message: '模型调用失败，请检查配置后重试' } } }))
  await consent.getByRole('button', { name: '允许并发送本轮' }).click()
  await expect(f.drawer.getByRole('alert')).toContainText('模型调用失败')
  await expect(question).toBeEnabled()
  await expect(f.drawer.getByRole('button', { name: '重试本轮分析' })).toBeEnabled()
  expect((await box(input)).y + (await box(input)).height).toBeLessThanOrEqual((await box(footer)).y)
  expect((await box(footer)).y + (await box(footer)).height).toBeLessThanOrEqual(720)
  await page.screenshot({ path: test.info().outputPath('job-failure-short.png') })
  await page.unroute('**/api/ai/tasks/*')
  await question.fill('补充信息后继续分析')
  await f.drawer.getByRole('button', { name: '发送并更新证据', exact: true }).click()
  await consent.getByRole('button', { name: '允许并发送本轮' }).click()
  await expect(f.drawer).toContainText('AI 已回答')
  expect(f.tasks).toHaveLength(2)
  expect(f.errors).toEqual([])
})

test('修复分栏顶部对齐、对话优先，缩窄后仍可滚动到应用操作', async ({ page }) => {
  await page.setViewportSize({ width: 1920, height: 900 })
  const f = await fixture(page)
  await generate(f)
  const conversation = (await f.drawer.getByRole('region', { name: '诊断对话' }).boundingBox())!
  const plan = (await f.drawer.getByRole('region', { name: '修复方案审阅' }).boundingBox())!
  expect(plan.y).toBe(conversation.y)
  expect(plan.y).toBeLessThan(235)
  expect(plan.width).toBeLessThan(conversation.width)
  await page.screenshot({ path: test.info().outputPath('job-split.png') })
  await page.setViewportSize({ width: 900, height: 600 })
  const apply = f.drawer.getByRole('button', { name: '确认仅保存草稿', exact: true })
  await apply.scrollIntoViewIfNeeded()
  await expect(apply).toBeInViewport()
  expect(f.errors).toEqual([])
})
