package com.flinkweb;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.flinkweb.client.flink.SessionRestClient;
import com.flinkweb.client.kubernetes.KubernetesClient;
import com.flinkweb.dto.job.creation.DraftRequest;
import com.flinkweb.dto.session.SessionRequest;
import com.flinkweb.dto.sql.SqlDraftRequest;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.job.creation.*;
import com.flinkweb.service.session.SessionClusterService;
import com.flinkweb.service.sql.*;
import com.flinkweb.service.sql.support.ValidationJarBundle;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 在真实 SQLite 与权限过滤下验证 Session 生命周期、开发映射及 SQL 验证边界。 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class SessionDevelopmentIntegrationTest {
  static final Path DATABASE;

  static {
    try {
      DATABASE = Files.createTempDirectory("session-development-test").resolve("test.db");
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }

  /** 使用隔离数据库和仅供测试的凭据。 */
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("platform.storage.local-path", () -> DATABASE.toString());
    registry.add("platform.security.password", () -> "Test-password-12345");
    registry.add(
        "platform.security.master-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    registry.add("platform.artifacts.downloader-image", () -> "registry.test/downloader:current");
  }

  @Autowired RecordRepository records;
  @Autowired ObjectMapper mapper;
  @Autowired SessionClusterService sessions;
  @Autowired SqlJobService sql;
  @Autowired SqlExecutionService executions;
  @Autowired JobService jobs;
  @Autowired DevelopmentService development;
  @Autowired YamlService yaml;
  @Autowired MockMvc mvc;
  @MockitoBean KubernetesClient kubernetes;
  @MockitoBean SessionRestClient rest;
  @MockitoBean ValidationJarBundle bundles;
  final AtomicReference<ObjectNode> cluster = new AtomicReference<>();
  String image, runner;

  /** 每次建立独立镜像并模拟异步资源；元数据保存和乐观锁使用真实数据库。 */
  @BeforeEach
  void setup() throws Exception {
    for (String collection : List.of("sql_validations", "sessions"))
      for (var record : records.list(collection))
        records.delete(collection, record.path("id").asText(), record.path("revision").asLong());
    cluster.set(null);
    image = "image-" + UUID.randomUUID();
    runner = "runner-" + UUID.randomUUID();
    records.save(
        "images",
        image,
        mapper
            .createObjectNode()
            .put("repository", "registry.test/flink")
            .put("version", "1.20.3")
            .put("flinkVersion", "1.20.3"),
        0);
    records.save(
        "artifacts",
        runner,
        mapper
            .createObjectNode()
            .put("filename", "flink-web-sql-runner.jar")
            .put("uri", "https://artifacts.test/flink-web-sql-runner.jar"),
        0);
    when(kubernetes.base()).thenReturn("/deployments");
    when(kubernetes.core()).thenReturn("/core");
    when(kubernetes.call(anyString(), anyString(), nullable(JsonNode.class)))
        .thenAnswer(
            invocation -> {
              String method = invocation.getArgument(0), path = invocation.getArgument(1);
              JsonNode body = invocation.getArgument(2);
              if (method.equals("GET"))
                return cluster.get() == null ? MissingNode.getInstance() : cluster.get().deepCopy();
              if (method.equals("DELETE")) {
                cluster.set(null);
                return mapper.createObjectNode();
              }
              if (method.equals("POST")) {
                var resource = ((ObjectNode) body).deepCopy();
                resource
                    .withObject("/metadata")
                    .put("uid", "test-session-uid")
                    .put("resourceVersion", "1");
                if (!path.contains("dryRun")) {
                  resource.withObject("/status").put("jobManagerDeploymentStatus", "READY");
                  cluster.set(resource);
                }
                return resource;
              }
              return mapper.createObjectNode();
            });
    when(bundles.build(anyList()))
        .thenAnswer(invocation -> Files.createTempFile("validation-test-", ".jar"));
    when(rest.upload(anyString(), any()))
        .thenReturn(mapper.createObjectNode().put("filename", "/tmp/upload_sql-validation.jar"));
    when(rest.call(anyString(), anyString(), anyString(), nullable(JsonNode.class)))
        .thenReturn(mapper.createObjectNode().put("state", "RUNNING"));
  }

  /** 最小运行配置复用普通 Flink 镜像与挂载 Runner。 */
  ObjectNode runtime() {
    return mapper
        .createObjectNode()
        .put("imageId", image)
        .put("runnerArtifactId", runner)
        .put("parallelism", 1)
        .put("slots", 2);
  }

  /** 验证专用 Session 必须显式选择 Runner，普通 Session 可不挂载 Runner。 */
  @Test
  void validationRunnerRequiresExplicitSelection() {
    var runtime = runtime();
    runtime.remove("runnerArtifactId");
    assertThatThrownBy(
            () ->
                sessions.save(
                    "sql-validation", new SessionRequest("validation", "", true, runtime, 0)))
        .hasMessageContaining("runnerArtifactId");
    var saved = sessions.save("ordinary", new SessionRequest("ordinary", "", false, runtime, 0));
    sessions.start("ordinary", saved.path("revision").asLong());
    assertThat(cluster.get().at("/spec/job").isMissingNode()).isTrue();
    assertThat(cluster.get().toString()).doesNotContain("flink-web-sql-runner.jar");
  }

  /** 开发标记来自当前资源而非历史部署记录，并识别同名外部资源。 */
  @Test
  void jarDeploymentStatusComesFromCurrentResourceIdentity() {
    String name = "jar-status-" + UUID.randomUUID().toString().substring(0, 8);
    var job =
        jobs.create(
            new DraftRequest(
                name,
                "",
                "1.20.3",
                "kind: FlinkDeployment\nmetadata:\n  name: "
                    + name
                    + "\nspec:\n  image: flink:1.20.3\n",
                0));
    var response = mapper.createObjectNode();
    var resource = response.putArray("items").addObject();
    resource.withObject("/metadata").put("name", job.deploymentName()).put("uid", "fresh-resource");
    resource.withObject("/metadata/labels").put("flink-web/job-id", job.id());
    when(kubernetes.call("GET", "/deployments", null)).thenReturn(response);
    assertThat(development.deploymentStatus().path(job.id()).asText()).isEqualTo("DEPLOYED");
    resource.withObject("/metadata/labels").remove("flink-web/job-id");
    assertThat(development.deploymentStatus().path(job.id()).asText()).isEqualTo("CONFLICT");
    response.putArray("items");
    assertThat(development.deploymentStatus().path(job.id()).asText()).isEqualTo("UNDEPLOYED");
    when(kubernetes.call("GET", "/deployments", null)).thenReturn(MissingNode.getInstance());
    assertThatThrownBy(() -> development.deploymentStatus()).hasMessageContaining("无法读取");
  }

  /** 创建并启动唯一验证 Session。 */
  ObjectNode startCluster() {
    var saved =
        sessions.save("sql-validation", new SessionRequest("validation", "", true, runtime(), 0));
    return sessions.start("sql-validation", saved.path("revision").asLong());
  }

  /** 保存可以通过检查的 SQL 草稿。 */
  ObjectNode draft() {
    String name = "sql-" + UUID.randomUUID().toString().substring(0, 8);
    return sql.save(
        name,
        new SqlDraftRequest(
            name,
            "",
            "CREATE TABLE sink (n INT) WITH ('connector'='blackhole'); INSERT INTO sink VALUES"
                + " (1);",
            runtime(),
            0));
  }

  /** SQL 关联在部署草稿删除后同时清理，重新发布仍可建立独立资源。 */
  @Test
  void deletedDeploymentClearsSqlLinkAndRepublishWorks() {
    var saved = draft();
    String id = saved.path("id").asText();
    var job = sql.publish(id, 1);
    assertThat(development.links().path(job.id()).path("sqlId").asText()).isEqualTo(id);
    assertThat(development.jarJobs()).noneMatch(j -> j.id().equals(job.id()));
    jobs.delete(job.id(), job.revision());
    var cleared = sql.get(id);
    assertThat(cleared.has("jobId")).isFalse();
    assertThat(cleared.path("revision").asLong()).isEqualTo(3);
    var next = sql.publish(id, 3);
    assertThat(next.id()).isNotEqualTo(job.id());
  }

  /** SQL 删除保护关联部署和修订号；移除关联记录后可从开发区删除草稿。 */
  @Test
  void sqlDraftDeletionHonorsRevisionAndDeploymentLink() throws Exception {
    var saved = draft();
    String id = saved.path("id").asText();
    var job = sql.publish(id, 1);
    var unpublished = draft();
    mvc.perform(
            delete("/api/sql/jobs/" + unpublished.path("id").asText())
                .param("revision", "99")
                .with(csrf()))
        .andExpect(status().isConflict());
    mvc.perform(delete("/api/sql/jobs/" + id).param("revision", "2").with(csrf()))
        .andExpect(status().isConflict());
    mvc.perform(get("/api/sql/jobs/" + id)).andExpect(jsonPath("$.data.jobId").value(job.id()));
    mvc.perform(
            delete("/api/jobs/" + job.id() + "/draft")
                .param("revision", String.valueOf(job.revision()))
                .with(csrf()))
        .andExpect(status().isOk());
    mvc.perform(delete("/api/sql/jobs/" + id).param("revision", "3").with(csrf()))
        .andExpect(status().isOk());
    mvc.perform(get("/api/sql/jobs/" + id)).andExpect(status().isNotFound());
  }

  /** 唯一 Session 使用无 job 资源，含 Runner 挂载和 Print 转日志设置，并保留异步状态。 */
  @Test
  void uniqueSessionMountsRunnerAndChecksResourceIdentity() {
    var started = startCluster();
    assertThat(started.path("state").asText()).isEqualTo("STARTING");
    assertThat(cluster.get().path("spec").has("job")).isFalse();
    assertThat(cluster.get().at("/spec/flinkConfiguration/taskmanager.system-out.mode").asText())
        .isEqualTo("LOG");
    assertThat(cluster.get().at("/spec/podTemplate/spec/initContainers").toString())
        .contains("flink-web-sql-runner.jar");
    assertThat(sessions.status("sql-validation").path("state").asText()).isEqualTo("READY");
    assertThatThrownBy(
            () ->
                sessions.save(
                    "sql-validation", new SessionRequest("another", "", true, runtime(), 0)))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> sessions.delete("sql-validation", started.path("revision").asLong()))
        .isInstanceOf(BusinessException.class);
    cluster.get().withObject("/metadata").put("uid", "replacement");
    assertThatThrownBy(() -> sessions.stop("sql-validation")).hasMessageContaining("身份");
  }

  /** 普通 Session 保留用户账号、共享模板与独立角色覆盖，保存不会启动资源。 */
  @Test
  void ordinarySessionRetainsAccountAndThreePodTemplates() {
    var options = runtime().put("serviceAccount", "session-custom");
    options.remove("runnerArtifactId");
    options.withObject("/podTemplate/metadata/labels").put("scope", "shared");
    options.withObject("/jmPodTemplate/spec/nodeSelector").put("role", "jm");
    options.withObject("/tmPodTemplate/spec/nodeSelector").put("role", "tm");
    var saved =
        sessions.save("custom-pods", new SessionRequest("custom-pods", "", false, options, 0));
    assertThat(saved.at("/runtime/serviceAccount").asText()).isEqualTo("session-custom");
    sessions.start("custom-pods", saved.path("revision").asLong());
    assertThat(cluster.get().at("/spec/serviceAccount").asText()).isEqualTo("session-custom");
    assertThat(cluster.get().at("/spec/podTemplate/metadata/labels/scope").asText())
        .isEqualTo("shared");
    assertThat(cluster.get().at("/spec/jobManager/podTemplate/spec/nodeSelector/role").asText())
        .isEqualTo("jm");
    assertThat(cluster.get().at("/spec/taskManager/podTemplate/spec/nodeSelector/role").asText())
        .isEqualTo("tm");
  }

  /** 提交进行二次 SQL 检查，重复请求只提交一次；日志保留重复业务行且只收集本次一百条。 */
  @Test
  void validationIsIdempotentAndWaitsForCancelConfirmation() {
    startCluster();
    var draft = draft();
    String sqlId = draft.path("id").asText();
    String requestId = UUID.randomUUID().toString();
    var run = executions.submit(sqlId, 1, requestId);
    String id = run.path("id").asText();
    assertThat(run.path("state").asText()).isEqualTo("ACCEPTED");
    assertThat(executions.submit(sqlId, 1, requestId).path("id").asText()).isEqualTo(id);
    verify(rest, times(1)).upload(anyString(), any());
    var managers = mapper.createObjectNode();
    managers.putArray("taskmanagers").addObject().put("id", "tm-1");
    when(rest.call(anyString(), eq("GET"), eq("/taskmanagers"), isNull())).thenReturn(managers);
    String output =
        "FWV_other_0> +I[wrong]\n"
            + java.util.stream.IntStream.range(0, 120)
                .mapToObj(i -> "log prefix FWV_" + id + "_0:1> +I[repeated, row]")
                .collect(java.util.stream.Collectors.joining("\n"));
    when(rest.log(anyString(), eq("tm-1"))).thenReturn(output);
    var running = executions.status(id);
    assertThat(running.path("rows").size()).isEqualTo(100);
    assertThat(running.path("rows").get(0).path("value").asText()).isEqualTo("repeated, row");
    assertThat(running.path("state").asText()).isEqualTo("CANCELING");
    when(rest.call(anyString(), eq("GET"), eq("/jobs/" + id), isNull()))
        .thenReturn(mapper.createObjectNode().put("state", "CANCELED"));
    var completed = executions.status(id);
    assertThat(completed.path("state").asText()).isEqualTo("COMPLETED");
    assertThat(completed.path("rows").size()).isEqualTo(100);
    verify(rest).call(anyString(), eq("DELETE"), eq("/jars/upload_sql-validation.jar"), isNull());
  }

  /** 验证集群缺失、保存修订失效和未通过检查都不能提交任务。 */
  @Test
  void invalidSqlAndRevisionCannotSubmit() {
    var draft = draft();
    assertThatThrownBy(
            () -> executions.submit(draft.path("id").asText(), 2, UUID.randomUUID().toString()))
        .hasMessageContaining("修改");
    assertThatThrownBy(
            () -> executions.submit(draft.path("id").asText(), 1, UUID.randomUUID().toString()))
        .hasMessageContaining("Session");
    verifyNoInteractions(rest, bundles);
  }

  /** 依赖准备失败不会保留 UNKNOWN 任务占用专用集群。 */
  @Test
  void dependencyFailureIsTerminalAndRetryCanProceed() throws Exception {
    startCluster();
    var draft = draft();
    when(bundles.build(anyList())).thenThrow(new java.io.IOException("连接器文件读取失败"));
    var first = executions.submit(draft.path("id").asText(), 1, UUID.randomUUID().toString());
    assertThat(first.path("state").asText()).isEqualTo("FAILED");
    var retry = executions.submit(draft.path("id").asText(), 1, UUID.randomUUID().toString());
    assertThat(retry.path("state").asText()).isEqualTo("FAILED");
    verifyNoInteractions(rest);
  }

  /** 未知提交保留固定任务编号，集群销毁后确认结束且不取消新 Session 的任务。 */
  @Test
  void uncertainSubmissionIsBoundToOriginalSession() {
    startCluster();
    var draft = draft();
    when(rest.call(anyString(), eq("POST"), contains("/run"), any()))
        .thenThrow(new IllegalStateException("network timeout"));
    var run = executions.submit(draft.path("id").asText(), 1, UUID.randomUUID().toString());
    assertThat(run.path("state").asText()).isEqualTo("UNKNOWN");
    cluster.set(null);
    sessions.stop("sql-validation");
    assertThat(executions.status(run.path("id").asText()).path("state").asText())
        .isEqualTo("ABORTED");
    verify(rest, never()).call(anyString(), eq("PATCH"), anyString(), any());
  }

  /** 资源 UID 被替换时立即结束旧验证，禁止取消或清理新集群中的内容。 */
  @Test
  void replacedSessionDoesNotReceiveOldValidationCleanup() {
    startCluster();
    var draft = draft();
    var run = executions.submit(draft.path("id").asText(), 1, UUID.randomUUID().toString());
    cluster.get().withObject("/metadata").put("uid", "replacement-session");
    clearInvocations(rest);
    assertThat(executions.status(run.path("id").asText()).path("state").asText())
        .isEqualTo("ABORTED");
    verifyNoInteractions(rest);
  }

  /** 运行失败直接显示脱敏异常，日志接口故障不能遮蔽 Planner 的错误。 */
  @Test
  void runtimeFailureDisplaysRedactedException() {
    startCluster();
    var draft = draft();
    var run = executions.submit(draft.path("id").asText(), 1, UUID.randomUUID().toString());
    String id = run.path("id").asText();
    when(rest.call(anyString(), eq("GET"), eq("/jobs/" + id), isNull()))
        .thenReturn(mapper.createObjectNode().put("state", "FAILED"));
    when(rest.call(anyString(), eq("GET"), eq("/jobs/" + id + "/exceptions"), isNull()))
        .thenReturn(
            mapper
                .createObjectNode()
                .put("root-exception", "Connector missing; password=private-value"));
    var result = executions.status(id);
    assertThat(result.path("state").asText()).isEqualTo("FAILED");
    assertThat(result.path("message").asText())
        .contains("Connector missing", "******")
        .doesNotContain("private-value");
  }

  /** 多行文本完整展示，反复监听不追加已读行，真实重复行仍作为新结果保留。 */
  @Test
  void multilinePrintRowsKeepOrderWithoutPollingDuplicates() {
    startCluster();
    var draft = draft();
    var run = executions.submit(draft.path("id").asText(), 1, UUID.randomUUID().toString());
    String id = run.path("id").asText();
    var managers = mapper.createObjectNode();
    managers.putArray("taskmanagers").addObject().put("id", "tm-1");
    when(rest.call(anyString(), eq("GET"), eq("/taskmanagers"), isNull())).thenReturn(managers);
    String output = "INFO FWV_" + id + "_0> +I[one,\n多行]\nINFO FWV_" + id + "_0> +I[repeated]\n";
    when(rest.log(anyString(), eq("tm-1"))).thenReturn(output);
    assertThat(executions.status(id).path("rows").get(0).path("value").asText())
        .isEqualTo("one,\n多行");
    assertThat(executions.status(id).path("rows").size()).isEqualTo(2);
    when(rest.log(anyString(), eq("tm-1")))
        .thenReturn(output + "INFO FWV_" + id + "_0> +I[repeated]\n");
    assertThat(executions.status(id).path("rows").size()).isEqualTo(3);
  }

  /** 缺失或格式错误的修订与请求编号在接口层拒绝，不调用运行服务。 */
  @Test
  void verificationAndStartContractsValidateInputs() throws Exception {
    mvc.perform(
            post("/api/sql/jobs/test/verifications")
                .with(csrf())
                .contentType("application/json")
                .content("{\"revision\":0,\"requestId\":\"invalid\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/sessions/sql-validation/start")
                .with(csrf())
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(rest, bundles);
  }

  /** SQL 修改后仍强制二次检查；镜像和环境不匹配在依赖上传前拒绝。 */
  @Test
  void invalidScriptAndMismatchedImageCannotReachSession() {
    startCluster();
    var draft = draft();
    String id = draft.path("id").asText();
    sql.save(id, new SqlDraftRequest(id, "", "DROP TABLE sink", runtime(), 1));
    assertThatThrownBy(() -> executions.submit(id, 2, UUID.randomUUID().toString()))
        .isInstanceOf(BusinessException.class);
    var other = runtime().put("imageId", "other-image");
    records.save(
        "images",
        "other-image",
        mapper
            .createObjectNode()
            .put("repository", "registry.test/flink")
            .put("version", "1.20.3")
            .put("flinkVersion", "1.20.3"),
        0);
    sql.save(id, new SqlDraftRequest(id, "", draft.path("sql").asText(), other, 2));
    assertThatThrownBy(() -> executions.submit(id, 3, UUID.randomUUID().toString()))
        .hasMessageContaining("相同运行镜像");
    verifyNoInteractions(rest, bundles);
  }

  /** 新 JAR 自动登记；既有作业只在显式编辑时加入 JAR 开发区。 */
  @Test
  void legacyJarOnlyAppearsAfterEditing() {
    String name = "jar-" + UUID.randomUUID().toString().substring(0, 8);
    String source =
        "apiVersion: flink.apache.org/v1beta1\nkind: FlinkDeployment\nmetadata:\n  name: "
            + name
            + "\n"
            + "spec:\n"
            + "  image: registry.test/flink:1.20.3\n"
            + "  flinkVersion: v1_20\n"
            + "  job:\n"
            + "    jarURI: local:///opt/flink/job.jar\n"
            + "    parallelism: 1\n";
    var job = jobs.create(new DraftRequest(name, "", "1.20.3", source, 0));
    var registry = records.require("jar_development", job.id());
    records.delete("jar_development", job.id(), registry.path("revision").asLong());
    assertThat(development.jarJobs()).noneMatch(j -> j.id().equals(job.id()));
    development.openJar(job.id());
    assertThat(development.jarJobs()).anyMatch(j -> j.id().equals(job.id()));
    assertThat(jobs.get(job.id()).yaml()).isEqualTo(job.yaml());
  }

  /** 开发者可验证但不能管理共享 Session；只读角色不能读取验证数据。 */
  @Test
  @WithMockUser(roles = "VIEWER")
  void endpointsEnforceRolesAndCsrf() throws Exception {
    mvc.perform(get("/api/sessions")).andExpect(status().isOk());
    mvc.perform(
            post("/api/sessions/sql-validation/start")
                .with(csrf())
                .contentType("application/json")
                .content("{\"revision\":1}"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/sql/verifications/test")).andExpect(status().isForbidden());
    mvc.perform(
            post("/api/sql/jobs/test/verifications").contentType("application/json").content("{}"))
        .andExpect(status().isForbidden());
  }
}
