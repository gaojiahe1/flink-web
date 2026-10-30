import { test, expect } from '@playwright/test'
import { platformFixture } from './fixtures/platform'

test('SQL 写入被记账，未声明路径及错误方法返回失败', async ({ page }) => {
  const fixture = await platformFixture(page)
  await page.goto('/#/login')
  const result = await page.evaluate(async () => {
    const send = (url: string, method: string) =>
      fetch(url, {
        method,
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sql: 'SELECT 99' }),
      }).then((response) => response.status)
    return [
      await send('/api/sql/jobs/s', 'PUT'),
      await send('/api/sql/jobs/s', 'POST'),
      await send('/api/sql/jobs/unknown', 'PUT'),
    ]
  })
  expect(result).toEqual([200, 501, 501])
  expect(fixture.sqlWrites).toEqual([
    { method: 'PUT', path: '/api/sql/jobs/s', body: { sql: 'SELECT 99' } },
  ])
  expect(fixture.writes).toHaveLength(1)
  expect(fixture.unexpectedRequests.splice(0)).toEqual([
    'POST /api/sql/jobs/s',
    'PUT /api/sql/jobs/unknown',
  ])
})

test('缺少本地代码字体时 ASCII 保持等宽，中文可正常回退', async ({ page }) => {
  await platformFixture(page)
  await page.goto('/#/login')
  const result = await page.evaluate(async () => {
    await document.fonts.ready
    const style = document.createElement('style')
    const stack = getComputedStyle(document.documentElement).getPropertyValue('--font-family-code')
    style.textContent =
      ':root{--font-family-code:' +
      stack
        .split(',')
        .map((font) =>
          /Consolas|SFMono|Liberation|Menlo|Monaco/i.test(font) ? '"MissingLocalMono"' : font,
        )
        .join(',') +
      '}'
    document.head.append(style)
    const span = document.createElement('span')
    span.style.cssText = 'font: 16px var(--font-family-code);white-space:pre'
    document.body.append(span)
    span.textContent = 'iiiiiiii'
    const narrow = span.getBoundingClientRect().width
    span.textContent = 'WWWWWWWW'
    const wide = span.getBoundingClientRect().width
    span.textContent = '作业配置'
    const chinese = span.getBoundingClientRect().width
    return { narrow, wide, chinese }
  })
  expect(Math.abs(result.narrow - result.wide)).toBeLessThan(0.1)
  expect(result.chinese).toBeGreaterThan(0)
})
