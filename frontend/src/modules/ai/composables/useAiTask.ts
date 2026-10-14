import { onBeforeUnmount, ref } from 'vue'
import { aiApi, type AiTask } from '../api/ai'
import { requestId } from '../../../shared/utils/requestId'

/** 仅在用户主动任务期间查询；切换上下文/离页取消，旧结果不回填。 */
export function useAiTask() {
  const task = ref<AiTask | null>(null),
    busy = ref(false),
    error = ref('')
  let epoch = 0,
    timer: ReturnType<typeof setTimeout> | undefined,
    active = '',
    disposed = false
  let pollController: AbortController | undefined
  function stop() {
    if (timer) clearTimeout(timer)
    pollController?.abort()
  }
  async function cancel() {
    const canceledEpoch = ++epoch
    stop()
    const id = active
    active = ''
    busy.value = false
    if (id) {
      try {
        const result = await aiApi.cancel(id)
        if (!disposed && canceledEpoch === epoch) task.value = result
      } catch {
        if (!disposed && canceledEpoch === epoch)
          error.value = '取消请求未确认，可在生成记录中查看任务状态'
      }
    }
  }
  async function start(input: Record<string, unknown>) {
    if (busy.value) return
    const current = ++epoch
    active = requestId()
    const id = active
    busy.value = true
    error.value = ''
    task.value = null
    async function accept(result: AiTask) {
      if (disposed || current !== epoch) return
      task.value = result
      if (['SUCCEEDED', 'FAILED', 'CANCELED'].includes(result.state)) {
        busy.value = false
        active = ''
        if (result.state === 'FAILED') error.value = result.message || 'AI 任务失败'
        return
      }
      timer = setTimeout(async () => {
        pollController = new AbortController()
        try {
          await accept(await aiApi.task(id, pollController.signal))
        } catch (cause) {
          if (current === epoch && !disposed) {
            busy.value = false
            error.value = (cause as Error).message
            // 查询失败不重发模型请求；服务端仍有任务，主动取消并保留记录。
            void cancel()
          }
        }
      }, 1000)
    }
    try {
      const result = await aiApi.start({ ...input, requestId: id })
      if (disposed || current !== epoch) {
        await aiApi.cancel(id).catch(() => {})
        return
      }
      await accept(result)
    } catch (cause) {
      if (!disposed && current === epoch) {
        error.value = (cause as Error).message
        busy.value = false
        active = ''
      }
    }
  }
  onBeforeUnmount(() => {
    disposed = true
    void cancel()
  })
  return { task, busy, error, start, cancel }
}
