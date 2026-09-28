import { test, expect } from '@playwright/test'

test('login layout and encrypted credentials work without SubtleCrypto', async ({ page }) => {
  await page.addInitScript(() => Object.defineProperty(crypto, 'subtle', { value: undefined }))
  await page.goto('/login')
  const username = page.getByLabel('用户名', { exact: true })
  const password = page.getByLabel('密码', { exact: true })
  const toggle = page.getByRole('button', { name: '显示密码', exact: true })
  const submit = page.getByRole('button', { name: '登录', exact: true })
  for (const width of [1920, 390]) {
    await page.setViewportSize({ width, height: 900 })
    const inputBox = (await password.boundingBox())!
    const toggleBox = (await toggle.boundingBox())!
    expect(inputBox.height).toBe(40)
    expect((await username.boundingBox())!.height).toBe(40)
    expect((await submit.boundingBox())!.height).toBe(40)
    expect(Math.abs(toggleBox.y + toggleBox.height / 2 - inputBox.y - inputBox.height / 2)).toBeLessThan(1)
    expect(toggleBox.x + toggleBox.width).toBeLessThanOrEqual(inputBox.x + inputBox.width)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  }
  await password.fill('layout-preview')
  await toggle.click()
  await expect(password).toHaveAttribute('type', 'text')
  await expect(page).toHaveURL(/login/)
  await page.getByRole('button', { name: '隐藏密码' }).click()
  await password.fill('layout-preview')
  await username.focus()
  await page.screenshot({ path: 'test-results/login-mobile.png', fullPage: true })
  await page.setViewportSize({ width: 1920, height: 900 })
  await page.screenshot({ path: 'test-results/login-desktop.png', fullPage: true })
  await username.fill(process.env.E2E_USERNAME!)
  await password.fill(process.env.E2E_PASSWORD!)
  const submitted = page.waitForRequest(r => r.url().endsWith('/api/auth/login') && r.method() === 'POST')
  await submit.click()
  const sent = new URLSearchParams((await submitted).postData()!)
  expect(sent.get('password')).toMatch(/^fwenc1:/)
  expect(sent.get('password')).not.toContain(process.env.E2E_PASSWORD!)
  await expect(page.getByRole('heading', { name: '作业管理', exact: true })).toBeVisible()
})

test('key retrieval failure blocks login and permits explicit retry', async ({ page }) => {
  await page.goto('/login')
  await page.getByLabel('用户名', { exact: true }).fill(process.env.E2E_USERNAME!)
  await page.getByLabel('密码', { exact: true }).fill(process.env.E2E_PASSWORD!)
  let submits = 0
  page.on('request', request => { if (request.url().endsWith('/api/auth/login')) submits++ })
  await page.route('**/api/auth/transport-key?*', route => route.fulfill({
    status: 503, contentType: 'application/json', body: JSON.stringify({ error: { message: '加密参数暂不可用' } }),
  }))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('加密参数暂不可用')
  expect(submits).toBe(0)
  await page.unroute('**/api/auth/transport-key?*')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('heading', { name: '作业管理', exact: true })).toBeVisible()
  expect(submits).toBe(1)
})

test('user password is encrypted before save and the new account can sign in', async ({ page, browser }) => {
  await page.goto('/login')
  await page.getByLabel('用户名', { exact: true }).fill(process.env.E2E_USERNAME!)
  await page.getByLabel('密码', { exact: true }).fill(process.env.E2E_PASSWORD!)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.getByRole('link', { name: '用户与权限', exact: true }).click()
  await page.getByRole('button', { name: '＋ 新增', exact: true }).click()
  const account = 'encrypted-' + Date.now()
  const password = 'Transport-only-' + Date.now()
  await page.getByLabel('用户名', { exact: false }).first().fill(account)
  await page.getByLabel('密码', { exact: false }).first().fill(password)
  const submitted = page.waitForRequest(r => r.url().endsWith('/api/users/' + account) && r.method() === 'PUT')
  await page.getByRole('button', { name: '保存', exact: true }).click()
  const body = (await submitted).postDataJSON()
  expect(body.password).toMatch(/^fwenc1:/)
  expect(JSON.stringify(body)).not.toContain(password)
  await expect(page.getByText('已保存', { exact: true })).toBeVisible()
  const isolated = await browser.newContext()
  try {
    const other = await isolated.newPage()
    await other.goto((process.env.E2E_BASE_URL || 'http://127.0.0.1:5173') + '/login')
    await other.getByLabel('用户名', { exact: true }).fill(account)
    await other.getByLabel('密码', { exact: true }).fill(password)
    await other.getByRole('button', { name: '登录', exact: true }).click()
    await expect(other.getByRole('heading', { name: '作业管理', exact: true })).toBeVisible()
  } finally {
    await isolated.close()
  }
})
