/** 模型及 JSON 草稿使用相同结构检查；缺少连接参数交给编译待补项。 */
export function validateDefinitions(value: unknown): asserts value is Record<string, unknown>[] {
  const object = (item: unknown): item is Record<string, unknown> =>
    !!item && typeof item === 'object' && !Array.isArray(item)
  if (
    !Array.isArray(value) ||
    value.length > 8 ||
    value.some(
      (item) =>
        !object(item) ||
        typeof item.tableName !== 'string' ||
        typeof item.connectorType !== 'string' ||
        !Array.isArray(item.columns) ||
        item.columns.some(
          (column: unknown) =>
            !object(column) ||
            typeof column.name !== 'string' ||
            typeof column.type !== 'string' ||
            ['primaryKey', 'nullable'].some(
              (field) => field in column && typeof column[field] !== 'boolean',
            ),
        ),
    )
  )
    throw new Error(
      '数据定义必须为最多八项的对象数组，表名、Connector 和列名称/类型须为字符串，columns 须为对象数组',
    )
}

/** 合入新增表和字段，用户已经填写的非空字段/连接参数优先。 */
export function mergeDefinitions(
  current: Record<string, unknown>[],
  proposed: Record<string, unknown>[],
) {
  validateDefinitions(current)
  validateDefinitions(proposed)
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
