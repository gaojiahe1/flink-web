import { onMounted, ref, computed } from 'vue'
import { request } from '../api/http'
import { useManualRefresh } from './useManualRefresh'
export interface OperatorStatus {
  found: boolean
  namespace: string
  deployment: string
  queriedAt: string
  image?: string
  replicas?: number
  readyReplicas?: number
  generation?: number
  observedGeneration?: number
  podMessage?: string
  compatibility: {
    known: boolean
    version: string
    supported?: string[]
    accepted?: string[]
    message: string
    source?: string
  }
  pods?: {
    name: string
    phase: string
    ready: boolean
    deleting: boolean
    containers: { name: string; ready: boolean; restarts: number; state: string; reason: string }[]
  }[]
}
/** 每个使用页面独立查询，结果不跨页面或标签共享。 */
export function useOperator() {
  const status = ref<OperatorStatus | null>(null)
  const error = ref('')
  const controls = useManualRefresh(async () => {
    error.value = ''
    try {
      status.value = await request<OperatorStatus>('/api/system/operator')
    } catch (e) {
      status.value = null
      error.value = (e as Error).message
    }
  })
  onMounted(controls.refresh)
  const hint = computed(() =>
    error.value
      ? 'Operator 查询失败：' + error.value
      : !status.value
        ? '正在查询 Operator 支持范围…'
        : !status.value.found
          ? '未找到 Operator Deployment，请检查系统设置中的查询目标'
          : !status.value.compatibility.known
            ? status.value.compatibility.message
            : 'Operator ' +
              status.value.compatibility.version +
              ' · 支持 Flink ' +
              status.value.compatibility.supported?.join(' / '),
  )
  return { status, error, hint, ...controls }
}
