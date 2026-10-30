import { test, expect, type Page } from '@playwright/test'

async function fixture(
  page: Page,
  role = 'ROLE_ADMIN',
  scenario: 'normal' | 'stall' | 'status' = 'normal',
) {
  const sessions: any[] = [],
    sent: any[] = [],
    applications: any[] = [],
    errors: string[] = []
  let streamRelease: (() => void) | undefined
  let released = false
  page.on('pageerror', (error) => errors.push(error.message))
  await page.route('**/api/**', async (route) => {
    const req = route.request(),
      path = new URL(req.url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    let data: any = []
    if (path === '/api/auth/me') data = { username: 'admin', roles: [role] }
    else if (path === '/api/auth/csrf') data = { headerName: 'X-CSRF-TOKEN', token: 'test' }
    else if (path === '/api/system') data = { namespace: 'test', capabilities: [] }
    else if (path === '/api/jobs') data = [{ id: 'job-1', name: '订单处理' }]
    else if (path === '/api/sql/jobs') data = [{ id: 'sql-1', name: '订单汇总' }]
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
        data = {
          id: input.requestId,
          revision: 1,
          title: '诊断 · 订单处理',
          kind: input.kind,
          updatedAt: new Date().toISOString(),
          turns: [],
        }
        sessions.unshift(data)
      } else data = sessions
    } else if (path.startsWith('/api/ai/harness/sessions/')) {
      const parts = path.split('/'),
        session = sessions.find((item) => item.id === parts[5])
      if (parts.length === 6) {
        if (req.method() === 'DELETE') sessions.splice(sessions.indexOf(session), 1)
        else data = session
      } else if (parts.length === 7 && req.method() === 'POST') {
        const input = req.postDataJSON()
        sent.push(input)
        data = {
          id: input.requestId,
          sessionId: session.id,
          sequence: 1,
          state: 'RUNNING',
          content: input.content,
          reply: '',
          events: [],
          plan: [],
          planStatus: 'PENDING',
          contextManifest: { modelCallPolicy: '不限制调用次数' },
        }
        session.turns.push(data)
        session.revision++
      } else {
        const turn = session.turns.find((item: any) => item.id === parts[7])
        if (parts[8] === 'events') {
          if (!released)
            await new Promise<void>((resolve) => {
              streamRelease = resolve
            })
          turn.plan = [
            '模型计划：核对作业上下文。',
            '模型计划：采集证据并生成候选。',
            '模型计划：交付结论，确认后修改。',
          ]
          turn.planStatus = 'READY'
          turn.scene = session.kind === 'sql' ? 'sql_development' : 'diagnosis'
          turn.state = 'AWAITING_APPROVAL'
          turn.reply =
            "诊断结果：内存不足可能导致退出。候选尚未实施。\n\n```sql\nSELECT 'print' AS sink, 10 AS rate; -- demo\n```\n\n行内代码 `sql_propose`"
          turn.events = [
            {
              sequence: 1,
              type: 'tool.completed',
              title: '采集分类证据完成',
              at: new Date().toISOString(),
              detail: { evidence: '内存压力' },
            },
          ]
          turn.candidate = {
            id: 'plan-1',
            check: '本地结构检查通过，业务结果待验证',
            beforeYaml: 'memory: 1024m',
            afterYaml: 'memory: 2048m',
          }
          if (session.kind === 'sql') {
            turn.candidate = {
              id: 'sql-plan',
              kind: 'sql',
              newDraft: true,
              sqlName: 'ai-sql-test',
              beforeSql: '',
              sql: "CREATE TABLE p (v INT) WITH ('connector'='print'); INSERT INTO p SELECT 1",
              check: 'Parser 语法检查通过',
              execution: {
                available: true,
                image: 'flink:1.20.5',
                message: '创建、发布并提交首次无状态运行',
              },
              runYaml: 'spec:\n  image: flink:1.20.5',
            }
          }
          if (scenario === 'stall') {
            turn.state = 'STALLED'
            delete turn.candidate
            turn.recovery = {
              action: 'stop',
              message: '连续相同 SQL 语法错误，已停止重复尝试。',
              nextSteps: ['修改第11行失败片段后继续'],
              diagnostic: {
                message: '第11行第5列 SQL语法错误',
                excerpt: [{ line: 11, text: 'invalid clause' }],
              },
            }
          }
          if (scenario === 'status') {
            turn.state = 'SUCCEEDED'
            turn.scene = 'status'
            delete turn.candidate
            turn.reply = '可以部署了'
            turn.runtimeStatus = {
              jobState: 'RUNNING',
              lifecycleState: 'STABLE',
              currentRunning: true,
              runningRevisionConfirmed: false,
              message: '本次实时查询：作业 RUNNING',
              observedAt: new Date().toISOString(),
              businessVerified: false,
              issues: [],
            }
          }
          session.revision++
          return route.fulfill({
            contentType: 'text/event-stream',
            body: `event: snapshot\ndata: ${JSON.stringify(turn)}\n\n`,
          })
        }
        if (parts[8] === 'apply') {
          applications.push(req.postDataJSON())
          turn.applicationId = applications[0].requestId
          turn.application = {
            mode: applications[0].mode,
            sqlId: session.kind === 'sql' ? 'ai-sql-test' : undefined,
            jobId: 'job-1',
            state: 'ACCEPTED',
            message: '运行请求已受理',
            operationId: 'op-1',
            request: applications[0],
          }
          turn.state = 'WAITING_OBSERVATION'
        }
        if (parts[8] === 'observe') {
          turn.observation = {
            jobState: 'RUNNING',
            runningRevisionConfirmed: false,
            message: '尚缺协调代际证明',
            businessVerified: false,
            observedAt: new Date().toISOString(),
            issues: [
              { source: 'Operator', message: 'ServiceAccount 未配置', historical: false },
              {
                source: 'Pod',
                message: 'FailedScheduling: Insufficient memory',
                historical: false,
              },
              { source: '容器', message: 'CrashLoopBackOff', historical: false },
            ],
          }
        }
        data = turn
      }
    }
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ data }) })
  })
  await page.goto('/#/ai')
  return {
    sessions,
    sent,
    applications,
    errors,
    release: () => {
      released = true
      streamRelease?.()
    },
  }
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
  expect(f.sessions).toHaveLength(1)
  expect(f.sessions[0].kind).toBe('sql')
  expect(f.errors).toEqual([])
})
test('计划先展示，真实事件流结束后展示结论，过程默认折叠且会话可删除', async ({ page }) => {
  const f = await fixture(page)
  await create(page)
  await page.getByRole('textbox', { name: '输入需求' }).fill('检查内存并给出修改建议')
  await expect(page.getByRole('button', { name: '发送', exact: true })).toBeEnabled()
  await expect(page.getByLabel(/允许本轮最多/)).toHaveCount(0)
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect(page.getByText('正在调用模型制定执行计划…', { exact: true })).toBeVisible()
  await expect(page.locator('.harness-plan')).toHaveCount(0)
  await expect(page.locator('.harness-process')).not.toHaveAttribute('open', '')
  await expect(page.locator('.harness-turn')).not.toContainText('诊断结果')
  await expect.poll(() => f.sent.length).toBe(1)
  f.release()
  await expect(page.locator('.harness-turn')).toContainText('诊断结果')
  await expect(page.locator('.harness-plan')).toContainText('模型计划：核对作业上下文')
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
  expect(f.sessions).toHaveLength(0)
  expect(f.errors).toEqual([])
})
test('重启必须审阅和确认影响，受理后只能手动核验原操作', async ({ page }) => {
  const f = await fixture(page)
  await create(page)
  await page.getByRole('textbox', { name: '输入需求' }).fill('调整内存')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect(page.getByText('正在调用模型制定执行计划…', { exact: true })).toBeVisible()
  f.release()
  await expect(page.locator('.harness-candidate')).toBeVisible()
  await page.getByLabel('执行方式').selectOption('restart')
  await expect(page.getByRole('button', { name: '确认修改并启动 / 重启' })).toBeDisabled()
  await page.getByRole('combobox', { name: /^恢复方式/ }).selectOption('stateless')
  await page.getByLabel(/我接受重启中断/).check()
  await page.getByLabel(/我已审阅完整差异/).check()
  await page.getByRole('button', { name: '确认修改并启动 / 重启' }).click()
  await expect(page.locator('.harness-application')).toContainText('尚不能判定已运行成功')
  expect(f.applications).toHaveLength(1)
  expect(f.applications[0]).toMatchObject({
    confirmed: true,
    restoreMode: 'stateless',
    mode: 'restart',
    acceptImpact: true,
  })
  await page.getByRole('button', { name: '手动刷新运行结果' }).click()
  await expect(page.locator('.harness-application')).toContainText('缺少本次修订运行成功的完整证明')
  await expect(page.locator('.harness-observation-issues')).toContainText('FailedScheduling')
  await expect(page.locator('.harness-observation-issues')).toContainText('CrashLoopBackOff')
  expect(f.errors).toEqual([])
})
test('运维角色保持只读候选，窄屏对话没有横向溢出', async ({ page }) => {
  const f = await fixture(page, 'ROLE_OPERATOR')
  await page.setViewportSize({ width: 800, height: 900 })
  await create(page)
  await page.getByRole('textbox', { name: '输入需求' }).fill('只诊断')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect(page.getByText('正在调用模型制定执行计划…', { exact: true })).toBeVisible()
  f.release()
  await expect(page.locator('.harness-candidate')).toBeVisible()
  await expect(page.locator('.harness-approval')).toHaveCount(0)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  expect(f.errors).toEqual([])
})

test('SQL 首轮直达创建运行，Enter 发送且后续发送无需重复授权', async ({ page }) => {
  const f = await fixture(page)
  await page.getByRole('button', { name: '新建会话', exact: true }).first().click()
  await page.getByRole('dialog').getByRole('combobox').first().selectOption('sql')
  await page.getByRole('button', { name: '创建会话' }).click()
  const input = page.getByRole('textbox', { name: '输入需求' })
  await input.fill('随机生成10个字段，带watermark，写入print，Flink1.20')
  await input.press('Shift+Enter')
  expect(f.sent).toHaveLength(0)
  await input.press('Enter')
  await expect.poll(() => f.sent.length).toBe(1)
  f.release()
  await expect(page.getByRole('button', { name: '确认创建并运行', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '确认创建并运行', exact: true })).toBeDisabled()
  await page.getByLabel(/我已审阅完整差异/).check()
  await page.getByLabel(/我确认首次无状态运行/).check()
  await page.getByRole('button', { name: '确认创建并运行', exact: true }).click()
  await expect.poll(() => f.applications.length).toBe(1)
  expect(f.applications[0]).toMatchObject({ mode: 'run', confirmed: true, acceptImpact: true })
  await expect(page.getByRole('link', { name: '打开 SQL 工作台' })).toHaveAttribute(
    'href',
    /draft=ai-sql-test/,
  )
  await expect(page.getByRole('button', { name: '手动刷新运行结果' })).toBeVisible()
  await expect(page.getByRole('link', { name: '查看作业', exact: true })).toHaveAttribute(
    'href',
    '#/jobs?job=job-1',
  )
  await input.fill('说明当前结果')
  await expect(page.getByRole('button', { name: '发送', exact: true })).toBeEnabled()
  await input.press('Enter')
  await expect.poll(() => f.sent.length).toBe(2)
  expect(f.errors).toEqual([])
})

test('重复失败明确停止并给出错误定位，保留输入入口', async ({ page }) => {
  const f = await fixture(page, 'ROLE_ADMIN', 'stall')
  await create(page)
  await page.getByRole('textbox', { name: '输入需求' }).fill('修复同一处 SQL 错误')
  await page.getByRole('textbox', { name: '输入需求' }).press('Enter')
  f.release()
  await expect(page.locator('.harness-recovery')).toContainText('已停止无进展循环')
  await page.getByText('查看最近错误定位', { exact: true }).click()
  await expect(page.locator('.harness-recovery')).toContainText('11: invalid clause')
  await expect(page.getByRole('button', { name: '停止本轮' })).toHaveCount(0)
  await expect(page.getByRole('textbox', { name: '输入需求' })).toBeEnabled()
  expect(f.applications).toEqual([])
  expect(f.errors).toEqual([])
})
test('SQL 会话状态查询展示实时 RUNNING，模型旧部署说法默认折叠且不出现执行按钮', async ({
  page,
}) => {
  const f = await fixture(page, 'ROLE_ADMIN', 'status')
  await page.getByRole('button', { name: '新建会话', exact: true }).first().click()
  await page.getByRole('dialog').getByRole('combobox').first().selectOption('sql')
  await page.getByRole('button', { name: '创建会话' }).click()
  await page.getByRole('textbox', { name: '输入需求' }).fill('现在作业的状态是什么')
  await page.getByRole('textbox', { name: '输入需求' }).press('Enter')
  f.release()
  await expect(page.locator('.harness-scene')).toHaveText('本轮：作业状态查询')
  await expect(page.locator('.harness-current-status')).toContainText('RUNNING')
  await expect(page.getByText('可以部署了', { exact: true })).not.toBeVisible()
  await expect(page.locator('.harness-approval')).toHaveCount(0)
  expect(f.applications).toEqual([])
  expect(f.errors).toEqual([])
})
for (const theme of ['mist', 'pearl', 'dark'])
  test(`对话代码在 ${theme} 主题有语法高亮和可读行内代码`, async ({ page }) => {
    const f = await fixture(page)
    await create(page)
    await page.getByRole('combobox', { name: '界面主题' }).selectOption(theme)
    await page.getByRole('textbox', { name: '输入需求' }).fill('展示 SQL')
    await page.getByRole('textbox', { name: '输入需求' }).press('Enter')
    f.release()
    await expect(page.locator('.ai-code-block .ai-code-keyword').first()).toContainText('SELECT')
    await expect(page.locator('.ai-code-block .ai-code-string')).toContainText('print')
    await expect(page.locator('.ai-code-language')).toHaveText('SQL')
    const colors = await page.locator('.ai-code-block').evaluate((el) => {
      const style = getComputedStyle(el.querySelector('pre')!),
        keyword = getComputedStyle(el.querySelector('.ai-code-keyword')!)
      const luminance = (color: string) => {
        const channels = (color.match(/[\d.]+/g) || [])
          .slice(0, 3)
          .map(Number)
          .map((value) => {
            const channel = value / 255
            return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4
          })
        return channels[0]! * 0.2126 + channels[1]! * 0.7152 + channels[2]! * 0.0722
      }
      const background = luminance(style.backgroundColor)
      const ratios = [...el.querySelectorAll('code span')].map((span) => {
        const foreground = luminance(getComputedStyle(span).color)
        return (Math.max(foreground, background) + 0.05) / (Math.min(foreground, background) + 0.05)
      })
      return {
        text: style.color,
        background: style.backgroundColor,
        keyword: keyword.color,
        contrast: Math.min(...ratios),
        fontSize: getComputedStyle(el.querySelector('code')!).fontSize,
      }
    })
    expect(colors.keyword).not.toEqual(colors.text)
    expect(colors.text).not.toEqual(colors.background)
    expect(colors.contrast).toBeGreaterThanOrEqual(4.5)
    expect(colors.fontSize).toBe('12px')
    expect(f.errors).toEqual([])
  })
