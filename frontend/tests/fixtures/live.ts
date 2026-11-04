import { expect, type Page } from '@playwright/test'

/** Only use with a disposable backend: these helpers perform real writes. */
export async function login(page: Page) {
  const username = process.env.E2E_USERNAME
  const password = process.env.E2E_PASSWORD
  if (!username || !password) {
    throw new Error('Live tests require E2E_USERNAME and E2E_PASSWORD for an isolated backend')
  }
  await page.goto('/#/login')
  await page.getByLabel('用户名', { exact: true }).fill(username)
  await page.getByLabel('密码', { exact: true }).fill(password)
  const refreshed = page.waitForResponse((response) => response.url().endsWith('/api/jobs/refresh'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await refreshed
  await expect(page.getByRole('heading', { name: '作业管理', exact: true })).toBeVisible()
}

export async function write(page: Page, path: string, data: object) {
  const csrf = (await (await page.request.get('/api/auth/csrf')).json()).data
  const response = await page.request.put(path, {
    data,
    headers: { [csrf.headerName]: csrf.token },
  })
  expect(response.ok(), await response.text()).toBeTruthy()
  return (await response.json()).data
}

export async function connection(page: Page, id: string) {
  await write(page, '/api/catalog/configs/' + id, {
    name: '测试密码引用',
    kind: 'secretRef',
    secretName: 'mysql-runtime',
    secretKey: 'password',
    revision: 0,
  })
  return write(page, '/api/catalog/datasources/' + id, {
    name: id,
    connectorType: 'mysql',
    tableName: 'orders',
    host: 'mysql.test',
    port: '3306',
    database: 'warehouse',
    table: 'orders',
    username: 'flink',
    passwordRef: id,
    columns: [{ name: 'id', type: 'BIGINT' }],
    revision: 0,
  })
}
