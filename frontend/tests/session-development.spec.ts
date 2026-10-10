import { test, expect, type Page } from '@playwright/test'

const source =
  'apiVersion: flink.apache.org/v1beta1\nkind: FlinkDeployment\nmetadata:\n  name: jar-draft\n  labels:\n    flink-web/editor: yaml\nspec:\n  image: registry.test/flink:1.20.5\n  flinkVersion: v1_20\n  job:\n    jarURI: local:///opt/flink/usrlib/job.jar\n'
const runtime = {
  imageId: 'flink',
  runnerArtifactId: '',
  workspaceId: 'sql-workspace',
  parallelism: 1,
  slots: 2,
  dependencyArtifactIds: [],
  flinkConfiguration: {},
  podTemplate: {},
}
async function fixture(page: Page, roles = ['ROLE_ADMIN']) {
  let session: any = null,
    reads = 0,
    opened = 0,
    verificationReads = 0,
    submits = 0,
    jarDeleted = false,
    deleteError = '',
    deletes = 0
  let sql: any = {
    id: 'sql-draft',
    name: 'sql-draft',
    description: '',
    sql: "CREATE TABLE sink (n INT) WITH ('connector'='print'); INSERT INTO sink VALUES (1);",
    runtime,
    revision: 1,
    jobId: 'sql-job',
  }
  const jar = {
    id: 'jar-job',
    name: 'jar-draft',
    description: '',
    flinkVersion: '1.20.5',
    revision: 1,
    yaml: source,
    updatedAt: new Date().toISOString(),
  }
  const job = { ...jar, id: 'sql-job', name: 'sql-draft' }
  const run = {
    id: 'run',
    jobId: 'fixed-id',
    sqlId: sql.id,
    sqlRevision: 2,
    limit: 100,
    state: 'RUNNING',
    message: '正在验证',
    rows: [],
  }
  await page.route('**/api/**', async (route) => {
    const req = route.request(),
      path = new URL(req.url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    let data: any = []
    if (path === '/api/auth/me') data = { username: 'test-user', roles }
    else if (path === '/api/system/operator') data = { found: true, namespace: 'test', deployment: 'operator', queriedAt: new Date().toISOString(), compatibility: { known: true, version: '1.14.0', supported: ['1.20'], message: '' } }
    else if (path === '/api/auth/csrf') data = { headerName: 'X-CSRF-TOKEN', token: 'test' }
    else if (path === '/api/system') data = { namespace: 'test', capabilities: [] }
    else if (path === '/api/catalog/images')
      data = [
        {
          id: 'flink',
          repository: 'registry.test/flink',
          version: '1.20.5',
          flinkVersion: '1.20.5',
        },
      ]
    else if (path === '/api/artifacts/defaults')
      data = { bucket: 'test', rootPrefix: 'artifacts/', stateRoot: 's3://state' }
    else if (path === '/api/artifacts/objects') data = { objects: [], directories: [] }
    else if (path === '/api/jobs') data = [job, jar]
    else if (path === '/api/jobs/jar-job') data = jar
    else if (path === '/api/jobs/refresh') data = { items: [], queriedAt: new Date().toISOString() }
    else if (path === '/api/platform/job-access')
      data = {
        'sql-job': { canEdit: true, canOperate: true },
        'jar-job': { canEdit: true, canOperate: true },
      }
    else if (path === '/api/development/links')
      data = { 'sql-job': { type: 'sql', sqlId: sql.id }, 'jar-job': { type: 'jar' } }
    else if (path === '/api/development/jar/jobs') data = opened && !jarDeleted ? [jar] : []
    else if ((path === '/api/jobs/jar-job/draft' || path === '/api/sql/jobs/sql-draft') && req.method() === 'DELETE') {
      deletes++
      expect(new URL(req.url()).searchParams.get('revision')).toBe('1')
      const failure = deleteError || (path.includes('/sql/') && sql.jobId ? '请先在作业管理删除对应集群资源和平台草稿，再删除 SQL 草稿' : '')
      if (failure) return route.fulfill({ status: 409, json: { error: { code: 'DEPLOYMENT_EXISTS', message: failure } } })
      if (path.includes('/sql/')) sql = null
      else jarDeleted = true
      data = null
    }
    else if (path === '/api/development/jar/jobs/jar-job/open') {
      opened++
      data = jar
    } else if (path === '/api/sql/jobs') data = sql ? [sql] : []
    else if (path === '/api/sql/validate')
      data = { level: 'BASIC', statementCount: 2, insertCount: 1, message: '检查通过' }
    else if (path === '/api/sql/jobs/sql-draft' && req.method() === 'PUT') {
      sql = { ...sql, ...req.postDataJSON(), revision: sql.revision + 1 }
      data = sql
    } else if (path === '/api/sql/jobs/sql-draft/verifications') {
      submits++
      data = run
    } else if (path === '/api/sql/verifications/run') {
      verificationReads++
      data = {
        ...run,
        state: 'FINISHED',
        message: '有界 SQL 验证完成',
        rows: [
          { sink: 1, kind: '+I', value: '1' },
          { sink: 1, kind: '+I', value: '1' },
        ],
      }
    } else if (path === '/api/sessions') {
      reads++
      data = session ? [session] : []
    } else if (path === '/api/sessions/sql-validation' && req.method() === 'PUT') {
      session = {
        ...req.postDataJSON(),
        id: 'sql-validation',
        deploymentName: 'fw-session-sql-validation',
        revision: 1,
        state: 'STOPPED',
        desiredState: 'stopped',
      }
      data = session
    } else if (path === '/api/sessions/sql-validation/status') data = session
    else if (path === '/api/sessions/sql-validation/start') {
      session = { ...session, state: 'READY', desiredState: 'running', revision: 3 }
      data = session
    } else if (path.endsWith('/access'))
      data = { canEdit: true, canOperate: true, readGroups: [], operateGroups: [], editGroups: [] }
    await route.fulfill({ json: { data } })
  })
  return {
    ready: () => {
      session = { id: 'sql-validation', validation: true, runtime, state: 'READY' }
    },
    reads: () => reads,
    submits: () => submits,
    verificationReads: () => verificationReads,
    opened: () => opened,
    jarReady: () => { opened = 1 },
    deletes: () => deletes,
    draftOnly: () => { sql = { ...sql, jobId: undefined } },
    rejectDelete: (message: string) => { deleteError = message },
  }
}

test('SQL 检查约束、Session 引导、结果页签与停止监听', async ({ page }) => {
  const f = await fixture(page)
  await page.goto('/#/sql?draft=sql-draft')
  const verify = page.getByRole('button', { name: 'SQL 验证', exact: true })
  await expect(verify).toBeDisabled()
  await expect(page.getByRole('button', { name: '执行 SQL', exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: 'SQL 检查', exact: true }).click()
  await expect(verify).toBeEnabled()
  await verify.click()
  await expect(page.getByRole('link', { name: /前往 Session 集群，创建或启动/ })).toBeVisible()
  expect(f.submits()).toBe(0)
  f.ready()
  await verify.click()
  await expect(page.getByRole('tab', { name: '作业状态', exact: true })).toBeVisible()
  await expect(page.getByRole('tabpanel')).toContainText('有界 SQL 验证完成')
  await page.getByRole('tab', { name: '作业数据 · 2 / 100' }).click()
  await expect(page.locator('.verification-table tbody tr')).toHaveCount(2)
  await page.screenshot({ path: 'test-results/sql-verification.png', fullPage: true })
  const reads = f.verificationReads()
  await page.waitForTimeout(2300)
  expect(f.verificationReads()).toBe(reads)
  await page.getByRole('textbox', { name: 'SQL 编辑器', exact: true }).fill(sqlText())
  await expect(verify).toBeDisabled()
})
function sqlText() {
  return "CREATE TABLE sink(n INT) WITH ('connector'='print'); INSERT INTO sink VALUES (2);"
}

test('Session 创建入口与配置、启动和唯一用途提示', async ({ page }) => {
  const f = await fixture(page)
  await page.goto('/#/sessions')
  await page.getByRole('button', { name: '＋ 新建 Session', exact: true }).click()
  await expect(page.getByRole('heading', { name: '新建 Session', exact: true })).toBeVisible()
  const columns = page.locator('.session-form-grid > .editor-form-column')
  const left = (await columns.nth(0).boundingBox())!, right = (await columns.nth(1).boundingBox())!
  expect(right.x).toBeGreaterThan(left.x + left.width)
  expect(Math.abs(left.y - right.y)).toBeLessThan(2)
  await expect(page.locator('.session-form-grid')).toHaveCSS('border-top-width', '0px')
  await page.screenshot({ path: 'test-results/session-editor-desktop.png', fullPage: true })
  await page.getByLabel('集群名称', { exact: true }).fill('validation')
  await page.getByRole('checkbox', { name: /SQL 验证专用 Session/ }).check()
  await page.getByRole('combobox', { name: '运行镜像', exact: true }).selectOption('flink')
  await expect(page.locator('.session-dependencies').getByLabel('文件目录', { exact: true })).toHaveValue('shared')
  const runner = page.locator('[data-field="runnerArtifactId"]')
  await expect(runner.getByLabel('文件目录', { exact: true })).toHaveValue('runner')
  await runner.getByRole('button', { name: 'HTTP / S3 地址', exact: true }).click()
  await runner.getByLabel('SQL Runner 文件地址', { exact: true }).fill('s3://test/sql-runner/runner.jar')
  await runner.getByRole('button', { name: '确认文件地址', exact: true }).click()
  await page.locator('.session-pod summary').click()
  await page.getByRole('textbox', { name: 'PodTemplate（JSON）', exact: true }).fill('{bad')
  await page.getByRole('button', { name: '保存配置', exact: true }).click()
  await expect(page.locator('.notice.error')).toContainText('PodTemplate 必须为有效 JSON 对象')
  await page.getByRole('textbox', { name: 'PodTemplate（JSON）', exact: true }).fill('{}')
  await page.getByRole('button', { name: '保存配置', exact: true }).click()
  await expect(page.getByRole('row').filter({ hasText: 'validation' })).toContainText(
    'SQL 验证 · 唯一',
  )
  await page.getByRole('button', { name: '启动', exact: true }).click()
  await expect(page.getByRole('row').filter({ hasText: 'validation' })).toContainText('已就绪')
  await expect(page.getByRole('button', { name: '配置', exact: true })).toBeDisabled()
  await page.screenshot({ path: 'test-results/session-clusters.png', fullPage: true })
  const reads = f.reads()
  await page.waitForTimeout(1200)
  expect(f.reads()).toBe(reads)
})

test('JAR 草稿删除支持取消、失败保留和成功移除', async ({ page }) => {
  const f = await fixture(page)
  f.jarReady()
  await page.goto('/#/jar')
  const remove = page.getByRole('button', { name: '删除', exact: true })
  await remove.click()
  await page.getByRole('dialog').getByRole('button', { name: '取消', exact: true }).click()
  expect(f.deletes()).toBe(0)
  f.rejectDelete('请先删除集群资源并确认完成，再删除平台记录')
  await remove.click()
  await page.getByRole('dialog').getByRole('button', { name: '确定', exact: true }).click()
  await expect(page.locator('.notice.error')).toContainText('请先删除集群资源')
  await expect(page.locator('tbody tr')).toHaveCount(1)
  f.rejectDelete('')
  await remove.click()
  await page.getByRole('dialog').getByRole('button', { name: '确定', exact: true }).click()
  await expect(page.locator('tbody tr')).toHaveCount(0)
  await expect(page.getByRole('status')).toContainText('JAR 草稿已删除')
})

test('SQL 列表删除保留关联保护并重置已删除的当前草稿', async ({ page }) => {
  const f = await fixture(page)
  await page.goto('/#/sql?draft=sql-draft')
  const remove = page.getByRole('button', { name: '删除 SQL 草稿「sql-draft」', exact: true })
  await remove.click()
  await page.getByRole('dialog').getByRole('button', { name: '取消', exact: true }).click()
  expect(f.deletes()).toBe(0)
  await remove.click()
  await page.getByRole('dialog').getByRole('button', { name: '确定', exact: true }).click()
  await expect(page.locator('.notice.error')).toContainText('请先在作业管理删除')
  await expect(page.locator('.sql-file')).toHaveCount(1)
  f.draftOnly()
  await page.getByRole('textbox', { name: 'SQL 编辑器', exact: true }).fill(sqlText())
  await remove.click()
  await expect(page.getByRole('dialog')).toContainText('未保存的修改也将丢弃')
  await page.getByRole('dialog').getByRole('button', { name: '确定', exact: true }).click()
  await expect(page.locator('.sql-file')).toHaveCount(0)
  await expect(page.locator('.sql-tabbar')).toContainText('新建草稿')
  await expect(page).not.toHaveURL(/draft=sql-draft/)
  await expect(page.getByRole('status')).toContainText('SQL 草稿已删除')
})

test('删除其他 SQL 草稿不丢失当前未保存内容', async ({ page }) => {
  const f = await fixture(page)
  f.draftOnly()
  await page.goto('/#/sql')
  await page.getByRole('button', { name: '＋ 新建 SQL', exact: true }).click()
  const editor = page.getByRole('textbox', { name: 'SQL 编辑器', exact: true })
  await editor.fill(sqlText())
  await page.getByRole('button', { name: '删除 SQL 草稿「sql-draft」', exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: '确定', exact: true }).click()
  await expect(page.locator('.sql-file')).toHaveCount(0)
  await expect(editor).toContainText(sqlText())
})

test('只读角色不显示开发草稿删除入口', async ({ page }) => {
  const f = await fixture(page, ['ROLE_VIEWER'])
  f.jarReady()
  await page.goto('/#/jar')
  await expect(page.locator('tbody tr')).toHaveCount(1)
  await expect(page.getByRole('button', { name: '删除', exact: true })).toHaveCount(0)
  await page.goto('/#/sql?draft=sql-draft')
  await expect(page.locator('.sql-file')).toHaveCount(1)
  await expect(page.getByRole('button', { name: /删除 SQL 草稿/ })).toHaveCount(0)
})

test('SQL 与 JAR 类型标识及编辑到开发区的映射', async ({ page }) => {
  const f = await fixture(page)
  await page.goto('/#/jobs')
  await expect(page.getByRole('link', { name: '＋ 新建 JAR 作业', exact: true })).toHaveCount(0)
  await expect(page.locator('.job-type.sql-type')).toHaveText('SQL')
  await expect(page.locator('.job-type').filter({ hasText: 'JAR' })).toHaveText('JAR')
  await page
    .getByRole('row')
    .filter({ has: page.getByRole('button', { name: 'sql-draft', exact: true }) })
    .getByRole('button', { name: '编辑', exact: true })
    .click()
  await expect(page).toHaveURL(/sql\?draft=sql-draft/)
  await expect(page.getByRole('textbox', { name: 'SQL 编辑器', exact: true })).toContainText(
    'INSERT INTO sink',
  )
  await page.goto('/#/jar')
  await expect(page.getByRole('link', { name: '＋ 新建 JAR 作业', exact: true })).toBeVisible()
  await expect(page.locator('tbody tr')).toHaveCount(0)
  await page.goto('/#/jobs')
  await page
    .getByRole('row')
    .filter({ has: page.getByRole('button', { name: 'jar-draft', exact: true }) })
    .getByRole('button', { name: '编辑', exact: true })
    .click()
  await expect(page).toHaveURL(/jar\/jar-job\/edit/)
  await expect(page.getByRole('textbox', { name: 'YAML 编辑器', exact: true })).toContainText(
    'flink-web/editor: yaml',
  )
  expect(f.opened()).toBeGreaterThan(0)
  await page.goto('/#/jar')
  await expect(page.locator('tbody tr')).toHaveCount(1)
})

test('窄屏新开发导航、Session 表单与 SQL 结果保持可读', async ({ page }) => {
  const f = await fixture(page)
  await page.setViewportSize({ width: 390, height: 1000 })
  await page.goto('/#/sessions')
  await page.getByRole('button', { name: '＋ 新建 Session', exact: true }).click()
  await expect
    .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
    .toBe(true)
  await page.screenshot({ path: 'test-results/session-mobile.png', fullPage: true })
  f.ready()
  await page.goto('/#/sql?draft=sql-draft')
  await page.getByRole('button', { name: 'SQL 检查', exact: true }).click()
  await page.getByRole('button', { name: 'SQL 验证', exact: true }).click()
  await expect(page.getByRole('tab', { name: '作业状态', exact: true })).toBeVisible()
  await expect
    .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
    .toBe(true)
  await page.screenshot({ path: 'test-results/sql-verification-mobile.png', fullPage: true })
})
