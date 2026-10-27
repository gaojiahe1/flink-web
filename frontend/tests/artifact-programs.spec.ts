import { test, expect, type Page } from '@playwright/test'
import { confirmJarName } from './draft-name'

async function fixture(page: Page, entries = ['example.Main']) {
  const prefixes: string[] = [],
    renders: any[] = []
  const jobs = ['deployed-job', 'draft-job'].map((name, i) => ({
    id: i ? 'two' : 'one',
    name,
    deploymentName: `flink-000000${i + 1}`,
    description: '',
    revision: 1,
    flinkVersion: '1.20.5',
    yaml: `apiVersion: flink.apache.org/v1beta1\nkind: FlinkDeployment\nmetadata:\n  name: flink-000000${i + 1}\nspec:\n  image: registry.test/flink:1.20.5\n  flinkVersion: v1_20\n  job:\n    jarURI: local:///opt/flink/lib/job.jar\n`,
  }))
  await page.route('**/api/**', async (route) => {
    const req = route.request(),
      url = new URL(req.url()),
      path = url.pathname
    if (!path.startsWith('/api/')) return route.continue()
    let data: any = []
    if (path === '/api/auth/me') data = { username: 'admin', roles: ['ROLE_ADMIN'] }
    else if (path === '/api/auth/csrf') data = { headerName: 'X-CSRF-TOKEN', token: 'test' }
    else if (path === '/api/system') data = { namespace: 'test', capabilities: [] }
    else if (path === '/api/system/operator')
      data = { found: true, compatibility: { known: true, version: '1.14', supported: ['1.20'] } }
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
      data = { bucket: 'files', rootPrefix: 'artifacts/', stateRoot: 's3://state' }
    else if (path === '/api/artifacts/objects') {
      const prefix = url.searchParams.get('prefix') || ''
      prefixes.push(prefix)
      data = {
        bucket: 'files',
        rootPrefix: '',
        prefix,
        objects: prefix.includes('sql-runner') ? [{ key: prefix + 'runner.jar', size: 100 }] : [],
        directories: [],
      }
    } else if (path === '/api/artifacts/entry-classes') data = { entryClasses: entries }
    else if (path === '/api/jobs/name-availability') data = { available: true }
    else if (path === '/api/jobs/render-form') {
      renders.push(req.postDataJSON())
      return route.fulfill({
        status: 400,
        json: {
          error: {
            code: 'INVALID_YAML',
            message: 'upgradeMode: savepoint 模式需要配置 Savepoint 存储目录',
          },
        },
      })
    } else if (path === '/api/development/jar/jobs' || path === '/api/jobs') data = jobs
    else if (path === '/api/jobs/one') data = jobs[0]
    else if (path.endsWith('/access')) data = { canEdit: true, canOperate: true }
    else if (path === '/api/jobs/refresh')
      data = {
        items: [
          {
            name: jobs[0].deploymentName,
            jobId: 'one',
            managed: true,
            jobState: 'RUNNING',
            healthState: 'HEALTHY',
          },
        ],
        queriedAt: new Date().toISOString(),
      }
    await route.fulfill({ json: { data } })
  })
  return { prefixes, renders }
}

test('HTTP 地址确认后显示文件名，并自动填入单个主 JAR 入口', async ({ page }) => {
  await fixture(page)
  await page.goto('/#/jar/new')
  await confirmJarName(page, 'http-main')
  const picker = page.locator('.artifact-picker').first()
  await picker.getByRole('button', { name: 'HTTP / S3 地址', exact: true }).click()
  await picker
    .getByLabel('主 JAR 文件地址', { exact: true })
    .fill('https://files.test/apps/main.jar')
  await expect(picker.locator('.file-chip')).toHaveCount(0)
  await picker.getByRole('button', { name: '确认文件地址', exact: true }).click()
  await expect(picker.locator('.file-chip')).toContainText('main.jar')
  await expect(page.locator('input[data-field="entryClass"]')).toHaveValue('example.Main')
  await page.screenshot({ path: test.info().outputPath('jar-http-entry.png'), fullPage: true })
})

test('多个入口使用下拉选择，附加 HTTP 配置文件保留文件名', async ({ page }) => {
  await fixture(page, ['example.First', 'example.Second'])
  await page.goto('/#/jar/new')
  await confirmJarName(page, 'multi-main')
  const pickers = page.locator('.artifact-picker'),
    main = pickers.first(),
    dependencies = pickers.nth(1)
  await main.getByRole('button', { name: 'HTTP / S3 地址', exact: true }).click()
  await main.getByLabel('主 JAR 文件地址', { exact: true }).fill('s3://other-bucket/main.jar')
  await main.getByRole('button', { name: '确认文件地址', exact: true }).click()
  const entries = page.locator('select[data-field="entryClass"]')
  await expect(entries).toHaveValue('')
  await entries.selectOption('example.Second')
  await dependencies.getByRole('button', { name: 'HTTP / S3 地址', exact: true }).click()
  await dependencies
    .getByLabel('附加依赖与配置文件地址', { exact: true })
    .fill('http://files.test/config/runtime.yaml')
  await dependencies.getByRole('button', { name: '确认文件地址', exact: true }).click()
  await expect(dependencies.locator('.file-chip')).toContainText('runtime.yaml')
})

test('升级策略错误只标记升级策略，空文件区保持紧凑', async ({ page }) => {
  await fixture(page)
  await page.goto('/#/jar/new')
  await confirmJarName(page, 'new-job')
  const empty = page.locator('.browser-empty').first()
  await expect(empty).toBeVisible()
  expect((await empty.boundingBox())!.height).toBeLessThan(100)
  await page.getByLabel('镜像版本', { exact: true }).selectOption('flink')
  await page.getByRole('button', { name: '本地验证与预览', exact: true }).click()
  await expect(page.locator('select[data-field="upgradeMode"]')).toHaveAttribute(
    'aria-invalid',
    'true',
  )
  await expect(page.locator('.flink-config-editor')).not.toHaveAttribute('aria-invalid', 'true')
})

test('Session Runner 手动从列表选择，并可确认具体 HTTP 地址', async ({ page }) => {
  const f = await fixture(page)
  await page.goto('/#/sessions')
  await page.getByRole('button', { name: '＋ 新建 Session', exact: true }).click()
  const runner = page.locator('[data-field="runnerArtifactId"]')
  await expect(runner.locator('.file-chip')).toHaveCount(0)
  await expect.poll(() => f.prefixes).toContain('artifacts/shared/sql-runner/')
  await runner.getByText('runner.jar', { exact: true }).click()
  await expect(runner.locator('.file-chip')).toContainText('runner.jar')
  await runner.getByRole('button', { name: 'HTTP / S3 地址', exact: true }).click()
  await runner
    .getByLabel('SQL Runner 文件地址', { exact: true })
    .fill('http://files.test/sql/explicit-runner.jar')
  await runner.getByRole('button', { name: '确认文件地址', exact: true }).click()
  await expect(runner.locator('.file-chip')).toContainText('explicit-runner.jar')
  await page.screenshot({
    path: test.info().outputPath('session-runner-picker.png'),
    fullPage: true,
  })
})

test('全部文件从桶根目录读取，JAR 开发列表显示部署标记', async ({ page }) => {
  const f = await fixture(page)
  await page.goto('/#/artifacts')
  await expect.poll(() => f.prefixes.length).toBeGreaterThan(0)
  expect(f.prefixes[0]).toBe('')
  await page.goto('/#/jar')
  await expect(page.locator('.jar-draft-row').filter({ hasText: 'deployed-job' })).toContainText(
    'RUNNING',
  )
  await expect(page.locator('.jar-draft-row').filter({ hasText: 'draft-job' })).toContainText(
    '未部署',
  )
})

test('部署状态查询权限不足保留草稿并显示未知', async ({ page }) => {
  await fixture(page)
  await page.route('**/api/jobs/refresh', (route) =>
    route.fulfill({
      status: 502,
      json: { error: { code: 'KUBERNETES_FORBIDDEN', message: '查询权限不足' } },
    }),
  )
  await page.goto('/#/jar')
  await expect(page.locator('.jar-draft-row').filter({ hasText: 'deployed-job' })).toContainText(
    '状态未知',
  )
  await expect(page.getByRole('alert')).toContainText('查询权限不足')
})
