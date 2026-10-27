import { confirmJarName } from './draft-name'
import { test, expect, type Page } from '@playwright/test'
import { stringify, parse } from 'yaml'
import { hydrateJar } from '../src/modules/job-creation/jarForm'
import { defaultLogConfiguration, runtimeLogConfiguration } from '../src/shared/utils/logConfiguration'

const image = { id: 'flink', repository: 'registry.test/flink', version: '1.20.5', flinkVersion: '1.20.5' }
const deployment = {
  apiVersion: 'flink.apache.org/v1beta1', kind: 'FlinkDeployment',
  metadata: { name: 'existing', labels: { 'flink-web/editor': 'jar-v1' } },
  spec: { image: 'registry.test/flink:1.20.5', flinkVersion: 'v1_20', serviceAccount: 'existing-account',
    flinkConfiguration: {}, podTemplate: { metadata: { labels: { scope: 'shared' } } },
    jobManager: { resource: { memory: '2g', cpu: 1 }, podTemplate: { spec: { nodeSelector: { role: 'jm' } } } },
    taskManager: { resource: { memory: '2g', cpu: 1 }, podTemplate: { spec: { nodeSelector: { role: 'tm' } } } },
    job: { jarURI: 'local:///opt/flink/lib/job.jar', parallelism: 1, upgradeMode: 'stateless' } },
}
async function fixture(page: Page) {
  const renders: any[] = [], saves: any[] = []
  let session: any
  let sqlDraft: any = { id: 'sql-pods', name: 'sql-pods', description: '', sql: '', revision: 1, runtime: {} }
  const draft = { id: 'existing', name: 'existing', description: '', revision: 1, flinkVersion: '1.20.5', yaml: stringify(deployment) }
  await page.route('**/api/**', async route => {
    const req = route.request(), path = new URL(req.url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    let data: any = []
    if (path === '/api/auth/me') data = { username: 'admin', roles: ['ROLE_ADMIN'] }
    else if (path === '/api/auth/csrf') data = { headerName: 'X-CSRF-TOKEN', token: 'test' }
    else if (path === '/api/system') data = { namespace: 'team', capabilities: [] }
    else if (path === '/api/system/operator') data = { found: true, namespace: 'team', deployment: 'operator', queriedAt: new Date().toISOString(), compatibility: { known: true, version: '1.14.0', message: '', supported: ['1.20'] } }
    else if (path === '/api/catalog/images') data = [image]
    else if (path === '/api/catalog/templates') data = [{ id: 'template', name: '三处模板', yaml: stringify(deployment) }]
    else if (path === '/api/artifacts/defaults') data = { bucket: 'files', rootPrefix: 'artifacts/', stateRoot: 's3://state' }
    else if (path === '/api/artifacts/objects') data = { objects: [], directories: [] }
    else if (path === '/api/jobs/name-availability') data = { available: true }
    else if (path === '/api/jobs/existing' || path.endsWith('/existing/open')) data = draft
    else if (path === '/api/development/links') data = { existing: { type: 'jar' } }
    else if (path === '/api/jobs/render-form') {
      const input = req.postDataJSON(); renders.push(input)
      const result = structuredClone(deployment)
      result.spec.serviceAccount = input.serviceAccount
      result.spec.podTemplate = input.podTemplate
      result.spec.jobManager.podTemplate = input.jmPodTemplate
      result.spec.taskManager.podTemplate = input.tmPodTemplate
      data = { yaml: stringify(result), flinkVersion: '1.20.5' }
    } else if (path === '/api/jobs/validate') data = { yaml: req.postDataJSON().yaml, message: '校验通过' }
    else if (path === '/api/sessions') data = session ? [session] : []
    else if (path === '/api/sql/jobs') data = [sqlDraft]
    else if (path.startsWith('/api/sql/jobs/') && req.method() === 'PUT') { saves.push(req.postDataJSON()); data = sqlDraft = { ...req.postDataJSON(), id: 'sql-pods', revision: sqlDraft.revision + 1 } }
    else if (path.startsWith('/api/sessions/') && req.method() === 'PUT') {
      saves.push(req.postDataJSON()); session = { ...req.postDataJSON(), id: 'ordinary', desiredState: 'stopped', state: 'STOPPED', revision: 1 }; data = session
    } else if (path.endsWith('/status')) data = session
    await route.fulfill({ json: { data } })
  })
  return { renders, saves }
}
test('回填保留 serviceAccount、三处 PodTemplate 和资源字段', () => {
  const mapped = hydrateJar(stringify(deployment), [image])
  expect(mapped.options.serviceAccount).toBe('existing-account')
  expect(mapped.options.jmPodTemplate).toEqual(deployment.spec.jobManager.podTemplate)
  expect(mapped.options.tmPodTemplate).toEqual(deployment.spec.taskManager.podTemplate)
  expect(mapped.options.podTemplate).toEqual(deployment.spec.podTemplate)
  expect(mapped.options.jmMemory).toBe('2g')
})

test('日志回填保留多文件原文，旧运行参数读取原YAML且无配置不补默认', () => {
  const value = structuredClone(deployment) as any
  value.spec.logConfiguration = { 'logback-console.xml': '<configuration/>\n', 'custom.properties': 'key=value\n' }
  expect(hydrateJar(stringify(value), [image]).options.logConfiguration).toEqual(value.spec.logConfiguration)
  expect(runtimeLogConfiguration({ deploymentYaml: stringify(value) })).toEqual(value.spec.logConfiguration)
  expect(runtimeLogConfiguration({})).toEqual({})
})

test('新JAR日志默认折叠，默认双输出可编辑并进入预览请求，移除可显式清空', async ({ page }) => {
  const f = await fixture(page)
  await page.goto('/#/jar/new')
  await confirmJarName(page, 'log-defaults')
  await page.getByRole('combobox', { name: '镜像版本', exact: false }).selectOption('flink')
  const section=page.locator('[data-field="logConfiguration"]')
  await expect(section).not.toHaveAttribute('open','')
  await section.locator('summary').click()
  const text=page.getByLabel('日志配置文件 log4j-console.properties', { exact:true })
  await expect(text).toHaveValue(defaultLogConfiguration()['log4j-console.properties']!)
  await text.fill('rootLogger.level = WARN\n')
  await page.getByRole('button', { name: '本地验证与预览', exact:true }).click()
  await expect.poll(()=>f.renders.length).toBe(1)
  expect(f.renders[0].logConfiguration).toEqual({'log4j-console.properties':'rootLogger.level = WARN\n'})
  await section.getByRole('button',{name:'移除日志覆盖'}).click()
  await page.getByRole('button', { name: '本地验证与预览', exact:true }).click()
  await expect.poll(()=>f.renders.length).toBe(2)
  expect(f.renders[1].logConfiguration).toEqual({})
})
test('新 JAR 默认账号来自 namespace，模板可识别三处且默认折叠', async ({ page }) => {
  await fixture(page)
  await page.goto('/#/jar/new')
  await confirmJarName(page, 'new-jar-test')
  await expect(page.getByLabel('ServiceAccount', { exact: false })).toHaveValue('team-flink')
  await expect(page.locator('.pod-template-field[open]')).toHaveCount(0)
  await page.getByLabel('默认模板', { exact: false }).selectOption('template')
  await expect(page.getByLabel('ServiceAccount', { exact: false })).toHaveValue('existing-account')
  await page.locator('[data-field="jmPodTemplate"] summary').click()
  await expect(page.getByRole('textbox', { name: 'JobManager PodTemplate（YAML）' })).toContainText('role: jm')
})
test('JAR 编辑三处 YAML，清空单处、账号修改和语法错误阻止生成', async ({ page }) => {
  const f = await fixture(page)
  await page.goto('/#/jar/existing/edit')
  await expect(page.getByLabel('ServiceAccount', { exact: false })).toHaveValue('existing-account')
  for (const field of ['podTemplate', 'jmPodTemplate', 'tmPodTemplate']) await page.locator(`[data-field="${field}"] summary`).click()
  await page.getByRole('textbox', { name: 'JobManager PodTemplate（YAML）' }).fill('spec:\n  nodeSelector:\n    zone: new-zone')
  await page.getByRole('textbox', { name: 'TaskManager PodTemplate（YAML）' }).fill('')
  await page.getByLabel('ServiceAccount', { exact: false }).fill('custom-account')
  await page.getByRole('button', { name: '本地验证与预览', exact: true }).click()
  await expect.poll(() => f.renders.length).toBe(1)
  expect(f.renders[0]).toMatchObject({ serviceAccount: 'custom-account', jmPodTemplate: { spec: { nodeSelector: { zone: 'new-zone' } } }, tmPodTemplate: {} })
  expect(parse(f.renders[0].baseYaml).spec.jobManager.resource.memory).toBe('2g')
  await page.getByRole('textbox', { name: '共享 PodTemplate（YAML）' }).fill('spec: [bad')
  await page.getByRole('button', { name: '本地验证与预览', exact: true }).click()
  await expect(page.locator('.notice.error')).toContainText('修正 PodTemplate')
  await page.getByRole('button', { name: 'YAML 编辑', exact: true }).click()
  await expect(page.locator('.notice.error')).toContainText('修正 PodTemplate')
  expect(f.renders).toHaveLength(1)
})
test('Session 保存三个 YAML 模板和自定义账号，重新编辑能回填', async ({ page }) => {
  const f = await fixture(page)
  await page.goto('/#/sessions')
  await page.getByRole('button', { name: '＋ 新建 Session' }).click()
  await expect(page.getByLabel('ServiceAccount', { exact: false })).toHaveValue('team-flink')
  await page.getByLabel('集群名称', { exact: true }).fill('ordinary')
  await page.getByRole('combobox', { name: '运行镜像', exact: true }).selectOption('flink')
  await page.locator('[data-field="logConfiguration"] summary').click()
  await page.getByLabel('日志配置文件 log4j-console.properties', { exact:true }).fill('rootLogger.level=DEBUG\n')
  await page.getByLabel('ServiceAccount', { exact: false }).fill('session-account')
  for (const [key, label, text] of [['podTemplate', '共享', 'metadata:\n  labels:\n    shared: "yes"'], ['jmPodTemplate', 'JobManager', 'spec:\n  nodeSelector:\n    role: jm'], ['tmPodTemplate', 'TaskManager', 'spec:\n  nodeSelector:\n    role: tm']]) {
    await page.locator(`[data-field="${key}"] summary`).click()
    await page.getByRole('textbox', { name: label + ' PodTemplate（YAML）' }).fill(text!)
  }
  await page.getByRole('button', { name: '保存配置', exact: true }).click()
  await expect.poll(() => f.saves.length).toBe(1)
  expect(f.saves[0].runtime).toMatchObject({ serviceAccount: 'session-account', jmPodTemplate: { spec: { nodeSelector: { role: 'jm' } } }, tmPodTemplate: { spec: { nodeSelector: { role: 'tm' } } } })
  expect(f.saves[0].runtime.logConfiguration).toEqual({'log4j-console.properties':'rootLogger.level=DEBUG\n'})
  await page.getByRole('button', { name: '配置', exact: true }).click()
  await expect(page.getByLabel('ServiceAccount', { exact: false })).toHaveValue('session-account')
  await page.locator('[data-field="tmPodTemplate"] summary').click()
  await expect(page.getByRole('textbox', { name: 'TaskManager PodTemplate（YAML）' })).toContainText('role: tm')
  await page.evaluate(() => window.scrollTo(0, 0))
  await page.screenshot({ path: 'test-results/pod-templates-session.png', fullPage: true })
})
test('Ctrl+F 搜索与替换面板统一控件样式并在窄屏正常换行', async ({ page }) => {
  await fixture(page)
  await page.goto('/#/jar/new')
  await confirmJarName(page, 'new-jar-test')
  await page.getByRole('button', { name: 'YAML 编辑', exact: true }).click()
  const editor = page.getByRole('textbox', { name: 'YAML 编辑器', exact: true })
  await editor.fill('kind: FlinkDeployment\nmetadata:\n  name: find-me')
  await editor.press('Control+f')
  const panel = page.locator('.cm-search')
  await expect(panel).toBeVisible()
  await panel.getByRole('textbox', { name: 'Find', exact: true }).fill('find-me')
  await panel.getByRole('textbox', { name: 'Find', exact: true }).press('End')
  await expect(page.locator('.cm-searchMatch')).toHaveCount(1)
  await expect(panel.locator('.cm-textfield').first()).toHaveCSS('height', '30px')
  await expect(panel.locator('.cm-button').first()).toHaveCSS('border-radius', '6px')
  await panel.getByRole('textbox', { name: 'Replace', exact: true }).fill('replaced')
  await panel.getByRole('textbox', { name: 'Replace', exact: true }).press('End')
  await panel.getByRole('button', { name: 'replace all', exact: true }).click()
  await expect(editor).toContainText('replaced')
  await page.screenshot({ path: 'test-results/yaml-search-desktop.png', fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.locator('.sidebar')).toHaveCSS('width', '68px')
  await expect(panel).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy()
  await page.screenshot({ path: 'test-results/yaml-search-mobile.png', fullPage: true })
  await panel.getByRole('button', { name: 'close', exact: true }).click()
  await expect(panel).toHaveCount(0)
})

test('SQL 复制三处模板并保存账号，格式错误切换页签后保留且阻止保存', async ({ page }) => {
  const f = await fixture(page)
  await page.goto('/#/sql')
  await page.getByRole('button', { name: '部署配置', exact: true }).click()
  await expect(page.getByLabel('ServiceAccount', { exact: false })).toHaveValue('team-flink')
  await expect(page.getByLabel('作业名称', { exact: false })).toHaveValue('sql-pods')
  await page.getByRole('combobox', { name: '镜像版本', exact: true }).selectOption('flink')
  await page.getByText('程序与依赖及 Flink 配置', { exact: true }).click()
  await page.locator('[data-field="logConfiguration"] summary').click()
  await page.getByRole('button',{name:'填入 Log4j2 默认配置'}).click()
  await page.getByLabel('日志配置文件 log4j-console.properties',{exact:true}).fill('rootLogger.level=WARN\n')
  await page.getByLabel('从模板获取 PodTemplate').selectOption('template')
  await page.getByRole('button', { name: '应用 PodTemplate', exact: true }).click()
  await page.locator('[data-field="jmPodTemplate"] summary').click()
  const editor = page.getByRole('textbox', { name: 'JobManager PodTemplate（YAML）' })
  await expect(editor).toContainText('role: jm')
  await page.getByLabel('ServiceAccount', { exact: false }).fill('sql-account')
  await page.getByRole('button', { name: '保存 SQL', exact: true }).click()
  await expect.poll(() => f.saves.length).toBe(1)
  expect(f.saves[0].runtime).toMatchObject({ serviceAccount: 'sql-account', podTemplate: deployment.spec.podTemplate, jmPodTemplate: deployment.spec.jobManager.podTemplate, tmPodTemplate: deployment.spec.taskManager.podTemplate })
  expect(f.saves[0].runtime.logConfiguration).toEqual({'log4j-console.properties':'rootLogger.level=WARN\n'})
  await editor.fill('spec: [bad')
  await page.getByRole('button', { name: '部署配置', exact: true }).click()
  await page.getByRole('button', { name: '部署配置', exact: true }).click()
  await expect(editor).toContainText('spec: [bad')
  await page.getByRole('button', { name: '保存 SQL', exact: true }).click()
  await expect(page.locator('.notice.error')).toContainText('修正 PodTemplate YAML')
  expect(f.saves).toHaveLength(1)
  await editor.fill('spec:\n  nodeSelector:\n    role: edited')
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.locator('.sidebar')).toHaveCSS('width', '68px')
  await editor.scrollIntoViewIfNeeded()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy()
  await page.screenshot({ path: 'test-results/pod-templates-sql-mobile.png', fullPage: true })
})
