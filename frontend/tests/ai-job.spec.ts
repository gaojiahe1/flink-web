import { test, expect, type Page } from '@playwright/test'

async function fixture(
  page: Page,
  options: {
    role?: string
    existing?: boolean
    unrelated?: boolean
    loseCreate?: boolean
    denied?: boolean
  } = {},
) {
  const role = options.role || 'ROLE_ADMIN'
  const created: any[] = [],
    sent: any[] = [],
    unexpected: string[] = []
  const job = {
    id: 'job-ai',
    name: 'orders',
    description: '',
    flinkVersion: '1.20.5',
    deploymentName: 'flink-one',
    jobNumber: 1,
    revision: 1,
    yaml: 'metadata:\n  name: orders',
  }
  const oldTurn = {
    id: 'old-turn',
    sessionId: 'session-existing',
    sequence: 1,
    state: 'SUCCEEDED',
    content: '之前的问题',
    reply: '已保留的 Harness 分析',
    plan: [],
    events: [],
    contextManifest: {},
  }
  const sessions: any[] = options.existing
    ? [
        {
          id: 'session-existing',
          jobId: job.id,
          kind: 'diagnosis',
          title: '诊断 · orders',
          revision: 1,
          turns: [oldTurn],
          updatedAt: new Date().toISOString(),
        },
      ]
    : options.unrelated
      ? [
          {
            id: 'other',
            jobId: 'other-job',
            kind: 'diagnosis',
            title: '其他作业',
            revision: 1,
            turns: [],
            updatedAt: new Date().toISOString(),
          },
        ]
      : []
  await page.route('**/api/**', async (route) => {
    const req = route.request(),
      path = new URL(req.url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    let data: any
    if (path === '/api/auth/me') data = { username: 'admin', roles: [role] }
    else if (path === '/api/auth/csrf') data = { headerName: 'X-CSRF-TOKEN', token: 'test' }
    else if (path === '/api/system') data = { namespace: 'test', capabilities: [] }
    else if (path === '/api/jobs') data = [job]
    else if (path === '/api/platform/job-access')
      data = {
        [job.id]: {
          canEdit: role !== 'ROLE_OPERATOR',
          canOperate: role === 'ROLE_ADMIN' || role === 'ROLE_OPERATOR',
        },
      }
    else if (path === '/api/development/links') data = { [job.id]: { type: 'jar' } }
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
    else if (path === '/api/ai/models')
      data = [
        {
          id: 'tools',
          name: '工具模型',
          enabled: true,
          external: true,
          purposes: ['sql', 'diagnosis'],
        },
      ]
    else if (path === '/api/ai/harness/sessions') {
      if (req.method() === 'POST') {
        const input = req.postDataJSON()
        created.push(input)
        if (options.denied)
          return route.fulfill({
            status: 403,
            contentType: 'application/json',
            body: JSON.stringify({
              data: null,
              error: { code: 'FORBIDDEN', message: '无权读取目标作业' },
            }),
          })
        data = {
          id: input.requestId,
          jobId: input.jobId,
          kind: input.kind,
          title: '诊断 · orders',
          revision: 1,
          turns: [],
          updatedAt: new Date().toISOString(),
        }
        sessions.unshift(data)
        if (options.loseCreate && created.length === 1)
          return route.fulfill({
            status: 503,
            contentType: 'application/json',
            body: JSON.stringify({
              data: null,
              error: { code: 'UNAVAILABLE', message: '创建响应暂时不可用' },
            }),
          })
      } else data = sessions
    } else if (path.startsWith('/api/ai/harness/sessions/')) {
      const parts = path.split('/'),
        session = sessions.find((s) => s.id === parts[5])
      if (parts.length === 6 && req.method() === 'GET') data = session
      else if (parts.length === 7 && req.method() === 'POST') {
        const input = req.postDataJSON()
        sent.push(input)
        data = {
          id: input.requestId,
          sessionId: session.id,
          sequence: session.turns.length + 1,
          state: 'SUCCEEDED',
          content: input.content,
          reply: '已按需读取Pod事件和脱敏日志，完成分析',
          plan: ['读取当前证据', '分析异常'],
          events: [],
          contextManifest: { offeredTools: 17, modelInputCharacters: 12000 },
        }
        session.turns.push(data)
      }
    }
    if (data === undefined) {
      unexpected.push(req.method() + ' ' + path)
      return route.fulfill({
        status: 501,
        contentType: 'application/json',
        body: JSON.stringify({
          data: null,
          error: { code: 'UNEXPECTED', message: '未声明的测试接口' },
        }),
      })
    }
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({ data, error: null }),
    })
  })
  await page.goto('/#/jobs')
  await expect(page.getByRole('button', { name: 'AI 分析', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'AI 分析', exact: true }).click()
  return { created, sent, sessions, unexpected }
}

test('作业列表打开目标作业的统一工作台，不自动调用模型', async ({ page }) => {
  const f = await fixture(page)
  await expect(page).toHaveURL(/#\/ai\?job=job-ai$/)
  await expect(page.getByLabel('输入需求')).toBeVisible()
  await expect(page.getByLabel('允许取脱敏日志')).toHaveCount(0)
  expect(f.created).toHaveLength(1)
  expect(f.created[0]).toMatchObject({ kind: 'diagnosis', jobId: 'job-ai' })
  expect(f.sent).toHaveLength(0)
  expect(f.unexpected).toEqual([])
})
test('重用工作台中目标作业的已有会话和分析', async ({ page }) => {
  const f = await fixture(page, { existing: true })
  await expect(page.getByText('已保留的 Harness 分析', { exact: true })).toBeVisible()
  expect(f.created).toHaveLength(0)
  await page.reload()
  await expect(page.getByText('已保留的 Harness 分析', { exact: true })).toBeVisible()
  expect(f.created).toHaveLength(0)
})
test('其他作业最近会话不会被错误选中', async ({ page }) => {
  const f = await fixture(page, { unrelated: true })
  await expect(page.getByLabel('输入需求')).toBeVisible()
  expect(f.created[0].jobId).toBe('job-ai')
})
test('创建响应丢失后读取已创建会话，不重复创建', async ({ page }) => {
  const f = await fixture(page, { loseCreate: true })
  await expect(page.getByRole('alert')).toContainText('创建响应暂时不可用')
  await page.getByRole('button', { name: '重试打开作业会话' }).click()
  await expect(page.getByLabel('输入需求')).toBeVisible()
  expect(f.created).toHaveLength(1)
})
test('目标作业无权访问时不回退到其他会话', async ({ page }) => {
  const f = await fixture(page, { denied: true, unrelated: true })
  await expect(page.getByRole('alert')).toContainText('无权读取目标作业')
  await expect(page.getByLabel('输入需求')).toHaveCount(0)
  expect(f.sent).toHaveLength(0)
})
test('发送使用同一Harness接口，不再申请只读取证授权', async ({ page }) => {
  const f = await fixture(page)
  await page.getByLabel('输入需求').fill('检查初始化容器失败原因')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect(
    page.getByText('已按需读取Pod事件和脱敏日志，完成分析', { exact: true }),
  ).toBeVisible()
  expect(f.sent).toHaveLength(1)
  expect(f.sent[0].includeLogs).toBeUndefined()
  expect(f.unexpected).toEqual([])
})
for (const role of ['ROLE_OPERATOR', 'ROLE_DEVELOPER'])
  test(`${role}与工作台诊断入口一致`, async ({ page }) => {
    const f = await fixture(page, { role })
    await expect(page.getByLabel('输入需求')).toBeVisible()
    expect(f.created[0].kind).toBe('diagnosis')
  })
test('模板和模型位于同一输入区，三主题及窄屏无溢出', async ({ page }) => {
  await fixture(page)
  const composer = page.locator('form.harness-composer')
  await expect(composer.getByLabel('任务模板')).toBeVisible()
  await expect(composer.getByLabel('模型', { exact: true })).toBeVisible()
  for (const width of [1440, 390]) {
    await page.setViewportSize({ width, height: 900 })
    for (const theme of ['mist', 'pearl', 'dark']) {
      await page.getByLabel('界面主题').selectOption(theme)
      await expect(composer.getByLabel('任务模板')).toBeVisible()
      expect(
        await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1),
      ).toBe(true)
    }
  }
  await page.screenshot({ path: 'test-results/harness-job-template-mobile.png' })
})
