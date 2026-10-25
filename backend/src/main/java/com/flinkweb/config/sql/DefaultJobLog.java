package com.flinkweb.config.sql;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** SQL 新草稿的服务端日志缺省值；显式配置或显式移除覆盖始终优先。 */
public final class DefaultJobLog {
  private static final String LOG4J =
      """
      rootLogger.level = INFO
      rootLogger.appenderRef.console.ref = LogConsole
      rootLogger.appenderRef.file.ref = LogFile
      appender.console.type = Console
      appender.console.name = LogConsole
      appender.console.layout.type = PatternLayout
      appender.console.layout.pattern = %d{yyyy-MM-dd HH:mm:ss,SSS} %-5p %-60c - %m%n
      appender.file.type = RollingFile
      appender.file.name = LogFile
      appender.file.fileName = ${sys:log.file}
      appender.file.filePattern = ${sys:log.file}.%i
      appender.file.layout.type = PatternLayout
      appender.file.layout.pattern = %d{yyyy-MM-dd HH:mm:ss,SSS} %-5p %-60c - %m%n
      appender.file.policies.type = Policies
      appender.file.policies.size.type = SizeBasedTriggeringPolicy
      appender.file.policies.size.size = 10MB
      appender.file.strategy.type = DefaultRolloverStrategy
      appender.file.strategy.max = 1
      """;

  /** 工具类不实例化。 */
  private DefaultJobLog() {}

  /** 不覆盖已有 YAML 或显式日志选择，只为全新 SQL 草稿补齐双输出。 */
  public static void apply(ObjectNode runtime) {
    if (!runtime.has("logConfiguration")
        && runtime.path("baseYaml").asText().isBlank()
        && runtime.path("deploymentYaml").asText().isBlank())
      runtime.putObject("logConfiguration").put("log4j-console.properties", LOG4J);
  }
}
