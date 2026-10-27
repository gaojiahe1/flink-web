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
for (const size of [
  { width: 1440, height: 900 },
  { width: 1280, height: 720 },
  { width: 900, height: 650 },
  { width: 390, height: 844 },
]) {
  test(`全部页面统一边界、标题与控件密度 ${size.width}`, async ({ page }) => {
    await page.setViewportSize(size)
    const f = await platformFixture(page)
    for (const route of routes) {
      await page.goto('/#/' + route)
      const main = page.locator('main.content')
      const title = main.locator('h1').first()
      const titles: Record<string, string> = {
        jobs: '作业管理',
        sql: 'SQL 开发',
        'jar/jar/edit': 'JAR 开发',
        catalogs: 'Catalog 工作台',
        sessions: 'Session 集群',
        templates: '默认模板',
        images: '镜像管理',
        configs: '配置与密钥',
        artifacts: '文件与制品',
        users: '用户管理',
        audit: '操作审计',
        settings: '系统设置',
        'settings/models': '模型服务',
      }
      await expect(title, route).toHaveText(titles[route])
      await expect(title, route).toBeVisible()
      await expect(title, route).toHaveCSS('font-size', '18px')
      await expect
        .poll(() => page.evaluate(() => document.documentElement.scrollWidth), { message: route })
        .toBeLessThanOrEqual(size.width)
      const shell = await page.locator('.main-shell').boundingBox(),
        content = await main.boundingBox()
      expect(Math.round(content!.x), route).toBe(Math.round(shell!.x))
      expect(Math.round(content!.width), route).toBe(Math.round(shell!.width))
      const controls = await main
        .locator(
          'input:visible:not([type=checkbox]):not([type=radio]):not([type=file]), select:visible:not([multiple]), .button:visible',
        )
        .evaluateAll((items) =>
          items.map((el) => ({
            name: el.getAttribute('aria-label') || el.textContent?.slice(0, 30),
            height: el.getBoundingClientRect().height,
          })),
        )
      for (const control of controls)
        expect(control.height, `${route} ${control.name}`).toBeCloseTo(30, 0)
      await page.screenshot({ path: test.info().outputPath(route.replaceAll('/', '-') + '.png') })
    }
    await page.goto('/#/login')
    await expect(page.getByRole('button', { name: '登录', exact: true })).toBeVisible()
    await expect
      .poll(() => page.evaluate(() => document.documentElement.scrollWidth))
      .toBeLessThanOrEqual(size.width)
    await page.screenshot({ path: test.info().outputPath('login.png') })
    expect(f.errors).toEqual([])
  })
}

test('常规资源编辑、Session与Catalog表单分组紧凑，完整操作保持可达', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 720 })
  const f = await platformFixture(page)
  for (const route of ['templates', 'images', 'configs', 'users']) {
    await page.goto('/#/' + route)
    await page.getByRole('button', { name: '＋ 新增', exact: true }).click()
    const editor = page.getByRole('region', { name: '记录编辑' })
    await expect(editor).toBeVisible()
    await expect(editor.getByRole('button', { name: '保存', exact: true })).toBeEnabled()
    await page.screenshot({ path: test.info().outputPath(route + '-editor.png') })
    await editor.getByRole('button', { name: '取消', exact: true }).click()
  }
  await page.goto('/#/sessions')
  await page.getByRole('button', { name: '＋ 新建 Session', exact: true }).click()
  await expect(page.getByLabel('集群名称', { exact: false })).toBeVisible()
  await page.getByRole('button', { name: '保存配置', exact: true }).scrollIntoViewIfNeeded()
  await expect(page.getByRole('button', { name: '保存配置', exact: true })).toBeInViewport()
  expect(f.errors).toEqual([])
})
