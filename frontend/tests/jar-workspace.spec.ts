import { confirmJarName } from './draft-name'
import { test, expect, type Page } from '@playwright/test'
import { stringify } from 'yaml'
async function fixture(page: Page) {
  const errors: string[] = [], calls: string[] = []
  page.on('pageerror', e => errors.push(e.message))
  const jobs = ['first-job', 'second-job'].map(id => ({ id, name: id, description: id + ' description', revision: 1, flinkVersion: '1.20.5', updatedAt: new Date().toISOString(), yaml: stringify({ apiVersion: 'flink.apache.org/v1beta1', kind: 'FlinkDeployment', metadata: { name: id, labels: { 'flink-web/editor': 'jar-v1' } }, spec: { image: 'test/flink:1.20.5', flinkVersion: 'v1_20', serviceAccount: 'team-flink', flinkConfiguration: {}, jobManager: { resource: { cpu: 1, memory: '2g' } }, taskManager: { resource: { cpu: 1, memory: '2g' } }, job: { jarURI: 'local:///opt/flink/usrlib/job.jar', parallelism: 1, upgradeMode: 'stateless' } } }) }))
  await page.route('**/api/**', async route => {
    const req = route.request(), path = new URL(req.url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    calls.push(req.method() + ' ' + path)
    let data: any = []
    if (path === '/api/auth/me') data = { username: 'admin', roles: ['ROLE_ADMIN'] }
    else if (path === '/api/auth/csrf') data = { headerName: 'X-CSRF-TOKEN', token: 'test' }
    else if (path === '/api/system') data = { namespace: 'team' }
    else if (path === '/api/jobs/name-availability') data = { available: new URL(req.url()).searchParams.get('name') !== 'first-job' }
    else if (path === '/api/jobs') data = jobs
    else if (path === '/api/jobs/refresh') data = { items: [], stopProgress: { 'first-job': { phase: 'DONE' } }, queriedAt: new Date().toISOString() }
    else if (path === '/api/development/jar/jobs') data = jobs
    else if (path === '/api/development/jar/deployment-status') data = { 'first-job': 'UNDEPLOYED', 'second-job': 'DEPLOYED' }
    else if (path === '/api/development/links') data = Object.fromEntries(jobs.map(j => [j.id, { type: 'jar' }]))
    else if (path.startsWith('/api/jobs/') || path.endsWith('/open')) data = jobs.find(j => path.includes(j.id))
    else if (path === '/api/catalog/images') data = [{ id: 'flink', repository: 'test/flink', version: '1.20.5', flinkVersion: '1.20.5' }]
    else if (path === '/api/artifacts/defaults') data = { rootPrefix: 'artifacts/', bucket: 'files' }
    else if (path === '/api/artifacts/objects') data = { objects: [], directories: [] }
    else if (path.endsWith('/access')) data = { canEdit: true, canOperate: true }
    await route.fulfill({ json: { data, error: null } })
  })
  return { errors, calls }
}
test('JAR 左侧列表与编辑器并列，选择后回填正确，搜索及折叠不影响编辑', async ({ page }) => {
  const f = await fixture(page)
  await page.goto('/#/jar')
  await expect(page).toHaveURL(/#\/jar\/first-job\/edit$/)
  await expect(page.getByLabel('描述', { exact: true })).toHaveValue('first-job description')
  const list = page.getByRole('complementary', { name: 'JAR 草稿列表' }), pane = page.locator('.jar-edit-pane')
  const a = await list.boundingBox(), b = await pane.boundingBox()
  expect(a!.x + a!.width).toBeLessThanOrEqual(b!.x + 1)
  await list.getByRole('button', { name: /^second-job/ }).click()
  await expect(page.getByLabel('描述', { exact: true })).toHaveValue('second-job description')
  await expect(list.getByRole('button', { name: /^second-job/ })).toHaveAttribute('aria-current', 'page')
  await list.getByLabel('搜索 JAR 草稿').fill('first')
  await expect(list.locator('.jar-draft-row')).toHaveCount(1)
  await expect(page.getByLabel('描述', { exact: true })).toHaveValue('second-job description')
  await list.getByRole('button', { name: '收起 JAR 草稿栏' }).click()
  expect((await list.boundingBox())!.width).toBeLessThan(50)
  await list.getByRole('button', { name: '展开 JAR 草稿栏' }).click()
  await page.screenshot({ path: test.info().outputPath('jar-desktop.png'), fullPage: true })
  for (const width of [1280, 1024, 736, 390]) { await page.setViewportSize({ width, height: 900 }); await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width) }
  await page.screenshot({ path: test.info().outputPath('jar-mobile.png'), fullPage: true })
  expect(f.errors).toEqual([])
})
test('切换 JAR 草稿保护未保存内容，取消保持原文，确认后重新加载目标作业', async ({ page }) => {
  await fixture(page); await page.goto('/#/jar/first-job/edit')
  await expect(page.getByLabel('描述', { exact: true })).toHaveValue('first-job description')
  await page.getByLabel('描述', { exact: true }).fill('unsaved content')
  const list = page.getByRole('complementary', { name: 'JAR 草稿列表' })
  await list.getByRole('button', { name: /^second-job/ }).click()
  const dialog = page.getByRole('dialog', { name: '确认操作' })
  await expect(dialog).toContainText('未保存')
  await dialog.getByRole('button', { name: '取消', exact: true }).click()
  await expect(page).toHaveURL(/first-job\/edit$/)
  await expect(page.getByLabel('描述', { exact: true })).toHaveValue('unsaved content')
  await list.getByRole('button', { name: /^second-job/ }).click()
  await dialog.getByRole('button', { name: '确定', exact: true }).click()
  await expect(page.getByLabel('描述', { exact: true })).toHaveValue('second-job description')
})

test('旧 JAR 尚在加载时可新建，迟到响应不回填或打开旧草稿', async ({ page }) => {
  const f = await fixture(page)
  let release!: () => void
  const held = new Promise<void>(resolve => { release = resolve })
  await page.route('**/api/jobs/first-job', async route => { await held; await route.fallback() })
  await page.goto('/#/jar/first-job/edit')
  await page.getByRole('link', { name: '＋ 新建 JAR 作业', exact: true }).click()
  await expect(page.getByRole('dialog')).toContainText('新建 JAR 作业')
  release()
  await confirmJarName(page, 'new-jar-test')
  await expect(page.getByLabel('作业名称', { exact: false }).first()).toHaveValue('new-jar-test')
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(page).toHaveURL(/#\/jar\/new$/)
  expect(f.calls).not.toContain('POST /api/development/jar/jobs/first-job/open')
})

test('JAR 命名弹窗查重并锁定名称，表单默认更宽且支持拖动和键盘', async ({ page }) => {
  await fixture(page)
  await page.goto('/#/jar/new')
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('新草稿作业名称').fill('first-job')
  await expect(dialog).toContainText('作业名称已存在')
  await dialog.getByRole('checkbox').check()
  await expect(dialog.getByRole('button', { name: '确认并创建' })).toBeDisabled()
  await confirmJarName(page, 'new-job')
  await expect(page.getByLabel('作业名称', { exact: false }).first()).toBeDisabled()
  const form = page.locator('.editor-form-column'), yaml = page.locator('.yaml-panel'), bar = page.getByRole('separator', { name: '调整表单与 YAML 宽度' })
  expect((await form.boundingBox())!.width).toBeGreaterThan((await yaml.boundingBox())!.width)
  const width = (await form.boundingBox())!.width, bounds = (await bar.boundingBox())!
  await page.mouse.move(bounds.x + 4, bounds.y + 30)
  await page.mouse.down(); await page.mouse.move(bounds.x - 100, bounds.y + 30); await page.mouse.up()
  expect((await form.boundingBox())!.width).toBeLessThan(width - 50)
  await bar.focus(); await bar.press('Home'); await expect(bar).toHaveAttribute('aria-valuenow', '62')
  await bar.press('ArrowRight'); await expect(bar).toHaveAttribute('aria-valuenow', '64')
  await page.screenshot({ path: test.info().outputPath('jar-split.png'), fullPage: true })
  await page.setViewportSize({ width: 1024, height: 900 })
  await expect(bar).toBeHidden()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(1024)
})

test('作业管理、JAR 和 SQL 草稿共用已停止状态，查询失败显示未知', async ({ page }) => {
  await fixture(page)
  await page.goto('/#/jobs')
  await expect(page.locator('.jobs-panel tbody tr').filter({ hasText: 'first-job' })).toContainText('已停止')
  await page.goto('/#/jar')
  await expect(page.getByRole('button', { name: /^first-job/ })).toContainText('已停止')
  await page.route('**/api/sql/jobs', route => route.fulfill({ json: { data: [{ id: 'first-sql', name: 'first-sql', sql: '', description: '', revision: 1, jobId: 'first-job', runtime: { imageId: 'flink' } }] } }))
  await page.goto('/#/sql?draft=first-sql')
  await expect(page.locator('.sql-file').filter({ hasText: 'first-sql' })).toContainText('已停止 · 1.20.5')
  const sqlList = page.locator('.sql-explorer')
  expect((await sqlList.boundingBox())!.width).toBeGreaterThan(200)
  await page.screenshot({ path: test.info().outputPath('sql-list.png'), fullPage: true })
  await page.route('**/api/jobs/refresh', route => route.fulfill({ status: 503, json: { message: '临时不可用' } }))
  await page.goto('/#/jar/first-job/edit')
  await expect(page.getByRole('button', { name: /^first-job/ })).toContainText('状态未知')
  await expect(page.getByRole('alert').filter({ hasText: '状态查询失败' })).toBeVisible()
})
