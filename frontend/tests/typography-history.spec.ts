import { test, expect } from '@playwright/test'
import { platformFixture } from './fixtures/platform'

test.use({
  launchOptions: {
    ...(process.env.E2E_BROWSER ? { executablePath: process.env.E2E_BROWSER } : {}),
    ignoreDefaultArgs: ['--hide-scrollbars'],
  },
})

for (const theme of ['mist', 'pearl', 'dark']) {
  test(`历史比较在长内容、原生滚动条及短屏下两栏对齐 ${theme}`, async ({ page }) => {
    const f = await platformFixture(page)
    await page.route('**/api/sql/jobs/s/versions', (route) =>
      route.fulfill({
        json: {
          data: [
            {
              id: 'long',
              actor: 'admin',
              createdAt: '2026-10-28T04:00:00Z',
              sql: Array.from(
                { length: 140 },
                (_, i) => '-- 历史行 ' + i + ' ' + 'long_column_name_'.repeat(i % 7 === 0 ? 20 : 1),
              ).join('\n'),
            },
          ],
        },
      }),
    )
    await page.goto('/#/sql')
    await page.getByLabel('界面主题').selectOption(theme)
    await page.getByRole('button', { name: '版本历史', exact: true }).click()
    const diff = page.locator('.sql-history-diff')
    for (const size of [
      { width: 1440, height: 900 },
      { width: 1280, height: 720 },
      { width: 900, height: 650 },
      { width: 390, height: 844 },
    ]) {
      await page.setViewportSize(size)
      await expect(diff).toBeVisible()
      await page.evaluate(() => document.fonts.ready)
      await expect
        .poll(() =>
          diff.evaluate((root) => {
            const labels = [...root.querySelectorAll('.diff-labels > span')].map((e) =>
              e.getBoundingClientRect(),
            )
            const panes = [...root.querySelectorAll('.cm-mergeViewEditor')].map((e) =>
              e.getBoundingClientRect(),
            )
            return Math.max(
              ...labels.map((r, i) => Math.abs(r.x - panes[i]!.x)),
              ...labels.map((r, i) => Math.abs(r.width - panes[i]!.width)),
            )
          }),
        )
        .toBeLessThan(2)
      const sizes = await diff.locator('.cm-mergeViewEditor').evaluateAll((panes) =>
        panes.map((e) => ({
          top: e.getBoundingClientRect().top,
          height: e.getBoundingClientRect().height,
        })),
      )
      expect(Math.abs(sizes[0]!.top - sizes[1]!.top)).toBeLessThan(1)
      expect(Math.abs(sizes[0]!.height - sizes[1]!.height)).toBeLessThan(2)
      await diff.locator('.cm-mergeView').evaluate((e) => (e.scrollTop = e.scrollHeight))
      await expect(diff.locator('.cm-line').filter({ hasText: '历史行 139' })).toBeVisible()
      await page.screenshot({ path: test.info().outputPath('history-' + size.width + '.png') })
    }
    expect(f.writes).toEqual([])
    expect(f.errors).toEqual([])
  })
}

test('MiSans 本地实际加载，页面/分组/正文/说明保持字号层级，代码独立等宽', async ({ page }) => {
  const f = await platformFixture(page)
  const remote: string[] = []
  page.on('request', (r) => {
    if (r.resourceType() === 'font' && !r.url().startsWith(new URL(page.url()).origin))
      remote.push(r.url())
  })
  await page.goto('/#/jar/jar/edit')
  await expect(page.getByRole('heading', { name: 'JAR 开发', exact: true })).toBeVisible()
  await page.evaluate(() => document.fonts.load('400 13px "MiSans VF"', '状态存储与故障恢复'))
  expect(
    await page.evaluate(() => document.fonts.check('400 13px "MiSans VF"', '状态存储与故障恢复')),
  ).toBe(true)
  const font = await page.locator('main').evaluate((e) => getComputedStyle(e).fontFamily)
  expect(font).toContain('MiSans VF')
  const section = page.locator('.state-storage-form')
  await section.locator('.strategy-help summary').click()
  await expect(section.locator('h3')).toHaveCSS('font-size', '14px')
  await expect(section.locator('p.muted').first()).toHaveCSS('font-size', '12px')
  await expect(section.locator('.strategy-description')).toHaveCSS('font-size', '12px')
  await expect(section.locator('.strategy-help dt').first()).toHaveCSS('font-size', '13px')
  await expect(section.locator('.strategy-help dd').first()).toHaveCSS('font-size', '12px')
  await page.screenshot({ path: test.info().outputPath('state-typography.png'), fullPage: true })
  const family = await section.locator('h3').evaluate((e) => getComputedStyle(e).fontFamily)
  expect(family).toBe(font)
  const cdp = await page.context().newCDPSession(page)
  await cdp.send('DOM.enable')
  await cdp.send('CSS.enable')
  const { root } = await cdp.send('DOM.getDocument')
  const { nodeId } = await cdp.send('DOM.querySelector', {
    nodeId: root.nodeId,
    selector: '.state-storage-form h3',
  })
  const { fonts } = await cdp.send('CSS.getPlatformFontsForNode', { nodeId })
  expect(fonts.some((f) => f.isCustomFont && f.familyName.includes('MiSans'))).toBe(true)
  await section.scrollIntoViewIfNeeded()
  await section.screenshot({
    path: test.info().outputPath('state-section.png'),
    animations: 'disabled',
  })
  await page.goto('/#/sql')
  const editor = page.locator('.sql-center > .code-editor .cm-content')
  await expect(editor).toBeVisible()
  expect(await editor.evaluate((e) => getComputedStyle(e).fontFamily)).toContain('Consolas')
  expect(remote).toEqual([])
  expect(f.errors).toEqual([])
})

test('短历史两侧代码面填满同一高度，标题和滚动条底边一致', async ({ page }) => {
  await platformFixture(page)
  await page.goto('/#/sql')
  await page.getByRole('button', { name: '版本历史', exact: true }).click()
  const diff = page.locator('.sql-history-diff')
  await expect
    .poll(() =>
      diff.evaluate((root) => {
        const scrollers = [...root.querySelectorAll('.cm-scroller')].map((e) =>
          e.getBoundingClientRect(),
        )
        const area = root.querySelector('.cm-mergeView')!.getBoundingClientRect()
        return Math.max(
          Math.abs(scrollers[0]!.bottom - scrollers[1]!.bottom),
          Math.abs(scrollers[0]!.height - area.height),
        )
      }),
    )
    .toBeLessThan(2)
  await diff.screenshot({
    path: test.info().outputPath('short-history.png'),
    animations: 'disabled',
  })
})

test('字体资源暂不可用时正文与操作仍可见，使用系统回退字体', async ({ page }) => {
  const f = await platformFixture(page)
  await page.route('**/fonts/misans/*.woff2', (route) => route.abort())
  await page.goto('/#/jobs')
  await expect(page.getByRole('heading', { name: '作业管理', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'AI 助手', exact: true })).toBeEnabled()
  await page.getByLabel('界面主题').selectOption('dark')
  await expect(page.getByRole('heading', { name: '作业管理', exact: true })).toHaveCSS(
    'font-size',
    '18px',
  )
  expect(f.errors).toEqual([])
})
