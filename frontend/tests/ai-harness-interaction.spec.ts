import { test, expect, type Page } from '@playwright/test'
async function setup(page: Page, kind = 'clarification', failOnce = false) {
  const responses: any[] = []
  const card: any = {
    id: 'interaction-1',
    kind,
    title:
      kind === 'logs'
        ? '需要读取日志继续分析'
        : kind === 'choice'
          ? '选择排查方向'
          : '补充时间字段',
    questions: kind === 'clarification' ? ['时间字段是什么？'] : [],
    options: kind === 'choice' ? ['Checkpoint', '容器日志'] : [],
    state: 'PENDING',
    expiresAt: '2099-01-01T00:00:00Z',
    model: '测试模型',
    scope: kind === 'logs' ? '读取当前作业的脱敏日志并发送给当前模型' : undefined,
  }
  const turn: any = {
    id: 'turn-1',
    sessionId: 'session-1',
    sequence: 1,
    state: 'WAITING_USER',
    content: '请排查失败原因',
    reply: '需要补充以下信息。',
    plan: ['读取证据', '分析原因'],
    events: [],
    contextManifest: {},
    interaction: card,
  }
  const session = {
    id: 'session-1',
    revision: 2,
    title: '诊断 · 订单同步',
    kind: 'diagnosis',
    jobId: 'job-1',
    updatedAt: new Date().toISOString(),
    turns: [turn] as any[],
  }
  let preferences = { revision: 0, values: {} as Record<string, string> }
  await page.route('**/api/**', async (route) => {
    const req = route.request(),
      path = new URL(req.url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    let data: any = []
    if (path === '/api/auth/me') data = { username: 'admin', roles: ['ROLE_ADMIN'] }
    else if (path === '/api/auth/csrf') data = { headerName: 'X-CSRF-TOKEN', token: 'test' }
    else if (path === '/api/system') data = { namespace: 'test', capabilities: [] }
    else if (path === '/api/ai/models')
      data = [{ id: 'model', name: '测试模型', enabled: true, purposes: ['sql', 'diagnosis'] }]
    else if (path === '/api/ai/harness/preferences') {
      if (req.method() === 'PUT') {
        preferences = { revision: preferences.revision + 1, values: req.postDataJSON().values }
      }
      data = preferences
    } else if (path === '/api/ai/harness/sessions') data = [session]
    else if (path.endsWith('/respond')) {
      const input = req.postDataJSON()
      responses.push(input)
      if (failOnce && responses.length === 1)
        return route.fulfill({
          status: 503,
          contentType: 'application/json',
          body: JSON.stringify({
            data: null,
            error: { code: 'UNAVAILABLE', message: '暂时不可用，请重试' },
          }),
        })
      card.state = input.decision === 'cancel' ? 'CANCELED' : 'ANSWERED'
      card.answers = input.answers
      turn.state = input.decision === 'cancel' ? 'CANCELED' : 'ANSWERED'
      data =
        input.decision === 'cancel'
          ? turn
          : {
              ...turn,
              id: 'turn-2',
              sequence: 2,
              state: 'SUCCEEDED',
              content: `补充：${JSON.stringify(input.answers)}`,
              reply: '已根据补充继续分析',
              interaction: undefined,
            }
      if (data !== turn) session.turns.push(data)
    } else if (path === '/api/ai/harness/sessions/session-1') data = session
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({ data, error: null }),
    })
  })
  await page.goto('/#/ai')
  return { responses, session, getPreferences: () => preferences }
}
test('填写卡片后自动继续，刷新保留已提交记录', async ({ page }) => {
  const f = await setup(page)
  await page.getByLabel('时间字段是什么？').fill('event_time')
  await page.getByRole('button', { name: '提交并继续', exact: true }).click()
  await expect(page.getByText('已根据补充继续分析')).toBeVisible()
  expect(f.responses[0].answers.q0).toBe('event_time')
  await page.reload()
  await expect(page.getByText('已提交，已继续原任务')).toBeVisible()
  await expect(page.getByRole('button', { name: '提交并继续', exact: true })).toHaveCount(0)
})
test('提交失败重试保留原请求号和回答', async ({ page }) => {
  const f = await setup(page, 'clarification', true)
  await page.getByLabel('时间字段是什么？').fill('ts')
  await page.getByRole('button', { name: '提交并继续', exact: true }).click()
  await expect(page.getByText('暂时不可用，请重试')).toBeVisible()
  await page.getByRole('button', { name: '重试原提交' }).click()
  await expect(page.getByText('已根据补充继续分析')).toBeVisible()
  expect(f.responses[0]).toEqual(f.responses[1])
})
test('日志授权按钮显式授权，普通补充不授权', async ({ page }) => {
  const f = await setup(page, 'logs')
  await page.getByLabel('补充说明', { exact: true }).fill('先看Checkpoint')
  await page.getByRole('button', { name: '仅提交补充，不读取日志' }).click()
  await expect(page.getByText('已根据补充继续分析')).toBeVisible()
  expect(f.responses[0].grantLogs).toBe(false)
})
test('日志授权与取消有明确动作', async ({ page }) => {
  const f = await setup(page, 'logs')
  await page.getByRole('button', { name: '读取脱敏日志并继续' }).click()
  await expect(page.getByText('已根据补充继续分析')).toBeVisible()
  expect(f.responses[0].grantLogs).toBe(true)
})
test('对话输入框补充关联原卡片', async ({ page }) => {
  const f = await setup(page, 'choice')
  await page.getByLabel('输入需求').fill('优先检查Checkpoint超时')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect(page.getByText('已根据补充继续分析')).toBeVisible()
  expect(f.responses[0].interactionId).toBe('interaction-1')
  expect(f.responses[0].answers.note).toContain('Checkpoint')
})
test('偏好可以显式保存和遗忘', async ({ page }) => {
  const f = await setup(page)
  await page.getByRole('button', { name: '我的偏好', exact: true }).click()
  await page.getByLabel('回答方式', { exact: true }).fill('先给结论')
  await page.getByRole('button', { name: '保存偏好' }).click()
  await expect(page.getByText('偏好已保存，将用于后续轮次')).toBeVisible()
  expect(f.getPreferences().values.responseStyle).toBe('先给结论')
  await page.getByRole('button', { name: '清空偏好' }).click()
  await expect(page.getByText('已清空偏好')).toBeVisible()
  expect(f.getPreferences().values).toEqual({})
})

test('取消交互后保留取消记录', async ({ page }) => {
  const f = await setup(page, 'choice')
  await page.getByRole('button', { name: '取消本次交互' }).click()
  await expect(page.getByText('已取消', { exact: true }).last()).toBeVisible()
  expect(f.responses[0].decision).toBe('cancel')
})
test('验证执行必须点击具体确认按钮', async ({ page }) => {
  const f = await setup(page, 'verification')
  await page.getByRole('button', { name: '确认运行验证' }).click()
  await expect(page.getByText('已根据补充继续分析')).toBeVisible()
  expect(f.responses[0].confirmedVerification).toBe(true)
})
test('窄屏卡片选择可操作且没有横向溢出', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const f = await setup(page, 'choice')
  await page.getByRole('radio', { name: 'Checkpoint', exact: true }).check()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(
    true,
  )
  await page.getByRole('button', { name: '提交并继续', exact: true }).click()
  await expect(page.getByText('已根据补充继续分析')).toBeVisible()
  expect(f.responses[0].answers.choice).toBe('Checkpoint')
})
test('三主题下卡片和偏好保持可读', async ({ page }) => {
  await setup(page, 'logs')
  for (const theme of ['mist', 'pearl', 'dark']) {
    await page.getByLabel('界面主题').selectOption(theme)
    await expect(page.getByRole('button', { name: '读取脱敏日志并继续' })).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(
      true,
    )
  }
  await page.getByLabel('界面主题').selectOption('mist')
  await page.screenshot({
    path: 'C:/Users/11526/.codex/visualizations/2026/11/02/01a19b77-e871-76f2-b6d9-5117cbdea06e/harness-010-interaction.png',
  })
})
