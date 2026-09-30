import { test, expect, type Page } from '@playwright/test'

const source = ['apiVersion: flink.apache.org/v1beta1', 'kind: FlinkDeployment', 'metadata:', '  name: orders', 'spec:', '  image: registry.test/flink:1.20.5', '  flinkVersion: v1_20', '  job:', '    jarURI: local:///opt/flink/usrlib/job.jar', '    parallelism: 1', '    state: running', ''].join('\n')
const draft = { id: 'job-one', jobNumber: 1, deploymentName: 'flink-1', name: 'orders', description: '', flinkVersion: '1.20.5', revision: 0, yaml: source }
async function fixture(page: Page, editYaml = source) {
  const renders: any[] = []
  let live: any = { name: 'flink-1', jobId: draft.id, managed: true, application: true, jobState: 'SUSPENDED', desiredState: 'suspended', lifecycleState: 'STABLE', yaml: source + 'status:\n  error: original error\n', snapshots: [] }
  const actions: string[] = []
  let yamlReads = 0
  const dialogs: string[] = []
  page.on('dialog', async dialog => { dialogs.push(dialog.type()); await dialog.dismiss() })
  await page.route('**/api/**', async route => {
    const req = route.request(), url = new URL(req.url()), path = url.pathname
    if (!path.startsWith("/api/")) return route.continue()
    let data: any = []
    if (path === '/api/auth/me') data = { username: 'admin', roles: ['ROLE_ADMIN'] }
    else if (path === '/api/auth/csrf') data = { headerName: 'X-CSRF-TOKEN', token: 'test' }
    else if (path === '/api/system') data = { version: 'test', namespace: 'test', storageType: 'local', capabilities: [] }
    else if (path === '/api/system/operator') data = { found: true, compatibility: { known: true, version: '1.13', supported: ['1.20'] } }
    else if (path === '/api/artifacts/defaults') data = { bucket: 'flink-web', rootPrefix: 'artifacts/', stateRoot: 's3://flink-state', endpoint: 'http://minio:9000', region: 'us-east-1' }
    else if (path === '/api/artifacts/objects') data = { bucket: 'flink-web', rootPrefix: 'artifacts/', prefix: url.searchParams.get('prefix') || 'artifacts/', objects: [], directories: [] }
    else if (path === '/api/jobs/name-availability') data = { available: url.searchParams.get('name') !== 'orders' }
    else if (path === '/api/jobs/refresh') { yamlReads++; data = { items: [live], queriedAt: new Date().toISOString() } }
    else if (path === '/api/jobs') data = req.method() === 'POST' ? { ...draft, ...req.postDataJSON(), deploymentName: 'flink-2', jobNumber: 2 } : [draft]
    else if (path === '/api/jobs/job-one') data = { ...draft, yaml: editYaml }
    else if (path === '/api/jobs/render-form') { renders.push(req.postDataJSON()); data = { yaml: source, flinkVersion: '1.20.5' } }
    else if (path === '/api/jobs/validate') data = { yaml: source, valid: true, message: '验证通过' }
    else if (path.includes('/actions/')) { actions.push(path.split('/').pop()!); data = { state: 'ACCEPTED', message: 'API 已接受' } }
    else if (path === '/api/catalog/images') data = [{ id: 'image', name: 'image', repository: 'registry.test/flink', version: '1.20.5', flinkVersion: '1.20.5', revision: 1 }]
    else if (path === '/api/platform/job-access') data = { 'job-one': { canEdit: true, canOperate: true } }
    else if (path.endsWith('/access')) data = { canEdit: true, canOperate: true, owner: 'admin', readGroups: [], operateGroups: [], editGroups: [], links: [] }
    await route.fulfill({ json: { data } })
  })
  return { actions, dialogs, renders, setLive: (value: any) => { live = { ...live, ...value } }, yamlReads: () => yamlReads }
}

test('名称失焦查重显示红框，改名后清除，部署成功返回作业列表', async ({ page }) => {
  const f = await fixture(page)
  await page.goto('/#/jobs/jar/new')
  const name = page.locator('input[data-field="name"]')
  await name.fill('orders')
  await name.blur()
  await expect(name).toHaveAttribute('aria-invalid', 'true')
  await expect(page.locator('.inline-validation')).toContainText('作业名称已存在')
  await name.fill('orders-new')
  await name.blur()
  await expect(name).not.toHaveAttribute('aria-invalid', 'true')
  await page.getByLabel('镜像版本', { exact: true }).selectOption('image')
  await page.getByRole('button', { name: '保存并部署', exact: true }).click()
  await expect(page).toHaveURL(/#\/jobs$/)
  expect(f.actions).toEqual(['deploy'])
  expect(f.dialogs).toEqual([])
})

test('启动受理后立即可停止，重复启动和快照被禁止', async ({ page }) => {
  const f = await fixture(page)
  await page.goto('/#/jobs')
  const row = page.getByRole('row').filter({ has: page.getByRole('button', { name: 'orders', exact: true }) })
  await row.getByRole('button', { name: '启动', exact: true }).click()
  await page.getByRole('button', { name: '确认启动', exact: true }).click()
  await page.getByRole('button', { name: '关闭', exact: true }).click()
  await expect(row.getByRole('button', { name: '启动', exact: true })).toBeDisabled()
  await expect(row.getByRole('button', { name: '停止', exact: true })).toBeEnabled()
  await page.getByLabel('选择当前页', { exact: true }).check()
  await expect(page.locator('.panel-toolbar').getByRole('button', { name: 'Savepoint', exact: true })).toBeDisabled()
  await row.getByRole('button', { name: '停止', exact: true }).click()
  await page.getByRole('button', { name: '确认停止', exact: true }).click()
  await expect.poll(() => f.actions).toEqual(['start', 'stop'])
  await expect(page.getByRole('dialog')).not.toBeVisible()
  await expect(row.getByRole('button', { name: '停止', exact: true })).toBeEnabled()
})

test('当前 YAML 只展示本次查询，详情不再出现快捷操作区', async ({ page }) => {
  const f = await fixture(page)
  await page.goto('/#/jobs')
  await page.getByRole('button', { name: '当前 YAML', exact: true }).click()
  await expect(page.getByRole('dialog')).toContainText('original error')
  expect(f.yamlReads()).toBe(1)
  await page.getByRole('button', { name: '关闭', exact: true }).click()
  await page.getByRole('button', { name: 'orders', exact: true }).click()
  await expect(page.getByText('最近快照信息', { exact: true })).toHaveCount(0)
  await expect(page.getByText('状态升级策略', { exact: true })).toHaveCount(0)
})

test('镜像字段精简，非法填写就地提示，删除使用站内确认框', async ({ page }) => {
  const f = await fixture(page)
  await page.goto('/#/images')
  await page.getByRole('button', { name: '＋ 新增', exact: true }).click()
  await expect(page.getByText('显示名称', { exact: true })).toHaveCount(0)
  await expect(page.getByText('Flink 版本', { exact: true })).toHaveCount(0)
  await expect(page.getByText('imagePullSecret', { exact: true })).toHaveCount(0)
  const id = page.locator('input[data-field="id"]')
  await id.fill('Bad ID')
  await id.blur()
  await expect(id).toHaveAttribute('aria-invalid', 'true')
  await expect(page.locator('.inline-validation')).toBeVisible()
  await page.getByRole('button', { name: '取消', exact: true }).click()
  await page.getByRole('button', { name: '删除', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '确认操作' })).toBeVisible()
  await page.getByRole('button', { name: '取消', exact: true }).click()
  expect(f.dialogs).toEqual([])
})

test('已有 YAML 回填 JAR 表单，并把完整底稿交回后端保留自定义字段', async ({ page }) => {
  const custom = source.replace('    parallelism: 1', '    parallelism: 4\n    entryClass: com.example.Main\n    args: [--input, orders]')
    + '  jobManager:\n    replicas: 2\n    resource: {memory: 2g, cpu: 0.5}\n  flinkConfiguration:\n    taskmanager.numberOfTaskSlots: "3"\n    custom.option: kept\n'
  const f = await fixture(page, custom)
  await page.goto('/#/jobs/job-one/edit')
  await page.getByRole('button', { name: 'JAR 表单', exact: true }).click()
  await expect(page.locator('input[data-field="parallelism"]')).toHaveValue('4')
  await expect(page.locator('input[data-field="slots"]')).toHaveValue('3')
  await expect(page.locator('input[data-field="entryClass"]')).toHaveValue('com.example.Main')
  await expect(page.locator('textarea[data-field="args"]')).toHaveValue('--input\norders')
  await page.getByRole('button', { name: '本地验证与预览', exact: true }).click()
  await expect.poll(() => f.renders.length).toBe(1)
  expect(f.renders[0].baseYaml).toContain('replicas: 2')
  expect(f.renders[0].flinkConfiguration['custom.option']).toBe('kept')
})

test('不能映射历史镜像时仍留在 YAML 模式', async ({ page }) => {
  await fixture(page, source.replace('registry.test/flink:1.20.5', 'unknown/legacy:old'))
  await page.goto('/#/jobs/job-one/edit')
  await page.getByRole('button', { name: 'JAR 表单', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('只能在 YAML 页面修改')
  await expect(page.locator('input[data-field="parallelism"]')).toHaveCount(0)
})

test('状态存储和重试参数显式进入生成请求，关闭后不再生成 Checkpoint 设置', async ({ page }) => {
  const f = await fixture(page)
  await page.goto('/#/jobs/jar/new')
  await page.locator('input[data-field="name"]').fill('new-state-job')
  await page.getByLabel('镜像版本', { exact: true }).selectOption('image')
  await page.getByLabel('启用状态存储与 Checkpoint', { exact: true }).check()
  await expect(page.getByLabel('Checkpoint 间隔', { exact: true })).toBeVisible()
  for (const label of ['Checkpoint 存储', '状态后端', 'Checkpoint 目录', 'Savepoint 目录', '取消作业后的保留策略', 'S3 Endpoint', 'S3 Region', '路径式访问']) await expect(page.getByLabel(label, { exact: true })).toHaveCount(0)
  await page.getByLabel('Checkpoint 间隔', { exact: true }).fill('120 s')
  await page.getByLabel('保留 Checkpoint 数量', { exact: true }).fill('5')
  await page.getByLabel('故障恢复策略', { exact: true }).selectOption('fixed-delay')
  await expect(page.locator('.strategy-description')).toContainText('固定时间')
  await page.getByLabel('最大重试次数', { exact: true }).fill('8')
  await page.locator('.state-storage-form').screenshot({ path: 'test-results/state-storage.png' })
  await page.getByRole('button', { name: '本地验证与预览', exact: true }).click()
  await expect.poll(() => f.renders.length).toBe(1)
  expect(f.renders[0].flinkConfiguration).toMatchObject({ 'execution.checkpointing.interval':'120 s','state.checkpoints.num-retained':'5','restart-strategy.fixed-delay.attempts':'8','s3.endpoint':'http://minio:9000' })
  expect(f.renders[0].flinkConfiguration['state.checkpoints.dir']).toMatch(/^s3:\/\/flink-state\/[0-9a-f-]+\/checkpoints$/)
  expect(f.renders[0].flinkConfiguration['state.savepoints.dir']).toMatch(/^s3:\/\/flink-state\/[0-9a-f-]+\/savepoints$/)
  expect(f.renders[0].flinkConfiguration['s3.path.style.access']).toBe('true')
  for (const [strategy, intro, field] of [
    ['failure-rate', '统计周期', '周期内最大失败次数'],
    ['exponential-delay', '按倍数', '初始等待'],
  ]) {
    await page.getByLabel('故障恢复策略', { exact: true }).selectOption(strategy!)
    await expect(page.locator('.strategy-description')).toContainText(intro!)
    await expect(page.getByLabel(field!, {exact:true})).toBeVisible()
  }
  await page.getByLabel('故障恢复策略', { exact: true }).selectOption('none')
  await expect(page.locator('.strategy-description')).toContainText('不自动恢复')
  await page.getByLabel('启用状态存储与 Checkpoint', { exact: true }).uncheck()
  await page.getByRole('button', { name: '本地验证与预览', exact: true }).click()
  await expect.poll(() => f.renders.length).toBe(2)
  expect(f.renders[1].flinkConfiguration['execution.checkpointing.interval']).toBeUndefined()
  expect(f.renders[1].flinkConfiguration['state.checkpoints.dir']).toBeUndefined()
})

test('对象页提供目录浏览，不再出现制品登记表单', async ({ page }) => {
  await fixture(page)
  await page.route('**/api/artifacts/objects?*', async route => {
    const prefix = new URL(route.request().url()).searchParams.get('prefix') || 'artifacts/'
    await route.fulfill({json:{data:{bucket:'flink-web',rootPrefix:'artifacts/',prefix,directories:prefix === 'artifacts/' ? ['artifacts/jobs/'] : [],objects:prefix === 'artifacts/jobs/' ? [{key:'artifacts/jobs/job.jar',size:4096,modifiedAt:'2026-09-30T00:00:00Z'}] : []}}})
  })
  await page.goto('/#/artifacts')
  await expect(page.getByText('对象浏览器', { exact:true })).toBeVisible()
  await expect(page.getByText('制品登记', { exact:true })).toHaveCount(0)
  await page.getByRole('button',{name:'📁 jobs',exact:true}).click()
  await expect(page.getByRole('cell',{name:'job.jar',exact:true})).toBeVisible()
  await page.locator('.object-browser').screenshot({ path: 'test-results/object-browser.png' })
  await page.getByLabel('选择 artifacts/jobs/job.jar', {exact:true}).check()
  await expect(page.getByRole('link',{name:'下载',exact:true})).toHaveAttribute('href', /artifacts%2Fjobs%2Fjob.jar/)
})

test('主包与依赖直接从当前作业 S3 目录选择，不请求制品登记列表', async ({ page }) => {
  const f = await fixture(page)
  let catalogReads = 0
  page.on('request', req => { if (new URL(req.url()).pathname === '/api/catalog/artifacts') catalogReads++ })
  await page.route('**/api/artifacts/objects?*', async route => {
    const prefix = new URL(route.request().url()).searchParams.get('prefix')!
    await route.fulfill({json:{data:{bucket:'flink-web',rootPrefix:'artifacts/',prefix,directories:[],objects:[{key:prefix+'main.jar'},{key:prefix+'connector.jar'}]}}})
  })
  await page.goto('/#/jobs/jar/new')
  await page.locator('input[data-field="name"]').fill('direct-s3')
  await page.getByLabel('镜像版本',{exact:true}).selectOption('image')
  const main = page.getByRole('combobox',{name:'主 JAR 文件',exact:true})
  await expect(main.locator('option').filter({hasText:'main.jar'})).toHaveCount(1)
  const reference = await main.locator('option').filter({hasText:'main.jar'}).getAttribute('value')
  expect(reference).toMatch(new RegExp('^s3:artifacts/jobs/[0-9a-f-]+/main.jar$'))
  await main.selectOption(reference!)
  await page.locator('.object-choices').getByLabel('connector.jar',{exact:true}).check()
  await page.getByRole('button',{name:'本地验证与预览',exact:true}).click()
  await expect.poll(() => f.renders.length).toBe(1)
  expect(f.renders[0].mainArtifactId).toBe(reference)
  expect(f.renders[0].dependencyArtifactIds).toEqual([reference!.replace('main.jar','connector.jar')])
  expect(catalogReads).toBe(0)
})
