import { readonly, ref } from 'vue'

export const themes = [
  { value: 'mist', label: '雾灰 / 青绿', dark: false },
  { value: 'pearl', label: '暖白 / 墨蓝', dark: false },
  { value: 'dark', label: '深色开发工作台', dark: true },
] as const
export type Theme = (typeof themes)[number]['value']
const storageKey = 'flink-ui-theme'
const valid = (value: unknown): value is Theme => themes.some((theme) => theme.value === value)
const current = ref<Theme>(
  valid(document.documentElement.dataset.theme) ? document.documentElement.dataset.theme : 'mist',
)

function apply(value: Theme) {
  current.value = value
  document.documentElement.dataset.theme = value
  document.documentElement.style.colorScheme = value === 'dark' ? 'dark' : 'light'
  document
    .querySelector('meta[name="theme-color"]')
    ?.setAttribute(
      'content',
      value === 'dark' ? '#1c1d22' : value === 'pearl' ? '#f6f5f1' : '#eff2f0',
    )
}
apply(current.value)
export const currentTheme = readonly(current)
export function setTheme(value: Theme) {
  if (!valid(value)) return
  apply(value)
  try {
    localStorage.setItem(storageKey, value)
  } catch {
    /* Theme remains usable when storage is blocked. */
  }
}
window.addEventListener('storage', (event) => {
  if (event.key === storageKey) apply(valid(event.newValue) ? event.newValue : 'mist')
})
