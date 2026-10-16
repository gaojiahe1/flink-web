export interface VerificationEvent {
  sink: number
  kind: string
  value?: string
  target?: string
  columns?: string[]
  cells?: (string | null)[]
  keys?: string[]
  structureStatus?: 'STRUCTURED' | 'TOO_WIDE' | 'LEGACY_TEXT' | 'INVALID_STRUCTURE'
}
export interface VerificationTable {
  sink: number
  target: string
  columns: string[]
  rows: (string | null)[][]
  warning: string
}

/** 主键表使用 upsert；无主键表使用多重集保留重复行，撤回只删除一个匹配项。 */
export function materializeVerification(events: VerificationEvent[]): VerificationTable[] {
  const tables = new Map<number, VerificationTable>()
  for (const event of events) {
    let table = tables.get(event.sink)
    if (!table) {
      table = {
        sink: event.sink,
        target: event.target || '输出 ' + event.sink,
        columns: [],
        rows: [],
        warning: '',
      }
      tables.set(event.sink, table)
    }
    if (event.structureStatus && event.structureStatus !== 'STRUCTURED') {
      table.warning =
        event.structureStatus === 'TOO_WIDE'
          ? '输出超过 100 列展示上限，请减少查询输出字段后重新验证。'
          : event.structureStatus === 'LEGACY_TEXT'
            ? '当前 Runner 未提供字段结构。请下载新版 Runner，重新选择后再验证；不猜测或拆分旧文本。'
            : '输出字段结构损坏或不完整，请检查验证日志并重新验证。'
      continue
    }
    const legacy =
      !event.structureStatus &&
      event.columns === undefined &&
      event.cells === undefined &&
      event.keys === undefined
    if (
      !Array.isArray(event.columns) ||
      !event.columns.length ||
      !event.columns.every((column) => typeof column === 'string') ||
      new Set(event.columns).size !== event.columns.length ||
      !Array.isArray(event.cells) ||
      event.cells.length !== event.columns.length ||
      !event.cells.every((cell) => cell === null || typeof cell === 'string') ||
      (event.keys !== undefined &&
        (!Array.isArray(event.keys) ||
          !event.keys.every((key) => typeof key === 'string' && event.columns!.includes(key)) ||
          new Set(event.keys).size !== event.keys.length))
    ) {
      table.warning = legacy
        ? '当前 Runner 未提供字段结构。请下载新版 Runner，重新选择后再验证；不猜测或拆分旧文本。'
        : '输出字段结构损坏或不完整，请检查验证日志并重新验证。'
      continue
    }
    if (event.columns.length > 100) {
      table.warning = '输出超过 100 列展示上限，请减少查询输出字段后重新验证。'
      continue
    }
    if (table.columns.length && JSON.stringify(table.columns) !== JSON.stringify(event.columns)) {
      table.warning = '本次输出的字段结构发生变化，无法可靠合并，请重新验证。'
      continue
    }
    table.columns = event.columns
    const keys = Array.isArray(event.keys) ? event.keys : []
    const keyIndexes = keys.map((key) => event.columns!.indexOf(key))
    const keyed = keys.length > 0 && keyIndexes.every((index) => index >= 0)
    const identity = (row: (string | null)[]) =>
      JSON.stringify(keyed ? keyIndexes.map((index) => row[index]) : row)
    const index = table.rows.findIndex((row) => identity(row) === identity(event.cells!))
    if (event.kind === '-D' || event.kind === '-U') {
      if (index >= 0) table.rows.splice(index, 1)
      else table.warning = '采集范围中存在未匹配的撤回或删除，当前结果仅反映已接收的数据。'
    } else if (event.kind === '+I' || event.kind === '+U') {
      if (keyed && index >= 0) table.rows[index] = event.cells.slice()
      else table.rows.push(event.cells.slice())
    }
  }
  return Array.from(tables.values())
}
