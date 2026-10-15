import { test, expect, type Page } from '@playwright/test'
import {
  materializeVerification,
  type VerificationEvent,
} from '../src/modules/sql/utils/verificationResults'

test('主键增删改与无主键重复行分别合并，不损失大整数、空值或带逗号文本', () => {
  const event = (
    kind: string,
    cells: (string | null)[],
    sink = 1,
    keys = ['id'],
  ): VerificationEvent => ({
    sink,
    kind,
    columns: ['id', 'payload'],
    cells,
    keys,
    target: 'orders',
  })
  const result = materializeVerification([
    event('+I', ['9007199254740993', '中文,值\nnext']),
    event('-U', ['9007199254740993', '中文,值\nnext']),
    event('+U', ['9007199254740993', 'updated']),
    event('+I', ['2', 'deleted']),
    event('-D', ['2', 'deleted']),
    event('+I', ['3', null]),
    event('+I', ['1', 'same'], 2, []),
    event('+I', ['1', 'same'], 2, []),
    event('-D', ['1', 'same'], 2, []),
  ])
  expect(result[0].rows).toEqual([
    ['9007199254740993', 'updated'],
    ['3', null],
  ])
  expect(result[1].rows).toEqual([['1', 'same']])
})

test('旧文本不猜测字段，无法匹配的撤回明确提示结果范围', () => {
  expect(materializeVerification([{ sink: 1, kind: '+I', value: 'a,b,c' }])[0]).toMatchObject({
    rows: [],
    columns: [],
  })
  expect(
    materializeVerification([
      { sink: 1, kind: '-D', columns: ['id'], cells: ['missing'], keys: [] },
    ])[0].warning,
  ).toContain('未匹配')
})

async function fixture(page: Page) {
  let canceled = false
  const sql = "CREATE TABLE s(id STRING) WITH ('connector'='datagen'); SELECT id FROM s;"
  const draft = {
    id: 'draft',
    name: 'draft',
    sql,
    revision: 1,
    runtime: {
      imageId: 'flink',
      workspaceId: 'workspace',
      flinkConfiguration: {},
      podTemplate: {},
    },
  }
  const rows: VerificationEvent[] = [
    {
      sink: 1,
      kind: '+I',
      target: 'sink_events',
      columns: ['id', 'payload'],
      cells: ['9007199254740993', 'a'.repeat(2000)],
      keys: ['id'],
    },
    {
      sink: 1,
      kind: '+U',
      target: 'sink_events',
      columns: ['id', 'payload'],
      cells: ['9007199254740993', '最终数据'],
      keys: ['id'],
    },
  ]
  for (let id = 2; id <= 80; id++)
    rows.push({
      sink: 1,
      kind: '+I',
      target: 'sink_events',
      columns: ['id', 'payload'],
      cells: [String(id), '验证数据 ' + id],
      keys: ['id'],
    })
  const run = () => ({
    id: 'run',
    sqlId: 'draft',
    jobId: 'job',
    sqlRevision: 2,
    state: canceled ? 'CANCELED' : 'RUNNING',
    message: '测试验证',
    deadline: new Date(Date.now() + 120000).toISOString(),
    limit: 1000,
    rows,
  })
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    let data: any = []
    if (path === '/api/auth/me') data = { username: 'admin', roles: ['ROLE_ADMIN'] }
    else if (path === '/api/auth/csrf') data = { headerName: 'X-CSRF-TOKEN', token: 'test' }
    else if (path === '/api/system') data = { namespace: 'team', capabilities: [] }
    else if (path === '/api/catalog/images')
      data = [{ id: 'flink', repository: 'test/flink', version: '1.20.5', flinkVersion: '1.20.5' }]
    else if (path === '/api/sql/jobs') data = [draft]
    else if (path === '/api/sql/jobs/draft')
      data = {
        ...draft,
        revision: 2,
        ...(route.request().method() === 'PUT' ? route.request().postDataJSON() : {}),
      }
    else if (path === '/api/sql/validate')
      data = { level: 'PARSER', message: '语法检查通过', statementCount: 2, insertCount: 0 }
    else if (path === '/api/sessions') data = [{ id: 'sql-validation', validation: true }]
    else if (path === '/api/sessions/sql-validation/status')
      data = { id: 'sql-validation', state: 'RUNNING', runtime: { imageId: 'flink' } }
    else if (path.endsWith('/verifications')) data = run()
    else if (path.endsWith('/cancel')) {
      canceled = true
      data = run()
    } else if (path === '/api/sql/verifications/run') data = run()
    else if (path === '/api/artifacts/defaults')
      data = { bucket: 'files', rootPrefix: 'artifacts/', stateRoot: 's3://state' }
    else if (path === '/api/artifacts/objects') data = { directories: [], objects: [] }
    return route.fulfill({ json: { data, error: null } })
  })
  return { canceled: () => canceled }
}

test('结果面板只显示 Sink 字段，单一表格滚动，随时停止且窄屏不遮挡', async ({ page }) => {
  const f = await fixture(page)
  await page.goto('/#/sql?draft=draft')
  await page.getByRole('button', { name: 'SQL 检查', exact: true }).click()
  await page.getByRole('button', { name: 'SQL 验证', exact: true }).click()
  const panel = page.getByRole('region', { name: 'SQL 验证结果' })
  await expect(panel).toBeVisible()
  await expect(panel.locator('th')).toHaveText(['id', 'payload'])
  await expect(panel.locator('tbody tr')).toHaveCount(80)
  await expect(panel.locator('tbody')).toContainText('9007199254740993')
  await expect(panel.locator('tbody')).toContainText('最终数据')
  expect(await panel.evaluate((element) => !!element.closest('.sql-workbench'))).toBe(false)
  await panel.getByRole('button', { name: '停止验证', exact: true }).click()
  await expect(panel).toContainText('已停止')
  expect(f.canceled()).toBe(true)
  for (const width of [1440, 900, 390]) {
    await page.setViewportSize({ width, height: 900 })
    await panel.scrollIntoViewIfNeeded()
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(
      width,
    )
    await panel.locator('.verification-grid-scroll').evaluate((element) => {
      element.scrollTop = element.scrollHeight
    })
    await expect(panel.locator('tbody tr').last()).toContainText('验证数据 80')
    const header = await panel.locator('th').first().boundingBox(),
      scroll = await panel.locator('.verification-grid-scroll').boundingBox()
    expect(header!.y).toBeGreaterThanOrEqual(scroll!.y - 1)
    expect(header!.y + header!.height).toBeLessThanOrEqual(scroll!.y + scroll!.height + 1)
    const last = await panel.locator('tbody tr').last().boundingBox()
    expect(last!.y + last!.height).toBeLessThanOrEqual(scroll!.y + scroll!.height + 1)
    if (width === 1440 || width === 390)
      await panel.screenshot({ path: test.info().outputPath('verification-' + width + '.png') })
  }
})
