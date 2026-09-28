package com.flinkweb.service.sql.catalog.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.audit.AuditService;
import com.flinkweb.service.datasource.DataSourceProbeService;
import com.flinkweb.service.sql.catalog.FlinkCatalogService;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 管理内存、JDBC、Hive 和湖存储 Catalog；凭据复用数据源引用，平台不加载用户 Catalog 插件。 */
@Service
public class FlinkCatalogServiceImpl implements FlinkCatalogService {
  private final RecordRepository records;
  private final ObjectMapper mapper;
  private final AuditService audit;
  private final DataSourceProbeService probes;

  /** 注入元数据、审计和仅按请求执行的数据源探查服务。 */
  public FlinkCatalogServiceImpl(
      RecordRepository records,
      ObjectMapper mapper,
      AuditService audit,
      DataSourceProbeService probes) {
    this.records = records;
    this.mapper = mapper;
    this.audit = audit;
    this.probes = probes;
  }

  /** 返回登记列表，不探测外部 Catalog 或 Kubernetes。 */
  public List<ObjectNode> list() {
    return records.list("flink_catalogs");
  }

  /** 保存强类型目录字段；同名使用修订号避免覆盖。 */
  @Transactional
  public ObjectNode save(String id, ObjectNode input) {
    if (!id.matches("[a-z_][a-z0-9_]{0,63}") || Set.of("default_catalog", "builtin").contains(id))
      fail("Catalog 标识需为小写字母/数字/下划线，不能使用内置名称");
    String type = input.path("type").asText();
    if (!Set.of("memory", "jdbc", "hive", "paimon", "iceberg").contains(type))
      fail("不支持的 Catalog 类型");
    String name = input.path("name").asText().trim();
    if (name.isBlank() || name.length() > 100 || input.path("description").asText().length() > 500)
      fail("请填写名称（最多 100 字）和有效描述");
    String database = input.path("defaultDatabase").asText("default_database").trim();
    identifier(database);
    var node =
        mapper
            .createObjectNode()
            .put("name", name)
            .put("type", type)
            .put("description", input.path("description").asText())
            .put("defaultDatabase", database);
    if (type.equals("jdbc")) {
      String sourceId = input.path("dataSourceId").asText();
      jdbc(sourceId);
      node.put("dataSourceId", sourceId);
    }
    if (type.equals("hive")) {
      String dir = input.path("hiveConfDir").asText().trim();
      if (!dir.matches("/[A-Za-z0-9_./-]{1,255}") || Arrays.asList(dir.split("/")).contains(".."))
        fail("Hive 配置目录需为 Flink 容器内的绝对路径");
      node.put("hiveConfDir", dir);
    }
    if (Set.of("paimon", "iceberg").contains(type)) {
      String warehouse = input.path("warehouse").asText();
      storageUri(warehouse);
      node.put("warehouse", warehouse);
      if (type.equals("iceberg")) node.put("catalogType", "hadoop");
      else node.put("metastore", "filesystem");
    }
    var result = records.save("flink_catalogs", id, node, input.path("revision").asLong(-1));
    audit.record("FLINK_CATALOG_SAVE", id, "SUCCESS");
    return result;
  }

  /** 检查草稿引用后删除登记；不会删除远端库表。 */
  @Transactional
  public void delete(String id, long revision) {
    if (records.list("sql_jobs").stream()
        .anyMatch(s -> id.equals(s.path("runtime").path("catalogId").asText())))
      throw new BusinessException(
          HttpStatus.CONFLICT, "CATALOG_IN_USE", "Catalog 正被 SQL 草稿引用，请先修改草稿的 Catalog 选择");
    records.delete("flink_catalogs", id, revision);
    audit.record("FLINK_CATALOG_DELETE", id, "SUCCESS");
  }

  /** 生成 CREATE/USE 目录和数据库语句，密码始终是环境变量引用。 */
  public ObjectNode snippet(String id, String database) {
    var catalog = records.require("flink_catalogs", id);
    String db =
        database == null || database.isBlank()
            ? catalog.path("defaultDatabase").asText()
            : database;
    identifier(db);
    var result =
        mapper
            .createObjectNode()
            .put("catalogId", id)
            .put("revision", catalog.path("revision").asLong());
    var env = result.putArray("env");
    var options = new LinkedHashMap<String, String>();
    String type = catalog.path("type").asText();
    options.put("type", type.equals("memory") ? "generic_in_memory" : type);
    if (!Set.of("paimon", "iceberg").contains(type))
      options.put("default-database", catalog.path("defaultDatabase").asText());
    if (type.equals("jdbc")) {
      var source = jdbc(catalog.path("dataSourceId").asText());
      String variable =
          "CAT_"
              + HexFormat.of()
                  .formatHex(id.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                  .toUpperCase(Locale.ROOT);
      options.put(
          "base-url",
          "jdbc:"
              + source.path("connectorType").asText()
              + "://"
              + source.path("host").asText()
              + ":"
              + source.path("port").asText());
      options.put("username", source.path("username").asText());
      options.put("password", "{{env:" + variable + "}}");
      env.addObject()
          .put("name", variable)
          .put("value", "{{secret:" + source.path("passwordRef").asText() + "}}");
    } else if (type.equals("hive"))
      options.put("hive-conf-dir", catalog.path("hiveConfDir").asText());
    if (Set.of("paimon", "iceberg").contains(type)) {
      options.put("warehouse", catalog.path("warehouse").asText());
      if (type.equals("iceberg")) options.put("catalog-type", "hadoop");
      else options.put("metastore", "filesystem");
    }
    var lines = new ArrayList<String>();
    options.forEach(
        (key, value) -> lines.add("  '" + key + "' = '" + value.replace("'", "''") + "'"));
    if (type.equals("memory") && !db.equals(catalog.path("defaultDatabase").asText()))
      fail("内存 Catalog 仅支持其默认数据库；其他数据库请在 SQL 中显式创建");
    return result
        .put(
            "sql",
            "CREATE CATALOG `"
                + id
                + "` WITH (\n"
                + String.join(",\n", lines)
                + "\n);\nUSE CATALOG `"
                + id
                + "`;\nUSE `"
                + db
                + "`;\n")
        .put(
            "notice",
            type.equals("memory")
                ? "内存目录随作业生命周期创建，平台不会保存运行时表"
                : "运行镜像需包含匹配版本的 Catalog/Connector、驱动或 Hive 配置；预览不连接远端目录");
  }

  /** 按名称合并环境变量，冲突即拒绝，避免覆盖用户 PodTemplate。 */
  public String prepare(ObjectNode runtime) {
    String id = runtime.path("catalogId").asText();
    if (id.isBlank()) return "";
    var generated = snippet(id, runtime.path("database").asText());
    if (!generated.path("env").isEmpty()) {
      if (runtime.has("podTemplate") && !runtime.path("podTemplate").isObject())
        fail("PodTemplate 必须为对象");
      var pod = runtime.withObject("/podTemplate");
      if (pod.has("spec") && !pod.path("spec").isObject()) fail("PodTemplate.spec 必须为对象");
      var spec = pod.withObject("/spec");
      if (spec.has("containers") && !spec.path("containers").isArray())
        fail("PodTemplate containers 必须为数组");
      var containers = spec.withArray("/containers");
      ObjectNode main = null;
      for (var container : containers) {
        if (!container.isObject()) fail("PodTemplate container 必须为对象");
        if ("flink-main-container".equals(container.path("name").asText())) {
          if (main != null) fail("flink-main-container 重复");
          main = (ObjectNode) container;
        }
      }
      if (main == null) main = containers.addObject().put("name", "flink-main-container");
      if (main.has("env") && !main.path("env").isArray()) fail("PodTemplate env 必须为数组");
      var env = main.withArray("/env");
      for (JsonNode value : generated.path("env")) {
        JsonNode found = null;
        for (JsonNode existing : env)
          if (existing.path("name").equals(value.path("name"))) {
            if (found != null || !existing.equals(value)) fail("Catalog 环境变量与 PodTemplate 冲突");
            found = existing;
          }
        if (found == null) env.add(value.deepCopy());
      }
    }
    return generated.path("sql").asText();
  }

  /** JDBC 元数据显式读取；Hive 插件留在运行镜像，内存目录无远端元数据。 */
  public ObjectNode metadata(String id, String database, String table) {
    var catalog = records.require("flink_catalogs", id);
    if (!"jdbc".equals(catalog.path("type").asText()))
      fail("当前仅 JDBC Catalog 支持远程库表浏览；Hive 在运行镜像中初始化，内存目录无远程表");
    return probes.metadata(catalog.path("dataSourceId").asText(), database, table);
  }

  /** 检查登记的 JDBC 数据源及有效密码配置，返回引用定义而不解密。 */
  private ObjectNode jdbc(String id) {
    var source = records.require("datasources", id);
    if (!Set.of("mysql", "postgresql").contains(source.path("connectorType").asText()))
      fail("JDBC Catalog 必须选择 MySQL 或 PostgreSQL 数据源");
    if (!"PREFERRED".equals(source.path("sslMode").asText("PREFERRED")))
      fail("当前基础 JDBC Catalog 适配使用驱动默认 TLS（PREFERRED）；不支持复用显式 TLS 模式的数据源，请使用建表 SQL 保留该 TLS 配置");
    var secret = records.require("configs", source.path("passwordRef").asText());
    if (!Set.of("secret", "secretRef").contains(secret.path("kind").asText()))
      fail("数据源密码配置必须为密钥引用");
    return source;
  }

  /** 检查仓库 URI，不在平台后端挂载或访问 Flink 运行目录。 */
  private void storageUri(String value) {
    try {
      var uri = java.net.URI.create(value);
      if (value.length() > 500
          || (uri.getScheme() == null
              || !Set.of("s3", "s3a", "hdfs", "file").contains(uri.getScheme()))
          || uri.getUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null
          || uri.getPath() == null
          || !uri.getPath().startsWith("/")
          || (Set.of("s3", "s3a").contains(uri.getScheme()) && uri.getHost() == null))
        fail("Warehouse 需为不含凭据的 s3/s3a/hdfs/file 绝对 URI");
    } catch (IllegalArgumentException ex) {
      fail("Warehouse URI 无效");
    }
  }

  /** JDBC 执行协议探测；其他目录只验证初始化配置，明确报告校验层级。 */
  public ObjectNode test(String id) {
    var catalog = records.require("flink_catalogs", id);
    snippet(id, "");
    if ("jdbc".equals(catalog.path("type").asText()))
      return probes.probe(catalog.path("dataSourceId").asText(), false);
    return mapper
        .createObjectNode()
        .put("level", "CONFIGURATION")
        .put("validated", true)
        .put("checkedAt", java.time.Instant.now().toString())
        .put("message", "初始化配置校验通过；未验证远端服务、存储权限或运行镜像插件");
  }

  /** 限制可引用的数据库标识并统一使用反引号输出。 */
  private void identifier(String value) {
    if (!value.matches("[A-Za-z_][A-Za-z0-9_-]{0,63}")) fail("数据库名称仅允许字母、数字、下划线及连字符，最长 64 字符");
  }

  /** 返回不包含秘密或远程驱动异常的输入错误。 */
  private void fail(String message) {
    throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_FLINK_CATALOG", message);
  }
}
