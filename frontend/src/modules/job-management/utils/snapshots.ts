export type Snapshot = { type: 'savepoint' | 'checkpoint'; path: string; time: string }
export function snapshotOptions(runtime: any): Snapshot[] {
  const result: Snapshot[] = []
  const add = (type: Snapshot['type'], item: any) => {
    if (!item || typeof item !== 'object') return
    const path = item.location || item.externalPath || item.path
    if (
      typeof path === 'string' &&
      path.startsWith('s3://') &&
      (!item.state || ['COMPLETED', 'SUCCEEDED'].includes(item.state))
    )
      result.push({
        type,
        path,
        time: String(
          item.timeStamp || item.timestamp || item.triggerTimestamp || item.resultTimestamp || '',
        ),
      })
  }
  for (const type of ['savepoint', 'checkpoint'] as const) {
    const info = runtime?.[type + 'Info']
    add(type, info?.['last' + type[0]!.toUpperCase() + type.slice(1)])
    for (const entry of info?.[type + 'History'] || []) add(type, entry)
  }
  for (const point of runtime?.snapshots || [])
    if (['savepoint', 'checkpoint'].includes(point.type)) add(point.type, point)
  return result
    .filter((item, index) => result.findIndex((p) => p.path === item.path) === index)
    .sort((a, b) => timestamp(b.time) - timestamp(a.time))
}
function timestamp(value: string) {
  return /^\d+$/.test(value) ? Number(value) : Date.parse(value) || 0
}
export function snapshotTime(value: string) {
  return value ? new Date(timestamp(value)).toLocaleString('zh-CN') : '时间未提供'
}
