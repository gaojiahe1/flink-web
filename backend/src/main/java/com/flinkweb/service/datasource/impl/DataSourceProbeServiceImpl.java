package com.flinkweb.service.datasource.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.client.kubernetes.KubernetesClient;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.security.permission.SecretCipher;
import com.flinkweb.service.datasource.DataSourceProbeService;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 仅在用户点击时探查已登记数据源；不执行用户 SQL，不读取业务数据行。 */
@Service
public class DataSourceProbeServiceImpl implements DataSourceProbeService {
  private final RecordRepository records;
  private final SecretCipher cipher;
  private final KubernetesClient kubernetes;
  private final ObjectMapper mapper;
  private final com.flinkweb.service.datasource.DataSourceService definitions;

  /** 注入已登记连接、密钥和 Secret 读取依赖。 */
  public DataSourceProbeServiceImpl(
      RecordRepository records,
      SecretCipher cipher,
      KubernetesClient kubernetes,
      ObjectMapper mapper,
      com.flinkweb.service.datasource.DataSourceService definitions) {
    this.records = records;
    this.cipher = cipher;
    this.kubernetes = kubernetes;
    this.mapper = mapper;
    this.definitions = definitions;
  }

  /** 测试 JDBC/Kafka 协议可用性；MySQL 可加载指定表结构，Kafka 不猜测消息结构。 */
  public ObjectNode probe(String id, boolean schema) {
    var source = definitions.validate(records.require("datasources", id));
    var result = mapper.createObjectNode().put("checkedAt", java.time.Instant.now().toString());
    try {
      String type = source.path("connectorType").asText();
      if (Set.of("filesystem", "datagen", "print").contains(type)) {
        if (schema)
          throw new BusinessException(HttpStatus.BAD_REQUEST, "SCHEMA_UNAVAILABLE", "此类型需手动定义字段");
        return result
            .put("level", "CONFIGURATION")
            .put("validated", true)
            .put(
                "message",
                type.equals("filesystem")
                    ? "路径与格式配置有效；尚未验证运行时文件权限、S3 凭据或插件"
                    : "字段与连接器配置有效；此类型无外部网络连接");
      }
      result.put("level", "PROTOCOL");
      if (Set.of("mysql", "postgresql").contains(type)) {
        try (var connection = connect(source)) {
          if (!connection.isValid(3)) throw new SQLException("Invalid connection");
          result.put("connected", true).put("message", type + " 连接验证通过；没有读取业务行");
          if (schema) {
            result.set(
                "columns",
                columns(
                    connection,
                    source.path("database").asText(),
                    source.path("table").asText(),
                    source));
            result.put("message", "已读取表结构；包含精度、非空和主键信息，请复核后保存");
          }
        }
      } else {
        if (schema)
          throw new BusinessException(
              HttpStatus.BAD_REQUEST,
              "KAFKA_SCHEMA",
              "Kafka 消息字段需由 Schema Registry 或用户定义，平台不采样业务消息");
        Properties options = new Properties();
        options.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, source.path("brokers").asText());
        options.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000");
        options.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "7000");
        options.put(AdminClientConfig.RETRIES_CONFIG, "0");
        var admin = Admin.create(options);
        try {
          var topics =
              admin
                  .describeTopics(List.of(source.path("topic").asText()))
                  .allTopicNames()
                  .get(8, TimeUnit.SECONDS);
          result
              .put("connected", true)
              .put("partitions", topics.get(source.path("topic").asText()).partitions().size())
              .put("message", "Kafka Topic 元数据可访问；未消费消息");
        } finally {
          admin.close(java.time.Duration.ofSeconds(1));
        }
      }
      return result;
    } catch (BusinessException ex) {
      throw ex;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw failed();
    } catch (Exception ex) {
      throw failed();
    }
  }

  /** 显式分层读取库名、表名或字段；每次使用独立有超时的连接，不缓存远端状态。 */
  public ObjectNode metadata(String id, String database, String table) {
    var source = definitions.validate(records.require("datasources", id));
    if (!Set.of("mysql", "postgresql").contains(source.path("connectorType").asText()))
      throw new BusinessException(HttpStatus.BAD_REQUEST, "JDBC_ONLY", "仅 JDBC 数据源支持库表元数据浏览");
    if ((!database.isEmpty() && !database.matches("[A-Za-z_][A-Za-z0-9_-]{0,63}"))
        || (!table.isEmpty()
            && (database.isEmpty()
                || !table.matches(
                    "[A-Za-z_][A-Za-z0-9_$-]{0,63}(\\.[A-Za-z_][A-Za-z0-9_$-]{0,63})?"))))
      throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_IDENTIFIER", "数据库或表名称无效");
    var result =
        mapper
            .createObjectNode()
            .put("checkedAt", java.time.Instant.now().toString())
            .put("truncated", false);
    try (var connection = connect(source)) {
      var meta = connection.getMetaData();
      boolean pg = "postgresql".equals(source.path("connectorType").asText());
      if (pg && !database.isEmpty() && !database.equals(source.path("database").asText()))
        throw new BusinessException(
            HttpStatus.BAD_REQUEST, "DATABASE_SCOPE", "PostgreSQL 仅浏览该连接登记的数据库");

      if (database.isEmpty()) {
        var databases = result.putArray("databases");
        if (pg) databases.add(source.path("database").asText());
        else
          try (var rs = meta.getCatalogs()) {
            while (rs.next()) {
              if (databases.size() == 200) {
                result.put("truncated", true);
                break;
              }
              databases.add(rs.getString("TABLE_CAT"));
            }
          }
      } else if (table.isEmpty()) {
        var tables = result.putArray("tables");
        try (var rs =
            meta.getTables(
                database,
                pg ? source.path("schema").asText("public") : null,
                "%",
                new String[] {"TABLE", "VIEW"})) {
          while (rs.next()) {
            if (tables.size() == 200) {
              result.put("truncated", true);
              break;
            }
            tables
                .addObject()
                .put(
                    "name",
                    (pg ? rs.getString("TABLE_SCHEM") + "." : "") + rs.getString("TABLE_NAME"))
                .put("type", rs.getString("TABLE_TYPE"));
          }
        }
      } else result.set("columns", columns(connection, database, table, source));
      return result;
    } catch (BusinessException ex) {
      throw ex;
    } catch (Exception ex) {
      throw failed();
    }
  }

  /** 读取精确表名的字段、精度、可空性和主键，不执行用户 SQL。 */
  private com.fasterxml.jackson.databind.node.ArrayNode columns(
      Connection connection, String database, String table, ObjectNode source) throws SQLException {
    boolean pg = "postgresql".equals(source.path("connectorType").asText());
    String schema = pg ? source.path("schema").asText("public") : null;
    if (pg && table.contains(".")) {
      String[] parts = table.split("\\.", 2);
      schema = parts[0];
      table = parts[1];
    }
    if (!pg && table.contains("."))
      throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_TABLE", "MySQL 表名不能包含点");
    var meta = connection.getMetaData();
    String escape = meta.getSearchStringEscape();
    String pattern =
        table
            .replace(escape, escape + escape)
            .replace("_", escape + "_")
            .replace("%", escape + "%");
    var keys = new HashSet<String>();
    try (var rs = meta.getPrimaryKeys(database, schema, table)) {
      while (rs.next()) keys.add(rs.getString("COLUMN_NAME"));
    }
    var columns = mapper.createArrayNode();
    try (var rs = meta.getColumns(database, schema, pattern, null)) {
      while (rs.next()) {
        if (columns.size() >= 100)
          throw new BusinessException(
              HttpStatus.BAD_REQUEST, "TOO_MANY_COLUMNS", "表超过 100 列，请手动定义");
        String name = rs.getString("COLUMN_NAME");
        columns
            .addObject()
            .put("name", name)
            .put(
                "type",
                mapType(
                    "timestamptz".equalsIgnoreCase(rs.getString("TYPE_NAME"))
                        ? Types.TIMESTAMP_WITH_TIMEZONE
                        : rs.getInt("DATA_TYPE"),
                    rs.getInt("COLUMN_SIZE"),
                    rs.getInt("DECIMAL_DIGITS")))
            .put("sourceType", rs.getString("TYPE_NAME"))
            .put(
                "nullable",
                rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls && !keys.contains(name))
            .put("primaryKey", keys.contains(name));
      }
    }
    if (columns.isEmpty())
      throw new BusinessException(HttpStatus.NOT_FOUND, "TABLE_NOT_FOUND", "没有找到表字段，请核对表名与元数据权限");
    return columns;
  }

  /** 使用连接属性传递账号密码，避免凭据出现在 JDBC URL 和错误输出中。 */
  private Connection connect(ObjectNode source) throws SQLException {
    Properties options = new Properties();
    options.put("user", source.path("username").asText());
    options.put("password", password(source.path("passwordRef").asText()));
    if ("postgresql".equals(source.path("connectorType").asText())) {
      options.put("connectTimeout", "5");
      options.put("socketTimeout", "7");
      options.put(
          "sslmode",
          switch (source.path("sslMode").asText("PREFERRED")) {
            case "REQUIRED" -> "require";
            case "VERIFY_CA" -> "verify-ca";
            case "VERIFY_IDENTITY" -> "verify-full";
            case "DISABLED" -> "disable";
            default -> "prefer";
          });
      return DriverManager.getConnection(
          "jdbc:postgresql://"
              + source.path("host").asText()
              + ":"
              + source.path("port").asText()
              + "/"
              + source.path("database").asText(),
          options);
    }
    options.put("connectTimeout", "5000");
    options.put("socketTimeout", "7000");
    options.put("allowLoadLocalInfile", "false");
    options.put("allowUrlInLocalInfile", "false");
    options.put("allowMultiQueries", "false");
    options.put("allowPublicKeyRetrieval", "false");
    options.put("sslMode", source.path("sslMode").asText("PREFERRED"));
    return DriverManager.getConnection(
        "jdbc:mysql://"
            + source.path("host").asText()
            + ":"
            + source.path("port").asText()
            + "/"
            + source.path("database").asText(),
        options);
  }

  /** 在服务器内解析密码引用，禁止将密钥返回调用者。 */
  private String password(String id) {
    var config = records.require("configs", id);
    if ("secret".equals(config.path("kind").asText()))
      return cipher.decrypt(id, config.path("encryptedValue").asText());
    if ("secretRef".equals(config.path("kind").asText())) {
      var secret =
          kubernetes.call(
              "GET", kubernetes.core() + "/secrets/" + config.path("secretName").asText(), null);
      String data = secret.path("data").path(config.path("secretKey").asText()).asText();
      if (data.isBlank()) throw failed();
      return new String(Base64.getDecoder().decode(data), java.nio.charset.StandardCharsets.UTF_8);
    }
    throw failed();
  }

  /** 映射基础 Flink 字段类型，保留原始类型供页面复核。 */
  private String mapType(int type, int precision, int scale) {
    return switch (type) {
      case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> "INT";
      case Types.BIGINT -> "BIGINT";
      case Types.BOOLEAN, Types.BIT -> "BOOLEAN";
      case Types.FLOAT, Types.REAL -> "FLOAT";
      case Types.DOUBLE -> "DOUBLE";
      case Types.DECIMAL, Types.NUMERIC ->
          precision > 0 && precision <= 38 && scale >= 0 && scale <= precision
              ? "DECIMAL(" + precision + "," + scale + ")"
              : "STRING";
      case Types.DATE -> "DATE";
      case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> "BYTES";
      case Types.TIME -> "TIME(" + Math.max(0, Math.min(3, scale)) + ")";
      case Types.TIMESTAMP_WITH_TIMEZONE ->
          "TIMESTAMP_LTZ(" + Math.max(0, Math.min(6, scale)) + ")";
      case Types.TIMESTAMP -> "TIMESTAMP(" + Math.max(0, Math.min(6, scale)) + ")";
      default -> "STRING";
    };
  }

  /** 对连接失败统一脱敏，不返回驱动异常中的密码或服务端报文。 */
  private BusinessException failed() {
    return new BusinessException(
        HttpStatus.BAD_GATEWAY, "DATASOURCE_UNAVAILABLE", "连接或元数据读取失败，请检查网络、凭据、TLS 和目标权限");
  }
}
