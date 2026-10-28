import { test, expect } from '@playwright/test'
import { platformFixture } from './fixtures/platform'

const routes = [
  'jobs',
  'sql',
  'jar/jar/edit',
  'catalogs',
  'sessions',
  'templates',
  'images',
  'configs',
  'artifacts',
  'users',
  'audit',
  'settings',
  'settings/models',
]
for (const theme of ['mist', 'pearl', 'dark']) {
  for (const width of [1440, 390]) {
    test(`主题 ${theme} 全站边界与工作面 ${width}`, async ({ page }) => {
      await page.setViewportSize({ width, height: width === 390 ? 844 : 900 })
      await page.addInitScript((t) => localStorage.setItem('flink-ui-theme', t), theme)
      const f = await platformFixture(page)
      for (const route of routes) {
        await page.goto('/#/' + route)
        await expect(page.locator('main.content')).toHaveAttribute(
          'data-page',
          route.split('/')[0]!,
        )
        await expect(page.locator('main.content h1').first()).toBeVisible()
        await expect(page.getByLabel('界面主题')).toHaveValue(theme)
        await expect(page.locator('html')).toHaveAttribute('data-theme', theme)
        await expect
          .poll(() => page.evaluate(() => document.documentElement.scrollWidth))
          .toBeLessThanOrEqual(width)
        if (theme === 'dark') {
          const leaks = await page.locator('main.content').evaluate((root) =>
            [...root.querySelectorAll<HTMLElement>('*')]
              .filter((e) => {
                if (!e.getClientRects().length || e.tagName === 'OPTION') return false
                const color = getComputedStyle(e).backgroundColor.match(
                  /^rgb\((\d+), (\d+), (\d+)\)$/,
                )
                return color && color.slice(1).every((c) => Number(c) > 200)
              })
              .map((e) => e.className)
              .slice(0, 8),
          )
          expect(leaks, route + ' 残留浅色背景').toEqual([])
        }
        if (width === 1440 && ['jobs', 'sql', 'jar/jar/edit', 'catalogs'].includes(route))
          await page.screenshot({
            path: test.info().outputPath(route.replaceAll('/', '-') + '.png'),
          })
      }
      await page.goto('/#/login')
      await expect(page.getByLabel('界面主题')).toHaveValue(theme)
      await expect(page.getByRole('button', { name: '登录', exact: true })).toBeVisible()
      expect(f.errors).toEqual([])
    })
  }
}

test('主题可持久化、跨标签页同步，切换不保存 SQL 或重建编辑器', async ({ page, context }) => {
  const f = await platformFixture(page)
  await page.goto('/#/sql')
  const editor = page.locator('.sql-center > .code-editor .cm-content')
  await expect(editor).toBeVisible()
  await editor.click()
  await page.keyboard.press('Control+End')
  await page.keyboard.type('\n-- unsaved theme check')
  const content = await editor.innerText()
  await editor.evaluate((e) => ((window as any).__originalEditor = e))
  await page.getByLabel('界面主题').selectOption('dark')
  expect(await editor.innerText()).toBe(content)
  expect(await editor.evaluate((e) => (window as any).__originalEditor === e)).toBe(true)
  expect(await editor.locator('..').evaluate((e) => getComputedStyle(e).backgroundColor)).not.toBe(
    'rgb(255, 255, 255)',
  )
  expect(f.writes).toEqual([])
  // Undo must still be available in the existing editor after changing the selector.
  await editor.click()
  await page.keyboard.press('Control+End')
  await page.keyboard.press('Control+z')
  await expect(editor).not.toContainText('theme check')
  const second = await context.newPage()
  await platformFixture(second)
  await second.goto('/#/jobs')
  await expect(second.getByLabel('界面主题')).toHaveValue('dark')
  await second.getByLabel('界面主题').selectOption('pearl')
  await expect(page.getByLabel('界面主题')).toHaveValue('pearl')
  await page.reload()
  await expect(page.getByLabel('界面主题')).toHaveValue('pearl')
  expect(f.errors).toEqual([])
})

test('无效历史主题与禁止持久化时仍能使用主题和登录', async ({ page }) => {
  await platformFixture(page)
  await page.addInitScript(() => {
    localStorage.setItem('flink-ui-theme', '<invalid>')
    Storage.prototype.setItem = () => {
      throw new DOMException('blocked', 'SecurityError')
    }
    Storage.prototype.getItem = () => {
      throw new DOMException('blocked', 'SecurityError')
    }
  })
  await page.goto('/#/login')
  await expect(page.getByLabel('界面主题')).toHaveValue('mist')
  await page.getByLabel('界面主题').selectOption('dark')
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  await expect(page.getByLabel('密码', { exact: true })).toBeEnabled()
  await page.goto('/#/jobs')
  await expect(page.getByRole('heading', { name: '作业管理', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '收起导航' }).click()
  await expect(page.locator('.sidebar')).toHaveCSS('width', '68px')
})

test('深色覆盖 Catalog 嵌套弹窗、确认框和 SQL 高亮差异', async ({ page }) => {
  await platformFixture(page)
  await page.goto('/#/catalogs')
  await page.getByLabel('界面主题').selectOption('dark')
  await page.getByRole('button', { name: '＋ 注册 Catalog' }).click()
  const catalog = page.getByRole('dialog', { name: 'Catalog 配置' })
  await expect(catalog).toHaveCSS('background-color', 'rgb(35, 36, 43)')
  await catalog.getByRole('button', { name: '管理 JDBC 连接', exact: true }).click()
  const connection = page.getByRole('dialog', { name: 'Catalog JDBC 连接' })
  await expect(connection).toHaveCSS('background-color', 'rgb(35, 36, 43)')
  await page.keyboard.press('Escape')
  await page.keyboard.press('Escape')
  await page.goto('/#/sql')
  await page.getByRole('button', { name: '版本历史', exact: true }).click()
  const changed = page.locator('.sql-history-diff .cm-changedText').first()
  await expect(changed).toBeVisible()
  const before = await changed.evaluate((e) => getComputedStyle(e).backgroundColor)
  await page.getByLabel('界面主题').selectOption('mist')
  expect(await changed.evaluate((e) => getComputedStyle(e).backgroundColor)).not.toBe(before)
})
