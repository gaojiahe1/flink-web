/** 合入新增表和字段，用户已经填写的非空字段/连接参数优先。 */
export function mergeDefinitions(
  current: Record<string, unknown>[],
  proposed: Record<string, unknown>[],
) {
  const filled = (value: Record<string, unknown>) =>
    Object.fromEntries(
      Object.entries(value).filter(
        ([, item]) => item !== '' && item !== null && item !== undefined,
      ),
    )
  const merged = proposed.map((next) => {
    const old = current.find((value) => value.tableName === next.tableName)
    if (!old) return next
    const previousColumns = Array.isArray(old.columns)
      ? (old.columns as Record<string, unknown>[])
      : []
    const nextColumns = Array.isArray(next.columns)
      ? (next.columns as Record<string, unknown>[])
      : []
    const columns = nextColumns.map((column) => ({
      ...column,
      ...filled(previousColumns.find((value) => value.name === column.name) || {}),
    }))
    for (const column of previousColumns)
      if (!columns.some((value) => value.name === column.name)) columns.push(column)
    return { ...next, ...filled(old), columns }
  })
  for (const old of current)
    if (!merged.some((value) => value.tableName === old.tableName)) merged.push(old)
  return merged
}
