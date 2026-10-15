import { sql, SQLDialect, StandardSQL } from '@codemirror/lang-sql'
import type { CompletionContext, CompletionResult } from '@codemirror/autocomplete'

const flinkDialect = SQLDialect.define({
  ...StandardSQL.spec,
  identifierQuotes: '`"',
  keywords:
    StandardSQL.spec.keywords +
    ' WATERMARK METADATA VIRTUAL PROCTIME TIMESTAMP_LTZ DESCRIPTOR TUMBLE HOP CUMULATE MATCH_RECOGNIZE',
  types:
    StandardSQL.spec.types +
    ' STRING BYTES BIGINT TINYINT SMALLINT BOOLEAN DECIMAL TIMESTAMP_LTZ MAP MULTISET ARRAY ROW',
})
const common = ['connector', 'format', 'scan.startup.mode', 'sink.parallelism']
const connectors: Record<string, string[]> = {
  kafka: [
    'topic',
    'topic-pattern',
    'properties.bootstrap.servers',
    'properties.group.id',
    'scan.startup.mode',
    'scan.startup.timestamp-millis',
    'key.format',
    'value.format',
    'sink.delivery-guarantee',
  ],
  'upsert-kafka': [
    'topic',
    'properties.bootstrap.servers',
    'key.format',
    'value.format',
    'sink.buffer-flush.max-rows',
    'sink.buffer-flush.interval',
  ],
  jdbc: [
    'url',
    'table-name',
    'driver',
    'username',
    'password',
    'lookup.cache',
    'lookup.partial-cache.max-rows',
    'sink.buffer-flush.max-rows',
    'sink.buffer-flush.interval',
    'sink.max-retries',
  ],
  filesystem: ['path', 'sink.rolling-policy.file-size', 'sink.rolling-policy.rollover-interval'],
  datagen: ['rows-per-second', 'number-of-rows'],
  print: ['print-identifier', 'standard-error'],
  blackhole: [],
}
const values: Record<string, string[]> = {
  connector: Object.keys(connectors),
  format: ['json', 'csv', 'avro', 'debezium-json', 'canal-json', 'raw'],
  'key.format': ['json', 'csv', 'avro', 'raw'],
  'value.format': ['json', 'csv', 'avro', 'debezium-json', 'canal-json', 'raw'],
  'scan.startup.mode': [
    'earliest-offset',
    'latest-offset',
    'group-offsets',
    'timestamp',
    'specific-offsets',
  ],
  'sink.delivery-guarantee': ['at-least-once', 'exactly-once', 'none'],
  'standard-error': ['true', 'false'],
}

/** 仅在当前 WITH (...) 的选项字符串内提示，注释及其他语句不触发。 */
export function connectorCompletion(context: CompletionContext): CompletionResult | null {
  const text = context.state.doc.sliceString(0, context.pos)
  // 完整字符串和注释先遮蔽，避免其中的分号、WITH 和括号改变上下文。
  const masked = text.replace(
    /--[^\n]*(?:\n|$)|\/\*[\s\S]*?(?:\*\/|$)|'(?:''|[^'])*'|`(?:``|[^`])*`|"(?:""|[^"])*"/g,
    (s) => ' '.repeat(s.length),
  )
  const start = masked.toUpperCase().lastIndexOf('WITH')
  if (start < 0 || !/\bWITH\s*\(/i.test(masked.slice(start))) return null
  const scope = masked.slice(start)
  if (scope.includes(';') || (scope.match(/\(/g)?.length ?? 0) <= (scope.match(/\)/g)?.length ?? 0))
    return null
  // 保留字符串和原始位置，只遮蔽注释，避免注释中的 Connector/已用键影响候选。
  const uncommented = text.replace(
    /'(?:''|[^'])*'|`(?:``|[^`])*`|"(?:""|[^"])*"|--[^\n]*(?:\n|$)|\/\*[\s\S]*?(?:\*\/|$)/g,
    (s) => (s.startsWith('--') || s.startsWith('/*') ? s.replace(/[^\r\n]/g, ' ') : s),
  )
  const optionsText = uncommented.slice(start)
  const token = /'([^'\n]*)$/.exec(optionsText)
  if (!token) return null
  // 当前未闭合字符串必须是真正的引号，不能是注释里的引号。
  const quote = context.pos - token[1]!.length - 1
  if (masked[quote] !== "'") return null
  const before = uncommented.slice(start, quote)
  const key = /'([^']+)'\s*=\s*$/.exec(before)?.[1]
  const connector = /'connector'\s*=\s*'([^']+)'/i.exec(optionsText)?.[1]?.toLowerCase()
  let candidates = key
    ? values[key.toLowerCase()]
    : [...common, ...(connectors[connector || ''] || [])]
  if (!candidates) return null
  const used = new Set([...before.matchAll(/'([^']+)'\s*=/g)].map((m) => m[1]))
  candidates = [...new Set(candidates)].filter((v) => key || !used.has(v))
  return {
    from: quote + 1,
    options: candidates.map((label) => ({
      label,
      type: key ? 'enum' : 'property',
      detail: key ? '选项值' : 'Connector 选项',
    })),
    validFor: /^[\w.-]*$/,
  }
}

export function flinkSqlSupport() {
  return [
    sql({ dialect: flinkDialect, upperCaseKeywords: true }),
    flinkDialect.language.data.of({ autocomplete: connectorCompletion }),
  ]
}

/** 格式化只允许改变 token 之间的空白，字符串、引用、注释及运算符必须逐项保留。 */
export function sameSqlTokens(before: string, after: string) {
  const tokens = (text: string) =>
    text.match(
      /--[^\r\n]*|\/\*[\s\S]*?\*\/|'(?:''|[^'])*'|"(?:""|[^"])*"|`(?:``|[^`])*`|[A-Za-z_$][\w$]*|\d+(?:\.\d+)?(?:[Ee][+-]?\d+)?|<>|!=|>=|<=|\|\||=>|::|[^\s]/g,
    ) || []
  return JSON.stringify(tokens(before)) === JSON.stringify(tokens(after))
}
