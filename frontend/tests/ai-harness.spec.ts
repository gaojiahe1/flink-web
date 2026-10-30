import { test, expect, type Page } from '@playwright/test'

async function fixture(page: Page, role = 'ROLE_ADMIN') {
  const sessions: any[] = [], sent: any[] = [], applications: any[] = [], errors: string[] = []
  let streamRelease: (() => void) | undefined
  let released = false
  page.on('pageerror', error => errors.push(error.message))
  await page.route('**/api/**', async route => {
    const req = route.request(), path = new URL(req.url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    let data: any = []
    if (path === '/api/auth/me') data = { username: 'admin', roles: [role] }
    else if (path === '/api/auth/csrf') data = { headerName: 'X-CSRF-TOKEN', token: 'test' }
    else if (path === '/api/system') data = { namespace: 'test', capabilities: [] }
    else if (path === '/api/jobs') data = [{ id: 'job-1', name: '订单处理' }]
    else if (path === '/api/sql/jobs') data = [{ id: 'sql-1', name: '订单汇总' }]
    else if (path === '/api/ai/models') data = [{ id: 'tools', name: '工具模型', enabled: true, external: true, purposes: ['sql', 'diagnosis'] }]
    else if (path === '/api/ai/harness/sessions') {
      if (req.method() === 'POST') {
        const input = req.postDataJSON()
        data = { id: input.requestId, revision: 1, title: '诊断 · 订单处理', kind: input.kind, updatedAt: new Date().toISOString(), turns: [] }
        sessions.unshift(data)
      } else data = sessions
    } else if (path.startsWith('/api/ai/harness/sessions/')) {
      const parts = path.split('/'), session = sessions.find(item => item.id === parts[5])
      if (parts.length === 6) {
        if (req.method() === 'DELETE') sessions.splice(sessions.indexOf(session), 1)
        else data = session
      } else if (parts.length === 7 && req.method() === 'POST') {
        const input = req.postDataJSON(); sent.push(input)
        data = { id: input.requestId, sessionId: session.id, sequence: 1, state: 'RUNNING', content: input.content, reply: '', events: [], plan: ['核对作业上下文。', '采集证据并生成候选。', '交付结论，确认后修改。'], contextManifest: { maxModelRounds: 4 } }
        session.turns.push(data); session.revision++
      } else {
        const turn = session.turns.find((item: any) => item.id === parts[7])
        if (parts[8] === 'events') {
          if (!released) await new Promise<void>(resolve => { streamRelease = resolve })
          turn.state = 'AWAITING_APPROVAL'; turn.reply = '诊断结果：内存不足可能导致退出。候选尚未实施。'
          turn.events = [{ sequence: 1, type: 'tool.completed', title: '采集分类证据完成', at: new Date().toISOString(), detail: { evidence: '内存压力' } }]
          turn.candidate = { id: 'plan-1', check: '本地结构检查通过，业务结果待验证', beforeYaml: 'memory: 1024m', afterYaml: 'memory: 2048m' }
          session.revision++
          return route.fulfill({ contentType: 'text/event-stream', body: `event: snapshot\ndata: ${JSON.stringify(turn)}\n\n` })
        }
        if (parts[8] === 'apply') {
          applications.push(req.postDataJSON()); turn.applicationId = applications[0].requestId; turn.application = { state: 'ACCEPTED', message: '运行请求已受理', operationId: 'op-1', request: applications[0] }; turn.state = 'WAITING_OBSERVATION'
        }
        if (parts[8] === 'observe') { turn.observation = { jobState: 'RUNNING', runningRevisionConfirmed: false, message: '尚缺协调代际证明', businessVerified: false } }
        data = turn
      }
    }
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ data }) })
  })
  await page.goto('/#/ai')
  return { sessions, sent, applications, errors, release: () => { released = true; streamRelease?.() } }
}
async function create(page: Page) {
  await page.getByRole('button', { name: '新建会话', exact: true }).first().click()
  await page.getByRole('dialog').getByRole('combobox').nth(1).selectOption('job-1')
  await page.getByRole('button', { name: '创建会话' }).click()
}
test('SQL 开发无需先选草稿即可创建会话', async ({ page }) => {
  const f = await fixture(page)
  await page.getByRole('button', { name: '新建会话', exact: true }).first().click()
  await page.getByRole('dialog').getByRole('combobox').first().selectOption('sql')
  await expect(page.getByRole('dialog').getByRole('combobox').nth(1)).toHaveValue('')
  await expect(page.getByRole('button', { name: '创建会话' })).toBeEnabled()
  await page.getByRole('button', { name: '创建会话' }).click()
  await expect(page.getByRole('textbox', { name: '输入需求' })).toBeVisible()
  expect(f.sessions).toHaveLength(1); expect(f.sessions[0].kind).toBe('sql'); expect(f.errors).toEqual([])
})
test('计划先展示，真实事件流结束后展示结论，过程默认折叠且会话可删除', async ({ page }) => {
  const f = await fixture(page); await create(page)
  await page.getByRole('textbox', { name: '输入需求' }).fill('检查内存并给出修改建议')
  await expect(page.getByRole('button', { name: '发送', exact: true })).toBeDisabled()
  await page.getByLabel(/允许本轮最多/).check()
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect(page.locator('.harness-plan')).toContainText('核对作业上下文')
  await expect(page.locator('.harness-process')).not.toHaveAttribute('open', '')
  await expect(page.locator('.harness-turn')).not.toContainText('诊断结果')
  await expect.poll(() => f.sent.length).toBe(1); f.release()
  await expect(page.locator('.harness-turn')).toContainText('诊断结果')
  await expect(page.locator('.harness-process')).not.toHaveAttribute('open', '')
  await page.locator('.harness-process > summary').click()
  await expect(page.locator('.harness-process')).toContainText('采集分类证据完成')
  await expect(page.locator('.harness-workspace')).toHaveCSS('font-size', '13px')
  await page.getByRole('button', { name: '收起会话列表' }).click()
  await expect(page.getByRole('button', { name: '展开会话列表' })).toBeVisible()
  await page.getByRole('button', { name: '展开会话列表' }).click()
  await page.getByRole('button', { name: /删除会话 诊断/ }).click()
  await page.locator('.app-confirm-dialog').getByRole('button', { name: '确定' }).click()
  await expect(page.locator('.harness-session')).toHaveCount(0)
  expect(f.sessions).toHaveLength(0); expect(f.errors).toEqual([])
})
test('重启必须审阅和确认影响，受理后只能手动核验原操作', async ({ page }) => {
  const f = await fixture(page); await create(page)
  await page.getByRole('textbox', { name: '输入需求' }).fill('调整内存')
  await page.getByLabel(/允许本轮最多/).check(); await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect(page.locator('.harness-plan')).toBeVisible(); f.release()
  await expect(page.locator('.harness-candidate')).toBeVisible()
  await page.getByLabel('执行方式').selectOption('restart')
  await expect(page.getByRole('button', { name: '确认修改并启动 / 重启' })).toBeDisabled()
  await page.getByRole('combobox', { name: /^恢复方式/ }).selectOption('stateless')
  await page.getByLabel(/我接受重启中断/).check(); await page.getByLabel(/我已审阅完整差异/).check()
  await page.getByRole('button', { name: '确认修改并启动 / 重启' }).click()
  await expect(page.locator('.harness-application')).toContainText('尚不能判定已运行成功')
  expect(f.applications).toHaveLength(1); expect(f.applications[0]).toMatchObject({ confirmed: true, restoreMode: 'stateless', mode: 'restart', acceptImpact: true })
  await page.getByRole('button', { name: '手动刷新运行结果' }).click()
  await expect(page.locator('.harness-application')).toContainText('缺少本次修订运行成功的完整证明')
  expect(f.errors).toEqual([])
})
test('运维角色保持只读候选，窄屏对话没有横向溢出', async ({ page }) => {
  const f = await fixture(page, 'ROLE_OPERATOR'); await page.setViewportSize({ width: 800, height: 900 }); await create(page)
  await page.getByRole('textbox', { name: '输入需求' }).fill('只诊断')
  await page.getByLabel(/允许本轮最多/).check(); await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect(page.locator('.harness-plan')).toBeVisible(); f.release()
  await expect(page.locator('.harness-candidate')).toBeVisible(); await expect(page.locator('.harness-approval')).toHaveCount(0)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  expect(f.errors).toEqual([])
})
