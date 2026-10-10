import { test, expect, type Page } from '@playwright/test'

async function setup(page: Page, options: { failure?: boolean; viewer?: boolean } = {}) {
  const deleted: string[] = []
  let directories = ['outside/data/', 'outside/data-other/']
  await page.route('**/api/**', async (route) => {
    const req = route.request(), url = new URL(req.url())
    if (!url.pathname.startsWith('/api/')) return route.continue()
    let data: any = []
    if (url.pathname === '/api/auth/me') data = { username: 'test', roles: [options.viewer ? 'ROLE_VIEWER' : 'ROLE_ADMIN'] }
    else if (url.pathname === '/api/system') data = { namespace: 'test', capabilities: [] }
    else if (url.pathname === '/api/auth/csrf') data = { headerName: 'X-CSRF-TOKEN', token: 'test' }
    else if (url.pathname === '/api/artifacts/directories' && req.method() === 'DELETE') {
      const prefix = url.searchParams.get('prefix')!
      deleted.push(prefix)
      if (options.failure) return route.fulfill({ status: 502, json: { error: { code: 'S3_ERROR', message: 'S3 权限不足' } } })
      directories = directories.filter((dir) => dir !== prefix)
      data = { deleted: true, prefix, deletedObjects: 1200 }
    } else if (url.pathname === '/api/artifacts/objects') data = {
      bucket: 'files', rootPrefix: '', managedPrefix: 'artifacts/', prefix: 'outside/',
      directories, objects: [{ key: 'outside/keep.jar', size: 10, modifiedAt: new Date().toISOString() }],
    }
    await route.fulfill({ json: { data } })
  })
  await page.goto('/#/artifacts')
  await expect(page.getByRole('button', { name: 'data', exact: true })).toBeVisible()
  return deleted
}

test('目录删除确认包含全部内容，取消保留目录，确认后刷新并保留相邻文件', async ({ page }) => {
  const deleted = await setup(page)
  await page.getByLabel('选择目录 outside/data/', { exact: true }).check()
  await expect(page.locator('.object-actions').getByRole('link', { name: '下载' })).toHaveCount(0)
  await page.getByRole('button', { name: '删除所选目录与文件' }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog).toContainText('全部子目录和文件')
  await dialog.getByRole('button', { name: '取消', exact: true }).click()
  expect(deleted).toEqual([])
  await page.getByRole('button', { name: '删除所选目录与文件' }).click()
  await dialog.getByRole('button', { name: '确定', exact: true }).click()
  await expect(page.getByRole('button', { name: 'data', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'data-other', exact: true })).toBeVisible()
  await expect(page.getByText('keep.jar', { exact: true })).toBeVisible()
  expect(deleted).toEqual(['outside/data/'])
  await page.screenshot({ path: 'test-results/artifact-directory-delete.png', fullPage: true })
})

test('目录删除失败显示错误及部分删除提示，刷新后可重新选择', async ({ page }) => {
  await setup(page, { failure: true })
  await page.getByLabel('选择目录 outside/data/', { exact: true }).check()
  await page.getByRole('button', { name: '删除所选目录与文件' }).click()
  await page.getByRole('dialog').getByRole('button', { name: '确定', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('S3 权限不足')
  await expect(page.getByRole('alert')).toContainText('可能已有部分内容删除')
  await expect(page.getByLabel('选择目录 outside/data/', { exact: true })).not.toBeChecked()
})

test('只读角色没有目录删除入口，全选包含目录与文件', async ({ page }) => {
  await setup(page, { viewer: true })
  await page.getByLabel('选择当前页目录与文件', { exact: true }).check()
  await expect(page.getByLabel('选择目录 outside/data/', { exact: true })).toBeChecked()
  await expect(page.getByLabel('选择 outside/keep.jar', { exact: true })).toBeChecked()
  await expect(page.getByRole('button', { name: /删除所选/ })).toHaveCount(0)
})
