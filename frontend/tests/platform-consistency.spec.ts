import { test, expect } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { platformFixture as fixture } from './fixtures/platform'

test('版本显示与发布镜像标签一致，审计中文动作可搜索并保留未知码', async ({ page }) => {
  const f = await fixture(page)
  const pkg = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8'))
  const manifest = readFileSync(
    new URL('../../deploy/kubernetes/application.yaml', import.meta.url),
    'utf8',
  )
  expect(manifest).toContain('flink-web/app:' + pkg.version)
  expect(manifest).toContain('flink-web/web:' + pkg.version)
  const system = readFileSync(
    new URL(
      '../../backend/src/main/java/com/flinkweb/service/system/impl/SystemServiceImpl.java',
      import.meta.url,
    ),
    'utf8',
  )
  expect(system).toContain('"' + pkg.version + '"')
  await page.goto('/#/audit')
  await expect(page.locator('.version-tag')).toHaveText('v' + pkg.version)
  await expect(page.getByRole('cell', { name: '发布 SQL 作业', exact: true })).toHaveAttribute(
    'title',
    'SQL_PUBLISH',
  )
  await expect(page.getByRole('cell', { name: '已受理', exact: true })).toBeVisible()
  await expect(page.getByRole('cell', { name: 'FUTURE_ACTION', exact: true })).toBeVisible()
  await page.getByPlaceholder('搜索记录').fill('发布 SQL')
  await expect(page.locator('tbody tr')).toHaveCount(1)
  expect(f.errors).toEqual([])
})

test('模板、镜像、Session、作业使用相同列表行高与控件高度', async ({ page }) => {
  const f = await fixture(page)
  for (const route of ['templates', 'images', 'sessions', 'jobs']) {
    await page.goto('/#/' + route)
    await expect(
      page.getByRole('heading', {
        name: (
          {
            templates: '默认模板',
            images: '镜像管理',
            sessions: 'Session 集群',
            jobs: '作业管理',
          } as Record<string, string>
        )[route],
        exact: true,
      }),
    ).toBeVisible()
    await expect(page.locator('.table-wrap tbody tr').first()).toBeVisible()
    const heights = await page
      .locator('.table-wrap tbody tr')
      .evaluateAll((rows) => rows.map((r) => r.getBoundingClientRect().height))
    for (const height of heights) expect(height, route).toBeCloseTo(56, 0)
    const controls = await page
      .locator(
        'input:visible:not([type=checkbox]):not([type=radio]), select:visible, .button:visible',
      )
      .evaluateAll((items) => items.map((i) => i.getBoundingClientRect().height))
    for (const height of controls) expect(height, route).toBeCloseTo(30, 0)
  }
  expect(f.errors).toEqual([])
})

test('SQL 历史操作并排、语法和修改高亮，短屏可操作；SQL 无数据源插入或查询', async ({ page }) => {
  const f = await fixture(page)
  await page.goto('/#/sql')
  await expect(page.locator('.sql-center > .code-editor .cm-content')).toBeVisible()
  await expect(page.getByRole('button', { name: '数据源', exact: true })).toHaveCount(0)
  expect(f.calls.some((c) => c.includes('/api/catalog/datasources'))).toBe(false)
  await page.getByRole('button', { name: '版本历史', exact: true }).click()
  const select = page.getByLabel('SQL 历史版本'),
    restore = page.getByRole('button', { name: '恢复此版本' })
  for (const viewport of [
    { width: 1920, height: 1080 },
    { width: 1280, height: 720 },
  ]) {
    await page.setViewportSize(viewport)
    await expect(restore).toBeVisible()
    const a = await select.boundingBox(),
      b = await restore.boundingBox()
    expect(Math.abs(a!.y - b!.y)).toBeLessThan(2)
    expect(a!.x + a!.width).toBeLessThanOrEqual(b!.x)
  }
  await expect(page.locator('.sql-history-diff .cm-changedText').first()).toBeVisible()
  const token = page
    .locator('.sql-history-diff .cm-content span')
    .filter({ hasText: 'CREATE' })
    .first()
  await expect(token).toBeVisible()
  expect(await token.evaluate((e) => getComputedStyle(e).color)).not.toBe('rgb(36, 50, 72)')
  expect(f.errors).toEqual([])
})

test('SQL 与 JAR 草稿栏使用同一标题、计数及草稿行格式', async ({ page }) => {
  const f = await fixture(page)
  const dimensions: number[][] = []
  for (const route of ['sql', 'jar']) {
    await page.goto('/#/' + route)
    const head = page.locator('.draft-list-heading')
    await expect(head).toContainText(route.toUpperCase() + ' 草稿')
    await expect(head.locator('.draft-count')).toHaveText('1')
    const row = page.locator(route === 'sql' ? '.sql-file' : '.jar-draft-open').first()
    await expect(row).toBeVisible()
    dimensions.push([(await head.boundingBox())!.height, (await row.boundingBox())!.height])
    await head.getByRole('button', { name: /草稿栏$/ }).click()
    await expect(head.locator('.draft-count')).toHaveCount(0)
    await head.getByRole('button', { name: /草稿栏$/ }).click()
    await expect(row).toBeVisible()
  }
  expect(dimensions[0]).toEqual(dimensions[1])
  expect(f.errors).toEqual([])
})

test('旧数据源入口转到 Catalog，JDBC 连接仅在 Catalog 管理并支持嵌套保存', async ({ page }) => {
  const f = await fixture(page)
  await page.goto('/#/datasources')
  await expect(page).toHaveURL(/#\/catalogs$/)
  await expect(page.getByText('网络端口验证', { exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: '＋ 注册 Catalog' }).click()
  const catalog = page.getByRole('dialog', { name: 'Catalog 配置' })
  await catalog.getByLabel('Catalog 标识', { exact: false }).fill('kept_draft')
  await catalog.getByRole('button', { name: '管理 JDBC 连接', exact: true }).click()
  const connection = page.getByRole('dialog', { name: 'Catalog JDBC 连接' })
  await connection.getByRole('button', { name: '新增 JDBC 连接', exact: true }).click()
  for (const [name, value] of Object.entries({
    连接标识: 'new_db',
    连接名称: '新连接',
    主机: 'mysql.team.svc',
    数据库: 'business',
    用户名: 'reader',
  }))
    await connection.getByLabel(name, { exact: true }).fill(value)
  await connection.getByLabel('密码配置引用', { exact: false }).selectOption('password')
  await connection.getByRole('button', { name: '保存连接', exact: true }).click()
  await expect(connection).toContainText('连接已保存')
  await connection.getByRole('button', { name: '返回 Catalog', exact: true }).click()
  await expect(catalog.getByLabel('Catalog 标识', { exact: false })).toHaveValue('kept_draft')
  await expect(catalog.getByLabel('JDBC 连接', { exact: false })).toContainText('新连接')
  expect(f.writes[0]).toMatchObject({ id: 'new_db', passwordRef: 'password' })
  expect(f.errors).toEqual([])
})

test('网络诊断迁至系统设置，手动执行且只向管理员开放', async ({ page }) => {
  const f = await fixture(page)
  await page.goto('/#/settings')
  await page.locator('summary').filter({ hasText: '网络端口验证' }).click()
  expect(f.calls.filter((c) => c.includes('/network/test'))).toHaveLength(0)
  await page.getByLabel('诊断主机').fill('mysql.team.svc')
  await page.getByRole('button', { name: '验证端口', exact: true }).click()
  await expect(page.getByRole('status').filter({ hasText: '连接成功' })).toBeVisible()
  expect(f.calls.filter((c) => c.includes('/network/test'))).toHaveLength(1)
  expect(f.errors).toEqual([])
})

test('只读角色没有 Catalog 连接管理或网络诊断入口', async ({ page }) => {
  await fixture(page, 'ROLE_VIEWER')
  await page.goto('/#/catalogs')
  await expect(page.getByRole('heading', { name: 'Catalog 工作台', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '管理 JDBC 连接', exact: true })).toHaveCount(0)
  await page.goto('/#/settings')
  await expect(page.getByText('网络端口验证', { exact: true })).toHaveCount(0)
})

test('JAR、SQL、Catalog 和系统设置的表单在短屏统一控件高度', async ({ page }) => {
  const f = await fixture(page)
  await page.setViewportSize({ width: 900, height: 650 })
  for (const path of ['jar/jar/edit', 'sql', 'catalogs', 'settings']) {
    await page.goto('/#/' + path)
    if (path.startsWith('jar'))
      await expect(page.getByLabel('作业名称', { exact: false }).first()).toHaveValue('test-job')
    if (path === 'sql') {
      await expect(page.getByRole('textbox', { name: 'SQL 编辑器', exact: true })).toBeVisible()
      await page.getByRole('button', { name: '部署配置', exact: true }).click()
    }
    if (path === 'catalogs')
      await page.getByRole('button', { name: '编辑配置', exact: true }).click()
    if (path === 'settings') {
      await expect(page.getByRole('heading', { name: '系统设置', exact: true })).toBeVisible()
      await page.locator('summary').filter({ hasText: '网络端口验证' }).click()
    }
    const controls = page.locator(
      'input:visible:not([type=checkbox]):not([type=radio]):not([type=file]), select:visible',
    )
    expect(await controls.count()).toBeGreaterThan(0)
    for (const height of await controls.evaluateAll((items) =>
      items.map((item) => item.getBoundingClientRect().height),
    ))
      expect(height, path).toBeCloseTo(30, 0)
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
      path,
    ).toBe(true)
  }
  expect(f.errors).toEqual([])
})
