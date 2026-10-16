package com.flinkweb;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.client.ai.AiModelClient;
import com.flinkweb.entity.job.JobDraft;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.ai.*;
import com.flinkweb.service.ai.support.AiContentGuard;
import com.flinkweb.service.job.creation.JobService;
import com.flinkweb.service.job.runtime.DiagnosticService;
import com.flinkweb.service.permission.SecretTransportService;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 使用真实 SQLite、加密和本地模型协议替身验证完整 AI 安全与产物链路。 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "admin", roles = "ADMIN")
class AiAssistantIntegrationTest {
  static final Path DATABASE;
  static HttpServer server;
  static final AtomicReference<String> answer = new AtomicReference<>();
  static final AtomicReference<String> received = new AtomicReference<>("");
  static final AtomicInteger calls = new AtomicInteger(), delay = new AtomicInteger();

  static {
    try {
      DATABASE = Files.createTempDirectory("flink-ai-test-").resolve("test.db");
    } catch (Exception error) {
      throw new RuntimeException(error);
    }
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired AiModelService models;
  @Autowired AiTaskService tasks;
  @Autowired RecordRepository records;
  @Autowired SecretTransportService transport;
  @Autowired AiContentGuard guard;
  @Autowired AiModelClient client;
  @MockitoBean DiagnosticService diagnostics;
  @MockitoBean JobService jobs;
  String modelId;

  /** 独立测试库与测试主密钥，不读取工作区运行数据库。 */
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("platform.storage.type", () -> "local");
    registry.add("platform.storage.local-path", () -> DATABASE.toString());
    registry.add("platform.storage.initialize", () -> true);
    registry.add("platform.security.password", () -> "Test-password-12345");
    registry.add(
        "platform.security.master-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
  }

  /** 本机服务只接收合成上下文，真实调用适配器与有界 HTTP 响应。 */
  @BeforeAll
  static void startServer() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(
        java.util.concurrent.Executors.newCachedThreadPool(
            r -> {
              var t = new Thread(r);
              t.setDaemon(true);
              return t;
            }));
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          calls.incrementAndGet();
          received.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          String content = answer.get();
          int sleep = delay.get();
          try {
            if (sleep > 0) Thread.sleep(sleep);
            var m = new ObjectMapper();
            var body = m.createObjectNode();
            body.putArray("choices").addObject().putObject("message").put("content", content);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
          } catch (Exception ignored) {
          } finally {
            exchange.close();
          }
        });
    server.start();
  }

  /** 关闭本次协议替身。 */
  @AfterAll
  static void stopServer() {
    server.stop(0);
  }

  /** 每项测试使用独立模型记录，重置合成响应和诊断证据。 */
  @BeforeEach
  void setup() {
    delay.set(0);
    answer.set(
        "{\"summary\":\"原因分析\",\"steps\":[\"检查输入字段\"],\"risks\":[],\"evidence\":[],\"unknowns\":[],\"dml\":\"INSERT"
            + " INTO sink_events SELECT id FROM source_events;\"}");
    modelId = "test-" + UUID.randomUUID();
    if (org.springframework.security.core.context.SecurityContextHolder.getContext()
        .getAuthentication()
        .getAuthorities()
        .stream()
        .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))) models.save(modelId, model(false));
  }

  /** 构造只允许本机协议替身的管理员模型配置。 */
  private ObjectNode model(boolean external) {
    var node =
        mapper
            .createObjectNode()
            .put("name", "测试模型")
            .put("model", "test-model")
            .put("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
            .put("privateNetwork", true)
            .put("external", external)
            .put("enabled", true)
            .put("apiKey", "dummy-ai-key");
    node.putArray("purposes").add("sql").add("definition").add("diagnosis");
    return node;
  }

  /** 当前作业的测试表定义不写共享资产。 */
  private ObjectNode request() throws Exception {
    var node =
        mapper
            .createObjectNode()
            .put("kind", "sql")
            .put("mode", "generate")
            .put("modelId", modelId)
            .put("requestId", UUID.randomUUID().toString())
            .put("instruction", "复制测试数据")
            .put("sql", "")
            .put("flinkVersion", "1.20.5");
    node.putArray("sourceIds");
    node.putObject("spec").put("dedup", "keep");
    node.set(
        "definitions",
        mapper.readTree(
            "[{\"tableName\":\"source_events\",\"connectorType\":\"datagen\",\"rowsPerSecond\":1,\"columns\":[{\"name\":\"id\",\"type\":\"STRING\"}]},{\"tableName\":\"sink_events\",\"connectorType\":\"print\",\"columns\":[{\"name\":\"id\",\"type\":\"STRING\"}]}]"));
    return node;
  }

  /** 等待显式任务终态，不触发平台作业刷新。 */
  private ObjectNode await(String id) throws Exception {
    for (int i = 0; i < 500; i++) {
      var task = tasks.get(id);
      if (!Set.of("QUEUED", "RUNNING").contains(task.path("state").asText())) return task;
      Thread.sleep(10);
    }
    throw new AssertionError("AI task did not finish");
  }

  /** 密钥必须经现有加密信封，所有模型响应都剔除敏感字段。 */
  @Test
  void encryptedModelCrudAndRevision() throws Exception {
    String id = "model-" + UUID.randomUUID();
    var session = new MockHttpSession();
    var input = model(true);
    mvc.perform(
            put("/api/ai/models/" + id)
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(input.toString()))
        .andExpect(status().isBadRequest());
    input.put(
        "apiKey",
        CredentialEnvelopeFixture.encrypt(
            transport.issue(session, "config-secret", id), "transport-key-value"));
    String body =
        mvc.perform(
                put("/api/ai/models/" + id)
                    .session(session)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(input.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.hasKey").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).doesNotContain("transport-key-value", "encryptedKey", "fwenc1:");
    assertThat(records.require("ai_models", id).toString()).doesNotContain("transport-key-value");
    mvc.perform(get("/api/ai/models"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("baseUrl"))));
    mvc.perform(delete("/api/ai/models/" + id).param("revision", "99").with(csrf()))
        .andExpect(status().isConflict());
    mvc.perform(delete("/api/ai/models/" + id).param("revision", "1").with(csrf()))
        .andExpect(status().isOk());
  }

  /** 开发者能选择模型，不能维护或测试管理员配置。 */
  @Test
  @WithMockUser(username = "dev", roles = "DEVELOPER")
  void developerCannotManageModels() throws Exception {
    mvc.perform(get("/api/ai/models?management=true")).andExpect(status().isForbidden());
    mvc.perform(
            put("/api/ai/models/x")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/ai/models/x/test").with(csrf())).andExpect(status().isForbidden());
  }

  /** SQL 产物由确定性表定义和模型 DML 组成，重复请求不重复计费。 */
  @Test
  void generateAndIdempotency() throws Exception {
    var input = request();
    int before = calls.get();
    var created = tasks.create(input);
    var task = await(created.path("id").asText());
    assertThat(task.path("state").asText()).isEqualTo("SUCCEEDED");
    assertThat(task.at("/result/sql").asText())
        .contains("CREATE TEMPORARY TABLE", "INSERT INTO sink_events");
    assertThat(task.at("/result/validationLevel").asText()).isEqualTo("BASIC");
    assertThat(received.get()).contains("test-model").doesNotContain("dummy-ai-key");
    assertThat(tasks.create(input).toString()).doesNotContain("requestHash");
    assertThat(calls.get()).isEqualTo(before + 1);
    assertThat(records.list("datasources")).isEmpty();
  }

  /** 引用内容中的 WITH 不属于参数覆盖，真正的连接选项即使夹有注释也须拒绝。 */
  @Test
  void connectorGuardIgnoresQuotedTextButRejectsOptions() throws Exception {
    for (String dml : java.util.List.of(
        "INSERT INTO sink_events SELECT 'WITH (' FROM source_events",
        "INSERT INTO sink_events SELECT 'it''s WITH (' FROM source_events",
        "CREATE TEMPORARY VIEW v AS SELECT id AS `WITH (` FROM source_events; INSERT INTO sink_events SELECT * FROM v")) {
      answer.set(mapper.createObjectNode().put("summary", "合法引用").put("dml", dml).toString());
      assertThat(await(tasks.create(request()).path("id").asText()).path("state").asText()).isEqualTo("SUCCEEDED");
    }
    answer.set(mapper.createObjectNode().put("dml", "INSERT INTO sink_events /*+ OPTIONS */ SELECT * FROM source_events WITH /* gap */ ('connector'='print')").toString());
    var failed = await(tasks.create(request()).path("id").asText());
    assertThat(failed.path("state").asText()).isEqualTo("FAILED");
    assertThat(failed.toString()).contains("不能覆盖连接器参数");
  }

  /** 外部模型没有逐次授权不能开始任务。 */
  @Test
  void externalContextRequiresConsent() throws Exception {
    var model = records.require("ai_models", modelId);
    model.put("external", true);
    model.remove("encryptedKey");
    models.save(modelId, model);
    assertThatThrownBy(() -> tasks.create(request())).hasMessageContaining("外部模型");
    var input = request().put("allowExternal", true);
    assertThat(await(tasks.create(input).path("id").asText()).path("state").asText())
        .isEqualTo("SUCCEEDED");
  }

  /** SQL 中的明文凭据在模型请求之前被拦截。 */
  @Test
  void rejectCredentialsAndInventedDdl() throws Exception {
    var unsafe = request().put("sql", "CREATE TABLE t WITH ('password'='real-password');");
    assertThatThrownBy(() -> tasks.create(unsafe)).hasMessageContaining("明文凭据");
    answer.set(
        "{\"summary\":\"bad\",\"dml\":\"CREATE TABLE unexpected (id STRING) WITH"
            + " ('connector'='print'); INSERT INTO unexpected VALUES ('x');\"}");
    assertThat(await(tasks.create(request()).path("id").asText()).path("state").asText())
        .isEqualTo("FAILED");
  }

  /** 临时定义缺少连接参数时返回待补项，不产生无效共享数据源。 */
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "[null]",
        "[1]",
        "[true]",
        "[\"text\"]",
        "[{\"tableName\":\"t\",\"connectorType\":\"kafka\",\"columns\":null}]",
        "[{\"tableName\":\"t\",\"connectorType\":\"kafka\",\"columns\":{}}]",
        "[{\"tableName\":\"t\",\"connectorType\":\"kafka\",\"columns\":[null]}]",
        "[{\"tableName\":\"t\",\"connectorType\":\"kafka\",\"columns\":[{\"name\":1,\"type\":\"STRING\"}]}]"
      })
  void malformedDefinitionOutputNeverSucceeds(String definitions) throws Exception {
    answer.set(
        mapper
            .createObjectNode()
            .put("summary", "定义草稿")
            .set("definitions", mapper.readTree(definitions))
            .toString());
    var input = request().put("kind", "definition");
    var result = await(tasks.create(input).path("id").asText());
    assertThat(result.path("state").asText()).isEqualTo("FAILED");
    assertThat(result.has("result")).isFalse();
  }

  /** 未知地址允许空白，结构正常的模型草稿仍可作为建议。 */
  @Test
  void incompleteButStructuredDefinitionStillSucceeds() throws Exception {
    answer.set(
        "{\"summary\":\"待补参数\",\"definitions\":[{\"tableName\":\"orders\",\"connectorType\":\"kafka\",\"brokers\":\"\",\"columns\":[{\"name\":\"id\",\"type\":\"STRING\",\"nullable\":true}]}]}");
    var result = await(tasks.create(request().put("kind", "definition")).path("id").asText());
    assertThat(result.path("state").asText()).isEqualTo("SUCCEEDED");
    assertThat(result.at("/result/definitions/0/brokers").asText()).isEmpty();
  }

  /** 临时定义缺少连接参数时返回待补项，不产生无效共享数据源。 */
  @Test
  void incompleteDefinitionAndStatementLimits() throws Exception {
    var input = mapper.createObjectNode();
    input.set(
        "definitions",
        mapper.readTree(
            "[{\"tableName\":\"orders\",\"connectorType\":\"kafka\",\"columns\":[{\"name\":\"id\",\"type\":\"STRING\"}]}]"));
    var result = tasks.compile(input);
    assertThat(result.path("valid").asBoolean()).isFalse();
    assertThat(result.path("pending").size()).isEqualTo(1);
    assertThat(records.list("datasources")).isEmpty();
  }

  /** 取消后迟到响应不覆盖状态，后续请求可以正常开始。 */
  @Test
  void cancellationKeepsTerminalState() throws Exception {
    delay.set(500);
    var created = tasks.create(request());
    String id = created.path("id").asText();
    tasks.cancel(id);
    Thread.sleep(700);
    assertThat(tasks.get(id).path("state").asText()).isEqualTo("CANCELED");
    delay.set(0);
    assertThat(await(tasks.create(request()).path("id").asText()).path("state").asText())
        .isEqualTo("SUCCEEDED");
  }

  /** 其他用户不能通过任务标识读取或取消产物。 */
  @Test
  void taskOwnershipAndViewerWriteBoundary() throws Exception {
    var task = tasks.create(request());
    String id = task.path("id").asText();
    await(id);
    mvc.perform(get("/api/ai/tasks/" + id).with(user("someone").roles("DEVELOPER")))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/ai/tasks/" + id + "/cancel")
                .with(user("someone").roles("DEVELOPER"))
                .with(csrf()))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/ai/tasks")
                .with(user("reader").roles("VIEWER"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(request().toString()))
        .andExpect(status().isForbidden());
  }

  /** 主动诊断调用已核验日志服务，疑似密码不进入模型。 */
  @Test
  void jobDiagnosisUsesRedactedEvidence() throws Exception {
    String jobId = "job-" + UUID.randomUUID();
    when(jobs.get(jobId))
        .thenReturn(
            new JobDraft(jobId, "orders", "", "1.20.5", "", 1, Instant.now(), Instant.now()));
    var detail =
        mapper.createObjectNode().put("error", "OutOfMemoryError: Metaspace password=unsafe-value");
    detail
        .putArray("pods")
        .addObject()
        .put("name", "orders-jm")
        .putArray("containers")
        .addObject()
        .put("name", "flink-main-container");
    when(diagnostics.inspect(jobId)).thenReturn(detail);
    when(diagnostics.log(eq(jobId), anyString(), anyString(), eq(120), anyBoolean()))
        .thenReturn(mapper.createObjectNode().put("text", "Metaspace exhausted"));
    var input =
        mapper
            .createObjectNode()
            .put("kind", "diagnosis")
            .put("modelId", modelId)
            .put("jobId", jobId)
            .put("requestId", UUID.randomUUID().toString())
            .put("includeLogs", true);
    var result = await(tasks.create(input).path("id").asText());
    assertThat(result.path("state").asText()).isEqualTo("SUCCEEDED");
    assertThat(received.get()).contains("Metaspace", "REDACTED").doesNotContain("unsafe-value");
    verify(diagnostics).log(eq(jobId), anyString(), anyString(), eq(120), anyBoolean());
  }

  /** 网络授权不能允许云元数据目标、重定向凭据或不安全公网 HTTP。 */
  @Test
  void endpointRestrictions() {
    assertThatThrownBy(() -> client.endpoint("http://169.254.169.254/v1", true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> client.endpoint("http://127.0.0.1/v1", false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> client.endpoint("https://user:secret@example.com/v1", true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> client.endpoint("https://example.com/v1?token=value", true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            guard
                .sanitize(mapper.createObjectNode().put("error", "password=secret-value"))
                .isObject())
        .isTrue();
  }

  /** 新向导绑定数据角色与场景，确认单作为产物证据保留，仍不执行 SQL。 */
  @Test
  void wizardCompilesConfirmedRolesAndTimezone() throws Exception {
    var input = wizardRequest();
    var result = await(tasks.create(input).path("id").asText());
    assertThat(result.path("state").asText()).isEqualTo("SUCCEEDED");
    assertThat(result.at("/result/spec/pattern").asText()).isEqualTo("transform");
    assertThat(result.at("/result/flinkConfiguration/table.local-time-zone").asText()).isEqualTo("UTC");
    assertThat(result.path("promptVersion").asText()).isEqualTo("flink-wizard-v2");
    assertThat(received.get()).contains("workflowVersion", "bindings", "source_events");
  }

  /** 不完整角色或偷偷附带窗口参数的确认单在调用模型前终止。 */
  @Test
  void wizardRejectsIncompleteOrConflictingContractBeforeModelCall() throws Exception {
    int before = calls.get();
    var invalid = wizardRequest();
    ((ObjectNode) invalid.at("/spec/bindings/1")).put("role", "source");
    assertThat(await(tasks.create(invalid).path("id").asText()).path("state").asText()).isEqualTo("FAILED");
    var hiddenWindow = wizardRequest();
    hiddenWindow.withObject("/spec").put("windowMinutes", 5);
    assertThat(await(tasks.create(hiddenWindow).path("id").asText()).path("state").asText()).isEqualTo("FAILED");
    assertThat(calls.get()).isEqualTo(before);
  }

  /** 模型不能把已选输入表当作写入目标，即使基础语法合法。 */
  @Test
  void wizardRejectsModelWritingToInputTable() throws Exception {
    answer.set("{\"summary\":\"错误写入目标\",\"dml\":\"INSERT INTO source_events SELECT id FROM source_events;\"}");
    var result = await(tasks.create(wizardRequest()).path("id").asText());
    assertThat(result.path("state").asText()).isEqualTo("FAILED");
    assertThat(result.path("message").asText()).contains("输出表");
    assertThat(result.has("result")).isFalse();
  }

  /** 事件时间必须属于输入表；没有水位线时不能让模型自行推测。 */
  @Test
  void wizardWindowRequiresAnInputWatermark() throws Exception {
    var input = wizardRequest();
    input.withObject("/spec").put("pattern", "window").put("windowMinutes", 5)
        .put("eventTime", "ts").put("eventTable", "source_events").put("outputMode", "window-close");
    assertThat(await(tasks.create(input).path("id").asText()).path("state").asText()).isEqualTo("FAILED");
    input.put("requestId", UUID.randomUUID().toString());
    var source = (ObjectNode) input.withArray("definitions").get(0);
    source.put("watermarkColumn", "ts").put("watermarkDelaySeconds", 60);
    source.withArray("columns").addObject().put("name", "ts").put("type", "TIMESTAMP(3)");
    assertThat(await(tasks.create(input).path("id").asText()).path("state").asText()).isEqualTo("SUCCEEDED");
  }

  /** 生成不含窗口的新版最小确认单，保留旧协议测试独立覆盖。 */
  private ObjectNode wizardRequest() throws Exception {
    var input = request();
    var spec = input.withObject("/spec");
    spec.put("workflowVersion", 2).put("pattern", "transform").put("timezone", "UTC");
    spec.putArray("bindings").addObject().put("tableName", "source_events").put("role", "source");
    spec.withArray("bindings").addObject().put("tableName", "sink_events").put("role", "sink");
    return input;
  }

  /** 已确认的起始位点落实到本作业 DDL，未修改共享数据源。 */
  @Test
  void confirmedStartupIsCompiledAndUnknownTimeIsRejected() throws Exception {
    var input = request();
    var source = (ObjectNode) input.withArray("definitions").get(0);
    source
        .put("connectorType", "kafka")
        .put("brokers", "kafka.test:9092")
        .put("topic", "orders")
        .put("groupId", "test-group")
        .put("format", "json")
        .put("startupMode", "group-offsets");
    input.withObject("/spec").put("startPosition", "earliest-offset");
    var result = await(tasks.create(input).path("id").asText());
    assertThat(result.path("state").asText()).isEqualTo("SUCCEEDED");
    assertThat(result.at("/result/sql").asText())
        .contains("'scan.startup.mode' = 'earliest-offset'");
    var invalid = request();
    invalid.withObject("/spec").put("eventTime", "unregistered_time");
    assertThat(await(tasks.create(invalid).path("id").asText()).path("state").asText())
        .isEqualTo("FAILED");
  }
}
