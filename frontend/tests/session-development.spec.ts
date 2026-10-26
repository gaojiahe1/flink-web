import { test, expect, type Page } from '@playwright/test'
import { parse, stringify } from 'yaml'

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

test('SQL 辅助面板可拖动和键盘缩放，名称与 JAR 一致，窄屏与全屏保留可用布局', async ({ page }) => {
  await fixture(page)
  await page.goto('/#/sql?draft=sql-draft')
  await page.getByRole('button', { name: '部署配置', exact: true }).click()
  const handle = page.getByRole('separator', { name: '调整辅助面板宽度' })
  const panel = page.locator('.sql-runtime:visible')
  await expect(handle).toBeVisible()
  const initial = (await panel.boundingBox())!.width
  const position = (await handle.boundingBox())!
  await page.mouse.move(position.x + position.width / 2, position.y + 80)
  await page.mouse.down()
  await page.mouse.move(position.x - 170, position.y + 80)
  await page.mouse.up()
  expect((await panel.boundingBox())!.width).toBeGreaterThan(initial + 160)
  await handle.focus()
  const beforeKey = (await panel.boundingBox())!.width
  await handle.press('ArrowLeft')
  expect((await panel.boundingBox())!.width).toBeGreaterThan(beforeKey + 35)
  await handle.press('End')
  expect((await page.locator('.sql-center').boundingBox())!.width).toBeGreaterThanOrEqual(318)
  for (const name of [
    '作业名称',
    '镜像版本',
    'JobManager 内存',
    'TaskManager 内存',
    'JobManager CPU',
    'TaskManager CPU',
    '每个 TaskManager 的 Slot 数',
  ])
    await expect(panel.getByLabel(name, { exact: name !== '作业名称' })).toBeVisible()
  await panel.getByText('程序与依赖及 Flink 配置', { exact: true }).click()
  await expect(panel.getByRole('heading', { name: 'Flink 配置', exact: true })).toBeVisible()
  await expect(panel.getByRole('link', { name: '下载平台 SQL Runner' })).toHaveAttribute(
    'href',
    '/api/sql/runner/download',
  )
  await page.getByText('SQL Runner 与语法验证器怎么用？', { exact: true }).click()
  await expect(page.locator('.sql-runtime-guide')).toContainText('SQL 验证专用 Session')
  await expect(page.locator('.sql-runtime-guide')).toContainText('最多 1000')
  await page.getByRole('button', { name: '全屏编辑', exact: true }).click()
  await expect(handle).toBeVisible()
  await handle.press('Home')
  expect((await panel.boundingBox())!.width).toBeLessThanOrEqual(301)
  await page.getByRole('button', { name: '退出全屏', exact: true }).click()
  await page.setViewportSize({ width: 1100, height: 900 })
  await expect(handle).toBeVisible()
  await handle.press('End')
  expect((await page.locator('.sql-center').boundingBox())!.width).toBeGreaterThanOrEqual(318)
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(handle).toBeHidden()
  await expect(panel.getByLabel('作业名称', { exact: false })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
})
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
    limit: 1000,
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
    else if (path === '/api/system/operator')
      data = {
        found: true,
        namespace: 'test',
        deployment: 'operator',
        queriedAt: new Date().toISOString(),
        compatibility: { known: true, version: '1.14.0', supported: ['1.20'], message: '' },
      }
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
    else if (
      (path === '/api/jobs/jar-job/draft' || path === '/api/sql/jobs/sql-draft') &&
      req.method() === 'DELETE'
    ) {
      deletes++
      expect(new URL(req.url()).searchParams.get('revision')).toBe('1')
      const failure =
        deleteError ||
        (path.includes('/sql/') && sql.jobId
          ? '请先在作业管理删除对应集群资源和平台草稿，再删除 SQL 草稿'
          : '')
      if (failure)
        return route.fulfill({
          status: 409,
          json: { error: { code: 'DEPLOYMENT_EXISTS', message: failure } },
        })
      if (path.includes('/sql/')) sql = null
      else jarDeleted = true
      data = null
    } else if (path === '/api/development/jar/jobs/jar-job/open') {
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
          { sink: 1, kind: '+I', columns: ['id'], cells: ['1'], keys: [], target: 'sink_events' },
          { sink: 1, kind: '+I', columns: ['id'], cells: ['1'], keys: [], target: 'sink_events' },
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
    } else if (path === '/api/sessions/sql-validation/diagnostics') {
      data = {
        queriedAt: new Date().toISOString(),
        error: '',
        events: [],
        pods: [
          {
            name: 'validation-tm',
            phase: 'Running',
            node: 'test-node',
            containers: ['flink-main-container'],
            conditions: [],
            states: [{ name: 'flink-main-container', ready: true, state: 'running', restarts: 0 }],
          },
        ],
      }
    } else if (path === '/api/sessions/sql-validation/logs')
      data = { text: 'test log', queriedAt: new Date().toISOString() }
    else if (path === '/api/sessions/sql-validation/status') data = session
    else if (path === '/api/sessions/sql-validation/start') {
      session = {
        ...session,
        uid: 'session-uid',
        state: 'READY',
        desiredState: 'running',
        revision: 3,
        queriedAt: new Date().toISOString(),
      }
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
    jarReady: () => {
      opened = 1
    },
    deletes: () => deletes,
    draftOnly: () => {
      sql = { ...sql, jobId: undefined }
    },
    rejectDelete: (message: string) => {
      deleteError = message
    },
  }
}

async function configurationFixture(page: Page) {
  await page.route('**/api/*/configuration', async (route) => {
    const input = route.request().postDataJSON(),
      session = route.request().url().includes('/sessions/')
    const original = input.runtime
    const root = original.deploymentYaml
      ? parse(original.deploymentYaml)
      : original.baseYaml
        ? parse(original.baseYaml)
        : {
            apiVersion: 'flink.apache.org/v1beta1',
            kind: 'FlinkDeployment',
            metadata: {
              name: session ? 'fw-session-sql-validation' : input.name,
              namespace: 'test',
            },
            spec: {
              image: 'registry.test/flink:1.20.5',
              flinkVersion: 'v1_20',
              mode: original.deploymentMode || 'native',
              serviceAccount: original.serviceAccount || 'test-flink',
              flinkConfiguration: {
                ...original.flinkConfiguration,
                'taskmanager.numberOfTaskSlots': String(original.slots || 2),
              },
              podTemplate: original.podTemplate || {},
              jobManager: {
                resource: { cpu: original.jmCpu || 1, memory: original.jmMemory || '2048m' },
                podTemplate: original.jmPodTemplate || {},
              },
              taskManager: {
                resource: { cpu: original.tmCpu || 1, memory: original.tmMemory || '2048m' },
                podTemplate: original.tmPodTemplate || {},
                ...(original.tmReplicas ? { replicas: original.tmReplicas } : {}),
              },
              ...(!session
                ? {
                    job: {
                      jarURI: 'local:///opt/flink/usrlib/runner.jar',
                      entryClass: 'com.flinkweb.sql.SqlRunner',
                      parallelism: original.parallelism || 1,
                      upgradeMode: 'stateless',
                      args: [],
                    },
                  }
                : {}),
            },
          }
    const spec = root.spec
    if (!original.deploymentYaml && original.baseYaml) {
      spec.flinkConfiguration = {
        ...original.flinkConfiguration,
        'taskmanager.numberOfTaskSlots': String(original.slots),
      }
      spec.podTemplate = original.podTemplate || {}
      spec.jobManager.podTemplate = original.jmPodTemplate || {}
      spec.taskManager.podTemplate = original.tmPodTemplate || {}
      if (spec.job) spec.job.parallelism = original.parallelism
      if (original.tmReplicas) spec.taskManager.replicas = original.tmReplicas
    }
    const mapped = {
      ...original,
      baseYaml: stringify(root),
      imageId: 'flink',
      deploymentMode: spec.mode || 'native',
      tmReplicas: spec.taskManager?.replicas || null,
      slots: Number(spec.flinkConfiguration?.['taskmanager.numberOfTaskSlots'] || 1),
      parallelism: spec.job?.parallelism || 1,
      upgradeMode: spec.job?.upgradeMode || 'stateless',
      serviceAccount: spec.serviceAccount || '',
      flinkConfiguration: spec.flinkConfiguration || {},
      podTemplate: spec.podTemplate || {},
      jmPodTemplate: spec.jobManager?.podTemplate || {},
      tmPodTemplate: spec.taskManager?.podTemplate || {},
      jmMemory: spec.jobManager?.resource?.memory || '2048m',
      tmMemory: spec.taskManager?.resource?.memory || '2048m',
      jmCpu: spec.jobManager?.resource?.cpu || 1,
      tmCpu: spec.taskManager?.resource?.cpu || 1,
    }
    const formIssue =
      original.deploymentYaml &&
      (!spec.serviceAccount ||
        ['jobManager', 'taskManager'].some(
          (role) =>
            !/^[1-9][0-9]*(\.[0-9]+)?[mg]$/i.test(spec[role]?.resource?.memory || '') ||
            typeof spec[role]?.resource?.cpu !== 'number',
        ))
        ? '资源配置无法无损映射为表单，请继续使用 YAML'
        : ''
    await route.fulfill({ json: { data: { yaml: stringify(root), runtime: mapped, formIssue } } })
  })
}

test('Session 表单和 YAML 切换保留扩展配置，错误原文不被覆盖，固定数量可回填保存', async ({
  page,
}) => {
  await fixture(page)
  await configurationFixture(page)
  await page.goto('/#/sessions')
  await page.getByRole('button', { name: '＋ 新建 Session', exact: true }).click()
  await page.getByLabel('集群名称', { exact: true }).fill('validation')
  await page.getByRole('checkbox', { name: /SQL 验证专用 Session/ }).check()
  await page.getByRole('combobox', { name: '运行镜像', exact: true }).selectOption('flink')
  await expect(page.getByLabel('TaskManager 数量', { exact: true })).toBeDisabled()
  await page.locator('[data-field="deploymentMode"]').selectOption('standalone')
  await page.getByLabel('TaskManager 数量', { exact: true }).fill('3')
  await page.getByRole('button', { name: 'YAML 编辑', exact: true }).click()
  const editor = page.getByRole('textbox', { name: 'Session YAML 编辑器', exact: true })
  const generated = parse(await editor.innerText())
  generated.spec.logConfiguration = { 'log4j-console.properties': 'custom-config' }
  generated.spec.taskManager.replicas = 4
  await editor.fill('spec: [unfinished')
  await page.getByRole('button', { name: '表单编辑', exact: true }).click()
  await expect(page.locator('.notice.error')).toContainText('YAML 语法错误')
  await expect(editor).toContainText('spec: [unfinished')
  const unmappable = structuredClone(generated)
  unmappable.spec.jobManager.resource.memory = '3Gi'
  await editor.fill(stringify(unmappable))
  await page.getByRole('button', { name: '表单编辑', exact: true }).click()
  await expect(page.locator('.notice.error')).toContainText('无法无损')
  await expect(editor).toContainText('3Gi')
  await editor.fill(stringify(generated))
  await page.getByRole('button', { name: '表单编辑', exact: true }).click()
  await expect(page.getByLabel('TaskManager 数量', { exact: true })).toHaveValue('4')
  await page.getByRole('button', { name: 'YAML 编辑', exact: true }).click()
  await expect(editor).toContainText('custom-config')
  await page.getByRole('button', { name: '保存配置', exact: true }).click()
  await page.getByRole('button', { name: '配置', exact: true }).click()
  await expect(editor).toContainText('replicas: 4')
  await expect(editor).toContainText('custom-config')
})

test('SQL 配置 YAML 可回填资源，修改后检查失效，保存重开保留原文', async ({ page }) => {
  await fixture(page)
  await configurationFixture(page)
  await page.goto('/#/sql?draft=sql-draft')
  await page.getByRole('button', { name: 'SQL 检查', exact: true }).click()
  await expect(page.getByRole('button', { name: 'SQL 验证', exact: true })).toBeEnabled()
  await page.getByRole('button', { name: '部署配置', exact: true }).click()
  await page.getByRole('button', { name: 'YAML 编辑', exact: true }).click()
  const editor = page.getByRole('textbox', { name: 'SQL 部署 YAML 编辑器', exact: true })
  const generated = parse(await editor.innerText())
  generated.spec.job.parallelism = 6
  generated.spec.logConfiguration = { 'log4j-console.properties': 'retained-sql-config' }
  await editor.fill(stringify(generated))
  await expect(page.getByRole('button', { name: 'SQL 验证', exact: true })).toBeDisabled()
  await page.getByRole('button', { name: '表单编辑', exact: true }).click()
  await expect(page.locator('[data-field="parallelism"]')).toHaveValue('6')
  await page.getByRole('button', { name: 'YAML 编辑', exact: true }).click()
  await expect(editor).toContainText('retained-sql-config')
  await page.getByRole('button', { name: '保存 SQL', exact: true }).click()
  await page.reload()
  await page.getByRole('button', { name: '部署配置', exact: true }).click()
  await expect(editor).toContainText('retained-sql-config')
})

for (const invalidField of ['共享', 'JobManager', 'TaskManager']) {
  test(`无效 ${invalidField} PodTemplate 阻止数据源插入并保留全部编辑原文`, async ({ page }) => {
    await fixture(page)
    let inserts = 0
    await page.route('**/api/catalog/datasources', (route) =>
      route.fulfill({
        json: { data: [{ id: 'source', name: 'test-source', connectorType: 'JDBC' }] },
      }),
    )
    await page.route('**/api/sql/datasources/source/snippet', (route) => {
      inserts++
      return route.fulfill({
        json: {
          data: {
            sql: 'CREATE TABLE injected(n INT);',
            env: [{ name: 'DS_SECRET', value: '{{secret:test}}' }],
          },
        },
      })
    })
    await page.goto('/#/sql?draft=sql-draft')
    const sqlEditor = page.getByRole('textbox', { name: 'SQL 编辑器', exact: true })
    await expect(sqlEditor).toContainText('CREATE TABLE sink')
    const original = await sqlEditor.innerText()
    await page.getByRole('button', { name: '部署配置', exact: true }).click()
    await page.getByText('程序与依赖及 Flink 配置', { exact: true }).click()
    const names = ['共享', 'JobManager', 'TaskManager']
    for (const [index, name] of names.entries()) {
      await page
        .locator(
          `[data-field="${['podTemplate', 'jmPodTemplate', 'tmPodTemplate'][index]}"] summary`,
        )
        .click()
      await page
        .getByRole('textbox', { name: name + ' PodTemplate（YAML）', exact: true })
        .fill(
          name === invalidField
            ? 'spec: [unfinished'
            : 'metadata:\n  annotations:\n    keep: ' + name,
        )
    }
    await page.getByRole('button', { name: '数据源', exact: true }).click()
    await page.getByRole('button', { name: '插入 SQL', exact: true }).click()
    await expect(page.locator('.notice.error')).toContainText('修正 PodTemplate YAML')
    expect(inserts).toBe(0)
    await expect(sqlEditor).toHaveText(original)
    await page.getByRole('button', { name: '部署配置', exact: true }).click()
    for (const name of names)
      await expect(
        page.getByRole('textbox', { name: name + ' PodTemplate（YAML）', exact: true }),
      ).toContainText(name === invalidField ? 'spec: [unfinished' : 'keep: ' + name)
    await page
      .getByRole('textbox', { name: invalidField + ' PodTemplate（YAML）', exact: true })
      .fill('{}')
    await page.getByRole('button', { name: '数据源', exact: true }).click()
    await page.getByRole('button', { name: '插入 SQL', exact: true }).click()
    await expect(sqlEditor).toContainText('CREATE TABLE injected')
    expect(inserts).toBe(1)
  })
}

test('SQL 面板指针取消和丢失捕获清除拖动状态，1000px 边界与切换无溢出', async ({ page }) => {
  await fixture(page)
  await page.goto('/#/sql?draft=sql-draft')
  await page.getByRole('button', { name: '部署配置', exact: true }).click()
  const handle = page.getByRole('separator', { name: '调整辅助面板宽度' })
  for (const event of ['pointercancel', 'lostpointercapture']) {
    const box = (await handle.boundingBox())!
    await page.mouse.move(box.x + box.width / 2, box.y + 50)
    await page.mouse.down()
    await expect(page.locator('.sql-workbench')).toHaveClass(/inspector-resizing/)
    await handle.dispatchEvent(event)
    await expect(page.locator('.sql-workbench')).not.toHaveClass(/inspector-resizing/)
    await page.mouse.up()
  }
  for (const width of [1001, 1000, 999, 1001]) {
    await page.setViewportSize({ width, height: 900 })
    if (width > 1000) await expect(handle).toBeVisible()
    else await expect(handle).toBeHidden()
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(
      width,
    )
    await page.getByRole('button', { name: '收起部署配置', exact: true }).click()
    await page.getByRole('button', { name: '部署配置', exact: true }).click()
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(
      width,
    )
  }
})

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
  await page.getByRole('tab', { name: '运行状态', exact: true }).click()
  await expect(page.getByRole('tabpanel')).toContainText('有界 SQL 验证完成')
  await page.getByRole('tab', { name: '最终结果', exact: true }).click()
  await expect(page.locator('.verification-final-table tbody tr')).toHaveCount(2)
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
  const left = (await columns.nth(0).boundingBox())!,
    right = (await columns.nth(1).boundingBox())!
  expect(right.x).toBeGreaterThan(left.x + left.width)
  expect(Math.abs(left.y - right.y)).toBeLessThan(2)
  await expect(page.locator('.session-form-grid')).toHaveCSS('border-top-width', '0px')
  await page.screenshot({ path: 'test-results/session-editor-desktop.png', fullPage: true })
  await page.getByLabel('集群名称', { exact: true }).fill('validation')
  await page.getByRole('checkbox', { name: /SQL 验证专用 Session/ }).check()
  await page.getByRole('combobox', { name: '运行镜像', exact: true }).selectOption('flink')
  await expect(
    page.locator('.session-dependencies').getByLabel('文件目录', { exact: true }),
  ).toHaveValue('shared')
  const runner = page.locator('[data-field="runnerArtifactId"]')
  await expect(runner.getByLabel('文件目录', { exact: true })).toHaveValue('runner')
  await runner.getByRole('button', { name: 'HTTP / S3 地址', exact: true }).click()
  await runner
    .getByLabel('SQL Runner 文件地址', { exact: true })
    .fill('s3://test/sql-runner/runner.jar')
  await runner.getByRole('button', { name: '确认文件地址', exact: true }).click()
  await page.locator('[data-field="podTemplate"] summary').click()
  await page
    .getByRole('textbox', { name: '共享 PodTemplate（YAML）', exact: true })
    .fill('spec: [bad')
  await page.getByRole('button', { name: '保存配置', exact: true }).click()
  await expect(page.locator('.notice.error')).toContainText('修正 PodTemplate YAML')
  await page.getByRole('textbox', { name: '共享 PodTemplate（YAML）', exact: true }).fill('{}')
  await page.getByRole('button', { name: '保存配置', exact: true }).click()
  await expect(page.getByRole('row').filter({ hasText: 'validation' })).toContainText(
    'SQL 验证 · 唯一',
  )
  await page.getByRole('button', { name: '启动', exact: true }).click()
  await expect(page.getByRole('row').filter({ hasText: 'validation' })).toContainText('已就绪')
  await expect(page.getByRole('button', { name: '配置', exact: true })).toBeDisabled()
  const row = page.getByRole('row').filter({ hasText: 'validation' })
  await expect(row.getByRole('link', { name: 'Flink UI ↗' })).toHaveAttribute(
    'href',
    '/flink-ui/sessions/sql-validation/',
  )
  await row.locator('.session-state-trigger').hover()
  await expect(page.getByRole('tooltip')).toContainText('查询时间')
  await row.locator('.session-state-trigger').press('Escape')
  const beforeRowRefresh = f.reads()
  await expect(row.getByRole('button', { name: '刷新', exact: true })).toHaveCount(0)
  await page
    .locator('.panel > .filter-bar')
    .getByRole('button', { name: '刷新', exact: true })
    .click()
  await expect(page.getByRole('button', { name: /秒后刷新|正在查询/ })).toBeDisabled()
  expect(f.reads()).toBe(beforeRowRefresh)
  await row.getByRole('button', { name: 'Pod 状态', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: /Pod 状态/ })
  await expect(drawer).toContainText('validation-tm')
  await expect(drawer).toContainText('test-node')
  await drawer.getByRole('button', { name: '关闭 Pod 状态' }).click()
  await page.screenshot({ path: 'test-results/session-clusters.png', fullPage: true })
  const reads = f.reads()
  await page.waitForTimeout(1200)
  expect(f.reads()).toBe(reads)
})

test('SQL 格式化保留注释、引用和环境变量，支持撤销，失败保留原文', async ({ page }) => {
  await fixture(page)
  await page.goto('/#/sql?draft=sql-draft')
  const editor = page.getByRole('textbox', { name: 'SQL 编辑器', exact: true })
  await expect(editor).toContainText('CREATE TABLE')
  const original =
    "-- 保留注释\nCREATE TABLE `sink` (n INT, value STRING) WITH ('connector'='jdbc','password'='{{env:PASSWORD}}'); INSERT INTO `sink` VALUES (1,'a;''b');"
  await editor.fill(original)
  await page.getByRole('button', { name: '格式化 SQL', exact: true }).click()
  await expect.poll(async () => (await editor.innerText()).split('\n').length).toBeGreaterThan(4)
  await expect(editor).toContainText('保留注释')
  await editor.press('Control+End')
  await expect(editor).toContainText("'a;''b'")
  await expect(editor).toContainText('{{env:PASSWORD}}')
  await editor.press('Control+z')
  await expect(editor).toContainText("WITH ('connector'='jdbc','password'")
  await editor.fill("SELECT 'unfinished")
  await editor.press('Control+Shift+F')
  await expect(page.getByRole('alert')).toContainText('原文已保留')
  await expect(editor).toHaveText("SELECT 'unfinished")
})

test('SQL 提示包含 Flink 类型、Connector 名称及对应选项，注释不提示', async ({ page }) => {
  await fixture(page)
  await page.goto('/#/sql?draft=sql-draft')
  const editor = page.getByRole('textbox', { name: 'SQL 编辑器', exact: true })
  await expect(editor).toContainText('CREATE TABLE')
  await editor.fill('CREATE TABLE sink (n TIMESTAMP_L')
  await editor.press('Control+End')
  await editor.press('Control+Space')
  await expect(page.getByRole('option', { name: /^TIMESTAMP_LTZ/ }).first()).toBeVisible()
  await editor.press('Escape')
  await editor.fill("CREATE TABLE sink (n INT) WITH ('connector' = 'k")
  await editor.press('Control+End')
  await editor.press('Control+Space')
  await expect(page.getByRole('option', { name: /^kafka/ })).toBeVisible()
  await page.getByRole('option', { name: /^kafka/ }).click()
  await expect(editor).toContainText("'kafka")
  await editor.fill("CREATE TABLE sink (n INT) WITH ('connector' = 'kafka', 'properties.b")
  await editor.press('Control+End')
  await editor.press('Control+Space')
  await expect(page.getByRole('option', { name: /^properties.bootstrap.servers/ })).toBeVisible()
  await editor.press('Escape')
  await editor.fill("-- WITH ('connector' = 'k")
  await editor.press('Control+End')
  await editor.press('Control+Space')
  await expect(page.locator('.cm-tooltip-autocomplete [role="option"]')).toHaveCount(0)
})

test('JAR 草稿删除支持取消、失败保留和成功移除', async ({ page }) => {
  const f = await fixture(page)
  f.jarReady()
  await page.goto('/#/jar')
  const list = page.getByLabel('JAR 草稿列表', { exact: true })
  const remove = list.getByRole('button', { name: '删除 JAR 草稿「jar-draft」', exact: true })
  await expect(list.locator('.jar-draft-row')).toHaveCount(1)
  await remove.click()
  await page.getByRole('dialog', { name: '删除 JAR 草稿', exact: true }).getByRole('button', { name: '取消', exact: true }).click()
  expect(f.deletes()).toBe(0)
  await expect(remove).toBeVisible()
  f.rejectDelete('请先删除集群资源并确认完成，再删除平台记录')
  await remove.click()
  await page.getByRole('dialog', { name: '删除 JAR 草稿', exact: true }).getByRole('button', { name: '确定', exact: true }).click()
  await expect(page.locator('.notice.error')).toContainText('请先删除集群资源')
  await expect(list.locator('.jar-draft-row')).toHaveCount(1)
  expect(f.deletes()).toBe(1)
  f.rejectDelete('')
  await remove.click()
  await page.getByRole('dialog', { name: '删除 JAR 草稿', exact: true }).getByRole('button', { name: '确定', exact: true }).click()
  await expect(list.locator('.jar-draft-row')).toHaveCount(0)
  expect(f.deletes()).toBe(2)
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
  await expect(page.getByLabel('JAR 草稿列表', { exact: true }).locator('.jar-draft-row')).toHaveCount(1)
  await expect(page.getByRole('button', { name: /删除 JAR 草稿/ })).toHaveCount(0)
  await page.goto('/#/sql?draft=sql-draft')
  await expect(page.locator('.sql-file')).toHaveCount(1)
  await expect(page.getByRole('button', { name: /删除 SQL 草稿/ })).toHaveCount(0)
  expect(f.deletes()).toBe(0)
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
  await expect(page.getByLabel('JAR 草稿列表', { exact: true }).locator('.jar-draft-row')).toHaveCount(0)
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
  await expect(page.getByLabel('JAR 草稿列表', { exact: true }).locator('.jar-draft-row')).toHaveCount(1)
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
  await expect(page.getByRole('tab', { name: '运行状态', exact: true })).toBeVisible()
  await expect
    .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
    .toBe(true)
  await page.screenshot({ path: 'test-results/sql-verification-mobile.png', fullPage: true })
})

test('Session 完整 JSON 错误浮层可悬浮、点击和键盘查看，刷新位于列表右上角', async ({ page }) => {
  await fixture(page)
  const message = JSON.stringify({
    type: 'DeploymentFailedException',
    message: '完整错误原因：' + 'Operator 校验失败；'.repeat(120) + '错误尾部标记',
  })
  await page.route('**/api/sessions', (route) =>
    route.fulfill({
      json: {
        data: [
          {
            id: 'sql-validation',
            name: 'validation',
            validation: true,
            runtime,
            state: 'ERROR',
            message,
            queriedAt: new Date().toISOString(),
          },
        ],
      },
    }),
  )
  await page.goto('/#/sessions')
  const row = page.getByRole('row').filter({ hasText: 'validation' }),
    trigger = row.locator('.session-state-trigger')
  await trigger.hover()
  const popup = page.getByRole('tooltip')
  await expect(popup).toContainText('错误尾部标记')
  expect(await popup.locator('pre').textContent()).toBe(
    JSON.stringify(JSON.parse(message), null, 2),
  )
  const rect = await popup.boundingBox()
  expect(rect!.x).toBeGreaterThanOrEqual(0)
  expect(rect!.x + rect!.width).toBeLessThanOrEqual(1920)
  await trigger.press('Escape')
  await expect(popup).toHaveCount(0)
  await trigger.click()
  await expect(popup).toBeVisible()
  const refresh = page
    .locator('.panel > .filter-bar')
    .getByRole('button', { name: '刷新', exact: true })
  const a = await refresh.boundingBox(),
    b = await row.boundingBox()
  expect(a!.y).toBeLessThan(b!.y)
  expect(a!.x).toBeGreaterThan(b!.x + b!.width * 0.7)
  await page.screenshot({
    path: '../.tools/session-ui-model-wizard/session-error.png',
    fullPage: true,
    animations: 'disabled',
  })
})
