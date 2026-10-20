import { expect, type Page } from '@playwright/test'
export async function confirmJarName(page: Page, name: string) {
  const dialog = page.getByRole('dialog').filter({ hasText: '新建 JAR 作业' })
  await dialog.getByRole('textbox', { name: '新草稿作业名称' }).fill(name)
  await expect(dialog).toContainText('名称可用')
  await dialog.getByRole('checkbox').check()
  await dialog.getByRole('button', { name: '确认并创建' }).click()
  await expect(dialog).toHaveCount(0)
}
