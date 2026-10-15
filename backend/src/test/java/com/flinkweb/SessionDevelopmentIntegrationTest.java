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
    registry.add(
        "platform.sql-validation.runner-jar",
        () -> DATABASE.getParent().resolve("runner.jar").toString());
  }

  /** 下载入口使用固定文件及开发权限，缺失时明确报错，不允许访客下载。 */
  @Test
  void runnerDownloadRequiresInstalledFileAndDevelopmentPermission() throws Exception {
    Path runnerFile = DATABASE.getParent().resolve("runner.jar");
    mvc.perform(get("/api/sql/runner/download")).andExpect(status().isNotFound());
    byte[] artifact = new byte[] {80, 75, 3, 4};
    Files.write(runnerFile, artifact);
    try {
      mvc.perform(get("/api/sql/runner/download"))
          .andExpect(status().isOk())
          .andExpect(
              header()
                  .string("Content-Disposition", "attachment; filename=flink-web-sql-runner.jar"))
          .andExpect(header().string("Cache-Control", "no-store"))
          .andExpect(content().bytes(artifact));
      mvc.perform(
              get("/api/sql/runner/download")
                  .with(
                      org.springframework.security.test.web.servlet.request
                          .SecurityMockMvcRequestPostProcessors.user("viewer")
                          .roles("VIEWER")))
          .andExpect(status().isForbidden());
    } finally {
      Files.deleteIfExists(runnerFile);
    }
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
  @Autowired org.springframework.context.ApplicationContext applicationContext;
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
    when(rest.validationLog(anyString(), anyString(), anyString())).thenReturn("");
    when(rest.upload(anyString(), any()))
        .thenReturn(mapper.createObjectNode().put("filename", "/tmp/upload_sql-validation.jar"));
    when(rest.call(anyString(), anyString(), anyString(), nullable(JsonNode.class)))
        .thenReturn(mapper.createObjectNode().put("state", "RUNNING"));
    var files = mapper.createObjectNode();
    files.putArray("logs").addObject().put("name", "sql-validation.log");
    ((ArrayNode) files.path("logs")).addObject().put("name", "sql-validation.log.1");
    when(rest.call(anyString(), eq("GET"), eq("/taskmanagers/tm-1/logs"), isNull()))
        .thenReturn(files);
    when(rest.budgetedCall(
            anyString(),
            anyString(),
            anyString(),
            nullable(JsonNode.class),
            any(java.time.Duration.class)))
        .thenAnswer(
            call ->
                rest.call(
                    call.getArgument(0),
                    call.getArgument(1),
                    call.getArgument(2),
                    call.getArgument(3)));
    when(rest.budgetedValidationLog(
            anyString(), anyString(), anyString(), any(java.time.Duration.class)))
        .thenAnswer(
            call ->
                rest.validationLog(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
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

  /** 配置转换保留未知字段，固定数量经过保存、启动和 dry-run 后仍有效。 */
  @Test
  void sessionYamlRoundTripPreservesConfigurationAndFixedReplicas() {
    var options = runtime().put("deploymentMode", "standalone").put("tmReplicas", 3);
    var input = new SessionRequest("yaml-session", "", false, options, 0);
    var source = yaml.parse(sessions.configuration(input).path("yaml").asText());
    source
        .withObject("/spec")
        .putObject("logConfiguration")
        .put("log4j-console.properties", "rootLogger.level=INFO");
    source.withObject("/spec/taskManager/resource").put("ephemeralStorage", "2G");
    source.withObject("/spec/flinkConfiguration").put("web.submit.enable", "true");
    options.put("deploymentYaml", yaml.dump(source));
    var converted =
        sessions.configuration(new SessionRequest("yaml-session", "", false, options, 0));
    assertThat(converted.at("/runtime/tmReplicas").asInt()).isEqualTo(3);
    var saved =
        sessions.save("yaml-session", new SessionRequest("yaml-session", "", false, options, 0));
    sessions.start("yaml-session", saved.path("revision").asLong());
    assertThat(cluster.get().at("/spec/taskManager/replicas").asInt()).isEqualTo(3);
    assertThat(cluster.get().at("/spec/taskManager/resource/ephemeralStorage").asText())
        .isEqualTo("2G");
    assertThat(cluster.get().at("/spec/logConfiguration/log4j-console.properties").asText())
        .contains("INFO");
    assertThat(cluster.get().at("/spec/job").isMissingNode()).isTrue();
    assertThat(cluster.get().at("/spec/flinkConfiguration/web.submit.enable").asText())
        .isEqualTo("false");
    when(rest.call(anyString(), eq("GET"), eq("/overview"), isNull()))
        .thenReturn(mapper.createObjectNode().put("taskmanagers", 2));
    var observed = sessions.status("yaml-session");
    assertThat(observed.path("configuredTaskManagers").asInt()).isEqualTo(3);
    assertThat(observed.path("registeredTaskManagers").asInt()).isEqualTo(2);
    assertThatThrownBy(
            () ->
                sessions.configuration(
                    new SessionRequest(
                        "native-session",
                        "",
                        false,
                        runtime().put("deploymentMode", "native").put("tmReplicas", 3),
                        0)))
        .hasMessageContaining("standalone");
    for (JsonNode invalid :
        List.of(
            mapper.getNodeFactory().numberNode(0),
            mapper.getNodeFactory().numberNode(-1),
            mapper.getNodeFactory().numberNode(1.5))) {
      options = runtime().put("deploymentMode", "standalone");
      options.set("tmReplicas", invalid);
      var request = new SessionRequest("bad-replicas", "", false, options, 0);
      assertThatThrownBy(() -> sessions.configuration(request)).hasMessageContaining("正整数");
    }
  }

  /** dry-run 的整数规范化不是配置丢失，真正裁剪仍阻止创建且不泄漏字段值。 */
  @Test
  void sessionDryRunAcceptsEquivalentNumbersAndReportsPrunedPaths() {
    when(kubernetes.call(eq("POST"), contains("dryRun"), any(JsonNode.class)))
        .thenAnswer(
            invocation -> {
              var value = ((ObjectNode) invocation.getArgument(2)).deepCopy();
              value.withObject("/spec/jobManager/resource").put("cpu", 1);
              value.withObject("/spec").put("imagePullPolicy", "IfNotPresent");
              return value;
            });
    var saved = sessions.save("numeric", new SessionRequest("numeric", "", false, runtime(), 0));
    sessions.start("numeric", saved.path("revision").asLong());
    assertThat(cluster.get()).isNotNull();
    sessions.stop("numeric");
    var options = runtime();
    options.putObject("flinkConfiguration").put("sensitive-key", "private-test-value");
    var pruned = sessions.save("pruned", new SessionRequest("pruned", "", false, options, 0));
    when(kubernetes.call(eq("POST"), contains("dryRun"), any(JsonNode.class)))
        .thenAnswer(
            invocation -> {
              var value = ((ObjectNode) invocation.getArgument(2)).deepCopy();
              value.withObject("/spec/flinkConfiguration").remove("sensitive-key");
              return value;
            });
    assertThatThrownBy(() -> sessions.start("pruned", pruned.path("revision").asLong()))
        .hasMessageContaining("/spec/flinkConfiguration/sensitive-key")
        .hasMessageNotContaining("private-test-value");
    assertThat(cluster.get()).isNull();
    assertThat(records.require("sessions", "pruned").path("desiredState").asText())
        .isEqualTo("stopped");
  }

  /** 未完成 SQL 也能编辑配置，发布时才生成执行参数；受控入口拒绝 YAML 覆盖。 */
  @Test
  void sqlYamlConfigurationPreservesUnknownFieldsAndControlsRunner() {
    String name = "yaml-sql-" + UUID.randomUUID().toString().substring(0, 8);
    var options = runtime().put("upgradeMode", "stateless");
    var input = new SqlDraftRequest(name, "", "unfinished sql", options, 0);
    var source = yaml.parse(sql.configuration(input).path("yaml").asText());
    source
        .withObject("/spec")
        .putObject("logConfiguration")
        .put("log4j-console.properties", "custom");
    source.withObject("/spec/taskManager/resource").put("ephemeralStorage", "3G");
    source.withObject("/spec/job").put("parallelism", 4);
    options.put("deploymentYaml", yaml.dump(source));
    var saved = sql.save(name, new SqlDraftRequest(name, "", "unfinished sql", options, 0));
    assertThat(saved.at("/runtime/parallelism").asInt()).isEqualTo(4);
    var published =
        sql.preview(new SqlDraftRequest(name, "", "INSERT INTO sink VALUES (1)", options, 0));
    var deployed = yaml.parse(published.path("yaml").asText());
    assertThat(deployed.at("/spec/taskManager/resource/ephemeralStorage").asText()).isEqualTo("3G");
    assertThat(deployed.at("/spec/logConfiguration/log4j-console.properties").asText())
        .isEqualTo("custom");
    assertThat(deployed.at("/spec/job/args").size()).isEqualTo(2);
    source.withObject("/spec/job").put("entryClass", "arbitrary.Main");
    options.put("deploymentYaml", yaml.dump(source));
    assertThatThrownBy(() -> sql.configuration(new SqlDraftRequest(name, "", "", options, 0)))
        .hasMessageContaining("Runner");
    assertThat(sql.get(name).path("revision").asLong()).isEqualTo(saved.path("revision").asLong());
  }

  /** Session 配置不能通过 YAML 改变 namespace 或混入 Application 作业。 */
  @Test
  void sessionYamlRejectsUnsafeIdentityAndApplicationJob() {
    var options = runtime();
    var source =
        yaml.parse(
            sessions
                .configuration(new SessionRequest("safe", "", false, options, 0))
                .path("yaml")
                .asText());
    source.withObject("/metadata").put("namespace", "other-namespace");
    options.put("deploymentYaml", yaml.dump(source));
    assertThatThrownBy(
            () -> sessions.configuration(new SessionRequest("safe", "", false, options, 0)))
        .hasMessageContaining("namespace");
    source.withObject("/metadata").remove("namespace");
    source.withObject("/spec/jobManager").putArray("resource");
    options.put("deploymentYaml", yaml.dump(source));
    assertThatThrownBy(
            () -> sessions.configuration(new SessionRequest("safe", "", false, options, 0)))
        .hasMessageContaining("resource 必须为对象");
    source.withObject("/spec/jobManager").remove("resource");
    source.withObject("/spec").putObject("job");
    options.put("deploymentYaml", yaml.dump(source));
    assertThatThrownBy(
            () -> sessions.configuration(new SessionRequest("safe", "", false, options, 0)))
        .hasMessageContaining("spec.job");
    assertThat(cluster.get()).isNull();
  }

  /** YAML 省略资源时不注入默认内存，表单无法表达时明确阻止回填。 */
  @Test
  void yamlResourceOmissionsArePreservedInsteadOfReplacingFlinkConfiguration() {
    var options = runtime().put("serviceAccount", "flink-jobs-flink");
    var source =
        yaml.parse(
            sessions
                .configuration(new SessionRequest("omit-resource", "", false, options, 0))
                .path("yaml")
                .asText());
    source.withObject("/spec/jobManager").remove("resource");
    source.withObject("/spec/flinkConfiguration").put("jobmanager.memory.process.size", "4g");
    options.put("deploymentYaml", yaml.dump(source));
    var result = sessions.configuration(new SessionRequest("omit-resource", "", false, options, 0));
    assertThat(result.path("formIssue").asText()).contains("jobManager.resource");
    var rendered = yaml.parse(result.path("yaml").asText());
    assertThat(rendered.at("/spec/jobManager/resource").isMissingNode()).isTrue();
    assertThat(rendered.at("/spec/flinkConfiguration/jobmanager.memory.process.size").asText())
        .isEqualTo("4g");
    sessions.save("omit-resource", new SessionRequest("omit-resource", "", false, options, 0));
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
    assertThat(cluster.get().at("/spec/flinkConfiguration/web.submit.enable").asText())
        .isEqualTo("true");
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

  /** 提交进行二次 SQL 检查，重复请求只提交一次；日志保留重复业务行且只收集本次一千条。 */
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
        validationEvent("00000000000000000000000000000000", 1, 0, "+I", "wrong")
            + java.util.stream.IntStream.range(0, 1200)
                .mapToObj(
                    i ->
                        validationEvent(
                            id,
                            i + 2,
                            i % 2,
                            new String[] {"+I", "-U", "+U", "-D"}[i % 4],
                            "repeated, row"))
                .collect(java.util.stream.Collectors.joining(""));
    when(rest.validationLog(anyString(), eq("tm-1"), eq("sql-validation.log"))).thenReturn(output);
    var running = executions.status(id);
    assertThat(running.path("rows").size()).isEqualTo(1000);
    assertThat(running.path("rows").get(0).path("value").asText()).isEqualTo("repeated, row");
    assertThat(running.path("state").asText()).isEqualTo("CANCELING");
    when(rest.call(anyString(), eq("GET"), eq("/jobs/" + id), isNull()))
        .thenReturn(mapper.createObjectNode().put("state", "CANCELED"));
    var completed = executions.status(id);
    assertThat(completed.path("state").asText()).isEqualTo("COMPLETED");
    assertThat(completed.path("rows").size()).isEqualTo(1000);
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

  /** 清理上传副本失败仍保留验证终态，只记录关联标识和故障类型。 */
  @Test
  @org.junit.jupiter.api.extension.ExtendWith(
      org.springframework.boot.test.system.OutputCaptureExtension.class)
  void uploadedJarCleanupFailureDoesNotChangeTerminalState(
      org.springframework.boot.test.system.CapturedOutput captured) {
    startCluster();
    var draft = draft();
    var run = executions.submit(draft.path("id").asText(), 1, UUID.randomUUID().toString());
    String id = run.path("id").asText();
    when(rest.call(anyString(), eq("GET"), eq("/jobs/" + id), isNull()))
        .thenReturn(mapper.createObjectNode().put("state", "FAILED"));
    when(rest.call(anyString(), eq("DELETE"), startsWith("/jars/"), isNull()))
        .thenThrow(new IllegalStateException("password=cleanup-private"));
    assertThat(executions.status(id).path("state").asText()).isEqualTo("FAILED");
    assertThat(captured.getAll())
        .contains("validationId=" + id, "stage=DELETE_JAR", "IllegalStateException")
        .doesNotContain("cleanup-private");
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
    String output =
        validationEvent(id, 1, 0, "+I", "one,\n多行") + validationEvent(id, 2, 0, "+I", "repeated");
    when(rest.validationLog(anyString(), eq("tm-1"), eq("sql-validation.log"))).thenReturn(output);
    assertThat(executions.status(id).path("rows").get(0).path("value").asText())
        .isEqualTo("one,\n多行");
    assertThat(executions.status(id).path("rows").size()).isEqualTo(2);
    when(rest.validationLog(anyString(), eq("tm-1"), eq("sql-validation.log")))
        .thenReturn(output + validationEvent(id, 3, 0, "+I", "repeated"));
    assertThat(executions.status(id).path("rows").size()).isEqualTo(3);
  }

  /** 构造与 Log4j2 JSON 转义布局一致的完整日志事件。 */
  String validationEvent(String id, long sequence, int sink, String kind, String value) {
    String encoded =
        mapper
            .getNodeFactory()
            .textNode("FWV_" + id + "_" + sink + ":1> " + kind + "[" + value + "]")
            .toString();
    return "FWV_EVENT|" + sequence + "|" + encoded.substring(1, encoded.length() - 1) + "\n";
  }

  /** 部分文件成功后失败，恢复读取不能重复计入事件，相同值的后续事件仍保留。 */
  @Test
  void partialFileFailureKeepsRowsAndCursorConsistent() {
    startCluster();
    var draft = draft();
    String id =
        executions
            .submit(draft.path("id").asText(), 1, UUID.randomUUID().toString())
            .path("id")
            .asText();
    var managers = mapper.createObjectNode();
    managers.putArray("taskmanagers").addObject().put("id", "tm-1");
    when(rest.call(anyString(), eq("GET"), eq("/taskmanagers"), isNull())).thenReturn(managers);
    when(rest.validationLog(anyString(), eq("tm-1"), eq("sql-validation.log.1")))
        .thenReturn(
            validationEvent(id, 11, 0, "+I", "same") + validationEvent(id, 12, 0, "+I", "same"));
    when(rest.validationLog(anyString(), eq("tm-1"), eq("sql-validation.log")))
        .thenThrow(new IllegalStateException("temporary read failure"));
    assertThat(executions.status(id).path("rows").size()).isEqualTo(2);
    when(rest.validationLog(anyString(), eq("tm-1"), eq("sql-validation.log")))
        .thenReturn(validationEvent(id, 13, 0, "+I", "same"));
    assertThat(executions.status(id).path("rows").size()).isEqualTo(3);
    assertThat(executions.status(id).path("rows").size()).isEqualTo(3);
  }

  /** 取消失败保留独占及意图，重试成功后仍等待 Flink 终态确认。 */
  @Test
  void cancellationFailureRetriesWithoutReleasingSession() {
    startCluster();
    var draft = draft();
    String id =
        executions
            .submit(draft.path("id").asText(), 1, UUID.randomUUID().toString())
            .path("id")
            .asText();
    when(rest.call(anyString(), eq("PATCH"), eq("/jobs/" + id + "?mode=cancel"), isNull()))
        .thenThrow(new IllegalStateException("temporary control failure"))
        .thenReturn(mapper.createObjectNode());
    assertThat(executions.cancel(id).path("state").asText()).isEqualTo("CANCEL_PENDING");
    assertThatThrownBy(
            () -> executions.submit(draft.path("id").asText(), 1, UUID.randomUUID().toString()))
        .hasMessageContaining("正在验证其他 SQL");
    assertThat(executions.status(id).path("state").asText()).isEqualTo("CANCELING");
    when(rest.call(anyString(), eq("GET"), eq("/jobs/" + id), isNull()))
        .thenReturn(mapper.createObjectNode().put("state", "CANCELED"));
    assertThat(executions.status(id).path("state").asText()).isEqualTo("CANCELED");
  }

  /** 实例重建恢复旧 SUBMITTING 的固定 JobID 和截止取消，结果数据不从数据库恢复。 */
  @Test
  void recoveredLegacySubmissionRetainsIdentityAndDeadline() throws Exception {
    startCluster();
    var draft = draft();
    var first =
        applicationContext
            .getAutowireCapableBeanFactory()
            .createBean(com.flinkweb.service.sql.impl.SqlExecutionServiceImpl.class);
    String id;
    try {
      id =
          first
              .submit(draft.path("id").asText(), 1, UUID.randomUUID().toString())
              .path("id")
              .asText();
    } finally {
      first.close();
    }
    var run = records.require("sql_validations", id);
    run.put("state", "SUBMITTING")
        .put("deadline", java.time.Instant.now().minusSeconds(1).toString());
    run.remove("submissionAttempted");
    records.save("sql_validations", id, run, run.path("revision").asLong());
    var canceled = new java.util.concurrent.CountDownLatch(1);
    when(rest.call(anyString(), eq("PATCH"), eq("/jobs/" + id + "?mode=cancel"), isNull()))
        .thenAnswer(
            call -> {
              canceled.countDown();
              return mapper.createObjectNode();
            });
    var recovered =
        applicationContext
            .getAutowireCapableBeanFactory()
            .createBean(com.flinkweb.service.sql.impl.SqlExecutionServiceImpl.class);
    try {
      assertThat(canceled.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      when(rest.call(anyString(), eq("GET"), eq("/jobs/" + id), isNull()))
          .thenReturn(mapper.createObjectNode().put("state", "CANCELED"));
      long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
      ObjectNode status;
      do {
        status = recovered.status(id);
        if (!"TIMED_OUT".equals(status.path("state").asText())) Thread.sleep(20);
      } while (!"TIMED_OUT".equals(status.path("state").asText()) && System.nanoTime() < end);
      assertThat(status.path("state").asText()).isEqualTo("TIMED_OUT");
      assertThat(status.path("jobId").asText()).isEqualTo(id);
      assertThat(status.path("rows").size()).isZero();
    } finally {
      recovered.close();
    }
  }

  /** 提交请求仍在途时 404 不释放独占，迟到失败不覆盖已经保存的截止取消。 */
  @Test
  void unknownSubmissionCannotOverwriteDeadlineIntent() throws Exception {
    startCluster();
    var draft = draft();
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    when(rest.call(anyString(), eq("POST"), contains("/run"), any()))
        .thenAnswer(
            call -> {
              entered.countDown();
              release.await(5, java.util.concurrent.TimeUnit.SECONDS);
              throw new IllegalStateException("network timeout");
            });
    var context = org.springframework.security.core.context.SecurityContextHolder.getContext();
    var submission =
        java.util.concurrent.CompletableFuture.supplyAsync(
            () -> {
              org.springframework.security.core.context.SecurityContextHolder.setContext(context);
              try {
                return executions.submit(
                    draft.path("id").asText(), 1, UUID.randomUUID().toString());
              } finally {
                org.springframework.security.core.context.SecurityContextHolder.clearContext();
              }
            });
    assertThat(entered.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    var run = records.list("sql_validations").get(0);
    String id = run.path("id").asText();
    run.put("deadline", java.time.Instant.now().minusSeconds(1).toString());
    records.save("sql_validations", id, run, run.path("revision").asLong());
    when(rest.call(anyString(), eq("GET"), eq("/jobs/" + id), isNull()))
        .thenThrow(
            new BusinessException(
                org.springframework.http.HttpStatus.BAD_GATEWAY,
                "SESSION_REST_NOT_FOUND",
                "not visible yet"));
    try {
      org.springframework.test.util.ReflectionTestUtils.invokeMethod(executions, "expire", id);
      assertThat(records.require("sql_validations", id).path("state").asText())
          .isEqualTo("CANCEL_PENDING");
      assertThatThrownBy(
              () -> executions.submit(draft.path("id").asText(), 1, UUID.randomUUID().toString()))
          .hasMessageContaining("正在验证其他 SQL");
    } finally {
      release.countDown();
    }
    var result = submission.get(5, java.util.concurrent.TimeUnit.SECONDS);
    assertThat(result.path("state").asText()).isEqualTo("CANCEL_PENDING");
    assertThat(records.require("sql_validations", id).path("cancelTarget").asText())
        .isEqualTo("TIMED_OUT");
    when(rest.call(anyString(), eq("GET"), eq("/jobs/" + id), isNull()))
        .thenReturn(mapper.createObjectNode().put("state", "CANCELED"));
    assertThat(executions.status(id).path("state").asText()).isEqualTo("TIMED_OUT");
  }

  /** 慢日志 I/O 期间截止任务仍及时取消，迟到的数据不能回填已进入取消的验证。 */
  @Test
  void slowLogDoesNotBlockDeadlineCancellation() throws Exception {
    startCluster();
    var draft = draft();
    String id =
        executions
            .submit(draft.path("id").asText(), 1, UUID.randomUUID().toString())
            .path("id")
            .asText();
    var managers = mapper.createObjectNode();
    managers.putArray("taskmanagers").addObject().put("id", "tm-1");
    when(rest.call(anyString(), eq("GET"), eq("/taskmanagers"), isNull())).thenReturn(managers);
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var canceled = new java.util.concurrent.CountDownLatch(1);
    when(rest.validationLog(anyString(), eq("tm-1"), eq("sql-validation.log")))
        .thenAnswer(
            call -> {
              entered.countDown();
              release.await(5, java.util.concurrent.TimeUnit.SECONDS);
              return validationEvent(id, 1, 0, "+I", "late");
            });
    when(rest.call(anyString(), eq("PATCH"), eq("/jobs/" + id + "?mode=cancel"), isNull()))
        .thenAnswer(
            call -> {
              canceled.countDown();
              return mapper.createObjectNode();
            });
    var run = records.require("sql_validations", id);
    run.put("deadline", java.time.Instant.now().minusSeconds(1).toString());
    records.save("sql_validations", id, run, run.path("revision").asLong());
    var context = org.springframework.security.core.context.SecurityContextHolder.getContext();
    var observation =
        java.util.concurrent.CompletableFuture.supplyAsync(
            () -> {
              org.springframework.security.core.context.SecurityContextHolder.setContext(context);
              try {
                return executions.status(id);
              } finally {
                org.springframework.security.core.context.SecurityContextHolder.clearContext();
              }
            });
    assertThat(entered.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    long started = System.nanoTime();
    var expiry =
        java.util.concurrent.CompletableFuture.runAsync(
            () ->
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    executions, "expire", id));
    boolean timely;
    try {
      timely = canceled.await(500, java.util.concurrent.TimeUnit.MILLISECONDS);
    } finally {
      release.countDown();
    }
    expiry.get(5, java.util.concurrent.TimeUnit.SECONDS);
    observation.get(5, java.util.concurrent.TimeUnit.SECONDS);
    System.out.println(
        "SQL deadline cancellation latencyMs="
            + java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    assertThat(timely).isTrue();
    assertThat(records.require("sql_validations", id).path("cancelTarget").asText())
        .isEqualTo("TIMED_OUT");
    assertThat(executions.status(id).path("rows").size()).isZero();
  }

  /** 固定文件轮转、重复行、四种变更和半条写入不会导致重复或漏读。 */
  @Test
  void validationReadsRotatedEventsAndAllRowKinds() {
    startCluster();
    var draft = draft();
    var run = executions.submit(draft.path("id").asText(), 1, UUID.randomUUID().toString());
    String id = run.path("id").asText();
    var managers = mapper.createObjectNode();
    managers.putArray("taskmanagers").addObject().put("id", "tm-1");
    when(rest.call(anyString(), eq("GET"), eq("/taskmanagers"), isNull())).thenReturn(managers);
    String first = validationEvent(id, 11, 0, "+I", "同一值");
    String second = validationEvent(id, 12, 0, "-U", "同一值");
    when(rest.validationLog(anyString(), eq("tm-1"), eq("sql-validation.log")))
        .thenReturn(first + second.substring(0, second.length() - 1));
    assertThat(executions.status(id).path("rows").size()).isEqualTo(1);
    when(rest.validationLog(anyString(), eq("tm-1"), eq("sql-validation.log.1")))
        .thenReturn(first + second);
    when(rest.validationLog(anyString(), eq("tm-1"), eq("sql-validation.log")))
        .thenReturn(
            validationEvent(id, 13, 1, "+U", "同一值")
                + validationEvent(id, 14, 1, "-D", "多行\nFWV_EVENT|fake|中文\"\\值"));
    var result = executions.status(id).path("rows");
    assertThat(result.findValuesAsText("kind")).containsExactly("+I", "-U", "+U", "-D");
    assertThat(result.get(3).path("value").asText()).isEqualTo("多行\nFWV_EVENT|fake|中文\"\\值");
    assertThat(executions.status(id).path("rows").size()).isEqualTo(4);
  }

  /** 不依赖浏览器查询也会在一千条后取消，确认结束前继续占用全局验证入口。 */
  @Test
  void validationListenerCancelsWithoutBrowserAndKeepsSingleActiveRun() throws Exception {
    startCluster();
    var draft = draft();
    var run = executions.submit(draft.path("id").asText(), 1, UUID.randomUUID().toString());
    String id = run.path("id").asText();
    assertThatThrownBy(
            () -> executions.submit(draft.path("id").asText(), 1, UUID.randomUUID().toString()))
        .hasMessageContaining("正在验证其他 SQL");
    var managers = mapper.createObjectNode();
    managers.putArray("taskmanagers").addObject().put("id", "tm-1");
    when(rest.call(anyString(), eq("GET"), eq("/taskmanagers"), isNull())).thenReturn(managers);
    String output =
        java.util.stream.IntStream.range(0, 1200)
            .mapToObj(i -> validationEvent(id, i + 1, 0, "+I", "value"))
            .collect(java.util.stream.Collectors.joining());
    when(rest.validationLog(anyString(), eq("tm-1"), eq("sql-validation.log"))).thenReturn(output);
    long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
    while (!"CANCELING".equals(records.require("sql_validations", id).path("state").asText())
        && System.nanoTime() < end) Thread.sleep(50);
    assertThat(records.require("sql_validations", id).path("state").asText())
        .isEqualTo("CANCELING");
    verify(rest).call(anyString(), eq("PATCH"), eq("/jobs/" + id + "?mode=cancel"), isNull());
    assertThat(executions.status(id).path("rows").size()).isEqualTo(1000);
  }

  /** 专用配置固定且旧运行资源必须显式重启，普通 Session 不强制日志配置。 */
  @Test
  void validationLogConfigurationIsFixedAndRequiredBeforeSubmission() {
    var ordinary = sessions.configuration(new SessionRequest("ordinary", "", false, runtime(), 0));
    assertThat(
            yaml.parse(ordinary.path("yaml").asText()).at("/spec/logConfiguration").isMissingNode())
        .isTrue();
    startCluster();
    assertThat(cluster.get().at("/spec/logConfiguration/log4j-console.properties").asText())
        .contains("sql-validation.log", "FWV_EVENT|%sn|%enc{%m}{JSON}");
    cluster.get().withObject("/spec").remove("logConfiguration");
    var draft = draft();
    assertThatThrownBy(
            () -> executions.submit(draft.path("id").asText(), 1, UUID.randomUUID().toString()))
        .hasMessageContaining("固定日志配置");
    verify(rest, never()).upload(anyString(), any());
  }

  /** Session Pod 沿用所有者 UID 校验，伪造相同标签的 Pod 不能读取日志。 */
  @Test
  void sessionPodDiagnosticsCheckOwnershipAndLogPermissions() throws Exception {
    startCluster();
    var pods = mapper.createObjectNode();
    var owned = pods.putArray("items").addObject();
    owned
        .withObject("/metadata")
        .put("name", "session-tm")
        .put("uid", "pod-uid")
        .putArray("ownerReferences")
        .addObject()
        .put("kind", "FlinkDeployment")
        .put("uid", "test-session-uid");
    owned
        .withObject("/spec")
        .putArray("containers")
        .addObject()
        .put("name", "flink-main-container");
    owned.withObject("/status").put("phase", "Running");
    var other = owned.deepCopy();
    other
        .withObject("/metadata")
        .put("name", "external-pod")
        .putArray("ownerReferences")
        .addObject()
        .put("kind", "FlinkDeployment")
        .put("uid", "external-uid");
    ((ArrayNode) pods.path("items")).add(other);
    when(kubernetes.call(eq("GET"), startsWith("/core/pods?"), isNull())).thenReturn(pods);
    when(kubernetes.call(eq("GET"), eq("/core/pods/session-tm"), isNull())).thenReturn(owned);
    when(kubernetes.call(eq("GET"), eq("/core/pods/external-pod"), isNull())).thenReturn(other);
    when(kubernetes.text(startsWith("/core/pods/session-tm/log"), eq(4 * 1024 * 1024)))
        .thenReturn("password=hide-me");
    mvc.perform(post("/api/sessions/sql-validation/diagnostics").with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.pods.length()").value(1));
    String body = "{\"pod\":\"session-tm\",\"container\":\"flink-main-container\"}";
    mvc.perform(
            post("/api/sessions/sql-validation/logs")
                .with(csrf())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.text").value("password=******"));
    mvc.perform(
            post("/api/sessions/sql-validation/logs")
                .with(csrf())
                .contentType("application/json")
                .content(body.replace("session-tm", "external-pod")))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/sessions/sql-validation/logs")
                .with(csrf())
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.user("viewer")
                        .roles("VIEWER"))
                .contentType("application/json")
                .content(body))
        .andExpect(status().isForbidden());
    cluster.get().withObject("/metadata").put("uid", "replaced");
    mvc.perform(post("/api/sessions/sql-validation/diagnostics").with(csrf()))
        .andExpect(status().isConflict());
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
