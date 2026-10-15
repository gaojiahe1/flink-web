import { sql, SQLDialect } from '@codemirror/lang-sql'
import type { CompletionContext, CompletionResult } from '@codemirror/autocomplete'
import { HighlightStyle, syntaxHighlighting } from '@codemirror/language'
import { tags } from '@lezer/highlight'

const flinkDialect = SQLDialect.define({
  identifierQuotes: '`"',
  // 标准方言 spec 为空；显式扩展必须带完整基础词表，并使用解析器要求的小写。
  keywords: (
    'SELECT FROM WHERE WITH CREATE TEMPORARY TABLE VIEW DROP ALTER INSERT INTO VALUES ' +
    'UPDATE DELETE SET ADD AS AND OR NOT NULL IS IN EXISTS BETWEEN LIKE DISTINCT ALL ' +
    'JOIN INNER LEFT RIGHT FULL OUTER CROSS ON USING UNION INTERSECT EXCEPT GROUP BY ' +
    'HAVING ORDER ASC DESC LIMIT OFFSET FETCH FIRST NEXT ROWS ONLY CASE WHEN THEN ELSE END ' +
    'CAST TRY_CAST PRIMARY KEY NOT ENFORCED CONSTRAINT PARTITIONED COMMENT OPTIONS ' +
    'EXPLAIN DESCRIBE DESC SHOW TABLES VIEWS CATALOGS DATABASES USE CATALOG DATABASE ' +
    'BEGIN STATEMENT EXECUTE STATEMENTSET OVER PARTITION RANGE ROW PRECEDING FOLLOWING CURRENT ' +
    'UNBOUNDED FILTER WITHIN LATERAL TABLESAMPLE MATCH_RECOGNIZE PATTERN DEFINE MEASURES ' +
    'WATERMARK FOR METADATA VIRTUAL PROCTIME ROWTIME DESCRIPTOR TUMBLE HOP CUMULATE ' +
    'INTERVAL SECOND MINUTE HOUR DAY MONTH YEAR TO TRUE FALSE IF DEFAULT ' +
    'COUNT SUM AVG MIN MAX COALESCE NULLIF CONCAT SUBSTRING EXTRACT FLOOR CEIL'
  ).toLowerCase(),
  types:
    'char varchar string boolean binary varbinary bytes decimal dec numeric tinyint smallint int integer bigint float double date time timestamp timestamp_ltz interval map multiset array row raw null',
})
const common = ['connector']
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
    'format',
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
  filesystem: [
    'format',
    'path',
    'sink.rolling-policy.file-size',
    'sink.rolling-policy.rollover-interval',
  ],
  datagen: ['rows-per-second', 'number-of-rows'],
  print: ['print-identifier', 'standard-error'],
  blackhole: [],
  'elasticsearch-7': [
    'hosts',
    'index',
    'document-id.key-delimiter',
    'sink.bulk-flush.max-actions',
    'sink.bulk-flush.max-size',
    'sink.bulk-flush.interval',
    'sink.bulk-flush.backoff.strategy',
    'connection.max-retry-timeout',
    'format',
    'username',
    'password',
  ],
  opensearch: [
    'hosts',
    'index',
    'username',
    'password',
    'sink.bulk-flush.max-actions',
    'sink.bulk-flush.max-size',
    'sink.bulk-flush.interval',
    'document-id.key-delimiter',
  ],
  'hbase-2.2': [
    'table-name',
    'zookeeper.quorum',
    'zookeeper.znode.parent',
    'sink.buffer-flush.max-size',
    'sink.buffer-flush.max-rows',
    'sink.buffer-flush.interval',
    'lookup.async',
    'lookup.cache',
    'lookup.partial-cache.max-rows',
  ],
  kinesis: [
    'stream',
    'aws.region',
    'aws.credentials.provider',
    'scan.stream.initpos',
    'scan.stream.initpos-timestamp',
    'format',
  ],
  mongodb: [
    'uri',
    'database',
    'collection',
    'scan.fetch-size',
    'scan.partition.strategy',
    'scan.partition.size',
    'sink.buffer-flush.max-rows',
    'sink.buffer-flush.interval',
    'sink.max-retries',
    'lookup.cache',
  ],
}
// Flink 文档的连接器选项仍取决于用户镜像中的实际 Connector 版本；仅提供编辑提示。
connectors.kafka!.push(
  'scan.bounded.mode',
  'scan.bounded.specific-offsets',
  'scan.bounded.timestamp-millis',
  'scan.topic-partition-discovery.interval',
  'properties.security.protocol',
  'properties.sasl.mechanism',
  'properties.sasl.jaas.config',
  'sink.partitioner',
  'sink.transactional-id-prefix',
  'sink.parallelism',
  'key.fields',
  'key.fields-prefix',
  'value.fields-include',
)
connectors.jdbc!.push(
  'scan.partition.column',
  'scan.partition.num',
  'scan.partition.lower-bound',
  'scan.partition.upper-bound',
  'scan.fetch-size',
  'scan.auto-commit',
  'lookup.max-retries',
  'lookup.partial-cache.expire-after-write',
  'lookup.partial-cache.expire-after-access',
  'lookup.partial-cache.cache-missing-key',
  'connection.max-retry-timeout',
  'sink.parallelism',
)
connectors.filesystem!.push(
  'source.monitor-interval',
  'source.path.regex-pattern',
  'partition.time-extractor.kind',
  'partition.time-extractor.timestamp-pattern',
  'sink.partition-commit.trigger',
  'sink.partition-commit.delay',
  'sink.partition-commit.policy.kind',
  'sink.shuffle-by-partition.enable',
  'sink.parallelism',
  'auto-compaction',
  'compaction.file-size',
)

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
  'scan.bounded.mode': ['unbounded', 'latest-offset', 'specific-offsets', 'timestamp'],
  'properties.security.protocol': ['PLAINTEXT', 'SSL', 'SASL_PLAINTEXT', 'SASL_SSL'],
  'sink.partitioner': ['default', 'fixed', 'round-robin'],
  'value.fields-include': ['ALL', 'EXCEPT_KEY'],
  'lookup.cache': ['NONE', 'PARTIAL'],
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
      detail: key ? '选项值' : (connector || '通用') + ' · Connector 选项',
      info: key ? '根据实际 Connector 版本选择有效值。' : optionInfo(label),
    })),
    validFor: /^[\w.-]*$/,
  }
}

/** 用短说明区分连接器用途，提示不代替运行时版本和参数校验。 */
function optionInfo(key: string) {
  const descriptions: Record<string, string> = {
    connector: '连接器名称；对应 JAR 必须安装在所选 Flink 镜像或依赖中。',
    topic: 'Kafka 主题；多主题可按连接器约定填写。',
    'properties.bootstrap.servers': 'Kafka broker 地址，例如 broker:9092。',
    'properties.group.id': 'Kafka 消费组。',
    'scan.startup.mode': '消费起始位置。',
    'key.format': 'Kafka key 编解码格式。',
    'value.format': 'Kafka value 编解码格式。',
    format: '记录序列化格式；需对应格式依赖。',
    url: 'JDBC 数据库 URL 或 HTTP 服务地址，以所选连接器为准。',
    'table-name': '数据库或 HBase 中的目标表名称。',
    path: '文件系统目录 URI，例如 s3://bucket/path。',
    'sink.delivery-guarantee': '写入交付保证；exactly-once 需相应事务配置。',
  }
  return descriptions[key] || '选项来自 Flink SQL Connector 配置。可用性以实际连接器版本为准。'
}

export function flinkSqlSupport() {
  return [
    sql({ dialect: flinkDialect, upperCaseKeywords: true }),
    syntaxHighlighting(
      HighlightStyle.define([
        { tag: tags.keyword, color: '#7952b3', fontWeight: '600' },
        { tag: tags.typeName, color: '#1768a4' },
        { tag: tags.string, color: '#25805b' },
        { tag: tags.comment, color: '#8592a7', fontStyle: 'italic' },
      ]),
    ),
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
