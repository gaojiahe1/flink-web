export type TableRole = '' | 'source' | 'lookup' | 'sink'
export type WizardPattern = 'transform' | 'lookup' | 'window'
export interface WizardTable {
  tableName: string
  connectorType: string
  name?: string
  columns?: { name: string; type: string }[]
  watermarkColumn?: string
  watermarkDelaySeconds?: number
}
export const roleLabel = { source: '输入表', lookup: '维表', sink: '输出表' }

/** 角色只在当前向导中保存，不修改共享数据源登记。 */
export function initialRole(connector: string): TableRole {
  return connector === 'print' ? 'sink' : connector === 'datagen' ? 'source' : ''
}

/** 前端可解释的进入条件；服务端仍独立校验相同业务边界。 */
export function wizardDataIssues(tables: WizardTable[], roles: Record<string, TableRole>) {
  const issues: string[] = []
  if (tables.length > 8) issues.push('数据定义总数最多八项')
  const names = tables.map((table) => table.tableName?.toLowerCase())
  if (names.some((name) => !name) || new Set(names).size !== names.length)
    issues.push('请补全表名并移除重复表定义')
  for (const table of tables) {
    const role = roles[table.tableName] ?? initialRole(table.connectorType)
    if (!role) issues.push('请选择 ' + (table.name || table.tableName) + ' 的数据角色')
    if (role === 'source' && table.connectorType === 'print') issues.push('Print 只能作为输出表')
    if (role === 'sink' && table.connectorType === 'datagen') issues.push('DataGen 只能作为输入表')
    if (role === 'lookup' && !['mysql', 'postgresql'].includes(table.connectorType))
      issues.push('首批维表仅支持 JDBC 数据源')
  }
  const chosen = tables.map((table) => roles[table.tableName] ?? initialRole(table.connectorType))
  if (!chosen.includes('source')) issues.push('至少选择一张输入表')
  if (!chosen.includes('sink')) issues.push('至少选择一张输出表')
  return issues
}
