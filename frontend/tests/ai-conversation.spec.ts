import { test, expect, type Page } from '@playwright/test'
import { createHash } from 'node:crypto'
const hash = (s: string) => createHash('sha256').update(s).digest('hex')
const sql = "CREATE TABLE source_events (id STRING) WITH ('connector'='datagen'); CREATE TABLE sink_events (id STRING) WITH ('connector'='print'); INSERT INTO sink_events SELECT id FROM source_events;"
const runtime = { imageId: 'flink', runnerArtifactId: '', workspaceId: 'workspace', parallelism: 1, slots: 1, flinkConfiguration: {}, podTemplate: {}, dependencyArtifactIds: [] }
const model = { id: 'private', name: '团队模型', model: 'test-model', enabled: true, external: false, purposes: ['sql'], revision: 1 }
async function fixture(page: Page) {
  const errors: string[] = []; page.on('pageerror', e => errors.push(e.message))
  const submissions: any[] = [], writes: any[] = [], operations: string[] = []
  const drafts: Record<string, any> = { 'sql-draft': { id: 'sql-draft', name: 'sql-draft', description: '', sql, runtime, revision: 1 } }
  const conversations: Record<string, any> = {}, tasks: Record<string, any> = {}, inputs: Record<string, any> = {}
  let models: any[] = [model], pending = false, loseResponse = false, generated = sql + '\n-- AI 建议', unknowns: string[] = []
  function conversation(id: string) {
    return conversations[id] ??= { id: 'chat-' + id, sqlId: id, revision: 0, contentVersion: 0, summaryVersion: -1, confirmedVersion: -1, summary: '', messages: [], unknowns: [] }
  }
  function finish(id: string) {
    const task = tasks[id], input = inputs[id], conv = conversation(input.sqlId)
    if (!['QUEUED', 'RUNNING'].includes(task.state)) return task
    if (input.conversationVersion !== conv.contentVersion) return Object.assign(task, { state: 'FAILED', message: '会话已补充' })
    const generating = ['generate', 'repair'].includes(input.mode)
    const summary = conv.messages.filter((m: any) => m.role === 'user').map((m: any) => m.content).join('\n')
    task.state = 'SUCCEEDED'
    task.result = { reply: input.mode === 'explain' ? '当前 SQL 从 source_events 读取并写入 sink_events。' : '已整理本次全部对话，请核对总结。', summary, unknowns, risks: [], steps: [], evidence: [], baseSqlHash: hash(input.sql), conversationVersion: conv.contentVersion, ...(generating ? { sql: generated } : {}) }
    conv.messages.push({ id, role: 'assistant', content: task.result.reply, taskId: id, createdAt: new Date().toISOString() })
    if (generating) conv.lastSqlTaskId = id
    else Object.assign(conv, { summary, summaryVersion: conv.contentVersion, summarySqlHash: hash(input.sql), unknowns, confirmedVersion: -1 })
    conv.revision++
    return task
  }
  await page.route('**/api/**', async route => {
    const req = route.request(), url = new URL(req.url()), path = url.pathname, method = req.method()
    if (!path.startsWith('/api/')) return route.continue()
    let data: any = []
    const body = method === 'POST' || method === 'PUT' ? req.postDataJSON() : undefined
    const fail = (message: string, code = 'REVISION_CONFLICT') => route.fulfill({ status: 409, json: { data: null, error: { code, message } } })
    if (path === '/api/auth/me') data = { username: 'admin', roles: ['ROLE_ADMIN'] }
    else if (path === '/api/auth/csrf') data = { headerName: 'X-CSRF-TOKEN', token: 'test' }
    else if (path === '/api/system') data = { namespace: 'team', capabilities: [] }
    else if (path === '/api/ai/models') data = models
    else if (path.startsWith('/api/ai/conversations/')) {
      const id = decodeURIComponent(path.split('/')[4]!), conv = conversation(id)
      if (method !== 'GET') {
        if (body.revision !== conv.revision && !conv.messages.some((m: any) => m.id === body.messageId)) return fail('对话已更新，请重新加载')
        if (path.endsWith('/messages') && !conv.messages.some((m: any) => m.id === body.messageId)) {
          conv.messages.push({ id: body.messageId, role: 'user', content: body.content, mode: body.mode, createdAt: new Date().toISOString() }); conv.contentVersion++; conv.confirmedVersion = -1; conv.revision++
        } else if (path.endsWith('/summary')) {
          conv.contentVersion++; Object.assign(conv, { summary: body.summary, summaryVersion: conv.contentVersion, summarySqlHash: hash(body.sql), confirmedVersion: -1 }); conv.revision++
        } else if (path.endsWith('/confirm')) { conv.confirmedVersion = conv.contentVersion; conv.revision++ }
      }
      data = conv
    } else if (path === '/api/ai/tasks' && method === 'POST') {
      submissions.push(body); inputs[body.requestId] = body
      data = tasks[body.requestId] = { id: body.requestId, state: 'QUEUED', kind: 'sql', model: 'test-model', createdAt: new Date().toISOString() }
      if (loseResponse) { loseResponse = false; return route.abort('failed') }
    } else if (path.startsWith('/api/ai/tasks/')) {
      const id = path.split('/')[4]!
      data = tasks[id]
      if (!data) return route.fulfill({ status: 404, json: { data: null, error: { code: 'NOT_FOUND', message: '不存在' } } })
      if (path.endsWith('/cancel')) data.state = 'CANCELED'
      else if (!pending) data = finish(id)
    } else if (path === '/api/catalog/images') data = [{ id: 'flink', repository: 'test/flink', version: '1.20.5', flinkVersion: '1.20.5' }]
    else if (path === '/api/artifacts/defaults') data = { bucket: 'files', rootPrefix: 'artifacts/' }
    else if (path === '/api/artifacts/objects') data = { objects: [], directories: [], nextToken: null }
    else if (path === '/api/jobs/name-availability') data = { available: !drafts[url.searchParams.get('name')!] && url.searchParams.get('name') !== 'hidden-job' }
    else if (path === '/api/sql/jobs') data = Object.values(drafts)
    else if (path.startsWith('/api/sql/jobs/')) {
      const id = decodeURIComponent(path.split('/')[4]!)
      if (path.endsWith('/publish')) { operations.push(path); drafts[id].jobId = 'published-job'; drafts[id].revision++; data = { id: 'published-job', name: id, description: '', revision: 1, yaml: '' } }
      else if (method === 'PUT') {
        if (drafts[id] && body.revision === 0) return fail('作业名称已存在', 'JOB_NAME_EXISTS')
        writes.push(body); data = drafts[id] = { ...body, id, revision: (drafts[id]?.revision || 0) + 1 }
      } else data = drafts[id]
    } else if (path === '/api/sql/validate') data = { level: 'PARSER', statementCount: 3, insertCount: 1, message: '检查通过' }
    else if (path.includes('/actions/deploy')) { operations.push(path); data = { state: 'ACCEPTED', message: '已受理' } }
    else if (path.endsWith('/access')) data = { canEdit: true, canOperate: true }
    else if (path === '/api/development/links') data = {}
    return route.fulfill({ json: { data, error: null } })
  })
  return { submissions, writes, operations, drafts, conversations, errors, loseNextResponse: () => { loseResponse = true },
    setModels: (value: any[]) => { models = value }, setPending: (value: boolean) => { pending = value },
    setGenerated: (value: string) => { generated = value }, setUnknowns: (value: string[]) => { unknowns = value } }
}
async function openAssistant(page: Page) {
  await page.goto('/#/sql?draft=sql-draft')
  await expect(page.getByRole('textbox', { name: 'SQL 编辑器', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'AI 助手', exact: true }).click()
  const panel = page.locator('.ai-sql-panel')
  await expect(panel.getByLabel('任务模型')).toHaveValue('private')
  return panel
}
async function discuss(page: Page, text = '按订单统计已支付金额') {
  const panel = page.locator('.ai-sql-panel')
  await panel.getByRole('textbox', { name: '与 AI 对话' }).fill(text)
  await panel.getByRole('button', { name: '发送', exact: true }).click()
  await expect(panel.getByRole('button', { name: /确认总结并.*SQL/ })).toBeEnabled()
}

test('只有三个步骤，对话和内嵌总结持久化，确认后生成并显式应用', async ({ page }) => {
  const f = await fixture(page), panel = await openAssistant(page)
  const nav = page.getByRole('navigation', { name: 'SQL 需求向导' })
  await expect(nav.getByRole('button')).toHaveCount(3)
  await expect(page.getByText('选择数据', { exact: true })).toHaveCount(0)
  await discuss(page)
  expect(f.submissions[0]).toMatchObject({ kind: 'sql', mode: 'chat', conversationVersion: 1 })
  expect(f.writes).toHaveLength(0)
  await page.reload(); await page.getByRole('button', { name: 'AI 助手', exact: true }).click()
  await expect(panel.locator('.ai-chat-message')).toHaveCount(2)
  await expect(panel.locator('.ai-inline-summary .ai-summary-text')).toContainText('已支付金额')
  await panel.getByRole('button', { name: /确认总结并生成 SQL/ }).click()
  await expect(page.getByRole('textbox', { name: 'AI 建议 SQL', exact: true })).toBeVisible()
  expect(f.submissions.at(-1).mode).toBe('generate')
  expect(f.writes).toHaveLength(0)
  await panel.getByRole('button', { name: '应用建议 SQL', exact: true }).click()
  await expect(nav.getByRole('button', { name: /检查和保存/ })).toBeEnabled()
  await expect(page.getByRole('textbox', { name: 'SQL 编辑器', exact: true })).toContainText('AI 建议')
  await panel.getByRole('button', { name: '检查 SQL', exact: true }).click()
  await expect(panel).toContainText('检查通过')
  await panel.getByRole('button', { name: '保存 SQL 草稿', exact: true }).click()
  await expect.poll(() => f.writes.length).toBe(1)
  await expect(nav.getByRole('button', { name: /检查和保存/ })).toBeEnabled()
  expect(f.operations).toEqual([]); expect(f.errors).toEqual([])
})

test('随时补充后总结重新确认，旧建议和检查保存资格失效', async ({ page }) => {
  const f = await fixture(page), panel = await openAssistant(page)
  await discuss(page)
  await panel.getByRole('button', { name: /确认总结并生成 SQL/ }).click()
  await panel.getByRole('button', { name: '应用建议 SQL', exact: true }).click()
  await panel.getByRole('button', { name: '返回对话继续补充' }).click()
  await discuss(page, '金额保留两位小数')
  await expect(page.getByRole('navigation', { name: 'SQL 需求向导' }).getByRole('button', { name: /检查和保存/ })).toBeDisabled()
  await expect(panel.locator('.ai-inline-summary .ai-summary-text')).toContainText('金额保留两位小数')
  expect(f.conversations['sql-draft'].messages.filter((m: any) => m.role === 'user')).toHaveLength(2)
  expect(f.conversations['sql-draft'].confirmedVersion).toBe(-1)
})

test('旧 SQL 无对话时可解释整理，报错保存到对话再确认修复', async ({ page }) => {
  const f = await fixture(page), panel = await openAssistant(page)
  await panel.getByRole('button', { name: '解释并整理当前 SQL', exact: true }).click()
  await expect(panel.locator('.ai-chat-message')).toHaveCount(2)
  await expect(panel).toContainText('从 source_events 读取')
  await panel.getByRole('button', { name: '粘贴报错', exact: true }).click()
  await discuss(page, 'Column missing，请修复字段引用')
  await expect(panel.locator('.ai-inline-summary .ai-summary-text')).toContainText('Column missing')
  await panel.getByRole('button', { name: /确认总结并修改 SQL/ }).click()
  await expect.poll(() => f.submissions.at(-1)?.mode).toBe('repair')
})

test('有待补事项不允许确认生成，人工总结可编辑并持久化', async ({ page }) => {
  const f = await fixture(page), panel = await openAssistant(page)
  f.setUnknowns(['请确认输入表字段'])
  await panel.getByRole('textbox', { name: '与 AI 对话' }).fill('统计金额')
  await panel.getByRole('button', { name: '发送', exact: true }).click()
  await expect(panel).toContainText('待补充：请确认输入表字段')
  await expect(panel.getByRole('button', { name: /确认总结并生成 SQL/ })).toBeDisabled()
  await panel.getByRole('button', { name: '修改总结', exact: true }).click()
  await panel.getByRole('textbox', { name: '编辑需求总结' }).fill('人工核对后的完整口径')
  await panel.getByRole('button', { name: '保存总结', exact: true }).click()
  await expect.poll(() => f.conversations['sql-draft'].summary).toBe('人工核对后的完整口径')
})

test('外部模型先授权，取消后回复不能加入对话', async ({ page }) => {
  const f = await fixture(page); f.setModels([{ ...model, external: true }]); f.setPending(true)
  const panel = await openAssistant(page)
  await panel.getByRole('textbox', { name: '与 AI 对话' }).fill('统计订单')
  await panel.getByRole('button', { name: '发送', exact: true }).click()
  await expect(panel.getByRole('alert')).toContainText('明确允许')
  expect(f.submissions).toHaveLength(0)
  await panel.getByRole('checkbox').check()
  await panel.getByRole('button', { name: '发送', exact: true }).click()
  await expect(panel).toContainText('正在处理')
  await panel.getByRole('button', { name: '取消', exact: true }).click()
  await expect(panel.locator('.ai-chat-message')).toHaveCount(1)
})

test('新建先验证并确认唯一名称，SQL 为空，名称锁定且默认 stateless', async ({ page }) => {
  const f = await fixture(page)
  await page.goto('/#/sql?draft=sql-draft')
  await page.getByRole('button', { name: '＋ 新建 SQL', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByRole('textbox', { name: '新草稿作业名称' }).fill('hidden-job')
  await expect(dialog).toContainText('名称已存在')
  await dialog.getByRole('textbox', { name: '新草稿作业名称' }).fill('new-empty-sql')
  await expect(dialog).toContainText('名称可用')
  await expect(dialog.getByRole('button', { name: '确认并创建' })).toBeDisabled()
  await dialog.getByRole('checkbox').check()
  await dialog.getByRole('button', { name: '确认并创建' }).click()
  await expect(dialog).toBeHidden()
  await expect.poll(() => f.drafts['new-empty-sql']?.sql).toBe('')
  expect(f.drafts['new-empty-sql'].runtime.upgradeMode).toBe('stateless')
  await page.getByRole('button', { name: '部署配置', exact: true }).click()
  await expect(page.locator('input[data-field="name"]')).toBeDisabled()
  await expect(page.locator('input[data-field="name"]')).toHaveValue('new-empty-sql')
})

test('确认需求布局对话更宽、输入框可见，SQL 可收起且窄屏不溢出', async ({ page }) => {
  await fixture(page); const panel = await openAssistant(page); await discuss(page)
  const sizes = await Promise.all([panel.boundingBox(), page.locator('.sql-center').boundingBox()])
  expect(sizes[0]!.width).toBeGreaterThan(sizes[1]!.width)
  const compose = await panel.locator('.ai-composer').boundingBox()
  expect(compose!.y + compose!.height).toBeLessThanOrEqual(sizes[0]!.y + sizes[0]!.height + 2)
  await panel.getByRole('button', { name: '收起 SQL', exact: true }).click()
  await expect(page.locator('.sql-center')).toBeHidden()
  await panel.getByRole('button', { name: '展开 SQL', exact: true }).click()
  await expect(page.locator('.sql-center')).toBeVisible()
  await expect.poll(async () => (await panel.boundingBox())!.width / (await page.locator('.sql-center').boundingBox())!.width).toBeGreaterThan(1)
  await page.screenshot({ path: test.info().outputPath('conversation-desktop.png'), fullPage: true })
  for (const width of [1024, 736, 390]) { await page.setViewportSize({ width, height: 900 }); await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width) }
  await page.screenshot({ path: test.info().outputPath('conversation-mobile.png'), fullPage: true })
})

test('完整审阅可查看第 1001 行之后的修改', async ({ page }) => {
  const f = await fixture(page); f.setGenerated('-- line\n'.repeat(1002) + sql + '\n-- AI_TAIL_CHANGE')
  const panel = await openAssistant(page); await discuss(page)
  await panel.getByRole('button', { name: /确认总结并生成 SQL/ }).click()
  await panel.getByRole('button', { name: '完整对比', exact: true }).click()
  const editor = page.getByRole('textbox', { name: '建议 SQL 完整审阅' })
  await editor.click(); await page.keyboard.press('Control+End')
  await expect(editor).toContainText('AI_TAIL_CHANGE')
})

test('生成部署草稿后跳转作业列表', async ({ page }) => {
  const f = await fixture(page); await page.goto('/#/sql?draft=sql-draft')
  await page.getByRole('button', { name: '部署配置', exact: true }).click()
  await page.getByRole('button', { name: '生成部署草稿', exact: true }).click()
  await expect(page).toHaveURL(/#\/jobs$/)
  expect(f.operations).toEqual(['/api/sql/jobs/sql-draft/publish'])
})

test('部署受理后跳转作业列表，保留受理与运行的区分', async ({ page }) => {
  const f = await fixture(page); await page.goto('/#/sql?draft=sql-draft')
  await page.getByRole('button', { name: '部署配置', exact: true }).click()
  await page.getByRole('button', { name: '部署并启动', exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: '确定', exact: true }).click()
  await expect(page).toHaveURL(/#\/jobs$/)
  expect(f.operations).toEqual(['/api/sql/jobs/sql-draft/publish', '/api/jobs/published-job/actions/deploy'])
})

test('提交响应丢失时核对同号任务，不重复提交模型调用', async ({ page }) => {
  const f = await fixture(page)
  f.loseNextResponse()
  const panel = await openAssistant(page)
  await panel.getByRole('textbox', { name: '与 AI 对话' }).fill('不要重复提交')
  await panel.getByRole('button', { name: '发送', exact: true }).click()
  await expect(panel.getByRole('button', { name: /确认总结并生成 SQL/ })).toBeEnabled()
  expect(f.submissions).toHaveLength(1)
  expect(f.conversations['sql-draft'].messages).toHaveLength(2)
})

test('生成时修改 SQL 会取消任务，迟到回复不能成为可应用建议', async ({ page }) => {
  const f = await fixture(page), panel = await openAssistant(page)
  await discuss(page); f.setPending(true)
  await panel.getByRole('button', { name: /确认总结并生成 SQL/ }).click()
  await expect(panel).toContainText('正在处理')
  const editor = page.getByRole('textbox', { name: 'SQL 编辑器', exact: true })
  await editor.click(); await page.keyboard.press('Control+End'); await page.keyboard.type('\n-- changed during generation')
  await expect(panel.getByText('AI 正在处理本次对话…', { exact: true })).toBeHidden()
  f.setPending(false)
  await expect(page.getByRole('navigation', { name: 'SQL 需求向导' }).getByRole('button', { name: /查看 SQL/ })).toBeDisabled()
  await expect(editor).toContainText('changed during generation')
  expect(f.writes).toHaveLength(0)
})
