import { test, expect } from '@playwright/test'
import { confirmJarName } from './draft-name'
import { login, write, connection } from './fixtures/live'

test('Catalog registration, SQL draft persistence and protected deletion', async ({ page }) => {
  await login(page)
  const id = 'jdbc_' + Date.now(),
    sqlId = 'catalog-sql-' + Date.now()
  await connection(page, id)
  await page.getByRole('link', { name: 'Catalog 管理', exact: true }).click()
  await page.getByRole('button', { name: '＋ 注册 Catalog', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: 'Catalog 配置' })
  await dialog.getByLabel('Catalog 标识', { exact: false }).fill(id)
  await dialog.getByLabel('显示名称', { exact: true }).fill(id)
  await dialog.locator('[data-field="dataSourceId"]').selectOption(id)
  await dialog.getByRole('button', { name: '保存 Catalog', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('Catalog 已登记')
  await page.getByRole('button', { name: '初始化 SQL', exact: true }).click()
  await expect(page.getByRole('textbox', { name: 'Catalog 初始化 SQL' })).toContainText(
    "'type' = 'jdbc'",
  )
  const sql =
    "CREATE TEMPORARY TABLE sink_t (id INT) WITH ('connector'='print'); INSERT INTO sink_t VALUES (1);"
  await write(page, '/api/sql/jobs/' + sqlId, {
    name: sqlId,
    description: '',
    sql,
    runtime: { catalogId: id, catalogIds: [id], database: 'warehouse' },
    revision: 0,
  })
  let cluster = 0
  page.on('request', (request) => {
    if (request.url().endsWith('/api/jobs/refresh')) cluster++
  })
  const initialStatus = page.waitForResponse((response) =>
    response.url().endsWith('/api/jobs/refresh'),
  )
  await page.goto('/#/sql?draft=' + sqlId)
  await initialStatus
  await expect(page.getByLabel('当前 Catalog', { exact: true })).toHaveValue(id)
  await page
    .getByRole('textbox', { name: 'SQL 编辑器', exact: true })
    .fill(sql.replace('VALUES (1)', 'VALUES (2)'))
  await page.getByRole('button', { name: '保存 SQL', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('SQL 草稿已保存')
  const saved = (await (await page.request.get('/api/sql/jobs/' + sqlId)).json()).data
  expect(saved.runtime.catalogId).toBe(id)
  expect(saved.sql).toContain('VALUES (2)')
  // The first page query is allowed; editing and saving must not start another refresh.
  expect(cluster).toBe(1)
  await page.goto('/#/catalogs')
  await page
    .getByLabel('Catalog 目录列表')
    .getByRole('button', { name: new RegExp(id) })
    .click()
  await page.getByRole('button', { name: '删除登记', exact: true }).click()
  await page
    .getByRole('dialog', { name: '确认操作' })
    .getByRole('button', { name: '确定', exact: true })
    .click()
  await expect(page.getByRole('alert')).toContainText('SQL 草稿引用')
})

test('JAR resources align by type and SQL draft column stays narrow', async ({ page }) => {
  await login(page)
  for (const width of [1920, 1440, 1280]) {
    await page.setViewportSize({ width, height: 1080 })
    await page.goto('/#/jobs/jar/new')
    await confirmJarName(page, 'layout-' + width + '-' + Date.now())
    for (const suffix of ['内存', 'CPU']) {
      const jm = page.getByRole('spinbutton', { name: 'JobManager ' + suffix, exact: true })
      const tm = page.getByRole('spinbutton', { name: 'TaskManager ' + suffix, exact: true })
      await jm.scrollIntoViewIfNeeded()
      const a = await jm.boundingBox(),
        b = await tm.boundingBox()
      expect(Math.abs(a!.y - b!.y)).toBeLessThan(2)
    }
    await page.goto('/#/sql')
    await expect(page.getByRole('heading', { name: 'SQL 开发', exact: true })).toBeVisible()
    expect((await page.locator('.sql-explorer').boundingBox())!.width).toBeLessThanOrEqual(300)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  }
})

test('Datasource precision, key and event-time settings generate reusable SQL through the retained API', async ({
  page,
}) => {
  await login(page)
  const id = 'events-' + Date.now()
  await write(page, '/api/catalog/datasources/' + id, {
    name: id,
    connectorType: 'kafka',
    groupId: 'regression',
    tableName: 'order_events',
    brokers: 'kafka:9092',
    topic: 'orders',
    format: 'json',
    startupMode: 'timestamp',
    startupTimestamp: 1720000000000,
    watermarkColumn: 'event_time',
    watermarkDelaySeconds: '7',
    columns: [
      { name: 'amount', type: 'DECIMAL(20,4)' },
      { name: 'event_time', type: 'TIMESTAMP(3)' },
    ],
    revision: 0,
  })
  const response = await page.request.get('/api/sql/datasources/' + id + '/snippet')
  expect(response.ok()).toBe(true)
  const data = (await response.json()).data
  expect(data.sql).toContain('DECIMAL(20,4)')
  expect(data.sql).toContain('WATERMARK FOR')
  expect(data.sql).toContain('1720000000000')
  await page.goto('/#/datasources')
  await expect(page).toHaveURL(/#\/catalogs$/)
  await expect(page.getByRole('heading', { name: 'Catalog 工作台', exact: true })).toBeVisible()
})

test('JDBC Catalog metadata is loaded only by user actions and inserts qualified references', async ({
  page,
}) => {
  await login(page)
  const id = 'metadata_' + Date.now(),
    draft = 'metadata-sql-' + Date.now()
  await connection(page, id)
  await write(page, '/api/sql/catalogs/' + id, {
    name: id,
    type: 'jdbc',
    dataSourceId: id,
    defaultDatabase: 'warehouse',
    revision: 0,
  })
  await write(page, '/api/sql/jobs/' + draft, {
    name: draft,
    description: '',
    sql: 'SELECT 1;',
    runtime: { catalogId: id, catalogIds: [id], database: 'warehouse' },
    revision: 0,
  })
  let calls = 0
  await page.route('**/api/sql/catalogs/' + id + '/metadata', async (route) => {
    calls++
    const input = route.request().postDataJSON()
    const data = !input.database
      ? { databases: ['warehouse'] }
      : !input.table
        ? { tables: [{ name: 'orders', type: 'TABLE' }] }
        : { columns: [{ name: 'id', type: 'BIGINT', primaryKey: true, nullable: false }] }
    await route.fulfill({
      json: { data: { ...data, truncated: false, checkedAt: new Date().toISOString() } },
    })
  })
  await page.goto('/#/sql?draft=' + draft)
  await page.getByRole('button', { name: 'Catalog', exact: true }).click()
  const explorer = page.getByRole('region', { name: 'Catalog 对象浏览' })
  expect(calls).toBe(0)
  await explorer.getByRole('button', { name: '读取数据库', exact: true }).click()
  await explorer.getByLabel('Catalog 数据库').selectOption('warehouse')
  await explorer.getByRole('button', { name: /orders/ }).click()
  await explorer.getByRole('button', { name: '插入 SQL', exact: true }).click()
  await expect(page.getByRole('textbox', { name: 'SQL 编辑器', exact: true })).toContainText(
    '`' + id + '`.`warehouse`.`orders`',
  )
  expect(calls).toBe(3)
})
