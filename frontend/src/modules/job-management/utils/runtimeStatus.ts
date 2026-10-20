export interface RuntimeObservation {
  items: {
    name: string
    jobId?: string
    managed?: boolean
    registrationConflict?: boolean
    healthState?: string
    deleting?: boolean
    jobState?: string
  }[]
  stopProgress?: Record<string, { phase: string }>
  queriedAt?: string
}
/** 三处列表共用同一次查询的语义；查询失败不能当成未部署。 */
export function observedJobStatus(
  id: string,
  deploymentName: string,
  observation?: RuntimeObservation,
) {
  if (!observation || !Array.isArray(observation.items)) return '状态未知'
  const item = observation.items.find(
    (row) => (row.jobId === id && row.managed) || (!!deploymentName && row.name === deploymentName),
  )
  if (item?.registrationConflict) return '资源身份冲突'
  if (item?.healthState === 'ERROR') return '部署异常'
  if (item?.deleting) return '删除中'
  if (item) return item.jobState || '状态未知'
  const phase = observation.stopProgress?.[id]?.phase
  if (phase === 'DONE') return '已停止'
  if (phase && phase !== 'ERROR') return '停止中'
  if (phase === 'ERROR') return '停止异常'
  return '未部署'
}
