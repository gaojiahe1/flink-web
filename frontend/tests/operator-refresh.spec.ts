import { test, expect, type Page } from '@playwright/test'
async function login(page: Page) {
  await page.goto('/')
  await page.getByLabel('用户名').fill(process.env.E2E_USERNAME!)
  await page.getByLabel('密码', { exact: false }).first().fill(process.env.E2E_PASSWORD!)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('heading', { name: '作业管理', exact: true })).toBeVisible()
}
test('3 second cooldown, YAML snapshot is local and updates only on refresh', async ({ page }) => {
  let requests = 0
  await page.route('**/api/jobs/refresh', route => {
    requests++
    return route.fulfill({ json: { data: { queriedAt: new Date().toISOString(), items: [
      { uid:'yaml-uid', name:'snapshot-test', namespace:'flink-jobs', managed:false, canAdopt:false, application:true, jobState:'RUNNING', yaml:'kind: FlinkDeployment\nmetadata:\n  resourceVersion: '+requests }
    ] } } })
  })
  await login(page)
  await expect.poll(() => requests).toBe(1)
  const button = page.getByRole('row').filter({hasText:'snapshot-test'}).getByRole('button', { name:'当前 YAML',exact:true })
  await expect(button).toBeEnabled()
  let apiCalls=0
  page.on('request', req=>{ if(req.url().includes('/api/')) apiCalls++ })
  await button.click()
  await expect(page.locator('.yaml-snapshot-content')).toContainText('resourceVersion: 1')
  expect(apiCalls).toBe(0)
  await page.getByRole('button',{name:'关闭',exact:true}).click()
  await expect(page.getByRole('button',{name:'↻ 刷新状态',exact:true})).toBeEnabled({timeout:4200})
  expect(requests).toBe(1)
  await page.getByRole('button',{name:'↻ 刷新状态',exact:true}).click()
  await expect.poll(()=>requests).toBe(2)
  await button.click()
  await expect(page.locator('.yaml-snapshot-content')).toContainText('resourceVersion: 2')
  await page.screenshot({path:'test-results/current-deployment-yaml.png',fullPage:true})
})
test('single JAR entry and matching filter, S3 and SQL control heights',async({page})=>{
  await login(page)
  await expect(page.getByRole('link',{name:'＋ 新建 JAR 作业',exact:true})).toHaveCount(1)
  const filters=await page.locator('.filter-bar input,.filter-bar select').evaluateAll(nodes=>nodes.map(n=>n.getBoundingClientRect().height))
  expect(new Set(filters).size).toBe(1)
  await page.goto('/#/artifacts')
  await expect(page.getByLabel('上传文件目录')).toBeVisible()
  const select=await page.getByLabel('上传文件目录').boundingBox()
  const input=await page.getByPlaceholder('留空使用配置的根目录').boundingBox()
  expect(select?.height).toBe(input?.height)
  await page.goto('/#/sql')
  await expect(page.getByLabel('当前 Catalog',{exact:true})).toBeVisible()
  const sqlHeights=await page.locator('.catalog-context input,.catalog-context select').evaluateAll(nodes=>nodes.map(n=>n.getBoundingClientRect().height))
  expect(sqlHeights.length).toBe(2)
  expect(new Set(sqlHeights).size).toBe(1)
  await page.screenshot({path:'test-results/sql-consistent-controls.png',fullPage:true})
})
test('operator status and version filtered image selection',async({page})=>{
  await login(page)
  await page.goto('/#/settings')
  await expect(page.getByText('operator-e2e',{exact:true})).toBeVisible()
  await expect(page.getByText('Ready',{exact:true})).toBeVisible()
  await expect(page.getByText(/Operator 1.12.0 · 支持 Flink/)).toBeVisible()
  await page.screenshot({path:'test-results/operator-status.png',fullPage:true})
  const csrf=(await (await page.request.get('/api/auth/csrf')).json()).data
  for(const [id,version] of [['supported','1.20.3'],['unsupported','2.3.0']]) {
    const response=await page.request.put('/api/catalog/images/'+id,{headers:{[csrf.headerName]:csrf.token},data:{name:id,repository:'registry/flink',version,flinkVersion:version,revision:0}})
    expect(response.ok()).toBeTruthy()
  }
  await page.goto('/#/jobs/jar/new')
  await expect(page.getByText(/Operator 1.12.0 · 支持 Flink/)).toBeVisible()
  await expect(page.locator('option[value="supported"]')).toHaveCount(1)
  await expect(page.locator('option[value="unsupported"]')).toHaveCount(0)
  await page.getByLabel('镜像版本',{exact:true}).selectOption('supported')
  await expect(page.locator('.image-selection')).toHaveText('Flink 1.20.3')
})
