import { parseDocument } from 'yaml'
/** 普通作业双输出日志；专用 SQL 验证集群仍使用服务端固定日志协议。 */
export function defaultLogConfiguration(): Record<string, string> {
  return {
    'log4j-console.properties': `rootLogger.level = INFO
rootLogger.appenderRef.console.ref = LogConsole
rootLogger.appenderRef.file.ref = LogFile
appender.console.type = Console
appender.console.name = LogConsole
appender.console.layout.type = PatternLayout
appender.console.layout.pattern = %d{yyyy-MM-dd HH:mm:ss,SSS} %-5p %-60c - %m%n
appender.file.type = RollingFile
appender.file.name = LogFile
appender.file.fileName = \${sys:log.file}
appender.file.filePattern = \${sys:log.file}.%i
appender.file.layout.type = PatternLayout
appender.file.layout.pattern = %d{yyyy-MM-dd HH:mm:ss,SSS} %-5p %-60c - %m%n
appender.file.policies.type = Policies
appender.file.policies.size.type = SizeBasedTriggeringPolicy
appender.file.policies.size.size = 32MB
appender.file.strategy.type = DefaultRolloverStrategy
appender.file.strategy.max = 5
`,
  }
}

export function readLogConfiguration(spec: { logConfiguration?: unknown }): Record<string, string> {
  const value = spec.logConfiguration
  if (value === undefined) return {}
  if (
    !value ||
    typeof value !== 'object' ||
    Array.isArray(value) ||
    Object.values(value).some((v) => typeof v !== 'string')
  )
    throw new Error('logConfiguration 必须为文件名到文本的映射，请在 YAML 中修正')
  return { ...value } as Record<string, string>
}

export function runtimeLogConfiguration(runtime: {
  logConfiguration?: unknown
  deploymentYaml?: string
  baseYaml?: string
}) {
  if (runtime.logConfiguration !== undefined) return readLogConfiguration(runtime)
  const source = runtime.deploymentYaml || runtime.baseYaml
  if (!source) return {}
  const doc = parseDocument(source)
  if (doc.errors.length) throw new Error('部署 YAML 无法解析，未覆盖日志配置')
  return readLogConfiguration(doc.toJS()?.spec || {})
}
