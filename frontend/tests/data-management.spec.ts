import { confirmJarName } from './draft-name'
import { test, expect, type Page } from '@playwright/test'
async function login(page: Page) {
  await page.goto('/')
  await page.getByLabel('用户名').fill(process.env.E2E_USERNAME!)
  await page.getByLabel('密码', { exact: false }).first().fill(process.env.E2E_PASSWORD!)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('heading', { name: '作业管理', exact: true })).toBeVisible()
}
async function write(page: Page, path: string, data: object) {
  const csrf = (await (await page.request.get('/api/auth/csrf')).json()).data
  const response = await page.request.put(path, {
    data,
    headers: { [csrf.headerName]: csrf.token },
  })
  expect(response.ok(), await response.text()).toBeTruthy()
  return (await response.json()).data
}
test('Catalog JDBC connections, legacy lake profiles and explicit network tests', async ({
  page,
}) => {
  await login(page)
  const id = 'connection-' + Date.now(),
    lake = 'lake_' + Date.now()
  await write(page, '/api/catalog/configs/' + id, {
    name: id,
    kind: 'secretRef',
    secretName: 'test-only',
    secretKey: 'password',
    revision: 0,
  })
  let probes = 0
  page.on('request', (r) => {
    if (r.url().includes('/network/test')) probes++
  })
  await page.goto('/#/settings')
  await page.locator('summary').filter({ hasText: '网络端口验证' }).click()
  expect(probes).toBe(0)
  await page.getByLabel('诊断主机', { exact: true }).fill('127.0.0.1')
  await page.getByLabel('诊断端口', { exact: true }).fill('8080')
  await page.getByRole('button', { name: '验证端口', exact: true }).click()
  await expect(page.getByRole('status').filter({ hasText: 'TCP 端口可达' })).toBeVisible()
  expect(probes).toBe(1)
  await page.goto('/#/datasources')
  await expect(page).toHaveURL(/#\/catalogs$/)
  await page.getByRole('button', { name: '管理 JDBC 连接', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: 'Catalog JDBC 连接' })
  await dialog.getByRole('button', { name: '新增 JDBC 连接', exact: true }).click()
  await dialog.getByLabel('连接标识', { exact: true }).fill(id)
  await dialog.getByLabel('连接名称', { exact: true }).fill(id)
  await dialog.getByLabel('数据库类型', { exact: false }).selectOption('postgresql')
  await expect(dialog.getByLabel('端口', { exact: true })).toHaveValue('5432')
  await dialog.getByLabel('主机', { exact: true }).fill('postgres.test')
  await dialog.getByLabel('数据库', { exact: true }).fill('warehouse')
  await dialog.getByLabel('用户名', { exact: true }).fill('reader')
  await dialog.getByLabel('密码配置引用', { exact: false }).selectOption(id)
  await dialog.getByRole('button', { name: '保存连接', exact: true }).click()
  await expect(dialog.getByRole('row').filter({ hasText: id })).toBeVisible()
  await dialog.getByRole('button', { name: '关闭连接管理', exact: true }).click()
  await write(page, '/api/sql/catalogs/' + lake, {
    name: lake,
    type: 'paimon',
    warehouse: 's3://lake/warehouse/',
    defaultDatabase: 'default',
    revision: 0,
  })
  await page.reload()
  await page
    .getByLabel('Catalog 目录列表')
    .getByRole('button', { name: new RegExp(lake) })
    .click()
  await page.getByRole('button', { name: '初始化 SQL', exact: true }).click()
  await expect(page.getByRole('textbox', { name: 'Catalog 初始化 SQL' })).toContainText(
    "'type' = 'paimon'",
  )
})

test('SQL draft list is compact scrollable and displays help only on demand', async ({ page }) => {
  await login(page)
  const suffix = Date.now()
  for (let i = 0; i < 20; i++)
    await write(page, '/api/sql/jobs/scroll-' + suffix + '-' + i, {
      name: 'scroll-' + suffix + '-' + i,
      description: '',
      sql: "CREATE TEMPORARY TABLE sink_t (id INT) WITH ('connector'='print'); INSERT INTO sink_t VALUES (1);",
      runtime: {},
      revision: 0,
    })
  await page.getByRole('link', { name: 'SQL 开发', exact: true }).click()
  await page.getByLabel('搜索 SQL 草稿', { exact: true }).fill('scroll-' + suffix)
  await expect(page.locator('.sql-file')).toHaveCount(20)
  await expect(page.getByRole('tooltip')).toHaveCount(0)
  const help = page.locator('.sql-explorer .help-tip button').first()
  await help.hover()
  await expect(page.getByRole('tooltip')).toContainText('SQL 草稿')
  await page.locator('.sql-tabbar').hover()
  await expect(page.getByRole('tooltip')).toHaveCount(0)
  for (const width of [1920, 1440, 1280]) {
    await page.setViewportSize({ width, height: 900 })
    const bounds = await page.locator('.sql-explorer').boundingBox()
    expect(bounds!.width).toBeLessThanOrEqual(300)
    const file = await page.locator('.sql-file').first().boundingBox()
    expect(file!.height).toBeLessThanOrEqual(64)
    expect(
      await page.locator('.sql-file-list').evaluate((e) => e.scrollHeight > e.clientHeight),
    ).toBeTruthy()
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
    ).toBeTruthy()
  }
  await page.locator('.sql-file-list').evaluate((e) => (e.scrollTop = e.scrollHeight))
  await expect(page.locator('.sql-file').last()).toBeInViewport()
  await page.screenshot({ path: 'test-results/sql-compact-scroll.png', fullPage: true })
})
test('image selectors show Flink version when closed and tags in choices without digest fields', async ({
  page,
}) => {
  await login(page)
  const id = 'image-' + Date.now()
  await write(page, '/api/catalog/images/' + id, {
    name: '生产 SQL 镜像',
    repository: 'registry/flink',
    version: '1.20.3-prod-v8',
    flinkVersion: '1.20.3',
    revision: 0,
  })
  await page.goto('/#/jobs/jar/new')
  await confirmJarName(page, 'image-check-' + Date.now())
  await page.getByLabel('镜像版本', { exact: true }).selectOption(id)
  await expect(page.locator('.image-selection')).toHaveText('Flink 1.20.3')
  await expect(
    page.getByLabel('镜像版本', { exact: true }).locator('option:checked'),
  ).toContainText('tag: 1.20.3-prod-v8')
  await page.getByRole('link', { name: '运行镜像', exact: true }).click()
  await page.getByRole('button', { name: '＋ 新增', exact: true }).click()
  await expect(page.getByLabel('镜像 digest', { exact: false })).toHaveCount(0)
})
