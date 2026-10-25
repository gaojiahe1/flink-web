package com.flinkweb.config.sql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** 专用 Session 的固定日志协议，只采集 Print 输出，顺序号保留重复行及轮转边界。 */
public final class SqlValidationLog {
  public static final int ROW_LIMIT = 1000;
  public static final int FILE_BYTES = 10 * 1024 * 1024;
  public static final String FILE = "sql-validation.log";
  public static final List<String> FILES = List.of(FILE + ".1", FILE);
  private static final String CONFIG =
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
      logger.validation.name = org.apache.flink.runtime.taskexecutor.SystemOutRedirectionUtils
      logger.validation.level = INFO
      logger.validation.appenderRef.validation.ref = ValidationFile
      appender.validation.type = RollingFile
      appender.validation.name = ValidationFile
      appender.validation.fileName = /opt/flink/log/sql-validation.log
      appender.validation.filePattern = /opt/flink/log/sql-validation.log.%i
      appender.validation.immediateFlush = true
      appender.validation.layout.type = PatternLayout
      appender.validation.layout.pattern = FWV_EVENT|%sn|%enc{%m}{JSON}%n
      appender.validation.filter.type = RegexFilter
      appender.validation.filter.regex = (?s)^FWV_[a-f0-9]{32}_.*
      appender.validation.filter.onMatch = ACCEPT
      appender.validation.filter.onMismatch = DENY
      appender.validation.policies.type = Policies
      appender.validation.policies.size.type = SizeBasedTriggeringPolicy
      appender.validation.policies.size.size = 10MB
      appender.validation.strategy.type = DefaultRolloverStrategy
      appender.validation.strategy.max = 1
      """;

  /** 工具类禁止实例化。 */
  private SqlValidationLog() {}

  /** 强制覆盖专用 Session 的日志文件与 stdout 路由配置，普通 Session 不调用。 */
  public static void configure(ObjectNode resource) {
    resource
        .withObject("/spec/logConfiguration")
        .removeAll()
        .put("log4j-console.properties", CONFIG);
    resource
        .withObject("/spec/flinkConfiguration")
        .put("taskmanager.system-out.mode", "LOG")
        .put("taskmanager.system-out.log.thread-name.enabled", "false")
        .put("taskmanager.system-out.log.cache-upper-size", "1 mb")
        .put("env.log.dir", "/opt/flink/log")
        .put("web.refresh-interval", "1000");
  }

  /** 旧运行集群须显式重启后验证，不能悄悄回退到普通日志。 */
  public static boolean configured(JsonNode resource) {
    return CONFIG.equals(resource.at("/spec/logConfiguration/log4j-console.properties").asText())
        && "LOG"
            .equals(resource.at("/spec/flinkConfiguration/taskmanager.system-out.mode").asText())
        && "false"
            .equals(
                resource
                    .at("/spec/flinkConfiguration/taskmanager.system-out.log.thread-name.enabled")
                    .asText())
        && "/opt/flink/log".equals(resource.at("/spec/flinkConfiguration/env.log.dir").asText());
  }
}
