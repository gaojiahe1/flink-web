import { test, expect } from '@playwright/test'

test('窄屏各页面保持内容宽度，表格横向查看且Catalog连接字段仍可编辑', async ({ page }) => {
  await page.goto('/')
  await page.getByLabel('用户名').fill(process.env.E2E_USERNAME!)
  await page.getByLabel('密码', { exact: false }).first().fill(process.env.E2E_PASSWORD!)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('heading', { name: '作业管理', exact: true })).toBeVisible()
  await page.setViewportSize({ width: 900, height: 1000 })
  await page.getByRole('button', { name: '收起导航' }).click()
  await expect(page.locator('.sidebar')).toHaveCSS('width', '68px')
  await page.setViewportSize({ width: 390, height: 1000 })
  for (const path of [
    'jobs',
    'jobs/jar/new',
    'sql',
    'templates',
    'images',
    'configs',
    'artifacts',
    'datasources',
    'catalogs',
    'users',
    'audit',
    'settings',
  ]) {
    await page.goto('/#/' + path)
    await expect(page.locator('main.content h1').first()).toBeVisible()
    await expect
      .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
      .toBe(true)
  }
  await page.goto('/#/datasources')
  await expect(page).toHaveURL(/#\/catalogs$/)
  await page.getByRole('button', { name: '管理 JDBC 连接', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: 'Catalog JDBC 连接' })
  await dialog.getByRole('button', { name: '新增 JDBC 连接', exact: true }).click()
  const name = dialog.getByLabel('连接名称', { exact: true }),
    host = dialog.getByLabel('主机', { exact: true })
  await name.fill('窄屏连接')
  await host.fill('database.test')
  await expect(name).toHaveValue('窄屏连接')
  for (const input of [name, host]) {
    await input.scrollIntoViewIfNeeded()
    const box = await input.boundingBox()
    expect(box!.x + box!.width).toBeLessThanOrEqual(390)
  }
  await dialog.getByRole('button', { name: '关闭连接管理', exact: true }).click()
})
