import { onBeforeUnmount, ref } from 'vue'
import { aiApi, type AiTask } from '../api/ai'
import { ApiError } from '../../../shared/api/http'
import { requestId } from '../../../shared/utils/requestId'

/** 用户主动任务的同号核对、查询和取消；未知提交不创建第二个请求号。 */
export function useAiTask() {
  const task = ref<AiTask | null>(null),
    busy = ref(false),
    error = ref(''),
    uncertain = ref(false)
  let epoch = 0,
    timer: ReturnType<typeof setTimeout> | undefined,
    active = '',
    disposed = false
  let pollController: AbortController | undefined, pending: Record<string, unknown> | undefined
  let cancelRequested = false,
    reconciling = false
  const current = (version: number, id: string) => !disposed && version === epoch && id === active
  function stop() {
    if (timer) clearTimeout(timer)
    timer = undefined
    pollController?.abort()
  }
  function clear() {
    active = ''
    pending = undefined
    busy.value = false
    uncertain.value = false
  }
  function unresolved(version: number, id: string) {
    if (!current(version, id)) return
    uncertain.value = true
    error.value =
      '任务 ' +
      id +
      ' 的' +
      (cancelRequested ? '取消' : '提交') +
      '尚未确认，请核对原任务后重试或取消。'
  }
  function accept(result: AiTask, version: number, id: string) {
    if (!current(version, id)) {
      if ((disposed || cancelRequested) && ['QUEUED', 'RUNNING'].includes(result.state))
        void aiApi.cancel(id).catch(() => {})
      return
    }
    task.value = result
    uncertain.value = false
    error.value = ''
    if (['SUCCEEDED', 'FAILED', 'CANCELED'].includes(result.state)) {
      clear()
      if (result.state === 'FAILED') error.value = result.message || 'AI 任务失败'
      return
    }
    timer = setTimeout(async () => {
      pollController = new AbortController()
      try {
        accept(await aiApi.task(id, pollController.signal), version, id)
      } catch {
        if (current(version, id)) void cancel()
      }
    }, 1000)
  }
  async function cancel() {
    const version = ++epoch,
      id = active
    stop()
    cancelRequested = true
    uncertain.value = false
    if (!id) {
      clear()
      return
    }
    try {
      const result = await aiApi.cancel(id)
      accept(result, version, id)
    } catch {
      unresolved(version, id)
    }
  }
  async function send(version: number, id: string) {
    try {
      const result = await aiApi.start(pending)
      if (!current(version, id)) {
        await aiApi.cancel(id).catch(() => {})
        return
      }
      accept(result, version, id)
    } catch (cause) {
      if (!current(version, id)) {
        await aiApi.cancel(id).catch(() => {})
        return
      }
      // 服务端可能已经接受；先查同号任务，绝不在异常路径自动重发模型调用。
      try {
        accept(await aiApi.task(id), version, id)
      } catch (lookup) {
        if (!current(version, id)) return
        if (
          lookup instanceof ApiError &&
          lookup.status === 404 &&
          cause instanceof ApiError &&
          [400, 401, 403, 409, 413, 422, 429].includes(cause.status)
        ) {
          clear()
          error.value = cause.message
        } else unresolved(version, id)
      }
    }
  }
  async function start(input: Record<string, unknown>) {
    if (busy.value || disposed) return
    const version = ++epoch,
      id = requestId()
    active = id
    pending = JSON.parse(JSON.stringify({ ...input, requestId: id }))
    cancelRequested = false
    busy.value = true
    uncertain.value = false
    error.value = ''
    task.value = null
    await send(version, id)
  }
  async function retry() {
    if (!active || !uncertain.value || reconciling || disposed) return
    if (cancelRequested) {
      await cancel()
      return
    }
    const version = epoch,
      id = active
    reconciling = true
    try {
      try {
        accept(await aiApi.task(id), version, id)
      } catch (cause) {
        if (!current(version, id)) return
        if (cause instanceof ApiError && cause.status === 404) await send(version, id)
        else unresolved(version, id)
      }
    } finally {
      reconciling = false
    }
  }
  onBeforeUnmount(() => {
    disposed = true
    void cancel()
  })
  return { task, busy, error, uncertain, start, cancel, retry }
}
