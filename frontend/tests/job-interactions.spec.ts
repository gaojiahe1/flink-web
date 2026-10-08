import { test, expect, type Page } from '@playwright/test'

const source = ['apiVersion: flink.apache.org/v1beta1', 'kind: FlinkDeployment', 'metadata:', '  name: orders', '  labels:', '    flink-web/editor: jar-v1', 'spec:', '  image: registry.test/flink:1.20.5', '  flinkVersion: v1_20', '  job:', '    jarURI: local:///opt/flink/usrlib/job.jar', '    parallelism: 1', '    state: running', ''].join('\n')
const draft = { id: 'job-one', jobNumber: 1, deploymentName: 'flink-0000001', name: 'orders', description: '', flinkVersion: '1.20.5', revision: 0, yaml: source }
async function fixture(page: Page, editYaml = source) {
  const renders: any[] = []
  let live: any = { name: 'flink-0000001', jobId: draft.id, managed: true, application: true, jobState: 'SUSPENDED', desiredState: 'suspended', lifecycleState: 'STABLE', yaml: source + 'status:\n  error: original error\n', snapshots: [] }
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
    else if (path === '/api/jobs') data = req.method() === 'POST' ? { ...draft, ...req.postDataJSON(), deploymentName: 'flink-0000002', jobNumber: 2 } : [draft]
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
  await page.getByRole('button', { name: '关闭 YAML', exact: true }).click()
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
  await expect(page.getByRole('alert')).toContainText('原 YAML 已保留')
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
  await page.getByLabel('Checkpoint 间隔', { exact: true }).fill('2')
  await page.getByLabel('Checkpoint 间隔单位', { exact: true }).selectOption('min')
  await page.getByLabel('Checkpoint 存储策略', { exact: true }).selectOption('DELETE_ON_CANCELLATION')
  await expect(page.locator('.retention-description')).toContainText('正常取消')
  await page.getByLabel('保留 Checkpoint 数量', { exact: true }).fill('5')
  await page.getByLabel('故障恢复策略', { exact: true }).selectOption('fixed-delay')
  await expect(page.locator('.strategy-description')).toContainText('固定时间')
  await page.getByLabel('最大重试次数', { exact: true }).fill('8')
  await page.locator('.state-storage-form').screenshot({ path: 'test-results/state-storage.png' })
  await page.getByRole('button', { name: '本地验证与预览', exact: true }).click()
  await expect.poll(() => f.renders.length).toBe(1)
  expect(f.renders[0].flinkConfiguration).toMatchObject({ 'execution.checkpointing.interval':'2 min','state.checkpoints.num-retained':'5','restart-strategy.fixed-delay.attempts':'8','s3.endpoint':'http://minio:9000' })
  expect(f.renders[0].flinkConfiguration['state.checkpoints.dir']).toMatch(/^s3:\/\/flink-state\/[0-9a-f-]+\/checkpoints$/)
  expect(f.renders[0].flinkConfiguration['state.savepoints.dir']).toMatch(/^s3:\/\/flink-state\/[0-9a-f-]+\/savepoints$/)
  expect(f.renders[0].flinkConfiguration['s3.path.style.access']).toBe('true')
  expect(f.renders[0].flinkConfiguration['s3.region']).toBeUndefined()
  expect(f.renders[0].flinkConfiguration['high-availability.type']).toBe('kubernetes')
  expect(f.renders[0].flinkConfiguration['high-availability.storageDir']).toMatch(/^s3:\/\/flink-state\/[0-9a-f-]+\/ha$/)
  expect(f.renders[0].flinkConfiguration['s3.access-key']).toBe('{{secret:s3-access-key}}')
  expect(f.renders[0].flinkConfiguration['s3.secret-key']).toBe('{{secret:s3-secret-key}}')
  expect(f.renders[0].flinkConfiguration['execution.checkpointing.externalized-checkpoint-retention']).toBe('DELETE_ON_CANCELLATION')
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
  expect(Object.keys(f.renders[1].flinkConfiguration).some(k => k.startsWith('s3.') || k.startsWith('high-availability'))).toBe(false)
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
  await page.getByRole('button',{name:'jobs',exact:true}).click()
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
  const main = page.locator('.artifact-picker').first().getByRole('radio', { name: 'main.jar', exact: true })
  await main.check()
  const reference = 's3:' + (await page.locator('.artifact-picker').first().locator('.picker-path').textContent())!.trim().replace('s3://flink-web/', '') + 'main.jar'
  expect(reference).toMatch(new RegExp('^s3:artifacts/jobs/[0-9a-f-]+/main.jar$'))
  const picker = page.locator('.artifact-picker').first()
  const heading = await picker.locator('strong').boundingBox()
  const refresh = await picker.getByRole('button', {name:'刷新文件'}).boundingBox()
  const upload = await picker.locator('.upload-button').boundingBox()
  expect(refresh!.x).toBeGreaterThan(heading!.x + heading!.width)
  expect(Math.abs(refresh!.y - upload!.y)).toBeLessThan(3)
  await picker.screenshot({path: 'test-results/artifact-picker.png'})
  await page.locator('.artifact-picker').nth(1).getByRole('checkbox', { name: 'connector.jar', exact: true }).check()
  await page.getByRole('button',{name:'本地验证与预览',exact:true}).click()
  await expect.poll(() => f.renders.length).toBe(1)
  expect(f.renders[0].mainArtifactId).toBe(reference)
  expect(f.renders[0].dependencyArtifactIds).toEqual([reference!.replace('main.jar','connector.jar')])
  expect(catalogReads).toBe(0)
})


test('更换升级策略后清除旧的 HA 校验边框与提示', async ({page}) => {
  await fixture(page)
  await page.route('**/api/jobs/render-form', route => route.fulfill({status:400,json:{error:{code:'VALIDATION',message:'last-state 模式需要配置 Kubernetes HA 及 HA 存储目录'}}}))
  await page.goto('/#/jobs/jar/new')
  await page.locator('input[data-field="name"]').fill('retry-config')
  await page.getByLabel('镜像版本',{exact:true}).selectOption('image')
  await page.locator('select[data-field="upgradeMode"]').selectOption('last-state')
  await page.getByRole('button',{name:'本地验证与预览',exact:true}).click()
  await expect(page.locator('.inline-validation')).toContainText('HA')
  await page.locator('select[data-field="upgradeMode"]').selectOption('stateless')
  await expect(page.locator('.inline-validation')).toHaveCount(0)
  await expect(page.getByRole('alert')).toHaveCount(0)
})

test('列表异常优先显示，Pod 抽屉展示初始化容器失败与事件', async ({page}) => {
  const f = await fixture(page)
  f.setLive({jobState:'RECONCILING',desiredState:'running',healthState:'ERROR',jobManagerDeploymentStatus:'ERROR',error:'下载器初始化失败'})
  await page.route('**/api/jobs/job-one/diagnostics', route => route.fulfill({json:{data:{
    queriedAt:'2026-10-01T08:00:00Z',error:'下载器初始化失败',
    pods:[{name:'flink-0000001-jm-7df98',phase:'Pending',node:'worker-01',createdAt:'2026-10-01T07:50:00Z',containers:['fw-download','flink-main-container'],
      states:[{name:'fw-download',init:true,state:'waiting',ready:false,restarts:4,reason:'CrashLoopBackOff',message:'S3 文件下载失败，请检查凭据和对象路径'}],
      conditions:[{type:'Initialized',status:'False',reason:'ContainersNotInitialized',message:'等待下载器完成'}]}],
    events:[{type:'Warning',reason:'BackOff',message:'Back-off restarting failed container fw-download',time:'2026-10-01T07:59:00Z'}]
  }}}))
  await page.goto('/#/jobs')
  await expect(page.getByText('部署异常',{exact:true})).toBeVisible()
  await page.getByLabel('选择当前页',{exact:true}).check()
  await expect(page.locator('.panel-toolbar').getByRole('button',{name:'Checkpoint',exact:true})).toBeDisabled()
  await page.getByRole('button',{name:'Pod 状态',exact:true}).click()
  const drawer = page.getByRole('dialog')
  await expect(drawer).toContainText('CrashLoopBackOff')
  await expect(drawer).toContainText('worker-01')
  await expect(drawer).toContainText('重启 4 次')
  await expect(drawer).toContainText('Back-off restarting')
  await drawer.screenshot({path:'test-results/pod-drawer.png'})
  await page.getByRole('button',{name:'关闭 Pod 状态'}).click()
  await expect(drawer).toHaveCount(0)
})

test('停止期望立即更新，刷新清除旧提示，重新启动不残留已停止', async ({page}) => {
  await fixture(page)
  let phase = 'running'
  await page.route('**/api/jobs/refresh', route => route.fulfill({json:{data:{
    items:phase === 'running' ? [{name:'flink-0000001',jobId:'job-one',managed:true,application:true,desiredState:'running',jobState:'RUNNING',lifecycleState:'STABLE'}] : [],
    queriedAt:new Date().toISOString(),stopProgress:phase === 'done' ? {'job-one':{phase:'DONE',message:'已删除 FlinkDeployment'}} : {}
  }}}))
  await page.goto('/#/jobs')
  const row=page.getByRole('row').filter({has:page.getByRole('button',{name:'orders',exact:true})})
  await row.getByRole('button',{name:'停止',exact:true}).click()
  await page.getByRole('button',{name:'确认停止',exact:true}).click()
  await expect(row.getByRole('cell').nth(3)).toContainText('停止')
  phase='done'
  await expect(page.getByRole('button',{name:/刷新状态/})).toBeEnabled({timeout:6000})
  await page.getByRole('button',{name:/刷新状态/}).click()
  await expect(row).toContainText('已停止')
  await expect(page.locator('.ongoing-jobs')).toHaveCount(0)
  await row.getByRole('button',{name:'启动',exact:true}).click()
  await page.getByRole('button',{name:'确认启动',exact:true}).click()
  await expect(row.getByRole('cell').nth(3)).not.toContainText('已停止')
})

test('操作记录与其他详情共用页签，刷新记录后同步展示', async ({page}) => {
  await fixture(page)
  let message='首次操作记录'
  await page.route('**/api/jobs/job-one/operations', route => route.fulfill({json:{data:[{id:'op-1',action:'start',state:'ACCEPTED',message,updatedAt:'2026-10-01T08:00:00Z'}]}}))
  await page.goto('/#/jobs')
  await page.getByRole('button',{name:'orders',exact:true}).click()
  await expect(page.getByText('首次操作记录',{exact:true})).toBeVisible()
  await page.getByRole('button',{name:'发布版本',exact:true}).click()
  await expect(page.getByText('首次操作记录',{exact:true})).toHaveCount(0)
  await page.getByRole('button',{name:'操作记录',exact:true}).click()
  message='已刷新操作记录'
  await page.getByRole('button',{name:'刷新记录',exact:true}).click()
  await expect(page.getByText('已刷新操作记录',{exact:true})).toBeVisible()
})

test('大量子目录在限高区域中筛选，不撑长 JAR 表单', async ({page}) => {
  await fixture(page)
  await page.route('**/api/artifacts/objects?*', route => {
    const prefix=new URL(route.request().url()).searchParams.get('prefix')!
    return route.fulfill({json:{data:{bucket:'flink-web',rootPrefix:'artifacts/',prefix,objects:[],directories:Array.from({length:100},(_,i)=>prefix+'folder-'+String(i).padStart(3,'0')+'/')}}})
  })
  await page.goto('/#/jobs/jar/new')
  const picker=page.locator('.artifact-picker').first()
  const list=picker.locator('.browser-entries')
  await expect(list.getByRole('button')).toHaveCount(100)
  expect((await list.boundingBox())!.height).toBeLessThanOrEqual(225)
  await picker.getByPlaceholder('搜索当前目录中的文件或文件夹').fill('folder-099')
  await expect(list.getByRole('button')).toHaveCount(1)
  await picker.screenshot({path:'test-results/folder-picker.png'})
})

test('下载器在系统设置独立绑定，镜像登记不再包含该字段', async ({ page }) => {
  await fixture(page)
  let saved: any
  await page.route('**/api/system/downloader', async route => {
    if (route.request().method() === 'PUT') saved = route.request().postDataJSON()
    await route.fulfill({ json: { data: saved ? { ...saved, revision: 3 } : { image: 'registry.test/downloader:old', revision: 2 } } })
  })
  await page.goto('/#/settings')
  const input = page.getByLabel('文件下载器镜像', { exact: true })
  await expect(input).toHaveValue('registry.test/downloader:old')
  await input.fill('192.168.68.1:5000/flink-web/downloader:0.0.5')
  await page.getByRole('button', { name: '保存下载器绑定' }).click()
  await expect(page.locator('.downloader-settings')).toContainText('已更新')
  expect(saved).toEqual({ image: '192.168.68.1:5000/flink-web/downloader:0.0.5', revision: 2 })
  await page.screenshot({ path: 'test-results/downloader-settings.png' })
  await page.goto('/#/images')
  await page.getByRole('button', { name: '新增' }).click()
  await expect(page.locator('[data-field="downloaderImage"]')).toHaveCount(0)
})

test('进度条仅显示进行中的任务，多个任务保持单行并可横向滚动', async ({ page }) => {
  await fixture(page)
  const jobs = Array.from({ length: 30 }, (_, i) => ({ ...draft, id: 'pending-' + i, name: '等待停止的作业-' + i, deploymentName: 'flink-' + i }))
  await page.route('**/api/jobs', route => route.fulfill({ json: { data: jobs } }))
  await page.route('**/api/jobs/refresh', route => route.fulfill({ json: { data: {
    items: [], queriedAt: new Date().toISOString(),
    stopProgress: Object.fromEntries(jobs.map((job, i) => [job.id, { phase: i === 0 ? 'DONE' : i === 1 ? 'ERROR' : 'DELETING', message: i < 2 ? '结束状态' : '正在删除资源' }]))
  } } }))
  await page.goto('/#/jobs')
  const progress = page.getByRole('status', { name: '进行中的作业' })
  await expect(progress.locator('span')).toHaveCount(28)
  await expect(progress).not.toContainText('结束状态')
  expect(await progress.evaluate(el => el.scrollWidth > el.clientWidth)).toBe(true)
  await progress.evaluate(el => { el.scrollLeft = 300 })
  await expect.poll(() => progress.evaluate(el => el.scrollLeft)).toBeGreaterThan(0)
  await page.screenshot({ path: 'test-results/ongoing-jobs.png' })
})

test('复制生成独立草稿并进入编辑，不启动源作业或新作业', async ({ page }) => {
  const f = await fixture(page)
  let payload: any
  await page.route('**/api/jobs/job-one/copy', async route => {
    payload = route.request().postDataJSON()
    await route.fulfill({ status: 201, json: { data: { ...draft, id: 'copy-job', name: payload.name, revision: 1 } } })
  })
  await page.route('**/api/jobs/copy-job', route => route.fulfill({ json: { data: { ...draft, id: 'copy-job', name: 'orders-copy' } } }))
  await page.goto('/#/jobs')
  await page.getByRole('button', { name: '复制', exact: true }).click()
  await expect(page.getByRole('dialog')).toContainText('运行状态与历史快照不会复制')
  const name = page.getByLabel('新作业名称')
  await name.fill('orders'); await name.blur()
  await expect(name).toHaveAttribute('aria-invalid', 'true')
  await name.fill('orders-copy'); await name.blur()
  await expect(name).not.toHaveAttribute('aria-invalid', 'true')
  await page.screenshot({path:'test-results/copy-dialog.png'})
  await page.setViewportSize({width:390,height:844})
  const box = await page.getByRole('dialog').boundingBox()
  expect(box!.x).toBeGreaterThanOrEqual(0)
  expect(box!.x + box!.width).toBeLessThanOrEqual(390)
  await expect(page.getByRole('button', {name:'确认复制'})).toBeInViewport()
  await page.screenshot({path:'test-results/copy-dialog-mobile.png'})
  await page.getByRole('button', { name: '确认复制' }).click()
  await expect(page).toHaveURL(/copy-job\/edit/)
  expect(payload).toEqual({ name: 'orders-copy', revision: 0, allowFormRewrite: false })
  expect(f.actions).toEqual([])
})

test('旧资源需确认转换，取消保留 YAML，确认后允许表单', async ({page}) => {
  const legacy = source.replace('    flink-web/editor: jar-v1', '    flink-web/editor: yaml')
  await fixture(page, legacy)
  await page.goto('/#/jobs/job-one/edit')
  const form = page.getByRole('button', {name:'JAR 表单', exact:true})
  await expect(form).toBeEnabled()
  await form.click()
  await expect(page.getByRole('dialog')).toContainText('YAML')
  await page.getByRole('dialog').getByRole('button', {name:'取消',exact:true}).click()
  await expect(page.getByRole('textbox', {name:'YAML 编辑器',exact:true})).toContainText('flink-web/editor: yaml')
  await form.click()
  await page.getByRole('dialog').getByRole('button', {name:'确定',exact:true}).click()
  await expect(page.getByLabel('入口类')).toBeVisible()
})

test('YAML 快照高亮与复制，复制内容和页面快照一致', async ({page}) => {
  await fixture(page)
  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'clipboard', {value: undefined, configurable: true})
    document.execCommand = (command: string) => {
      if (command !== 'copy') return false
      ;(window as any).copiedYaml = (document.activeElement as HTMLTextAreaElement).value
      return true
    }
  })
  await page.goto('/#/jobs')
  await page.getByRole('button', {name:'当前 YAML', exact:true}).click()
  const dialog = page.getByRole('dialog', {name:/当前 FlinkDeployment YAML/})
  await expect(dialog.locator('.cm-content')).toContainText('apiVersion')
  await expect(dialog.locator('.cm-content span').first()).toBeVisible()
  await dialog.getByRole('button', {name:'复制 YAML'}).click()
  await expect(dialog.getByRole('status')).toHaveText('已复制')
  expect(await page.evaluate(() => (window as any).copiedYaml)).toBe(source + 'status:\n  error: original error\n')
  await page.screenshot({path:'test-results/yaml-viewer.png'})
})

test('旧作业复制可选择 JAR 表单，解析失败保留副本 YAML', async ({page}) => {
  const legacy = source.replace('    flink-web/editor: jar-v1', '    flink-web/editor: yaml').replace('registry.test/flink:1.20.5', 'unknown/legacy:old')
  await fixture(page, legacy)
  let payload: any
  const copied = {...draft, id: 'copy-job', name: 'orders-copy', yaml: legacy.replace('flink-web/editor: yaml', 'flink-web/editor: jar-v1')}
  await page.route('**/api/jobs/job-one/copy', async route => {
    payload = route.request().postDataJSON()
    await route.fulfill({status:201,json:{data:copied}})
  })
  await page.route('**/api/jobs/copy-job', route => route.fulfill({json:{data:copied}}))
  await page.goto('/#/jobs')
  await page.getByRole('button', {name:'复制',exact:true}).click()
  await page.getByRole('radio', {name:'JAR 表单'}).check()
  await expect(page.getByRole('dialog')).toContainText('表单可能重新生成并改变原 YAML')
  await page.getByRole('button', {name:'确认复制'}).click()
  await expect(page).toHaveURL(/copy-job\/edit\?editor=jar/)
  expect(payload.allowFormRewrite).toBe(true)
  await expect(page.getByRole('alert')).toContainText('原 YAML 已保留')
  await expect(page.getByRole('textbox', {name:'YAML 编辑器',exact:true})).toContainText('unknown/legacy:old')
})

test('历史 Metaspace 回填且调整后进入 Flink 配置', async ({page}) => {
  const legacy = source.replace('  job:', '  flinkConfiguration:\n    jobmanager.memory.jvm-metaspace.size: 32m\n  job:')
  const calls = await fixture(page, legacy)
  await page.goto('/#/jobs/job-one/edit')
  await page.getByRole('button', {name:'JAR 表单',exact:true}).click()
  const metaspace = page.getByRole('spinbutton', {name:'JobManager Metaspace',exact:true})
  await expect(metaspace).toHaveValue('32')
  await metaspace.fill('512')
  await page.getByRole('spinbutton', {name:'JobManager 内存',exact:true}).fill('2048')
  await page.getByRole('button', {name:'本地验证与预览'}).click()
  await expect.poll(() => calls.renders.at(-1)?.flinkConfiguration?.['jobmanager.memory.jvm-metaspace.size']).toBe('512m')
  expect(calls.renders.at(-1)?.jmMemory).toBe('2048m')
  await metaspace.scrollIntoViewIfNeeded()
  await page.screenshot({path:'test-results/metaspace-form.png'})
})
