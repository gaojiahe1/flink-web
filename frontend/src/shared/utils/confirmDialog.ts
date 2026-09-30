// 统一站内确认框；原生 dialog 提供焦点约束与 Escape 取消。
export function confirmDialog(message: string, title = '确认操作'): Promise<boolean> {
  return new Promise((resolve) => {
    const previous = document.activeElement as HTMLElement | null
    const dialog = document.createElement('dialog')
    dialog.className = 'app-confirm-dialog'
    const heading = document.createElement('h3')
    heading.id = 'confirm-' + Math.random().toString(36).slice(2)
    heading.textContent = title
    dialog.setAttribute('aria-labelledby', heading.id)
    const content = document.createElement('p')
    content.textContent = message
    const actions = document.createElement('div')
    actions.className = 'dialog-footer'
    const cancel = document.createElement('button')
    cancel.className = 'button'
    cancel.textContent = '取消'
    const confirm = document.createElement('button')
    confirm.className = 'button primary'
    confirm.textContent = '确定'
    let finished = false
    const finish = (accepted: boolean) => {
      if (finished) return
      finished = true
      dialog.close()
      dialog.remove()
      if (previous?.isConnected) previous.focus()
      resolve(accepted)
    }
    cancel.onclick = () => finish(false)
    confirm.onclick = () => finish(true)
    dialog.addEventListener('cancel', (event) => {
      event.preventDefault()
      finish(false)
    })
    actions.append(cancel, confirm)
    dialog.append(heading, content, actions)
    document.body.append(dialog)
    dialog.showModal()
    cancel.focus()
  })
}
