import { test, expect, type Page } from '@playwright/test'

async function fixture(page: Page, role = 'ROLE_ADMIN') {
  const rows: any[] = [{ id: 'reports', name: '业务 JDBC', type: 'jdbc', dataSourceId: 'db', defaultDatabase: 'warehouse', description: '', revision: 1, dependencyArtifactIds: ['s3:artifacts/shared/jdbc.jar'] }, { id: 'hive_data', name: 'Hive 数仓', type: 'hive', defaultDatabase: 'warehouse', description: '', revision: 1, hiveConfDir: '/opt/flink/usrlib', hiveSiteArtifactId: 's3:artifacts/shared/hive-site.xml', hiveVersion: '3.1.3', dependencyArtifactIds: [] }]
  const reads: string[] = [], writes: any[] = [], errors: string[] = []
  page.on('pageerror', e => errors.push(e.message))
  await page.route('**/api/**', async route => {
    const req = route.request(), path = new URL(req.url()).pathname, method = req.method()
    if (!path.startsWith('/api/')) return route.continue()
    let data: any = []
    const body = method !== 'GET' && req.headers()['content-type']?.includes('json') ? req.postDataJSON() : {}
    if (path === '/api/auth/me') data = { username: 'admin', roles: [role] }
    else if (path === '/api/auth/csrf') data = { headerName: 'X-CSRF-TOKEN', token: 'test' }
    else if (path === '/api/system') data = { namespace: 'team', capabilities: [] }
    else if (path === '/api/catalog/datasources') data = [{ id: 'db', name: 'MySQL 业务库', database: 'warehouse', connectorType: 'mysql' }]
    else if (path === '/api/sql/catalogs') data = rows
    else if (path === '/api/artifacts/defaults') data = { rootPrefix: 'artifacts/', bucket: 'test' }
    else if (path === '/api/artifacts/objects') data = { directories: [], objects: [] }
    else if (path === '/api/artifacts/upload') { const filename = /filename="([^"]+)"/.exec(req.postDataBuffer()!.toString())![1]; data = { id: 's3:artifacts/shared/' + filename, name: filename, filename } }
    else if (path.endsWith('/metadata')) {
      reads.push(path + ':' + JSON.stringify(body))
      data = { checkedAt: new Date().toISOString(), truncated: false, ...(!body.database ? { databases: ['warehouse'] } : !body.table ? { tables: [{ name: 'orders', type: 'TABLE' }] } : { columns: [{ name: 'id', type: 'BIGINT', nullable: false, primaryKey: true }, { name: 'region', type: 'STRING', partition: path.includes('hive_data') }] }) }
    } else if (path.endsWith('/functions')) {
      reads.push(path + ':' + JSON.stringify(body))
      data = { checkedAt: new Date().toISOString(), available: true, truncated: false, ...(body.name ? { name: body.name, className: 'example.Normalize' } : { functions: [{ name: 'normalize', type: 'HIVE_FUNCTION' }] }) }
    } else if (path.endsWith('/snippet')) data = { sql: 'CREATE CATALOG `reports` WITH (\n  \'type\'=\'jdbc\'\n);', notice: '配置预览，不连接远端服务', env: [] }
    else if (path.endsWith('/usage')) data = { items: [{ id: 'sql-order', name: '订单作业' }] }
    else if (path.endsWith('/test')) data = { level: 'PROTOCOL', message: '协议连接通过；未验证 Flink 运行依赖' }
    else if (method === 'PUT' && path.startsWith('/api/sql/catalogs/')) { writes.push(body); data = { ...body, id: path.split('/').at(-1), revision: body.revision + 1 }; rows.push(data) }
    return route.fulfill({ json: { data, error: null } })
  })
  await page.goto('/#/catalogs')
  await expect(page.getByRole('heading', { name: 'Catalog 工作台', exact: true })).toBeVisible()
  return { rows, reads, writes, errors }
}

test('JDBC / Hive 三栏工作台按需浏览、切换函数和引用作业，不启动作业', async ({ page }) => {
  const f = await fixture(page)
  expect(f.reads).toHaveLength(0)
  const profiles = page.getByLabel('Catalog 目录列表'), explorer = page.getByRole('region', { name: 'Catalog 对象浏览' })
  await explorer.getByRole('button', { name: '读取数据库', exact: true }).click()
  await explorer.getByLabel('Catalog 数据库').selectOption('warehouse')
  await explorer.getByRole('button', { name: /orders/ }).click()
  await expect(explorer.getByRole('textbox', { name: 'Catalog 引用 SQL' })).toContainText('`reports`.`warehouse`.`orders`')
  const boxes = await Promise.all(['.catalog-profiles', '.catalog-object-list', '.catalog-object-detail'].map(s => page.locator(s).boundingBox()))
  expect(boxes[0]!.x + boxes[0]!.width).toBeLessThanOrEqual(boxes[1]!.x + 2)
  expect(boxes[1]!.x + boxes[1]!.width).toBeLessThanOrEqual(boxes[2]!.x + 2)
  await page.screenshot({ path: test.info().outputPath('catalog-desktop.png') })
  await profiles.getByRole('button', { name: /Hive 数仓/ }).click()
  await expect(explorer.getByLabel('Catalog 数据库')).toHaveValue('')
  await explorer.getByRole('button', { name: '读取数据库', exact: true }).click()
  await explorer.getByLabel('Catalog 数据库').selectOption('warehouse')
  await explorer.getByRole('button', { name: '函数', exact: true }).click()
  await explorer.getByRole('button', { name: /normalize/ }).click()
  await expect(explorer).toContainText('example.Normalize')
  await page.getByRole('button', { name: '引用作业', exact: true }).click()
  await expect(page.getByRole('link', { name: '打开 SQL ↗' })).toHaveAttribute('href', /draft=sql-order/)
  await page.getByRole('button', { name: '初始化 SQL', exact: true }).click()
  await expect(page.getByRole('textbox', { name: 'Catalog 初始化 SQL' })).toContainText('CREATE CATALOG')
  expect(f.errors).toEqual([])
  await page.screenshot({ path: test.info().outputPath('catalog-initialization.png') })
})

test('Hive 配置可上传 XML 和依赖，保存后回到目录；短屏配置与浏览不溢出', async ({ page }) => {
  const f = await fixture(page)
  await page.getByRole('button', { name: '＋ 注册 Catalog' }).click()
  const dialog = page.getByRole('dialog', { name: 'Catalog 配置' })
  await dialog.getByLabel('Catalog 标识', { exact: false }).fill('new_hive')
  await dialog.getByLabel('显示名称', { exact: true }).fill('新数仓')
  await dialog.getByLabel('Catalog 类型', { exact: true }).selectOption('hive')
  await dialog.locator('input[type=file]').first().setInputFiles({ name: 'hive-site.xml', mimeType: 'text/xml', buffer: Buffer.from('<configuration/>') })
  await expect(dialog).toContainText('hive-site.xml')
  await dialog.locator('input[type=file]').nth(1).setInputFiles({ name: 'hive-connector.jar', mimeType: 'application/java-archive', buffer: Buffer.from('fixture') })
  await dialog.getByRole('button', { name: '保存 Catalog', exact: true }).click()
  await expect(dialog).not.toBeVisible()
  expect(f.writes[0]).toMatchObject({ hiveSiteArtifactId: 's3:artifacts/shared/hive-site.xml', dependencyArtifactIds: ['s3:artifacts/shared/hive-connector.jar'], hiveVersion: '3.1.3' })
  await page.setViewportSize({ width: 700, height: 600 })
  await page.getByRole('button', { name: '编辑配置', exact: true }).click()
  await dialog.getByRole('button', { name: '保存 Catalog', exact: true }).scrollIntoViewIfNeeded()
  await expect(dialog.getByRole('button', { name: '保存 Catalog', exact: true })).toBeInViewport()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  expect(f.errors).toEqual([])
})

test('切换目录后迟到的元数据不能回填另一目录', async ({ page }) => {
  const f = await fixture(page)
  let release!: () => void
  const gate = new Promise<void>(resolve => { release = resolve })
  await page.route('**/api/sql/catalogs/reports/metadata', async route => { await gate; await route.fulfill({ json: { data: { databases: ['wrong_old_database'], checkedAt: new Date().toISOString(), truncated: false } } }).catch(() => {}) })
  await page.getByRole('button', { name: '读取数据库', exact: true }).click()
  await page.getByLabel('Catalog 目录列表').getByRole('button', { name: /Hive 数仓/ }).click()
  release()
  await expect(page.getByLabel('Catalog 数据库')).not.toContainText('wrong_old_database')
  expect(f.errors).toEqual([])
})

test('只读角色可以查看登记，不能测试、修改或读取远程元数据', async ({ page }) => {
  const f = await fixture(page, 'ROLE_VIEWER')
  await expect(page.getByRole('button', { name: '＋ 注册 Catalog' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '编辑配置' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '读取数据库', exact: true })).toBeDisabled()
  expect(f.reads).toHaveLength(0)
})

test('SQL 共用对象浏览，插入引用选择附加目录，设置主目录同步数据库并保存', async ({ page }) => {
  const f = await fixture(page)
  let draft: any = { id: 'sql-order', name: 'sql-order', description: '', revision: 1, sql: 'INSERT INTO sink_table VALUES (1);', runtime: { imageId: 'flink', workspaceId: 'sql-order', catalogIds: [], flinkConfiguration: {}, podTemplate: {} } }
  await page.route('**/api/sql/jobs**', route => {
    if (route.request().method() === 'PUT') { const input=route.request().postDataJSON();f.writes.push(input);draft={...draft,...input,revision:draft.revision+1} }
    return route.fulfill({json:{data:new URL(route.request().url()).pathname==='/api/sql/jobs'?[draft]:draft}})
  })
  await page.route('**/api/catalog/images', route => route.fulfill({json:{data:[{ id:'flink',repository:'test/flink',version:'1.20.5',flinkVersion:'1.20.5' }]}}))
  await page.goto('/#/sql?draft=sql-order&catalog=reports')
  const explorer=page.getByRole('region',{name:'Catalog 对象浏览'})
  await expect(page.getByLabel('浏览 Catalog')).toHaveValue('reports')
  expect(f.reads).toHaveLength(0)
  await explorer.getByRole('button',{name:'读取数据库',exact:true}).click()
  await explorer.getByLabel('Catalog 数据库').selectOption('warehouse')
  await explorer.getByRole('button',{name:/orders/}).click()
  await explorer.getByRole('button',{name:'插入 SQL',exact:true}).click()
  await expect(page.getByRole('textbox',{name:'SQL 编辑器',exact:true})).toContainText('`reports`.`warehouse`.`orders`')
  await page.getByRole('button',{name:'设为当前目录',exact:true}).click()
  await expect(page.getByLabel('当前 Catalog')).toHaveValue('reports')
  await page.getByRole('button',{name:'保存 SQL',exact:true}).click()
  await expect.poll(()=>f.writes.length).toBe(1)
  expect(f.writes[0].runtime).toMatchObject({catalogId:'reports',catalogIds:['reports'],database:'warehouse'})
  expect(f.errors).toEqual([])
})
