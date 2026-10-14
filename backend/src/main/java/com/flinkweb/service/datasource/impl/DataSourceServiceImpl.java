package com.flinkweb.service.datasource.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.datasource.DataSourceService;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 管理Kafka、关系型、文件及调试 数据源定义，生成不含明文密钥的 Flink SQL 建表片段。 */
@Service
public class DataSourceServiceImpl implements DataSourceService {
  private final RecordRepository records;

  /** 注入通用元数据仓库，数据源复用本地或 MySQL 持久化。 */
  public DataSourceServiceImpl(RecordRepository records) {
    this.records = records;
  }

  /** 校验连接参数、字段定义和密码引用，并仅保存允许的字段。 */
  public ObjectNode validate(ObjectNode input) {
    ObjectNode node = input.objectNode();
    String type = input.path("connectorType").asText();
    if (!Set.of("kafka", "upsert-kafka", "mysql", "postgresql", "filesystem", "datagen", "print")
        .contains(type)) fail("不支持的数据源类型");
    node.put("connectorType", type);
    String table = input.path("tableName").asText();
    if (!table.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) fail("SQL 表名仅支持字母、数字和下划线");
    node.put("tableName", table);
    JsonNode columns = input.path("columns");
    if (!columns.isArray() || columns.isEmpty() || columns.size() > 100) fail("请配置 1–100 个字段");
    Set<String> names = new HashSet<>();
    var cleanColumns = node.putArray("columns");
    boolean primaryKey = false;
    for (JsonNode column : columns) {
      String name = column.path("name").asText(),
          dataType = column.path("type").asText().toUpperCase(Locale.ROOT).replace(" ", "");
      if (!name.matches("[A-Za-z_][A-Za-z0-9_$-]{0,63}")
          || !names.add(name.toLowerCase(Locale.ROOT))) fail("字段名称无效或重复");
      if (!validType(dataType))
        fail("不支持的字段类型；支持基本类型、DECIMAL(p,s)、TIMESTAMP(p)、TIMESTAMP_LTZ(p) 和 TIME(p)");
      boolean key = column.path("primaryKey").asBoolean(false);
      primaryKey |= key;
      cleanColumns
          .addObject()
          .put("name", name)
          .put("type", dataType)
          .put("primaryKey", key)
          .put("nullable", !key && column.path("nullable").asBoolean(true));
    }
    if (type.equals("kafka") && primaryKey) fail("普通 Kafka 表不支持主键，请取消主键或使用单独配置的 upsert-kafka");
    if (type.equals("upsert-kafka") && !primaryKey) fail("Upsert Kafka 必须定义主键");
    if (Set.of("filesystem", "datagen", "print").contains(type) && primaryKey) fail("该连接器不支持主键约束");
    String eventTime = input.path("watermarkColumn").asText();
    if (!eventTime.isBlank()) {
      boolean valid = false;
      for (var column : cleanColumns)
        if (column.path("name").asText().equals(eventTime)
            && Set.of("TIMESTAMP(3)", "TIMESTAMP_LTZ(3)").contains(column.path("type").asText()))
          valid = true;
      if (!valid) fail("Watermark 必须选择 TIMESTAMP(3) 或 TIMESTAMP_LTZ(3) 字段");
      int delay = input.path("watermarkDelaySeconds").asInt(-1);
      if (delay < 0 || delay > 86400) fail("Watermark 延迟需为 0–86400 秒");
      node.put("watermarkColumn", eventTime).put("watermarkDelaySeconds", delay);
    }
    boolean kafka = Set.of("kafka", "upsert-kafka").contains(type);
    boolean jdbc = Set.of("mysql", "postgresql").contains(type);
    List<String> fields =
        kafka
            ? List.of("brokers", "topic", "format")
            : jdbc
                ? List.of("host", "port", "database", "table", "username", "passwordRef")
                : type.equals("filesystem") ? List.of("path", "format") : List.of();
    for (String field : fields) {
      String value = input.path(field).asText().trim();
      if (value.isEmpty() || value.length() > 500 || value.contains("\n") || value.contains("\r"))
        fail("缺少或无效的数据源参数：" + field);
      if (value.contains("{{") || value.contains("}}")) fail("连接参数不支持变量，请使用密码配置引用");
      node.put(field, value);
    }
    if (kafka) {
      var brokers = node.path("brokers").asText().split(",", -1);
      if (brokers.length > 10) fail("最多配置 10 个 Kafka 地址");
      for (String broker : brokers) {
        int colon = broker.lastIndexOf(':');
        if (colon < 1) fail("Kafka 地址需为 host:port");
        endpoint(broker.substring(0, colon), broker.substring(colon + 1));
      }
      if (!node.path("topic").asText().matches("[A-Za-z0-9._-]{1,249}")) fail("Topic 名称无效");
      if (type.equals("kafka")) {
        String group = input.path("groupId").asText().trim();
        if (!group.matches("[A-Za-z0-9._-]{1,200}")) fail("消费组名称无效");
        node.put("groupId", group);
        String startup = input.path("startupMode").asText("group-offsets");
        if (!Set.of("group-offsets", "earliest-offset", "latest-offset", "timestamp")
            .contains(startup)) fail("Kafka 起始位置无效");
        node.put("startupMode", startup);
        if (startup.equals("timestamp")) {
          if (!input.path("startupTimestamp").isIntegralNumber()
              || input.path("startupTimestamp").asLong() < 0) fail("Kafka 起始时间需为非负毫秒时间戳");
          node.put("startupTimestamp", input.path("startupTimestamp").asLong());
        }
      }
    }
    if (kafka || type.equals("filesystem")) {
      String format = node.path("format").asText();
      Set<String> formats =
          type.equals("upsert-kafka")
              ? Set.of("json", "csv")
              : type.equals("filesystem")
                  ? Set.of("json", "csv", "parquet", "orc", "raw")
                  : Set.of("json", "csv", "raw");
      if (!formats.contains(format)) fail("该数据源不支持所选格式");
      if (format.equals("raw")
          && (columns.size() != 1
              || !Set.of("STRING", "BYTES").contains(cleanColumns.get(0).path("type").asText())))
        fail("raw 格式必须且只能定义一个 STRING 或 BYTES 字段");
    }
    if (type.equals("filesystem")) storagePath(node.path("path").asText());
    if (type.equals("datagen")) {
      var rate = input.path("rowsPerSecond");
      if (!rate.isIntegralNumber() || rate.asLong() < 1 || rate.asLong() > 100000)
        fail("生成速率需为 1–100000 的整数");
      node.put("rowsPerSecond", rate.asLong());
    }
    if (jdbc) {
      endpoint(node.path("host").asText(), node.path("port").asText());
      if (!node.path("database").asText().matches("[A-Za-z_][A-Za-z0-9_-]{0,63}")
          || !node.path("table").asText().matches("[A-Za-z_][A-Za-z0-9_$-]{0,63}"))
        fail("数据库或表名无效");
      if (type.equals("postgresql")) {
        String schema = input.path("schema").asText("public");
        if (!schema.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) fail("PostgreSQL Schema 无效");
        node.put("schema", schema);
      }
      String sslMode = input.path("sslMode").asText("PREFERRED");
      if (!Set.of("PREFERRED", "REQUIRED", "VERIFY_CA", "VERIFY_IDENTITY", "DISABLED")
          .contains(sslMode)) fail("TLS 模式无效");
      node.put("sslMode", sslMode);
      String ref = node.path("passwordRef").asText();
      if (!ref.matches("[a-z0-9][a-z0-9_.-]{0,100}")) fail("密码配置标识无效");
      ObjectNode secret = records.require("configs", ref);
      if (!Set.of("secret", "secretRef").contains(secret.path("kind").asText()))
        fail("密码必须引用密钥或 Kubernetes Secret 配置");
    }
    return node;
  }

  /** 为指定数据源生成 CREATE TABLE 和必要的 Pod 环境变量，连接只在 Flink 运行时建立。 */
  public ObjectNode snippet(String id) {
    return snippetDefinition(id, records.require("datasources", id));
  }

  /** 编译当前作业的数据定义，复用同一验证和凭据引用规则而不写共享登记。 */
  public ObjectNode snippetDefinition(String id, ObjectNode input) {
    ObjectNode source = validate(input);
    StringBuilder ddl =
        new StringBuilder("CREATE TEMPORARY TABLE `")
            .append(source.path("tableName").asText())
            .append("` (\n");
    List<String> fields = new ArrayList<>();
    for (JsonNode column : source.path("columns"))
      fields.add(
          "  `"
              + column.path("name").asText()
              + "` "
              + column.path("type").asText().toUpperCase(Locale.ROOT)
              + (column.path("nullable").asBoolean(true) ? "" : " NOT NULL"));
    var primary = new ArrayList<String>();
    for (var column : source.path("columns"))
      if (column.path("primaryKey").asBoolean())
        primary.add("`" + column.path("name").asText() + "`");
    if (!primary.isEmpty())
      fields.add("  PRIMARY KEY (" + String.join(", ", primary) + ") NOT ENFORCED");
    if (source.has("watermarkColumn")) {
      String column = source.path("watermarkColumn").asText();
      fields.add(
          "  WATERMARK FOR `"
              + column
              + "` AS `"
              + column
              + "` - INTERVAL '"
              + source.path("watermarkDelaySeconds").asInt()
              + "' SECOND");
    }
    ddl.append(String.join(",\n", fields)).append("\n) WITH (\n");
    Map<String, String> options = new LinkedHashMap<>();
    ObjectNode result = source.objectNode();
    var env = result.putArray("env");
    String type = source.path("connectorType").asText();
    if (Set.of("kafka", "upsert-kafka").contains(type)) {
      options.put("connector", type);
      options.put("properties.bootstrap.servers", source.path("brokers").asText());
      options.put("topic", source.path("topic").asText());
      if (type.equals("kafka")) {
        options.put("properties.group.id", source.path("groupId").asText());
        options.put("scan.startup.mode", source.path("startupMode").asText("group-offsets"));
        if ("timestamp".equals(source.path("startupMode").asText()))
          options.put("scan.startup.timestamp-millis", source.path("startupTimestamp").asText());
        options.put("format", source.path("format").asText());
      } else {
        options.put("key.format", source.path("format").asText());
        options.put("value.format", source.path("format").asText());
      }
    } else if (type.equals("filesystem")) {
      options.put("connector", type);
      options.put("path", source.path("path").asText());
      options.put("format", source.path("format").asText());
    } else if (type.equals("datagen")) {
      options.put("connector", type);
      options.put("rows-per-second", source.path("rowsPerSecond").asText());
    } else if (type.equals("print")) {
      options.put("connector", type);
    } else {
      options.put("connector", "jdbc");
      options.put(
          "url",
          "jdbc:"
              + type
              + "://"
              + source.path("host").asText()
              + ":"
              + source.path("port").asText()
              + "/"
              + source.path("database").asText()
              + (type.equals("postgresql")
                  ? "?sslmode=" + postgresSsl(source.path("sslMode").asText("PREFERRED"))
                  : "?sslMode=" + source.path("sslMode").asText("PREFERRED")));
      options.put(
          "table-name",
          (type.equals("postgresql") ? source.path("schema").asText("public") + "." : "")
              + source.path("table").asText());
      options.put("username", source.path("username").asText());
      String variable =
          "DS_"
              + java.util.HexFormat.of()
                  .formatHex(id.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                  .toUpperCase(Locale.ROOT);
      options.put("password", "{{env:" + variable + "}}");
      env.addObject()
          .put("name", variable)
          .put("value", "{{secret:" + source.path("passwordRef").asText() + "}}");
    }
    List<String> entries = new ArrayList<>();
    options.forEach(
        (key, value) -> entries.add("  '" + key + "' = '" + value.replace("'", "''") + "'"));
    return result.put("sql", ddl.append(String.join(",\n", entries)).append("\n);\n").toString());
  }

  /** 校验 TCP 地址与端口，阻止把协议、路径或查询参数拼入连接地址。 */
  private void endpoint(String host, String port) {
    if (!host.matches("[A-Za-z0-9][A-Za-z0-9.-]{0,252}")) fail("主机格式无效，请使用 IPv4 或 DNS 名称");
    try {
      int number = Integer.parseInt(port);
      if (number < 1 || number > 65535) fail("端口需为 1–65535");
    } catch (NumberFormatException ex) {
      fail("端口必须是整数");
    }
  }

  /** 校验运行时文件路径；此处不读取后端容器的文件系统。 */
  private void storagePath(String value) {
    try {
      var uri = java.net.URI.create(value);
      if ((uri.getScheme() == null
              || !Set.of("s3", "s3a", "hdfs", "file").contains(uri.getScheme()))
          || uri.getUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null
          || uri.getPath() == null
          || !uri.getPath().startsWith("/")
          || (Set.of("s3", "s3a").contains(uri.getScheme()) && uri.getHost() == null))
        fail("路径需为不含凭据的 s3/s3a/hdfs/file URI");
    } catch (IllegalArgumentException ex) {
      fail("文件路径格式无效");
    }
  }

  /** 映射 PostgreSQL 驱动的 TLS 参数值，保留用户选择的安全级别。 */
  private String postgresSsl(String value) {
    return switch (value) {
      case "REQUIRED" -> "require";
      case "VERIFY_CA" -> "verify-ca";
      case "VERIFY_IDENTITY" -> "verify-full";
      case "DISABLED" -> "disable";
      default -> "prefer";
    };
  }

  /** 校验类型参数范围，禁止字段类型承载额外 SQL 语句。 */
  private boolean validType(String value) {
    if (Set.of(
            "STRING",
            "BYTES",
            "TINYINT",
            "SMALLINT",
            "INT",
            "BIGINT",
            "BOOLEAN",
            "DOUBLE",
            "FLOAT",
            "DATE")
        .contains(value)) return true;
    var decimal =
        java.util.regex.Pattern.compile("DECIMAL\\((\\d{1,2}),(\\d{1,2})\\)").matcher(value);
    if (decimal.matches()) {
      int p = Integer.parseInt(decimal.group(1)), s = Integer.parseInt(decimal.group(2));
      return p >= 1 && p <= 38 && s <= p;
    }
    return value.matches("TIMESTAMP(_LTZ)?\\([0-9]\\)|TIME\\([0-3]\\)");
  }

  /** 返回可在表单展示的校验错误，不包含实际密码。 */
  private void fail(String message) {
    throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_DATASOURCE", message);
  }
}
