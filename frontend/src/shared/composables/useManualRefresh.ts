import { computed, onUnmounted, ref } from 'vue'

/** Instance-local cooldown: no cache, cross-tab lock, or automatic polling. */
export function useManualRefresh(action: () => Promise<void>) {
  const busy = ref(false)
  const lastStarted = ref(0)
  const now = ref(Date.now())
  const remaining = computed(() =>
    Math.max(0, Math.ceil((lastStarted.value + 3000 - now.value) / 1000)),
  )
  const disabled = computed(() => busy.value || remaining.value > 0)
  let timer: ReturnType<typeof setInterval> | undefined

  async function run(task: () => Promise<void>) {
    now.value = Date.now()
    if (disabled.value) return
    busy.value = true
    lastStarted.value = now.value
    timer = setInterval(() => {
      now.value = Date.now()
      if (remaining.value === 0) {
        clearInterval(timer)
        timer = undefined
      }
    }, 250)
    try {
      await task()
    } finally {
      busy.value = false
    }
  }
  onUnmounted(() => clearInterval(timer))
  const refresh = () => run(action)
  return { refresh, run, remaining, disabled, busy }
}
