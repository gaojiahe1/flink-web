export async function copyText(value: string, parent: HTMLElement = document.body) {
  if (navigator.clipboard && window.isSecureContext) {
    try {
      await navigator.clipboard.writeText(value)
      return
    } catch {
      /* Fall back for denied permissions. */
    }
  }
  const focused = document.activeElement as HTMLElement | null
  const input = document.createElement('textarea')
  input.value = value
  input.readOnly = true
  input.style.cssText = 'position:fixed;left:0;top:0;width:1px;height:1px;opacity:0'
  parent.append(input)
  try {
    input.focus()
    input.select()
    if (!document.execCommand('copy')) throw new Error('复制失败，请选中 YAML 后手动复制')
  } finally {
    input.remove()
    focused?.focus()
  }
}
