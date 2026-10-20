import { onBeforeUnmount, ref } from 'vue'
import { jobsApi } from '../../modules/job-management/api/jobs'
import {
  observedJobStatus,
  type RuntimeObservation,
} from '../../modules/job-management/utils/runtimeStatus'
export function useDevelopmentStatus() {
  const names = ref<Record<string, string>>({})
  const observation = ref<RuntimeObservation>(),
    error = ref('')
  const controller = new AbortController()
  let version = 0,
    disposed = false
  async function loadStatus() {
    const current = ++version
    observation.value = undefined
    error.value = ''
    try {
      const [jobs, result] = await Promise.all([
        jobsApi.list(),
        jobsApi.refresh(controller.signal) as Promise<RuntimeObservation>,
      ])
      if (!Array.isArray(result.items)) throw new Error('运行状态响应无效')
      if (!disposed && current === version) {
        names.value = Object.fromEntries(jobs.map((job) => [job.id, job.deploymentName]))
        observation.value = result
      }
    } catch (cause) {
      if (!disposed && current === version)
        error.value = '状态查询失败：' + (cause as Error).message
    }
  }
  onBeforeUnmount(() => {
    disposed = true
    version++
    controller.abort()
  })
  return {
    loadStatus,
    statusError: error,
    status: (id?: string, name = '') =>
      id ? observedJobStatus(id, name || names.value[id] || '', observation.value) : '未部署',
  }
}
