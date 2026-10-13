import { test, expect, type Page } from '@playwright/test'

async function login(page: Page) {
  await page.goto('/')
  await page.getByLabel('用户名').fill(process.env.E2E_USERNAME!)
  await page.getByLabel('密码', {exact:false}).first().fill(process.env.E2E_PASSWORD!)
  await page.getByRole('button', {name:'登录',exact:true}).click()
  await expect(page.getByRole('heading', {name:'作业管理',exact:true})).toBeVisible()
}
async function catalog(page:Page,type:string,id:string,value:object) {
  const csrf = (await (await page.request.get('/api/auth/csrf')).json()).data
  const response = await page.request.put('/api/catalog/'+type+'/'+id,{data:{...value,revision:0},headers:{[csrf.headerName]:csrf.token}})
  expect(response.ok()).toBeTruthy()
}

test('JAR form preserves container JAR and arguments without artifact registration', async ({ page }) => {
  await login(page)
  const suffix=Date.now(), image='jar-image-'+suffix
  await catalog(page,'images',image,{name:'Browser Flink',repository:'registry.test/flink',version:'1.20',flinkVersion:'1.20'})
  await page.goto('/#/jar')
  await page.getByRole('link',{name:'＋ 新建 JAR 作业',exact:true}).click()
  await expect(page.getByRole('heading',{name:'新建 JAR 作业'})).toBeVisible()
  await page.getByLabel('作业名称',{exact:false}).first().fill('jar-e2e-'+suffix)
  await page.getByLabel('镜像版本',{exact:false}).selectOption(image)
  await page.getByLabel('入口类',{exact:false}).fill('com.example.Main')
  await page.getByLabel('程序参数',{exact:false}).fill('--message\nhello world')
  await page.getByRole('button',{name:'本地验证与预览',exact:true}).click()
  await expect(page.getByText(/本地验证通过；提交时仍需/)).toBeVisible()
  const yaml=await page.getByRole('textbox',{name:'YAML 编辑器',exact:true}).innerText()
  expect(yaml).toContain('jarURI: local:///opt/flink/usrlib/job.jar')
  expect(yaml).toContain('com.example.Main')
  expect(yaml).toContain('hello world')
  await page.screenshot({path:'test-results/jar-development.png',fullPage:true})
  await page.getByRole('button',{name:'保存草稿',exact:true}).click()
  await expect(page.getByRole('button',{name:'jar-e2e-'+suffix,exact:true})).toBeVisible()
})

test('SQL studio saves, checks and publishes without background cluster requests', async ({ page }) => {
  await login(page)
  const suffix=Date.now(), image='sql-image-'+suffix, name='sql-e2e-'+suffix
  await catalog(page,'images',image,{name:'SQL Runtime',repository:'registry.test/flink',version:'1.20',flinkVersion:'1.20'})
  let refreshes=0
  page.on('request',req=>{ if(req.url().endsWith('/api/jobs/refresh')) refreshes++ })
  await page.getByRole('link',{name:'SQL 开发',exact:true}).click()
  await expect(page.getByRole('heading',{name:'SQL 开发',exact:true})).toBeVisible()
  await page.getByRole('button',{name:'部署配置',exact:true}).click()
  await page.getByLabel('作业名称',{exact:false}).fill(name)
  await page.getByRole('combobox',{name:'镜像版本',exact:true}).selectOption(image)
  await page.getByLabel('SQL 编辑器',{exact:true}).fill("CREATE TEMPORARY TABLE output_table (n INT) WITH ('connector' = 'blackhole');\nINSERT INTO output_table VALUES (1);")
  await page.getByRole('button',{name:'SQL 检查',exact:true}).click()
  await expect(page.locator('.sql-diagnostics')).toContainText('仅通过基础检查')
  await page.getByRole('button',{name:'保存 SQL',exact:true}).click()
  await expect(page.getByRole('status')).toContainText('SQL 草稿已保存')
  await page.getByRole('button',{name:'预览 YAML',exact:true}).click()
  await expect(page.getByText('FlinkDeployment YAML 预览',{exact:true})).toBeVisible()
  await page.getByRole('button',{name:'生成部署草稿',exact:true}).click()
  await expect(page.getByRole('status')).toContainText('已生成部署草稿')
  await expect(page.getByRole('link',{name:'查看关联部署 ↗'})).toBeVisible()
  expect(refreshes).toBe(0)
  await page.screenshot({path:'test-results/sql-studio.png',fullPage:true})
  const records=(await (await page.request.get('/api/jobs')).json()).data
  const job=records.find((item:{name:string})=>item.name===name)
  expect(job.yaml).toContain('com.flinkweb.sql.SqlRunner')
  expect(job.yaml).toContain('flink-web/job-type: sql')
  await page.getByRole('button',{name:new RegExp(name)}).click()
  await expect(page.getByLabel('SQL 编辑器',{exact:true})).toContainText(/INSERT INTO output_table/)
})
