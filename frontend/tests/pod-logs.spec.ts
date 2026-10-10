import { test, expect, type Page } from '@playwright/test'

async function fixture(page: Page, readOnly = false) {
  const job = { id: 'diagnostic-job', jobNumber: 1, deploymentName: 'flink-0000001', name: '日志诊断作业', description: '', flinkVersion: '1.20.5', revision: 1, yaml: '' }
  const diagnostic = {
    queriedAt: '2026-10-09T08:00:00Z', error: '',
    pods: [
      { name: 'flink-0000001-jobmanager-long-name', phase: 'Running', node: 'worker-01', containers: ['init-download', 'main'], conditions: [{ type: 'Ready', status: 'False', reason: 'ContainersNotReady' }], states: [{ name: 'init-download', init: true, state: 'terminated', reason: 'Completed', exitCode: 0, restarts: 0, ready: true }, { name: 'main', state: 'waiting', reason: 'CrashLoopBackOff', message: 'Back-off restarting', restarts: 4, ready: false }] },
      { name: 'flink-0000001-taskmanager', phase: 'Running', node: 'worker-02', containers: ['main'], states: [{ name: 'main', state: 'running', restarts: 0, ready: true }] },
    ], events: [{ type: 'Warning', reason: 'BackOff', message: 'Back-off restarting', time: '2026-10-09T08:00:00Z' }],
  }
  const logs: any[] = []
  let snapshotFailure = true
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    let data: any = []
    if (path === '/api/auth/me') data = { username: 'admin', roles: [readOnly ? 'ROLE_VIEWER' : 'ROLE_ADMIN'] }
    else if (path === '/api/auth/csrf') data = { headerName: 'X-CSRF-TOKEN', token: 'test' }
    else if (path === '/api/system') data = { version: 'test', namespace: 'test', storageType: 'local', capabilities: [] }
    else if (path === '/api/jobs') data = [job]
    else if (path === '/api/platform/job-access') data = { [job.id]: { canOperate: !readOnly, canEdit: !readOnly } }
    else if (path === '/api/jobs/refresh') data = { items: [{ name: job.deploymentName, jobId: job.id, managed: true, application: true, jobState: 'RUNNING', desiredState: 'running', snapshots: [] }], queriedAt: new Date().toISOString(), snapshotQuery: snapshotFailure ? { state: 'UNAVAILABLE', message: '快照资源查询失败，作业状态已更新；权限不足 (HTTP 403)' } : { state: 'AVAILABLE', message: '' } }
    else if (path.endsWith('/diagnostics')) data = diagnostic
    else if (path.endsWith('/logs')) {
      logs.push(route.request().postDataJSON())
      data = { text: '2026-10-09T08:00:00Z INFO startup\n2026-10-09T08:00:01Z WARN retry\n2026-10-09T08:00:02Z ERROR failure\n<script>window.logInjected=true</script>', queriedAt: diagnostic.queriedAt }
    }
    await route.fulfill({ json: { data } })
  })
  await page.goto('/#/jobs')
  await page.getByRole('button', { name: 'Pod 状态', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: /Pod 状态/ })
  await expect(drawer.getByText('CrashLoopBackOff', { exact: true })).toBeVisible()
  return { drawer, logs, restoreSnapshots: () => { snapshotFailure = false } }
}

test('Pod 日志请求一万行，高亮、复制和上一实例沿用权限与页面风格', async ({ page }) => {
  const { drawer, logs } = await fixture(page)
  await page.evaluate(() => {
    Object.defineProperty(navigator, 'clipboard', { value: undefined, configurable: true })
    document.execCommand = () => { (window as any).copiedLog = (document.activeElement as HTMLTextAreaElement).value; return true }
  })
  await drawer.getByRole('button', { name: '查看最新日志', exact: true }).nth(1).click()
  await expect(drawer.locator('.log-token-error')).toHaveText('ERROR')
  await expect(drawer.locator('.log-token-warn')).toHaveText('WARN')
  expect(logs[0]).toMatchObject({ pod: 'flink-0000001-jobmanager-long-name', container: 'main', lines: 10000, previous: false })
  expect(await page.evaluate(() => (window as any).logInjected)).toBeUndefined()
  await drawer.getByRole('button', { name: '复制日志' }).click()
  await expect(drawer.getByRole('status')).toHaveText('已复制')
  expect(await page.evaluate(() => (window as any).copiedLog)).toContain('<script>')
  await drawer.getByRole('checkbox', { name: '上一实例' }).check()
  await expect(drawer.locator('.log-viewer')).toHaveCount(0)
  const read = drawer.getByRole('button', { name: '读取最新 10000 行' })
  await expect(read).toBeEnabled({ timeout: 6000 })
  await read.click()
  await expect.poll(() => logs.length).toBe(2)
  expect(logs[1].previous).toBe(true)
  await drawer.screenshot({ path: 'test-results/pod-logs-desktop.png' })
})

test('旧日志请求在切换 Pod 后不能回填，错误与空日志可见', async ({ page }) => {
  const { drawer } = await fixture(page)
  let release!: () => void
  const pending = new Promise<void>(resolve => { release = resolve })
  let started = false
  await page.route('**/api/jobs/diagnostic-job/logs', async route => {
    started = true
    await pending
    await route.fulfill({ json: { data: { text: 'ERROR stale-pod-output' } } })
  })
  await drawer.getByRole('button', { name: '查看最新日志', exact: true }).nth(1).click()
  await expect.poll(() => started).toBe(true)
  await drawer.getByRole('button', { name: /flink-0000001-taskmanager/ }).click()
  release()
  await expect(drawer.getByText('选择容器后读取最新日志')).toBeVisible()
  await expect(drawer.locator('.log-viewer')).toHaveCount(0)
  await page.route('**/api/jobs/diagnostic-job/logs', route => route.fulfill({ status: 502, json: { error: { code: 'LOG_UNAVAILABLE', message: 'Kubernetes 权限不足 (HTTP 403)，检查 pods/log 授权' } } }))
  await expect(drawer.getByRole('button', { name: '读取最新 10000 行' })).toBeEnabled({ timeout: 6000 })
  await drawer.getByRole('button', { name: '读取最新 10000 行' }).click()
  await expect(drawer.getByRole('alert')).toContainText('403')
  await page.route('**/api/jobs/diagnostic-job/logs', route => route.fulfill({ json: { data: { text: '' } } }))
  await expect(drawer.getByRole('button', { name: '读取最新 10000 行' })).toBeEnabled({ timeout: 6000 })
  await drawer.getByRole('button', { name: '读取最新 10000 行' }).click()
  await expect(drawer.getByText('该容器暂无日志输出')).toBeVisible()
  await expect(drawer.getByRole('alert')).toHaveCount(0)
})

test('快照查询失败仍显示运行作业，恢复刷新后清除提示', async ({ page }) => {
  const { drawer, restoreSnapshots } = await fixture(page)
  await drawer.getByRole('button', { name: '关闭 Pod 状态' }).click()
  await expect(page.getByRole('status')).toContainText('快照资源查询失败')
  await expect(page.locator('.jobs-panel')).toContainText('RUNNING')
  restoreSnapshots()
  const refresh = page.getByRole('button', { name: /刷新状态/ })
  await expect(refresh).toBeEnabled({ timeout: 6000 })
  await refresh.click()
  await expect(page.getByText(/快照资源查询失败/)).toHaveCount(0)
})

test('只读用户可查看 Pod 状态且没有日志操作入口', async ({ page }) => {
  const { drawer, logs } = await fixture(page, true)
  await expect(drawer.getByRole('button', { name: '容器日志', exact: true })).toHaveCount(0)
  await expect(drawer.getByRole('button', { name: '查看最新日志', exact: true })).toHaveCount(0)
  expect(logs).toEqual([])
})

test('一万行日志只渲染可见行，桌面与手机抽屉没有横向溢出', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  const { drawer } = await fixture(page)
  await drawer.screenshot({ path: 'test-results/pod-status-desktop.png' })
  const output = Array.from({ length: 10000 }, (_, i) => `2026-10-09T08:00:00Z ${i === 9999 ? 'ERROR' : 'INFO'} line-${i}`).join('\n')
  await page.route('**/api/jobs/diagnostic-job/logs', route => route.fulfill({ json: { data: { text: output } } }))
  await drawer.getByRole('button', { name: '查看最新日志', exact: true }).nth(1).click()
  await expect(drawer.locator('.cm-line').first()).toContainText('line-0')
  expect(await drawer.locator('.cm-line').count()).toBeLessThan(200)
  await drawer.locator('.cm-scroller').evaluate(el => { el.scrollTop = el.scrollHeight })
  await expect(drawer.locator('.cm-line').last()).toContainText('line-9999')
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(drawer.getByRole('button', { name: '复制日志' })).toBeVisible()
  expect(await drawer.evaluate(el => el.scrollWidth <= el.clientWidth)).toBe(true)
  expect(await drawer.locator('.drawer-scroll').evaluate(el => el.scrollWidth <= el.clientWidth)).toBe(true)
  await drawer.screenshot({ path: 'test-results/pod-logs-mobile.png' })
})
