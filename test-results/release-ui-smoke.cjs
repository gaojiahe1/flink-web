const assert = require('node:assert/strict');
const { chromium } = require('../frontend/node_modules/playwright');

(async () => {
  const browser = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe' });
  const context = await browser.newContext();
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  try {
    const base = process.env.RELEASE_URL;
    await page.goto(base + '/#/login');
    await page.getByLabel('用户名', { exact: true }).fill('admin');
    await page.getByLabel('密码', { exact: true }).fill(process.env.RELEASE_PASSWORD);
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await page.getByRole('heading', { name: '作业管理', exact: true }).waitFor();
    for (const path of ['/api/sessions', '/api/development/jar/jobs', '/api/development/links']) {
      const response = await context.request.get(base + path);
      assert.equal(response.status(), 200, path);
      assert.ok((await response.json()).data != null, path);
    }
    for (const path of ['jar', 'sessions', 'sql']) {
      await page.goto(base + '/#/' + path);
      await page.waitForLoadState('networkidle');
      assert.equal(page.url().split('#')[1], '/' + path);
      assert.ok((await page.locator('main').innerText()).length > 30, path);
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true, path);
    }
    await page.goto(base + '/#/sessions');
    await page.getByRole('button', { name: '＋ 新建 Session', exact: true }).click();
    await page.getByRole('heading', { name: '新建 Session', exact: true }).waitFor();
    assert.equal(await page.locator('.session-form-grid > .editor-form-column').count(), 2);
    await page.getByLabel('集群名称', { exact: true }).fill('release-smoke');
    await page.locator('.session-pod summary').click();
    await page.getByRole('textbox', { name: 'PodTemplate（JSON）', exact: true }).waitFor();
    await page.goto(base + '/#/jar/new');
    await page.waitForLoadState('networkidle');
    assert.equal(await page.getByRole('spinbutton', { name: 'JobManager Metaspace', exact: true }).count(), 0);
    assert.equal(await page.getByRole('spinbutton', { name: 'TaskManager Metaspace', exact: true }).count(), 0);
    assert.equal(await page.getByRole('button', { name: '执行 SQL', exact: true }).count(), 0);
    assert.deepEqual(errors, []);
    console.log('PASS: built images support encrypted login, new Session form, removed Metaspace fields and JAR/SQL pages');
    await context.request.post(base + '/api/auth/logout');
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
