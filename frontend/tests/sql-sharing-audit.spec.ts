import { test, expect } from '@playwright/test'
import { platformFixture } from './fixtures/platform'

test('SQL 作业可复制最新草稿，取消和失败保留原作业，成功进入独立 SQL 编辑', async ({ page }) => {
  const f = await platformFixture(page)
  await page.route('**/api/development/links', (route) =>
    route.fulfill({ json: { data: { jar: { type: 'sql', sqlId: 's' } } } }),
  )
  await page.route('**/api/jobs/name-availability?*', (route) =>
    route.fulfill({ json: { data: { available: true } } }),
  )
  const copied = {
    id: 'sql-copy',
    name: 'sql-copy',
    description: '',
    revision: 1,
    sql: "SET 'parallelism.default'='2'; SELECT '最新 SQL';",
    runtime: { imageId: 'flink', workspaceId: 'independent', flinkConfiguration: {} },
  }
  let fail = true
  let payload: any
  await page.route('**/api/sql/jobs', (route) =>
    route.fulfill({ json: { data: fail ? [] : [copied] } }),
  )
  await page.route('**/api/sql/jobs/s/copy', async (route) => {
    payload = route.request().postDataJSON()
    await route.fulfill(
      fail
        ? {
            status: 409,
            json: {
              error: {
                code: 'REVISION_CONFLICT',
                message: '源 SQL 草稿已修改，请重新打开复制窗口',
              },
            },
          }
        : { json: { data: copied } },
    )
  })
  await page.route('**/api/sql/jobs/sql-copy', (route) => route.fulfill({ json: { data: copied } }))
  await page.goto('/#/jobs')
  const copy = page.getByRole('button', { name: '复制', exact: true })
  await expect(copy).toBeEnabled()
  await copy.click()
  const dialog = page.getByRole('dialog', { name: '复制作业' })
  await expect(dialog).toContainText('最新已保存的 SQL')
  await expect(dialog.getByRole('radio')).toHaveCount(0)
  await dialog.getByRole('button', { name: '取消', exact: true }).click()
  expect(payload).toBeUndefined()
  await copy.click()
  await page.getByLabel('新作业名称').fill('sql-copy')
  await dialog.getByRole('button', { name: '确认复制' }).click()
  await expect(dialog.getByRole('alert')).toContainText('源 SQL 草稿已修改')
  await expect(page).toHaveURL(/#\/jobs$/)
  await dialog.getByRole('button', { name: '取消', exact: true }).click()
  await copy.click()
  await page.getByLabel('新作业名称').fill('sql-copy')
  fail = false
  await dialog.getByRole('button', { name: '确认复制' }).click()
  await expect(page).toHaveURL(/#\/sql\?draft=sql-copy$/)
  await expect(page.locator('.sql-center .cm-content')).toContainText('最新 SQL')
  expect(payload).toEqual({ name: 'sql-copy', revision: 2 })
  expect(f.calls.filter((c) => /\/operations|\/publish|\/copy$/.test(c))).toEqual([])
  expect(f.errors).toEqual([])
})

test('SQL 右侧标签完整显示，短屏可独立滚动，普通及全屏均可切换', async ({ page }) => {
  await platformFixture(page)
  for (const size of [
    { width: 1440, height: 900 },
    { width: 1280, height: 560 },
    { width: 900, height: 650 },
    { width: 390, height: 844 },
  ]) {
    await page.setViewportSize(size)
    await page.goto('/#/sql?draft=s')
    const rail = page.getByRole('navigation', { name: 'SQL 辅助面板' })
    await expect(rail.getByRole('button', { name: 'Catalog', exact: true })).toBeVisible()
    for (const label of ['AI 助手', '部署配置', 'UDF 函数', '版本历史', 'Catalog']) {
      const button = rail.getByRole('button', { name: label, exact: true })
      await button.scrollIntoViewIfNeeded()
      const dimensions = await button.evaluate((el) => {
        const range = document.createRange()
        range.selectNodeContents(el)
        const text = range.getBoundingClientRect(),
          box = el.getBoundingClientRect()
        return {
          overflow: Math.max(el.scrollWidth - el.clientWidth, el.scrollHeight - el.clientHeight),
          left: text.left - box.left,
          right: box.right - text.right,
        }
      })
      expect(dimensions.overflow).toBeLessThanOrEqual(1)
      expect(dimensions.left, JSON.stringify({ size, label, dimensions })).toBeGreaterThanOrEqual(0)
      expect(dimensions.right, JSON.stringify({ size, label, dimensions })).toBeGreaterThanOrEqual(
        0,
      )
    }
    await rail.getByRole('button', { name: 'Catalog', exact: true }).click()
    await expect(rail.getByRole('button', { name: 'Catalog', exact: true })).toHaveAttribute(
      'aria-expanded',
      'true',
    )
    await page.getByRole('button', { name: '全屏编辑', exact: true }).click()
    await rail.getByRole('button', { name: '版本历史', exact: true }).scrollIntoViewIfNeeded()
    await rail.getByRole('button', { name: '版本历史', exact: true }).click()
    await expect(page.getByRole('region', { name: 'SQL 历史对比' })).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(
      size.width,
    )
    await page.getByRole('button', { name: '退出全屏', exact: true }).click()
  }
})

test('审计显示名称、编号、中文动作和完整时间，旧记录回退明确，日志上下文可查看', async ({
  page,
}) => {
  await platformFixture(page)
  await page.route('**/api/audit', (route) =>
    route.fulfill({
      json: {
        data: [
          {
            id: 'a',
            actor: 'admin',
            action: 'JOB_LOG_READ',
            target: '6562a654-95bd-4c96-8bab-527f2ff29a9e',
            targetName: 'order-sql',
            targetType: '作业',
            targetReference: 'flink-0000007',
            createdAt: '2026-10-29T02:00:00Z',
            timeSource: 'createdAt',
            outcome: 'SUCCESS',
            context: { pod: 'taskmanager-1', container: 'main', previous: true, lines: 1000 },
          },
          {
            id: 'b',
            actor: 'operator',
            action: 'SESSION_LOG_READ',
            target: 'session',
            targetName: 'sql-check',
            time: '2026-10-29T01:00:00Z',
            outcome: 'SUCCESS',
          },
          {
            id: 'c',
            actor: 'admin',
            action: 'DRAFT_DELETE',
            target: 'deleted',
            targetName: '已删除或未登记的作业',
            outcome: 'SUCCESS',
          },
        ],
      },
    }),
  )
  await page.goto('/#/audit')
  const first = page.locator('tbody tr').first()
  await expect(first).toContainText('order-sql')
  await expect(first).toContainText('flink-0000007')
  await expect(first).toContainText('查看作业容器日志')
  await expect(first).toContainText('2026/10/29')
  await expect(first).toContainText('按记录创建时间')
  await expect(page.getByRole('cell', { name: '时间未记录', exact: true })).toBeVisible()
  await first.getByText('查看详情', { exact: true }).click()
  await expect(first).toContainText('taskmanager-1 / main')
  await expect(first).toContainText('上次容器 · 最近 1000 行')
  await expect(first).toContainText('6562a654-95bd-4c96-8bab-527f2ff29a9e')
  await page.getByPlaceholder('搜索记录').fill('order-sql')
  await expect(page.locator('tbody tr')).toHaveCount(1)
})

test('暖白墨蓝使用清晰白底、深文字和可区分的强调色', async ({ page }) => {
  await platformFixture(page)
  await page.addInitScript(() => localStorage.setItem('flink-ui-theme', 'pearl'))
  await page.goto('/#/jobs')
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'pearl')
  const colors = await page.evaluate(() => {
    const style = getComputedStyle(document.documentElement)
    return Object.fromEntries(
      ['color-text', 'color-muted', 'color-primary', 'color-surface', 'color-border'].map((key) => [
        key,
        style
          .getPropertyValue('--' + key)
          .trim()
          .replace(/^#([0-9a-f])([0-9a-f])([0-9a-f])$/i, '#$1$1$2$2$3$3'),
      ]),
    )
  })
  const luminance = (hex: string) =>
    hex
      .slice(1)
      .match(/../g)!
      .map((s) => parseInt(s, 16) / 255)
      .map((c) => (c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4))
      .reduce((total, c, i) => total + c * [0.2126, 0.7152, 0.0722][i]!, 0)
  const contrast = (a: string, b: string) =>
    (Math.max(luminance(a), luminance(b)) + 0.05) / (Math.min(luminance(a), luminance(b)) + 0.05)
  expect(colors['color-surface']).toBe('#ffffff')
  expect(contrast(colors['color-text']!, '#ffffff')).toBeGreaterThan(10)
  expect(contrast(colors['color-muted']!, '#ffffff')).toBeGreaterThan(4.5)
  expect(contrast(colors['color-primary']!, '#ffffff')).toBeGreaterThan(4.5)
})
