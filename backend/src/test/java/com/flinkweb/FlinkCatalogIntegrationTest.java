package com.flinkweb;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.client.kubernetes.KubernetesClient;
import com.flinkweb.dto.sql.SqlDraftRequest;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.catalog.CatalogService;
import com.flinkweb.service.datasource.DataSourceService;
import com.flinkweb.service.sql.SqlJobService;
import com.flinkweb.service.sql.SqlValidationService;
import com.flinkweb.service.sql.catalog.FlinkCatalogService;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 通过真实 SQLite 与 MVC 验证 Catalog 生命周期、发布链、权限和无明文密钥约束。 */
@SpringBootTest
@AutoConfigureMockMvc
class FlinkCatalogIntegrationTest {
  static final Path DATABASE;

  static {
    try {
      DATABASE = Files.createTempDirectory("catalog-test-").resolve("test.db");
    } catch (Exception ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  /** 使用隔离的元数据库与测试专用安全配置。 */
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("platform.artifacts.downloader-image", () -> "registry.test/downloader:current");
    registry.add("platform.storage.local-path", () -> DATABASE.toString());
    registry.add("platform.security.password", () -> "Test-password-12345");
    registry.add(
        "platform.security.master-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
  }

  @Autowired ObjectMapper mapper;
  @Autowired RecordRepository records;
  @Autowired FlinkCatalogService catalogs;
  @Autowired CatalogService resources;
  @Autowired SqlJobService sql;
  @Autowired DataSourceService sources;
  @Autowired SqlValidationService validation;
  @Autowired MockMvc mvc;
  @Autowired com.flinkweb.service.datasource.DataSourceProbeService probes;
  @Autowired com.flinkweb.service.network.NetworkProbeService network;
  @MockitoBean KubernetesClient kubernetes;

  /** 每个测试建立明确管理员身份，真实权限过滤器另由 MVC 用例检查。 */
  @BeforeEach
  void actor() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "admin", "", AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
  }

  /** 清理线程身份，防止测试间串用认证上下文。 */
  @AfterEach
  void cleanup() {
    SecurityContextHolder.clearContext();
  }

  /** 生成合法且独立的 SQL 目录标识。 */
  String id() {
    return "cat_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  }

  /** 创建最小内存 Catalog 定义。 */
  ObjectNode memory() {
    return mapper
        .createObjectNode()
        .put("name", "测试目录")
        .put("type", "memory")
        .put("defaultDatabase", "analytics")
        .put("revision", 0);
  }

  /** 登记包含 Secret 引用的 MySQL 数据源，测试过程不连接数据库。 */
  String mysql() {
    String id = id();
    resources.save(
        "configs",
        id,
        mapper
            .createObjectNode()
            .put("kind", "secret")
            .put("value", "only-test-secret")
            .put("revision", 0));
    var source =
        mapper
            .createObjectNode()
            .put("name", "mysql")
            .put("connectorType", "mysql")
            .put("tableName", "orders")
            .put("host", "mysql.test")
            .put("port", "3306")
            .put("database", "sales")
            .put("table", "orders")
            .put("username", "flink")
            .put("passwordRef", id)
            .put("revision", 0);
    source
        .putArray("columns")
        .addObject()
        .put("name", "id")
        .put("type", "BIGINT")
        .put("primaryKey", true);
    resources.save("datasources", id, source);
    return id;
  }

  /** 登记修订冲突、非法标识和目录操作权限均在后端校验。 */
  @Test
  void revisionAndPermissions() throws Exception {
    String id = id();
    catalogs.save(id, memory());
    assertThatThrownBy(() -> catalogs.save(id, memory())).isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> catalogs.save("bad-name", memory()))
        .isInstanceOf(BusinessException.class);
    mvc.perform(
            put("/api/sql/catalogs/" + id())
                .with(user("viewer").roles("VIEWER"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(memory().toString()))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/sql/catalogs").with(user("viewer").roles("VIEWER")))
        .andExpect(status().isOk());
    mvc.perform(
            post("/api/sql/catalogs/" + id + "/metadata")
                .with(user("viewer").roles("VIEWER"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(kubernetes);
  }

  /** SQL 保存、历史恢复、检查和 YAML 预览共同保留目录上下文，初始化排在 INSERT 前。 */
  @Test
  void sqlContextSurvivesSaveRestoreAndPreview() {
    String id = id(), job = "sql-" + id.replace("_", "-"), image = id();
    catalogs.save(id, memory());
    records.save(
        "images",
        image,
        mapper
            .createObjectNode()
            .put("repository", "registry.test/flink")
            .put("version", "1.20.3")
            .put("flinkVersion", "1.20.3"),
        0);
    var runtime =
        mapper
            .createObjectNode()
            .put("imageId", image)
            .put("catalogId", id)
            .put("database", "analytics")
            .put("parallelism", 1);
    String script =
        "CREATE TEMPORARY TABLE sink_table (n INT) WITH ('connector'='blackhole'); INSERT INTO"
            + " sink_table VALUES (1);";
    var input = new SqlDraftRequest(job, "", script, runtime, 0);
    records.save("artifacts", "runner-" + job, mapper.createObjectNode()
        .put("filename", "flink-web-sql-runner.jar").put("uri", "https://artifacts.test/flink-web-sql-runner.jar"), 0);
    runtime.put("runnerArtifactId", "runner-" + job);
    var saved = sql.save(job, input);
    assertThat(sql.check(script, runtime).statementCount()).isEqualTo(5);
    String yaml = sql.preview(input).path("yaml").asText();
    assertThat(yaml)
        .contains(
            Base64.getEncoder()
                .encodeToString(
                    ("USE CATALOG `" + id + "`")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    assertThatThrownBy(() -> catalogs.delete(id, 1))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("引用");
    String version = sql.versions(job).get(0).path("id").asText();
    var restored = sql.restoreVersion(job, version, saved.path("revision").asLong());
    assertThat(restored.path("runtime").path("catalogId").asText()).isEqualTo(id);
    sql.delete(job, restored.path("revision").asLong());
    catalogs.delete(id, 1);
    verifyNoInteractions(kubernetes);
  }

  /** JDBC 初始化只携带引用，环境变量冲突拒绝，正在使用的数据源不可删除。 */
  @Test
  void jdbcReferencesSecretsAndProtectsDependencies() {
    String source = mysql(), id = id();
    catalogs.save(
        id,
        memory().put("type", "jdbc").put("dataSourceId", source).put("defaultDatabase", "sales"));
    var snippet = catalogs.snippet(id, "sales");
    assertThat(snippet.toString())
        .contains("{{env:CAT_", "{{secret:" + source + "}}")
        .doesNotContain("only-test-secret");
    assertThat(snippet.path("sql").asText())
        .contains("'base-url' = 'jdbc:mysql://mysql.test:3306'");
    var runtime = mapper.createObjectNode().put("catalogId", id);
    catalogs.prepare(runtime);
    String first = runtime.toString();
    catalogs.prepare(runtime);
    assertThat(runtime.toString()).isEqualTo(first);
    ((ObjectNode)
            runtime.path("podTemplate").path("spec").path("containers").get(0).path("env").get(0))
        .put("value", "collision");
    assertThatThrownBy(() -> catalogs.prepare(runtime))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("冲突");
    assertThatThrownBy(() -> resources.delete("datasources", source, 1))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("引用");
    var registered = records.require("datasources", source);
    registered.put("sslMode", "REQUIRED");
    records.save("datasources", source, registered, 1);
    assertThatThrownBy(() -> catalogs.snippet(id, "sales"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("TLS");
    verifyNoInteractions(kubernetes);
  }

  /** 可选 Connector 参数、字段精度、主键和 Watermark 能生成完整 DDL，并拒绝注入。 */
  @Test
  void schemaAndStartupGenerateSafeSql() {
    String source = mysql();
    String ddl = sources.snippet(source).path("sql").asText();
    assertThat(ddl).contains("PRIMARY KEY (`id`) NOT ENFORCED", "BIGINT NOT NULL");
    String id = id();
    var input =
        mapper
            .createObjectNode()
            .put("name", "events")
            .put("connectorType", "kafka")
            .put("tableName", "events")
            .put("brokers", "kafka:9092")
            .put("topic", "events")
            .put("groupId", "flink")
            .put("format", "json")
            .put("startupMode", "timestamp")
            .put("startupTimestamp", 123456789L)
            .put("watermarkColumn", "event_time")
            .put("watermarkDelaySeconds", 5)
            .put("revision", 0);
    var fields = input.putArray("columns");
    fields.addObject().put("name", "amount").put("type", "DECIMAL(20,4)");
    fields.addObject().put("name", "event_time").put("type", "TIMESTAMP(3)");
    resources.save("datasources", id, input);
    ddl = sources.snippet(id).path("sql").asText();
    assertThat(ddl)
        .contains(
            "DECIMAL(20,4)",
            "WATERMARK FOR `event_time`",
            "'scan.startup.timestamp-millis' = '123456789'");
    ((ObjectNode) fields.get(0)).put("type", "STRING); DROP TABLE secret");
    assertThatThrownBy(() -> sources.validate(input)).isInstanceOf(BusinessException.class);
    ((ObjectNode) fields.get(0)).put("type", "DECIMAL(10,20)");
    assertThatThrownBy(() -> sources.validate(input)).isInstanceOf(BusinessException.class);
    verifyNoInteractions(kubernetes);
  }

  /** Catalog 初始化必须在全部 INSERT 之前；无执行写入和破坏性语句仍然被拒绝。 */
  @Test
  void rejectsUnsafeStatementOrderAndCommands() {
    assertThatThrownBy(() -> validation.validate("INSERT INTO t VALUES (1); USE CATALOG c;"))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> validation.validate("DROP CATALOG c; INSERT INTO t VALUES (1);"))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(
            () -> validation.validate("CREATE CATALOG c WITH ('type'='generic_in_memory');"))
        .isInstanceOf(BusinessException.class);
    String id = id();
    catalogs.save(id, memory());
    assertThatThrownBy(() -> catalogs.snippet(id, "bad'; DROP"))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> catalogs.metadata(id, "", ""))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("JDBC");
  }

  /** 使用 JDBC 协议边界替身验证库、表和字段查询、精度映射及连接释放，不读取业务行。 */
  @Test
  void metadataUsesBoundedJdbcApiAndPreservesPrecision() throws Exception {
    String source = mysql(), id = id();
    catalogs.save(
        id,
        memory().put("type", "jdbc").put("dataSourceId", source).put("defaultDatabase", "sales"));
    var connection = mock(java.sql.Connection.class);
    var metadata = mock(java.sql.DatabaseMetaData.class);
    when(connection.getMetaData()).thenReturn(metadata);
    when(metadata.getSearchStringEscape()).thenReturn("\\\\");
    var databases = mock(java.sql.ResultSet.class);
    when(databases.next()).thenReturn(true, false);
    when(databases.getString("TABLE_CAT")).thenReturn("sales");
    when(metadata.getCatalogs()).thenReturn(databases);
    var tables = mock(java.sql.ResultSet.class);
    when(tables.next()).thenReturn(true, false);
    when(tables.getString("TABLE_NAME")).thenReturn("order_items");
    when(tables.getString("TABLE_TYPE")).thenReturn("TABLE");
    when(metadata.getTables(
            org.mockito.ArgumentMatchers.eq("sales"),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq("%"),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(tables);
    var keys = mock(java.sql.ResultSet.class);
    when(keys.next()).thenReturn(true, false);
    when(keys.getString("COLUMN_NAME")).thenReturn("amount");
    when(metadata.getPrimaryKeys("sales", null, "order_items")).thenReturn(keys);
    var columns = mock(java.sql.ResultSet.class);
    when(columns.next()).thenReturn(true, false);
    when(columns.getString("COLUMN_NAME")).thenReturn("amount");
    when(columns.getString("TYPE_NAME")).thenReturn("DECIMAL");
    when(columns.getInt("DATA_TYPE")).thenReturn(java.sql.Types.DECIMAL);
    when(columns.getInt("COLUMN_SIZE")).thenReturn(20);
    when(columns.getInt("DECIMAL_DIGITS")).thenReturn(4);
    when(metadata.getColumns(
            org.mockito.ArgumentMatchers.eq("sales"),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.isNull()))
        .thenReturn(columns);
    try (var drivers = mockStatic(java.sql.DriverManager.class)) {
      drivers
          .when(
              () ->
                  java.sql.DriverManager.getConnection(
                      org.mockito.ArgumentMatchers.anyString(),
                      org.mockito.ArgumentMatchers.any(java.util.Properties.class)))
          .thenReturn(connection);
      assertThat(catalogs.metadata(id, "", "").path("databases").get(0).asText())
          .isEqualTo("sales");
      assertThat(catalogs.metadata(id, "sales", "").path("tables").get(0).path("name").asText())
          .isEqualTo("order_items");
      var field = catalogs.metadata(id, "sales", "order_items").path("columns").get(0);
      assertThat(field.path("type").asText()).isEqualTo("DECIMAL(20,4)");
      assertThat(field.path("primaryKey").asBoolean()).isTrue();
      assertThat(field.path("nullable").asBoolean()).isFalse();
      verify(connection, times(3)).close();
      verify(connection, never()).createStatement();
      assertThatThrownBy(() -> catalogs.metadata(id, "sales;drop", ""))
          .isInstanceOf(BusinessException.class);
    }
  }

  /** 数据源扩展必须生成相应 DDL，且配置校验不能伪装为连接成功。 */
  @Test
  void newSourceTypesHaveDistinctValidationAndDdl() {
    var input = mapper.createObjectNode().put("tableName", "events").put("revision", 0);
    input.putArray("columns").addObject().put("name", "id").put("type", "BIGINT");
    for (String type : List.of("datagen", "print", "filesystem")) {
      String source = id();
      input
          .put("connectorType", type)
          .put("rowsPerSecond", 10)
          .put("path", "s3://bucket/data/")
          .put("format", "json");
      resources.save("datasources", source, input);
      assertThat(sources.snippet(source).path("sql").asText())
          .contains("'connector' = '" + type + "'");
      var checked = probes.probe(source, false);
      assertThat(checked.path("level").asText()).isEqualTo("CONFIGURATION");
      assertThat(checked.has("connected")).isFalse();
    }
    input.put("connectorType", "filesystem").put("path", "s3://user:password@bucket/data");
    assertThatThrownBy(() -> sources.validate(input)).isInstanceOf(BusinessException.class);
    input.put("path", "relative/path");
    assertThatThrownBy(() -> sources.validate(input)).isInstanceOf(BusinessException.class);
    input.put("connectorType", "upsert-kafka").put("brokers", "broker:9092").put("topic", "events");
    assertThatThrownBy(() -> sources.validate(input)).hasMessageContaining("主键");
    ((ObjectNode) input.path("columns").get(0)).put("primaryKey", true);
    String upsert = id();
    resources.save("datasources", upsert, input);
    assertThat(sources.snippet(upsert).path("sql").asText())
        .contains("'key.format' = 'json'", "'value.format' = 'json'")
        .doesNotContain("'scan.startup.mode'");
    for (String brokers :
        List.of("broker:0", "broker:65536", "broker:3.5", "broker:9092,", "http://host:80")) {
      input.put("brokers", brokers);
      assertThatThrownBy(() -> sources.validate(input)).isInstanceOf(BusinessException.class);
    }
    verifyNoInteractions(kubernetes);
  }

  /** PostgreSQL DDL 与 Catalog 使用独立协议、Schema 和密钥引用，不复用 MySQL TLS 值。 */
  @Test
  void postgresSourceAndCatalogKeepRuntimeSemantics() {
    String source = mysql(), catalog = id();
    var input = records.require("datasources", source);
    input
        .put("connectorType", "postgresql")
        .put("port", "5432")
        .put("schema", "analytics")
        .put("sslMode", "VERIFY_IDENTITY");
    resources.save("datasources", source, input);
    assertThat(sources.snippet(source).path("sql").asText())
        .contains(
            "jdbc:postgresql://mysql.test:5432/sales?sslmode=verify-full",
            "'table-name' = 'analytics.orders'");
    assertThatThrownBy(
            () -> catalogs.save(catalog, memory().put("type", "jdbc").put("dataSourceId", source)))
        .hasMessageContaining("TLS");
    input = records.require("datasources", source).put("sslMode", "PREFERRED");
    resources.save("datasources", source, input);
    catalogs.save(
        catalog,
        memory().put("type", "jdbc").put("dataSourceId", source).put("defaultDatabase", "sales"));
    assertThat(catalogs.snippet(catalog, "").path("sql").asText())
        .contains("'base-url' = 'jdbc:postgresql://mysql.test:5432'")
        .doesNotContain("only-test-secret");
  }

  /** 湖目录配置生成可审查 SQL，测试层级明确，不连接未知文件系统。 */
  @Test
  void lakeCatalogsGenerateInitializationAndConfigurationTests() {
    for (String type : List.of("paimon", "iceberg")) {
      String id = id();
      catalogs.save(
          id,
          memory()
              .put("type", type)
              .put("warehouse", "s3://warehouse/flink/")
              .put("defaultDatabase", "default"));
      String ddl = catalogs.snippet(id, "").path("sql").asText();
      assertThat(ddl).contains("'type' = '" + type + "'", "'warehouse' = 's3://warehouse/flink/'");
      assertThat(ddl)
          .contains(
              type.equals("iceberg") ? "'catalog-type' = 'hadoop'" : "'metastore' = 'filesystem'");
      assertThat(catalogs.test(id).path("level").asText()).isEqualTo("CONFIGURATION");
    }
    assertThatThrownBy(
            () ->
                catalogs.save(
                    id(),
                    memory()
                        .put("type", "iceberg")
                        .put("warehouse", "https://user:secret@host/path")))
        .isInstanceOf(BusinessException.class);
    verifyNoInteractions(kubernetes);
  }

  /** 端口探测校验权限、非法数字以及真实本地监听和关闭端口，结果不等同协议认证。 */
  @Test
  void networkProbeIsBoundedValidatedAndAdminOnly() throws Exception {
    mvc.perform(
            post("/api/platform/network/test")
                .with(user("viewer").roles("VIEWER"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"host\":\"localhost\",\"port\":3306}"))
        .andExpect(status().isForbidden());
    for (String port : List.of("0", "65536", "2.5", "null")) {
      mvc.perform(
              post("/api/platform/network/test")
                  .with(user("admin").roles("ADMIN"))
                  .with(csrf())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"host\":\"localhost\",\"port\":" + port + "}"))
          .andExpect(status().isBadRequest());
    }
    assertThatThrownBy(() -> network.test("http://host/path", 80))
        .isInstanceOf(BusinessException.class);
    int closed;
    try (var server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
      closed = server.getLocalPort();
      var result = network.test("localhost", closed);
      assertThat(result.path("connected").asBoolean()).isTrue();
      assertThat(result.path("level").asText()).isEqualTo("TCP");
    }
    assertThat(network.test("localhost", closed).path("connected").asBoolean()).isFalse();
  }

  /** 镜像旧 digest 不再影响新表单渲染，也不会回传页面。 */
  @Test
  void imageRegistryIgnoresRemovedDigestField() {
    String id = id();
    var image =
        mapper
            .createObjectNode()
            .put("repository", "registry/flink")
            .put("version", "1.20-custom")
            .put("flinkVersion", "1.20.3")
            .put("digest", "sha256:" + "a".repeat(64))
            .put("revision", 0);
    resources.save("images", id, image);
    assertThat(records.require("images", id).has("digest")).isFalse();
    assertThat(resources.get("images", id).has("digest")).isFalse();
  }

  /** PostgreSQL 浏览保持数据库与 Schema 语义，连接属性使用秒级超时及原始 TLS 配置。 */
  @Test
  void postgresMetadataUsesQualifiedTableAndSafeConnectionProperties() throws Exception {
    String source = mysql();
    var input =
        records
            .require("datasources", source)
            .put("connectorType", "postgresql")
            .put("port", "5432")
            .put("schema", "public")
            .put("sslMode", "REQUIRED");
    resources.save("datasources", source, input);
    var connection = mock(java.sql.Connection.class);
    var metadata = mock(java.sql.DatabaseMetaData.class);
    when(connection.getMetaData()).thenReturn(metadata);
    when(metadata.getSearchStringEscape()).thenReturn("\\");
    var keys = mock(java.sql.ResultSet.class);
    when(keys.next()).thenReturn(false);
    when(metadata.getPrimaryKeys("sales", "public", "orders")).thenReturn(keys);
    var columns = mock(java.sql.ResultSet.class);
    when(columns.next()).thenReturn(true, false);
    when(columns.getString("COLUMN_NAME")).thenReturn("created_at");
    when(columns.getString("TYPE_NAME")).thenReturn("timestamptz");
    when(columns.getInt("DATA_TYPE")).thenReturn(java.sql.Types.TIMESTAMP);
    when(columns.getInt("DECIMAL_DIGITS")).thenReturn(3);
    when(metadata.getColumns("sales", "public", "orders", null)).thenReturn(columns);
    try (var drivers = mockStatic(java.sql.DriverManager.class)) {
      drivers
          .when(
              () ->
                  java.sql.DriverManager.getConnection(
                      anyString(), any(java.util.Properties.class)))
          .thenAnswer(
              call -> {
                assertThat((String) call.getArgument(0))
                    .isEqualTo("jdbc:postgresql://mysql.test:5432/sales");
                java.util.Properties properties = call.getArgument(1);
                assertThat(properties.getProperty("sslmode")).isEqualTo("require");
                assertThat(properties.getProperty("connectTimeout")).isEqualTo("5");
                assertThat(properties.getProperty("socketTimeout")).isEqualTo("7");
                return connection;
              });
      assertThat(
              probes
                  .metadata(source, "sales", "public.orders")
                  .path("columns")
                  .get(0)
                  .path("type")
                  .asText())
          .isEqualTo("TIMESTAMP_LTZ(3)");
      assertThatThrownBy(() -> probes.metadata(source, "another", "public.orders"))
          .hasMessageContaining("登记的数据库");
      verify(connection, times(2)).close();
      verify(connection, never()).createStatement();
    }
  }
}
