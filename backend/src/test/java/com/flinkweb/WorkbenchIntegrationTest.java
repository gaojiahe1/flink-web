package com.flinkweb;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.flinkweb.client.kubernetes.KubernetesClient;
import com.flinkweb.dto.job.creation.DraftRequest;
import com.flinkweb.dto.sql.SqlDraftRequest;
import com.flinkweb.entity.job.JobDraft;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.job.creation.JobService;
import com.flinkweb.service.job.creation.YamlService;
import com.flinkweb.service.job.release.ReleaseService;
import com.flinkweb.service.job.runtime.DiagnosticService;
import com.flinkweb.service.job.runtime.OperationService;
import com.flinkweb.service.job.runtime.SnapshotService;
import com.flinkweb.service.permission.AccessService;
import com.flinkweb.service.sql.SqlJobService;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 使用真实 SQLite、MVC 鉴权和集群协议替身验证工作台的权限及历史边界。 */
@SpringBootTest
@AutoConfigureMockMvc
class WorkbenchIntegrationTest {
  static final Path DATABASE;

  static {
    try {
      DATABASE = Files.createTempDirectory("workbench-test-").resolve("test.db");
    } catch (Exception ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  /** 每个测试上下文使用独立元数据文件和仅供测试的凭据。 */
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
  @Autowired JobService jobs;
  @Autowired AccessService access;
  @Autowired SnapshotService snapshots;
  @Autowired ReleaseService releases;
  @Autowired SqlJobService sql;
  @Autowired OperationService operations;
  @Autowired com.flinkweb.service.job.runtime.StopService stops;
  @Autowired YamlService yaml;
  @Autowired DiagnosticService diagnostics;
  @Autowired com.flinkweb.collector.job.FlinkStateCollector collector;
  @Autowired com.flinkweb.service.job.management.JobQueryService query;
  @Autowired MockMvc mvc;

  /** 空白草稿创建即占用名称，其他入口不可复用，删除后可重新创建。 */
  @Test
  void emptySqlDraftReservesGlobalImmutableName() {
    actor("admin", "ADMIN");
    String name = "empty-sql-" + UUID.randomUUID().toString().substring(0, 8);
    var saved = sql.save(name, new SqlDraftRequest(name, "", "", mapper.createObjectNode(), 0));
    assertThat(saved.path("sql").asText()).isEmpty();
    assertThat(saved.at("/runtime/upgradeMode").asText()).isEqualTo("stateless");
    assertThat(jobs.nameAvailable(name, "")).isFalse();
    assertThatThrownBy(() -> sql.save(name, new SqlDraftRequest(name, "", "", mapper.createObjectNode(), 0)))
        .hasMessageContaining("名称已存在");
    assertThatThrownBy(() -> sql.save(name, new SqlDraftRequest(name + "-changed", "", "", mapper.createObjectNode(), 1)))
        .hasMessageContaining("名称不可修改");
    var jar = draft();
    assertThatThrownBy(() -> jobs.create(new DraftRequest(name, "", jar.flinkVersion(), jar.yaml(), 0)))
        .hasMessageContaining("SQL 草稿占用");
    sql.delete(name, saved.path("revision").asLong());
    assertThat(jobs.nameAvailable(name, "")).isTrue();
  }

  /** YAML 顶层和部署字段按稳定结构展示，不排序或丢弃用户的扩展值。 */
  @Test
  void yamlUsesStableResourceOrderAndPreservesUnknownValues() {
    var value = mapper.createObjectNode();
    value.putObject("metadata").put("namespace", "test").put("name", "ordered");
    value.putObject("spec").putObject("job").put("upgradeMode", "stateless").put("parallelism", 1);
    value.withObject("/spec").put("flinkVersion", "v1_20").put("image", "image:test");
    value.withObject("/spec").putObject("flinkConfiguration").put("z-custom", "value").put("a-custom", true);
    value.put("kind", "FlinkDeployment").put("apiVersion", "flink.apache.org/v1beta1");
    String rendered = yaml.dump(value);
    assertThat(rendered).startsWith("apiVersion:");
    assertThat(rendered.indexOf("kind:")).isLessThan(rendered.indexOf("metadata:"));
    assertThat(rendered.indexOf("metadata:")).isLessThan(rendered.indexOf("spec:"));
    assertThat(rendered.indexOf("image:")).isLessThan(rendered.indexOf("flinkVersion:"));
    assertThat(yaml.parse(rendered)).isEqualTo(value);
  }
  @MockitoBean KubernetesClient kubernetes;

  /** 显式切换服务调用身份，避免依赖 MVC 请求结束后的线程上下文。 */
  void actor(String name, String role) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                name, "", AuthorityUtils.createAuthorityList("ROLE_" + role)));
  }

  /** 测试结束清理线程身份。 */
  @AfterEach
  void cleanup() {
    SecurityContextHolder.clearContext();
  }

  /** 创建最小作业草稿，由管理员拥有。 */
  JobDraft draft() {
    actor("admin", "ADMIN");
    String name = "wb-" + UUID.randomUUID().toString().substring(0, 8);
    String source =
        """
        apiVersion: flink.apache.org/v1beta1
        kind: FlinkDeployment
        metadata:
          name: %s
        spec:
          image: registry.test/flink:1.20.3
          flinkVersion: v1_20
          job:
            jarURI: local:///opt/flink/usrlib/job.jar
            parallelism: 1
            upgradeMode: savepoint
            state: running
        """
            .formatted(name);
    return jobs.create(new DraftRequest(name, "original", "1.20.3", source, 0));
  }

  /** 标记测试集群资源的 UID 与平台所属关系。 */
  ObjectNode live(JobDraft job, String state, String desired) {
    var node = yaml.parse(job.yaml());
    node.withObject("/metadata")
        .put("name", job.deploymentName())
        .put("uid", "uid-" + job.id())
        .put("resourceVersion", "5")
        .withObject("/labels")
        .put("flink-web/job-id", job.id());
    node.withObject("/spec/job").put("state", desired);
    node.withObject("/status/jobStatus").put("state", state);
    return node;
  }

  /** 创建唯一幂等请求。 */
  ObjectNode command(JobDraft job) {
    return mapper
        .createObjectNode()
        .put("requestId", UUID.randomUUID().toString())
        .put("revision", job.revision());
  }

  /** 重复提交作业名称应明确返回冲突，不再成为 SQLite 未分类存储错误。 */
  @Test
  void duplicateDraftNameReturnsConflictWithoutPartialRecords() throws Exception {
    var job = draft();
    long before = jobs.list().size();
    mvc.perform(
            post("/api/jobs")
                .with(user("alice").roles("DEVELOPER"))
                .with(csrf())
                .contentType("application/json")
                .content(
                    mapper.writeValueAsString(
                        new DraftRequest(job.name(), "", job.flinkVersion(), job.yaml(), 0))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("JOB_NAME_EXISTS"));
    actor("admin", "ADMIN");
    assertThat(jobs.list()).hasSize((int) before);
  }

  /** 列表、直接草稿接口和 Flink UI 都拒绝越权；只读组无法更新草稿。 */
  @Test
  void aclProtectsDirectEndpointsAndHonorsReadOnlyGroups() throws Exception {
    var job = draft();
    mvc.perform(get("/api/jobs/" + job.id()).with(user("outsider").roles("DEVELOPER")))
        .andExpect(status().isForbidden());
    mvc.perform(get("/flink-ui/" + job.id() + "/").with(user("outsider").roles("DEVELOPER")))
        .andExpect(status().isForbidden());
    actor("outsider", "DEVELOPER");
    assertThat(jobs.list()).noneMatch(j -> j.id().equals(job.id()));
    records.save(
        "groups",
        "team-" + job.id(),
        mapper.createObjectNode().set("members", mapper.valueToTree(List.of("outsider"))),
        0);
    var acl = records.require("access", job.id());
    acl.putArray("readGroups").add("team-" + job.id());
    records.save("access", job.id(), acl, acl.path("revision").asLong());
    assertThat(jobs.get(job.id()).id()).isEqualTo(job.id());
    assertThat(access.get(job.id()).path("canOperate").asBoolean()).isFalse();
    assertThatThrownBy(
            () ->
                jobs.update(
                    job.id(),
                    new DraftRequest(
                        job.name(), "forbidden", "1.20.3", job.yaml(), job.revision())))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("权限");
    mvc.perform(get("/api/platform/job-access").with(user("outsider").roles("DEVELOPER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data['" + job.id() + "'].canEdit").value(false));
  }

  /** 服务端使用即时状态阻止运行中、挂起和未知状态删除，终态才允许。 */
  @Test
  void deletionAndSuspensionAreCheckedAgainstFreshClusterState() {
    var job = draft();
    when(kubernetes.base()).thenReturn("/cr");
    for (String state : List.of("RUNNING", "UNKNOWN", "SUSPENDED", "FINISHED")) {
      var node = live(job, state, state.equals("FINISHED") ? "suspended" : "running");
      when(kubernetes.call(eq("GET"), anyString(), isNull())).thenReturn(node);
      var result = operations.execute(job.id(), "delete", command(job), () -> "admin");
      assertThat(result.path("state").asText()).isEqualTo("REJECTED");
    }
    verify(kubernetes, never()).call(eq("DELETE"), anyString(), any());
    when(kubernetes.call(eq("GET"), anyString(), isNull()))
        .thenReturn(live(job, "FINISHED", "running"));
    assertThat(
            operations
                .execute(job.id(), "suspend", command(job), () -> "admin")
                .path("state")
                .asText())
        .isEqualTo("REJECTED");
    when(kubernetes.call(eq("DELETE"), anyString(), any())).thenReturn(mapper.createObjectNode());
    assertThat(
            operations
                .execute(job.id(), "delete", command(job), () -> "admin")
                .path("state")
                .asText())
        .isEqualTo("ACCEPTED");
  }

  /** 发布快照保留旧内容，恢复只更新草稿，拒绝跨作业和旧修订号。 */
  @Test
  void releaseRestoreIsImmutableScopedAndDoesNotDeploy() {
    var job = draft();
    var another = draft();
    var captured = releases.capture(job, yaml.parse(job.yaml()));
    String releaseId = captured.path("id").asText();
    var edited =
        jobs.update(
            job.id(),
            new DraftRequest(job.name(), "modified", "1.20.3", job.yaml(), job.revision()));
    var restored = releases.restore(job.id(), releaseId, edited.revision(), jobs);
    assertThat(restored.description()).isEqualTo("original");
    assertThat(records.require("releases", releaseId).toString()).isEqualTo(captured.toString());
    assertThat(captured.path("contentSha256").asText()).hasSize(64);
    assertThatThrownBy(() -> releases.restore(job.id(), releaseId, edited.revision(), jobs))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> releases.restore(another.id(), releaseId, another.revision(), jobs))
        .isInstanceOf(BusinessException.class);
    verifyNoInteractions(kubernetes);
  }

  /** 已轮换的历史密钥也不能作为环境变量字面值进入发布历史。 */
  @Test
  void releaseManifestMasksHistoricalEnvironmentValues() {
    var job = draft();
    var resource = yaml.parse(job.yaml());
    resource
        .withObject("/spec/podTemplate/spec")
        .putArray("containers")
        .addObject()
        .put("name", "flink-main-container")
        .putArray("env")
        .addObject()
        .put("name", "PASSWORD")
        .put("value", "historical-secret-no-longer-in-config");
    resource
        .withObject("/spec/flinkConfiguration")
        .put("s3.access-key", "historical-s3-access")
        .put("s3.secret-key", "historical-s3-secret");
    var captured = releases.capture(job, resource);
    assertThat(captured.toString())
        .doesNotContain(
            "historical-secret-no-longer-in-config",
            "historical-s3-access",
            "historical-s3-secret");
    assertThat(resource.toString()).contains("historical-secret-no-longer-in-config");
  }

  /** 停止前的原配置加密保存，重建仍能还原当时密钥而非未发布草稿。 */
  @Test
  void stopSnapshotEncryptsLiteralCredentialsAndRestoresOriginalConfiguration() {
    var job = draft();
    var resource = savepointStopFixture(job);
    resource
        .withObject("/spec/podTemplate/spec")
        .putArray("containers")
        .addObject()
        .put("name", "flink-main-container")
        .putArray("env")
        .addObject()
        .put("name", "PASSWORD")
        .put("value", "deployed-secret-at-stop");
    stops.stop(job.id(), "stateless", resource);
    var stored = records.require("stop-intents", job.id());
    assertThat(stored.has("encryptedDeployment")).isTrue();
    assertThat(stored.toString()).doesNotContain("deployed-secret-at-stop");
    assertThat(stops.restartResource(job.id()).toString()).contains("deployed-secret-at-stop");
  }

  /** SQL 保存历史恢复形成新修订且不更改另一个 SQL 草稿。 */
  @Test
  void sqlRestoreCreatesANewRevisionWithoutPublishing() {
    actor("admin", "ADMIN");
    String id = "sql-" + UUID.randomUUID();
    var runtime = mapper.createObjectNode().put("imageId", "example");
    var first =
        sql.save(id, new SqlDraftRequest(id, "first", "INSERT INTO sink VALUES (1);", runtime, 0));
    var version = sql.versions(id).get(0);
    var second =
        sql.save(
            id,
            new SqlDraftRequest(
                id,
                "second",
                "INSERT INTO sink VALUES (2);",
                runtime,
                first.path("revision").asLong()));
    var restored =
        sql.restoreVersion(id, version.path("id").asText(), second.path("revision").asLong());
    assertThat(restored.path("sql").asText()).contains("VALUES (1)");
    assertThat(sql.versions(id)).hasSize(3);
    assertThat(restored.path("revision").asLong()).isEqualTo(3);
    assertThatThrownBy(() -> sql.restoreVersion(id, version.path("id").asText(), 1))
        .isInstanceOf(BusinessException.class);
    verifyNoInteractions(kubernetes);
  }

  /** 多用户并发发现相同快照只登记一次，观测不能提前结束 SUBMITTING 操作。 */
  @Test
  void concurrentSnapshotsDeduplicateAndKeepSubmittingOperation() throws Exception {
    var job = draft();
    String request = UUID.randomUUID().toString();
    records.save(
        "operations",
        request,
        mapper.createObjectNode().put("jobId", job.id()).put("state", "SUBMITTING"),
        0);
    var row =
        mapper
            .createObjectNode()
            .put("jobId", job.id())
            .put("uid", "uid")
            .put("lastRequest", request)
            .put("jobState", "RUNNING");
    row.putObject("savepointInfo")
        .putObject("lastSavepoint")
        .put("location", "s3://state/savepoint-one");
    var pool = Executors.newFixedThreadPool(4);
    try {
      var futures = new ArrayList<Future<?>>();
      for (int i = 0; i < 8; i++)
        futures.add(pool.submit(() -> snapshots.observe(row, "2026-09-24T00:00:00Z")));
      for (var future : futures) future.get(10, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }
    assertThat(snapshots.list(job.id())).hasSize(1);
    assertThat(records.require("operations", request).has("observedAt")).isFalse();
    var operation = records.require("operations", request);
    records.save(
        "operations",
        request,
        operation.put("state", "ACCEPTED"),
        operation.path("revision").asLong());
    snapshots.observe(row, "2026-09-24T00:00:10Z");
    var observed = records.require("operations", request);
    assertThat(observed.path("state").asText()).isEqualTo("ACCEPTED");
    assertThat(observed.path("observedState").asText()).isEqualTo("RUNNING");
  }

  /** 已移除的 CI 与矩阵接口不可通过会话或旧 Bearer 令牌重新访问。 */
  @Test
  void removedIntegrationEndpointsAreUnavailable() throws Exception {
    mvc.perform(get("/api/ci/jobs/missing").with(user("admin").roles("ADMIN")))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/platform/compatibility").with(user("admin").roles("ADMIN")))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/platform/tokens").with(user("admin").roles("ADMIN")))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/ci/jobs/missing").header("Authorization", "Bearer obsolete"))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(kubernetes);
  }

  /** 仅标签匹配的伪造 Pod 不可读日志，合法所有者的输出会遮蔽常见凭据。 */
  @Test
  void logsRequireOwnerChainAndRedactCredentials() {
    var job = draft();
    var deployment = live(job, "RUNNING", "running");
    when(kubernetes.require(job.deploymentName())).thenReturn(deployment);
    when(kubernetes.core()).thenReturn("/api/v1/namespaces/test");
    var pod = mapper.createObjectNode();
    pod.putObject("metadata").putObject("labels").put("app", job.deploymentName());
    pod.putObject("spec").putArray("containers").addObject().put("name", "main");
    when(kubernetes.call(eq("GET"), contains("/pods/pod-test"), isNull())).thenReturn(pod);
    assertThatThrownBy(() -> diagnostics.log(job.id(), "pod-test", "main", 10000, false))
        .isInstanceOf(BusinessException.class);
    verify(kubernetes, never()).text(anyString(), anyInt());
    pod.withObject("/metadata")
        .putArray("ownerReferences")
        .addObject()
        .put("kind", "FlinkDeployment")
        .put("uid", "uid-" + job.id());
    when(kubernetes.text(anyString(), eq(4 * 1024 * 1024)))
        .thenReturn("password=should-not-show Bearer hidden-token https://user:secret@host/");
    String text = diagnostics.log(job.id(), "pod-test", "main", 10000, false).path("text").asText();
    assertThat(text).doesNotContain("should-not-show", "hidden-token", "user:secret");
    verify(kubernetes).text(contains("tailLines=10000"), eq(4 * 1024 * 1024));
    verify(kubernetes)
        .text(
            argThat(path -> !path.contains("limitBytes=") && path.contains("previous=false")),
            eq(4 * 1024 * 1024));
  }

  /** 编号稳定递增，名称预检不能通过不可见作业绕过唯一性。 */
  @Test
  void jobNumbersAreStableAndNeverReused() throws Exception {
    var first = draft();
    assertThat(first.jobNumber()).isPositive();
    assertThat(first.deploymentName()).isEqualTo(JobDraft.numberedName(first.jobNumber()));
    mvc.perform(
            get("/api/jobs/name-availability")
                .param("name", first.name())
                .with(user("outsider").roles("DEVELOPER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.available").value(false));
    actor("admin", "ADMIN");
    var updated =
        jobs.update(
            first.id(),
            new DraftRequest(
                first.name(), "updated", first.flinkVersion(), first.yaml(), first.revision()));
    assertThat(updated.jobNumber()).isEqualTo(first.jobNumber());
    assertThat(updated.deploymentName()).isEqualTo(first.deploymentName());
    jobs.delete(updated.id(), updated.revision());
    var second = draft();
    assertThat(second.jobNumber()).isGreaterThan(first.jobNumber());
    assertThat(jobs.get(second.id()).jobNumber()).isEqualTo(second.jobNumber());
  }

  /** 启动阶段允许停止；快照只在实时正常运行时提交，重复启动被拒绝。 */
  @Test
  void startingCanStopButOnlyStableRunningCanSnapshot() {
    var job = draft();
    when(kubernetes.base()).thenReturn("/cr");
    when(kubernetes.call(eq("PUT"), anyString(), any()))
        .thenAnswer(invocation -> ((ObjectNode) invocation.getArgument(2)).deepCopy());
    for (String state : List.of("CREATED", "INITIALIZING", "RESTARTING", "UNKNOWN")) {
      var resource = live(job, state, "running");
      when(kubernetes.call(eq("GET"), anyString(), isNull())).thenReturn(resource);
      assertThat(
              operations
                  .execute(job.id(), "start", command(job), () -> "admin")
                  .path("state")
                  .asText())
          .isEqualTo("REJECTED");
      for (String action : List.of("savepoint", "checkpoint"))
        assertThat(
                operations
                    .execute(job.id(), action, command(job), () -> "admin")
                    .path("state")
                    .asText())
            .isEqualTo("REJECTED");
      assertThat(
              operations
                  .execute(
                      job.id(), "stop", command(job).put("upgradeMode", "stateless"), () -> "admin")
                  .path("state")
                  .asText())
          .isEqualTo("ACCEPTED");
    }
    stops.clear(job.id());
    var resource = live(job, "RUNNING", "suspended");
    when(kubernetes.call(eq("GET"), anyString(), isNull())).thenReturn(resource);
    assertThat(
            operations
                .execute(job.id(), "checkpoint", command(job), () -> "admin")
                .path("state")
                .asText())
        .isEqualTo("REJECTED");
    resource.withObject("/spec/job").put("state", "running");
    resource.withObject("/metadata").put("generation", 2);
    resource.withObject("/status").put("observedGeneration", 1);
    assertThat(
            operations
                .execute(job.id(), "savepoint", command(job), () -> "admin")
                .path("state")
                .asText())
        .isEqualTo("REJECTED");
    resource.withObject("/status").put("observedGeneration", 2).put("lifecycleState", "STABLE");
    for (String action : List.of("savepoint", "checkpoint"))
      assertThat(
              operations
                  .execute(job.id(), action, command(job), () -> "admin")
                  .path("state")
                  .asText())
          .isEqualTo("ACCEPTED");
  }

  /** 保留状态的停止必须观测到新的 Savepoint 后才删除，并且删除前保存恢复路径。 */
  @Test
  void stopPersistsSavepointBeforeDeletingAndCanResume() {
    var job = draft();
    var resource = live(job, "RUNNING", "running");
    resource
        .withObject("/spec/flinkConfiguration")
        .put("state.savepoints.dir", "s3://state/savepoints");
    resource
        .withObject("/status/jobStatus/savepointInfo/lastSavepoint")
        .put("location", "s3://state/old")
        .put("triggerType", "UPGRADE");
    when(kubernetes.base()).thenReturn("/cr");
    when(kubernetes.call(eq("GET"), anyString(), isNull())).thenReturn(resource);
    when(kubernetes.call(eq("PUT"), anyString(), any()))
        .thenAnswer(
            i -> {
              ObjectNode changed = ((ObjectNode) i.getArgument(2)).deepCopy();
              if (!i.getArgument(1).toString().contains("dryRun"))
                resource.set("spec", changed.path("spec").deepCopy());
              return changed;
            });
    when(kubernetes.call(eq("DELETE"), anyString(), any())).thenReturn(mapper.createObjectNode());
    assertThat(
            operations
                .execute(job.id(), "stop", command(job), () -> "admin")
                .path("state")
                .asText())
        .isEqualTo("ACCEPTED");
    assertThat(stops.pending(job.id())).isTrue();
    stops.advance(job.id());
    verify(kubernetes, never()).call(eq("DELETE"), anyString(), any());
    resource.withObject("/spec/job").put("state", "suspended");
    resource.withObject("/status").put("lifecycleState", "SUSPENDED");
    resource.withObject("/status/jobStatus").put("state", "FINISHED");
    stops.advance(job.id());
    verify(kubernetes, never()).call(eq("DELETE"), anyString(), any());
    resource
        .withObject("/status/jobStatus/savepointInfo/lastSavepoint")
        .put("location", "s3://state/new")
        .put("triggerType", "UPGRADE");
    var progress = stops.advance(job.id());
    assertThat(progress.path("phase").asText()).isEqualTo("DELETING");
    assertThat(stops.restorePath(job.id())).isEqualTo("s3://state/new");
    verify(kubernetes)
        .call(
            eq("DELETE"),
            eq("/cr/" + job.deploymentName()),
            argThat(body -> body.at("/preconditions/uid").asText().equals("uid-" + job.id())));
    resource.withObject("/metadata").put("deletionTimestamp", "2026-09-30T00:00:00Z");
    assertThat(
            operations
                .execute(job.id(), "stop", command(job), () -> "admin")
                .path("state")
                .asText())
        .isEqualTo("ACCEPTED");
    when(kubernetes.call(eq("GET"), anyString(), isNull())).thenReturn(MissingNode.getInstance());
    assertThat(stops.advance(job.id()).path("phase").asText()).isEqualTo("DONE");
    assertThat(stops.pending(job.id())).isFalse();
  }

  /** 停止中的资源不能重复启动或做快照，即便旧观测仍显示运行。 */
  @Test
  void immediateStopCanRepeatButBlocksStartingAndSnapshots() {
    var job = draft();
    var resource = live(job, "RUNNING", "running");
    when(kubernetes.base()).thenReturn("/cr");
    when(kubernetes.call(eq("GET"), anyString(), isNull())).thenReturn(resource);
    when(kubernetes.call(eq("DELETE"), anyString(), any())).thenReturn(mapper.createObjectNode());
    for (int i = 0; i < 2; i++)
      assertThat(
              operations
                  .execute(
                      job.id(), "stop", command(job).put("upgradeMode", "stateless"), () -> "admin")
                  .path("state")
                  .asText())
          .isEqualTo("ACCEPTED");
    for (String action : List.of("start", "savepoint", "checkpoint", "deploy"))
      assertThat(
              operations
                  .execute(job.id(), action, command(job), () -> "admin")
                  .path("state")
                  .asText())
          .isEqualTo("REJECTED");
    verify(kubernetes, atLeast(2)).call(eq("DELETE"), anyString(), any());
    verify(kubernetes, never()).call(eq("PUT"), anyString(), any());
    verify(kubernetes, never()).call(eq("POST"), anyString(), any());
  }

  /** 原资源尚在时，编辑角色直接启动使用新账号，运维角色继续保留原账号。 */
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"ADMIN", "DEVELOPER", "OPERATOR"})
  void startExistingResourceAppliesAccountOnlyForEditors(String role) {
    var job = draft();
    var resource = live(job, "FINISHED", "suspended");
    resource.withObject("/spec").put("serviceAccount", "legacy-flink");
    var edited = yaml.parse(job.yaml());
    edited.withObject("/spec").put("serviceAccount", "team-flink");
    var changed =
        jobs.update(
            job.id(),
            new DraftRequest(
                job.name(), "new account", job.flinkVersion(), yaml.dump(edited), job.revision()));
    actor("admin", role);
    when(kubernetes.base()).thenReturn("/cr");
    when(kubernetes.call(eq("GET"), anyString(), isNull())).thenReturn(resource);
    when(kubernetes.call(eq("PUT"), anyString(), any()))
        .thenAnswer(i -> ((ObjectNode) i.getArgument(2)).deepCopy());
    assertThat(
            operations
                .execute(
                    job.id(),
                    "start",
                    command(changed).put("upgradeMode", "stateless"),
                    () -> "admin")
                .path("state")
                .asText())
        .isEqualTo("ACCEPTED");
    String expected = role.equals("OPERATOR") ? "legacy-flink" : "team-flink";
    verify(kubernetes)
        .call(
            eq("PUT"),
            eq("/cr/" + job.deploymentName()),
            argThat(body -> body.at("/spec/serviceAccount").asText().equals(expected)));
  }

  /** 对已有运行资源显式部署，也必须提交最新草稿账号而非旧集群配置。 */
  @Test
  void deployExistingResourceUpdatesServiceAccount() {
    var job = draft();
    var resource = live(job, "RUNNING", "running");
    resource.withObject("/spec").put("serviceAccount", "legacy-flink");
    var edited = yaml.parse(job.yaml());
    edited.withObject("/spec").put("serviceAccount", "team-flink");
    var changed =
        jobs.update(
            job.id(),
            new DraftRequest(
                job.name(), "new account", job.flinkVersion(), yaml.dump(edited), job.revision()));
    when(kubernetes.base()).thenReturn("/cr");
    when(kubernetes.call(eq("GET"), anyString(), isNull())).thenReturn(resource);
    when(kubernetes.call(eq("PUT"), anyString(), any()))
        .thenAnswer(i -> ((ObjectNode) i.getArgument(2)).deepCopy());
    assertThat(
            operations
                .execute(job.id(), "deploy", command(changed), () -> "admin")
                .path("state")
                .asText())
        .isEqualTo("ACCEPTED");
    verify(kubernetes)
        .call(
            eq("PUT"),
            eq("/cr/" + job.deploymentName()),
            argThat(body -> body.at("/spec/serviceAccount").asText().equals("team-flink")));
  }

  /** 旧作业停止后，编辑者启动或显式部署均采用已保存的新运行账号。 */
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"start", "deploy"})
  void editorAfterStopUsesSavedServiceAccount(String action) {
    var job = draft();
    var resource = live(job, "RUNNING", "running");
    resource.withObject("/spec").put("serviceAccount", "legacy-flink");
    when(kubernetes.base()).thenReturn("/cr");
    when(kubernetes.call(eq("GET"), anyString(), isNull())).thenReturn(resource);
    when(kubernetes.call(eq("DELETE"), anyString(), any())).thenReturn(mapper.createObjectNode());
    assertThat(
            operations
                .execute(
                    job.id(), "stop", command(job).put("upgradeMode", "stateless"), () -> "admin")
                .path("state")
                .asText())
        .isEqualTo("ACCEPTED");
    var edited = yaml.parse(job.yaml());
    edited.withObject("/spec").put("serviceAccount", "team-flink");
    var changed =
        jobs.update(
            job.id(),
            new DraftRequest(
                job.name(), "new account", job.flinkVersion(), yaml.dump(edited), job.revision()));
    when(kubernetes.call(eq("GET"), anyString(), isNull())).thenReturn(MissingNode.getInstance());
    when(kubernetes.call(eq("POST"), anyString(), any()))
        .thenAnswer(i -> ((ObjectNode) i.getArgument(2)).deepCopy());
    assertThat(
            operations
                .execute(job.id(), action, command(changed), () -> "admin")
                .path("state")
                .asText())
        .isEqualTo("ACCEPTED");
    verify(kubernetes)
        .call(
            eq("POST"),
            eq("/cr"),
            argThat(body -> body.at("/spec/serviceAccount").asText().equals("team-flink")));
  }

  /** 仅有运维权限时仍复用停止前账号，不能发布编辑者保存的草稿变更。 */
  @Test
  void operatorAfterStopKeepsDeployedServiceAccount() {
    var job = draft();
    var resource = live(job, "RUNNING", "running");
    resource.withObject("/spec").put("serviceAccount", "legacy-flink");
    when(kubernetes.base()).thenReturn("/cr");
    when(kubernetes.call(eq("GET"), anyString(), isNull())).thenReturn(resource);
    when(kubernetes.call(eq("DELETE"), anyString(), any())).thenReturn(mapper.createObjectNode());
    assertThat(
            operations
                .execute(
                    job.id(), "stop", command(job).put("upgradeMode", "stateless"), () -> "admin")
                .path("state")
                .asText())
        .isEqualTo("ACCEPTED");
    var edited = yaml.parse(job.yaml());
    edited.withObject("/spec").put("serviceAccount", "team-flink");
    var changed =
        jobs.update(
            job.id(),
            new DraftRequest(
                job.name(), "new account", job.flinkVersion(), yaml.dump(edited), job.revision()));
    actor("admin", "OPERATOR");
    when(kubernetes.call(eq("GET"), anyString(), isNull())).thenReturn(MissingNode.getInstance());
    when(kubernetes.call(eq("POST"), anyString(), any()))
        .thenAnswer(i -> ((ObjectNode) i.getArgument(2)).deepCopy());
    assertThat(
            operations
                .execute(job.id(), "start", command(changed), () -> "admin")
                .path("state")
                .asText())
        .isEqualTo("ACCEPTED");
    verify(kubernetes)
        .call(
            eq("POST"),
            eq("/cr"),
            argThat(body -> body.at("/spec/serviceAccount").asText().equals("legacy-flink")));
  }

  /** 重建复用停止前的运行配置，不把未部署草稿修改隐式发布，也保留旧工作负载账号。 */
  @Test
  void startAfterStopUsesDeployedConfigurationInsteadOfEditedDraft() {
    var job = draft();
    var resource = live(job, "RUNNING", "running");
    resource.withObject("/spec").put("serviceAccount", "legacy-flink");
    resource.withObject("/spec/job").put("parallelism", 2);
    var init =
        resource
            .withObject("/spec/podTemplate/spec")
            .putArray("initContainers")
            .addObject()
            .put("name", "fw-download")
            .put("image", "legacy/downloader:old");
    init.putArray("env")
        .addObject()
        .put("name", "ARTIFACT_MANIFEST")
        .put(
            "value",
            "[{\"uri\":\"https://files.test/job.jar\",\"filename\":\"job.jar\",\"sha256\":\"old\"}]");
    when(kubernetes.base()).thenReturn("/cr");
    when(kubernetes.call(eq("GET"), anyString(), isNull())).thenReturn(resource);
    when(kubernetes.call(eq("DELETE"), anyString(), any())).thenReturn(mapper.createObjectNode());
    assertThat(
            operations
                .execute(
                    job.id(), "stop", command(job).put("upgradeMode", "stateless"), () -> "admin")
                .path("state")
                .asText())
        .isEqualTo("ACCEPTED");
    var edited = yaml.parse(job.yaml());
    edited.withObject("/spec/job").put("parallelism", 9);
    var changed =
        jobs.update(
            job.id(),
            new DraftRequest(
                job.name(), "not deployed", job.flinkVersion(), yaml.dump(edited), job.revision()));
    when(kubernetes.call(eq("GET"), anyString(), isNull())).thenReturn(MissingNode.getInstance());
    when(kubernetes.call(eq("POST"), anyString(), any()))
        .thenAnswer(i -> ((ObjectNode) i.getArgument(2)).deepCopy());
    assertThat(
            operations
                .execute(job.id(), "start", command(changed), () -> "admin")
                .path("state")
                .asText())
        .isEqualTo("ACCEPTED");
    verify(kubernetes)
        .call(
            eq("POST"),
            eq("/cr"),
            argThat(
                body ->
                    body.at("/spec/job/parallelism").asInt() == 2
                        && body.at("/spec/serviceAccount").asText().equals("legacy-flink")
                        && body.at("/spec/podTemplate/spec/initContainers/0/image")
                            .asText()
                            .equals("registry.test/downloader:current")
                        && !body.at("/spec/podTemplate/spec/initContainers/0/env")
                            .toString()
                            .contains("sha256")
                        && !body.at("/spec/podTemplate/spec/initContainers/0/env")
                            .toString()
                            .contains("AWS_ACCESS_KEY_ID")
                        && !body.path("metadata").has("uid")));
    assertThat(stops.advance(job.id())).isNull();
  }

  /** JobManager 异常优先于协调中状态，且不能触发快照。 */
  @Test
  void deploymentErrorsOverrideReconcilingAndRejectSnapshots() {
    var job = draft();
    var resource = live(job, "RECONCILING", "running");
    resource
        .withObject("/status")
        .put("jobManagerDeploymentStatus", "ERROR")
        .put("error", "downloader failed")
        .put("lifecycleState", "DEPLOYED");
    var items = mapper.createObjectNode();
    items.putArray("items").add(resource);
    when(kubernetes.base()).thenReturn("/cr");
    when(kubernetes.call(eq("GET"), anyString(), isNull())).thenReturn(mapper.createObjectNode());
    when(kubernetes.call("GET", "/cr", null)).thenReturn(items);
    var row = collector.collect().path("items").get(0);
    assertThat(row.path("jobState").asText()).isEqualTo("RECONCILING");
    assertThat(row.path("healthState").asText()).isEqualTo("ERROR");
    assertThat(row.path("error").asText()).isEqualTo("downloader failed");
    resource.withObject("/status/jobStatus").put("state", "RUNNING");
    assertThatThrownBy(() -> stops.stop(job.id(), "savepoint", resource))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("正常运行");
    when(kubernetes.call("GET", "/cr/" + job.deploymentName(), null)).thenReturn(resource);
    for (String action : List.of("savepoint", "checkpoint"))
      assertThat(
              operations
                  .execute(job.id(), action, command(job), () -> "admin")
                  .path("state")
                  .asText())
          .isEqualTo("REJECTED");
  }

  /** Pod 诊断包含初始化失败、节点和条件，同时排除伪造同标签的资源。 */
  @Test
  void diagnosticsExposeInitFailureAndOnlyOwnedPods() {
    var job = draft();
    var deployment = live(job, "RECONCILING", "running");
    var pod = mapper.createObjectNode();
    pod.withObject("/metadata")
        .put("name", "pod-test")
        .put("uid", "pod-uid")
        .put("creationTimestamp", "2026-10-01T00:00:00Z")
        .putArray("ownerReferences")
        .addObject()
        .put("kind", "FlinkDeployment")
        .put("uid", "uid-" + job.id());
    pod.withObject("/spec")
        .put("nodeName", "worker-1")
        .putArray("initContainers")
        .addObject()
        .put("name", "fw-download");
    pod.withObject("/status")
        .put("phase", "Pending")
        .putArray("initContainerStatuses")
        .addObject()
        .put("name", "fw-download")
        .put("restartCount", 3)
        .put("ready", false)
        .withObject("/state/terminated")
        .put("exitCode", 1)
        .put("reason", "Error")
        .put("message", "download failed password=hidden-value");
    pod.withObject("/status")
        .putArray("conditions")
        .addObject()
        .put("type", "Initialized")
        .put("status", "False")
        .put("reason", "ContainersNotInitialized");
    var list = mapper.createObjectNode();
    list.putArray("items").add(pod).add(mapper.createObjectNode().put("fake", true));
    when(kubernetes.require(job.deploymentName())).thenReturn(deployment);
    when(kubernetes.core()).thenReturn("/api/v1/namespaces/test");
    when(kubernetes.call(eq("GET"), anyString(), isNull())).thenReturn(mapper.createObjectNode());
    when(kubernetes.call(eq("GET"), contains("/pods?"), isNull())).thenReturn(list);
    when(kubernetes.call(eq("GET"), contains("/pods/pod-test"), isNull())).thenReturn(pod);
    var result = diagnostics.inspect(job.id());
    assertThat(result.path("pods")).hasSize(1);
    var row = result.path("pods").get(0);
    assertThat(row.path("node").asText()).isEqualTo("worker-1");
    assertThat(row.at("/states/0/init").asBoolean()).isTrue();
    assertThat(row.at("/states/0/exitCode").asInt()).isEqualTo(1);
    assertThat(row.at("/conditions/0/status").asText()).isEqualTo("False");
    assertThat(result.toString()).doesNotContain("hidden-value");
  }

  /** 本次刷新应先完成停止再采集，不能返回删除之前的运行快照。 */
  @Test
  void refreshCollectsAfterAdvancingStop() {
    var job = draft();
    var resource = live(job, "RUNNING", "running");
    resource
        .withObject("/spec/flinkConfiguration")
        .put("state.savepoints.dir", "s3://state/savepoints");
    when(kubernetes.base()).thenReturn("/cr");
    var current =
        new java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode>(
            resource);
    when(kubernetes.call(eq("GET"), anyString(), isNull()))
        .thenAnswer(
            i -> {
              if ("/cr".equals(i.getArgument(1))) {
                var list = mapper.createObjectNode();
                if (!current.get().isMissingNode()) list.putArray("items").add(current.get());
                else list.putArray("items");
                return list;
              }
              return i.getArgument(1).equals("/cr/" + job.deploymentName())
                  ? current.get()
                  : mapper.createObjectNode();
            });
    when(kubernetes.call(eq("PUT"), anyString(), any())).thenAnswer(i -> i.getArgument(2));
    when(kubernetes.call(eq("DELETE"), anyString(), any()))
        .thenAnswer(
            i -> {
              current.set(MissingNode.getInstance());
              return mapper.createObjectNode();
            });
    assertThat(
            operations
                .execute(job.id(), "stop", command(job), () -> "admin")
                .path("state")
                .asText())
        .isEqualTo("ACCEPTED");
    resource.withObject("/spec/job").put("state", "suspended");
    resource
        .withObject("/status/jobStatus")
        .put("state", "FINISHED")
        .withObject("/savepointInfo/lastSavepoint")
        .put("location", "s3://state/new")
        .put("triggerType", "UPGRADE");
    resource.withObject("/status").put("lifecycleState", "SUSPENDED");
    var refreshed = query.refresh();
    assertThat(refreshed.path("items")).isEmpty();
    assertThat(refreshed.at("/stopProgress/" + job.id() + "/phase").asText()).isEqualTo("DELETING");
  }

  /** 未预期的客户端异常保留 UNKNOWN 结果，避免操作永久卡在 SUBMITTING。 */
  @Test
  void unexpectedClientFailureDoesNotLeaveSubmittingOperation() {
    var job = draft();
    var request = command(job);
    when(kubernetes.base()).thenReturn("/cr");
    when(kubernetes.call(eq("GET"), anyString(), isNull()))
        .thenThrow(new IllegalStateException("private details"));
    var result = operations.execute(job.id(), "start", request, () -> "admin");
    assertThat(result.path("state").asText()).isEqualTo("UNKNOWN");
    assertThat(result.toString()).doesNotContain("private details");
    assertThat(operations.execute(job.id(), "start", request, () -> "admin")).isEqualTo(result);
  }

  /** 使用实际配置代数和异步状态的集群替身准备停止流程。 */
  ObjectNode savepointStopFixture(JobDraft job) {
    var resource = live(job, "RUNNING", "running");
    resource.withObject("/metadata").put("generation", 1);
    resource.withObject("/status").put("observedGeneration", 1).put("lifecycleState", "STABLE");
    resource
        .withObject("/spec/flinkConfiguration")
        .put("state.savepoints.dir", "s3://state/savepoints");
    when(kubernetes.base()).thenReturn("/cr");
    when(kubernetes.call(eq("GET"), anyString(), isNull())).thenReturn(resource);
    when(kubernetes.call(eq("PUT"), anyString(), any()))
        .thenAnswer(
            i -> {
              ObjectNode changed = ((ObjectNode) i.getArgument(2)).deepCopy();
              if (!i.getArgument(1).toString().contains("dryRun")) {
                resource.set("spec", changed.path("spec").deepCopy());
                resource.withObject("/metadata").put("generation", 2).put("resourceVersion", "6");
                changed.withObject("/metadata").put("generation", 2).put("resourceVersion", "6");
              }
              return changed;
            });
    when(kubernetes.call(eq("DELETE"), anyString(), any())).thenReturn(mapper.createObjectNode());
    return resource;
  }

  /** 模拟新 Operator 完成停止并记录专用的升级保存点。 */
  void completeSavepointStop(ObjectNode resource) {
    resource.withObject("/status").put("observedGeneration", 2).put("lifecycleState", "SUSPENDED");
    resource
        .withObject("/status/jobStatus")
        .put("state", "FINISHED")
        .put("upgradeSavepointPath", "s3://state/stop-new");
    var snapshots = mapper.createObjectNode();
    var snapshot = snapshots.putArray("items").addObject();
    snapshot.withObject("/metadata/labels").put("snapshot.trigger-type", "UPGRADE");
    snapshot
        .withObject("/spec/jobReference")
        .put("kind", "FlinkDeployment")
        .put("name", resource.at("/metadata/name").asText());
    snapshot
        .withObject("/spec/savepoint")
        .put("alreadyExists", true)
        .put("path", "s3://state/stop-new");
    snapshot.withObject("/status").put("state", "COMPLETED").put("path", "s3://state/stop-new");
    when(kubernetes.call("GET", "/flinkstatesnapshots", null)).thenReturn(snapshots);
  }

  /** 新版 Operator 只更新 upgradeSavepointPath 时仍可完成停止并保留恢复地址。 */
  @Test
  void stopAcceptsUpgradeSavepointPathWithoutLegacyField() {
    var job = draft();
    var resource = savepointStopFixture(job);
    stops.stop(job.id(), "savepoint", resource);
    completeSavepointStop(resource);
    assertThat(stops.advance(job.id()).path("phase").asText()).isEqualTo("DELETING");
    assertThat(stops.restorePath(job.id())).isEqualTo("s3://state/stop-new");
    verify(kubernetes).call(eq("DELETE"), anyString(), any());
  }

  /** 仅观测到旧保存点、普通手动保存点或尚未确认的代数都不能删除。 */
  @Test
  void stopRejectsUnrelatedSavepointsAndStaleObservation() {
    var job = draft();
    var resource = savepointStopFixture(job);
    resource.withObject("/status/jobStatus").put("upgradeSavepointPath", "s3://state/old");
    stops.stop(job.id(), "savepoint", resource);
    completeSavepointStop(resource);
    resource.withObject("/status/jobStatus").put("upgradeSavepointPath", "s3://state/old");
    stops.advance(job.id());
    resource.withObject("/status/jobStatus").remove("upgradeSavepointPath");
    resource
        .withObject("/status/jobStatus/savepointInfo/lastSavepoint")
        .put("location", "s3://state/manual")
        .put("triggerType", "MANUAL");
    assertThatThrownBy(() -> stops.advance(job.id())).hasMessageContaining("尚未确认");
    completeSavepointStop(resource);
    resource.withObject("/status").put("observedGeneration", 1);
    stops.advance(job.id());
    verify(kubernetes, never()).call(eq("DELETE"), anyString(), any());
  }

  /** 删除失败后作业在外部恢复运行，刷新不得凭历史 DELETING 阶段继续删。 */
  @Test
  void failedDeleteDoesNotDeleteResumedJob() {
    var job = draft();
    var resource = savepointStopFixture(job);
    stops.stop(job.id(), "savepoint", resource);
    completeSavepointStop(resource);
    when(kubernetes.call(eq("DELETE"), anyString(), any()))
        .thenThrow(
            new BusinessException(
                org.springframework.http.HttpStatus.CONFLICT, "KUBERNETES_CONFLICT", "changed"));
    assertThatThrownBy(() -> stops.advance(job.id())).isInstanceOf(BusinessException.class);
    resource.withObject("/spec/job").put("state", "running");
    resource.withObject("/metadata").put("generation", 3);
    resource.withObject("/status").put("observedGeneration", 3).put("lifecycleState", "STABLE");
    resource.withObject("/status/jobStatus").put("state", "RUNNING");
    assertThatThrownBy(() -> stops.advance(job.id())).hasMessageContaining("配置已变化");
    verify(kubernetes, times(1)).call(eq("DELETE"), anyString(), any());
  }

  /** 只有状态版本变化的删除失败允许安全重试，恢复地址早于 DELETE 持久化。 */
  @Test
  void failedDeleteCanRetryWhileStopProofStillMatches() {
    var job = draft();
    var resource = savepointStopFixture(job);
    stops.stop(job.id(), "savepoint", resource);
    completeSavepointStop(resource);
    when(kubernetes.call(eq("DELETE"), anyString(), any()))
        .thenAnswer(
            i -> {
              assertThat(stops.restorePath(job.id())).isEqualTo("s3://state/stop-new");
              throw new BusinessException(
                  org.springframework.http.HttpStatus.CONFLICT, "KUBERNETES_CONFLICT", "changed");
            });
    assertThatThrownBy(() -> stops.advance(job.id())).isInstanceOf(BusinessException.class);
    when(kubernetes.call(eq("DELETE"), anyString(), any())).thenReturn(mapper.createObjectNode());
    resource.withObject("/metadata").put("resourceVersion", "8");
    stops.advance(job.id());
    verify(kubernetes)
        .call(
            eq("DELETE"),
            anyString(),
            argThat(v -> "8".equals(v.at("/preconditions/resourceVersion").asText())));
  }

  /** 保存点异常不删除资源，修复后刷新仍可继续；立即删除始终需要明确选择。 */
  @Test
  void savepointErrorIsVisibleAndRecoverable() {
    var job = draft();
    var resource = savepointStopFixture(job);
    stops.stop(job.id(), "savepoint", resource);
    resource.withObject("/status").put("error", "Savepoint failed");
    assertThatThrownBy(() -> stops.advance(job.id())).hasMessageContaining("Savepoint 异常");
    verify(kubernetes, never()).call(eq("DELETE"), anyString(), any());
    resource.withObject("/status").remove("error");
    completeSavepointStop(resource);
    stops.advance(job.id());
    verify(kubernetes).call(eq("DELETE"), anyString(), any());
  }

  /** 未结束的升级不能被 Savepoint 停止覆盖，未知结果重试不会重复提交挂起。 */
  @Test
  void stopWaitsForReconciliationAndSurvivesLostPutResponse() {
    var job = draft();
    var resource = savepointStopFixture(job);
    resource.withObject("/status").put("lifecycleState", "UPGRADING");
    assertThatThrownBy(() -> stops.stop(job.id(), "savepoint", resource))
        .hasMessageContaining("协调或升级");
    resource.withObject("/status").put("lifecycleState", "STABLE");
    when(kubernetes.call(eq("PUT"), argThat(p -> !p.contains("dryRun")), any()))
        .thenAnswer(
            i -> {
              resource.set("spec", ((ObjectNode) i.getArgument(2)).path("spec").deepCopy());
              resource.withObject("/metadata").put("generation", 2);
              throw new BusinessException(
                  org.springframework.http.HttpStatus.BAD_GATEWAY,
                  "KUBERNETES_UNAVAILABLE",
                  "timeout");
            });
    assertThatThrownBy(() -> stops.stop(job.id(), "savepoint", resource))
        .isInstanceOf(BusinessException.class);
    stops.advance(job.id());
    completeSavepointStop(resource);
    stops.advance(job.id());
    verify(kubernetes, times(1)).call(eq("PUT"), argThat(p -> !p.contains("dryRun")), any());
    verify(kubernetes).call(eq("DELETE"), anyString(), any());
  }

  /** 新终态路径可能是 Checkpoint，缺少对应停止 Savepoint 证据时不得删除。 */
  @Test
  void upgradePathWithoutSavepointProofDoesNotDelete() {
    var job = draft();
    var resource = savepointStopFixture(job);
    stops.stop(job.id(), "savepoint", resource);
    completeSavepointStop(resource);
    when(kubernetes.call("GET", "/flinkstatesnapshots", null))
        .thenReturn(mapper.createObjectNode());
    assertThatThrownBy(() -> stops.advance(job.id())).hasMessageContaining("尚未确认");
    verify(kubernetes, never()).call(eq("DELETE"), anyString(), any());
    completeSavepointStop(resource);
    stops.advance(job.id());
    verify(kubernetes).call(eq("DELETE"), anyString(), any());
  }

  /** 保存点已生成但停止配置被外部修改时，即使仍挂起也不能沿用旧证明删除。 */
  @Test
  void changedGenerationBlocksDeleteAndImmediateModeCanOverride() {
    var job = draft();
    var resource = savepointStopFixture(job);
    stops.stop(job.id(), "savepoint", resource);
    completeSavepointStop(resource);
    resource.withObject("/metadata").put("generation", 3);
    resource.withObject("/status").put("observedGeneration", 3);
    assertThatThrownBy(() -> stops.advance(job.id())).hasMessageContaining("配置已变化");
    verify(kubernetes, never()).call(eq("DELETE"), anyString(), any());
    resource.withObject("/status").put("error", "Savepoint failed");
    stops.stop(job.id(), "stateless", resource);
    verify(kubernetes).call(eq("DELETE"), anyString(), any());
  }

  /** 外部提前删除资源只能确认已停止，不能宣称本次状态保存成功。 */
  @Test
  void disappearingResourceDoesNotClaimSavepointSuccess() {
    var job = draft();
    var resource = savepointStopFixture(job);
    stops.stop(job.id(), "savepoint", resource);
    when(kubernetes.call("GET", "/cr/" + job.deploymentName(), null))
        .thenReturn(MissingNode.getInstance());
    var result = stops.advance(job.id());
    assertThat(result.path("phase").asText()).isEqualTo("DONE");
    assertThat(result.path("message").asText()).contains("未确认本次 Savepoint");
    assertThat(stops.restorePath(job.id())).isEmpty();
    verify(kubernetes, never()).call(eq("DELETE"), anyString(), any());
  }

  /** 改选立即删除时不能把尚未核实的终态路径登记成可恢复的保存点。 */
  @Test
  void immediateOverrideDoesNotPersistUnverifiedCheckpoint() {
    var job = draft();
    var resource = savepointStopFixture(job);
    stops.stop(job.id(), "savepoint", resource);
    completeSavepointStop(resource);
    when(kubernetes.call("GET", "/flinkstatesnapshots", null))
        .thenReturn(mapper.createObjectNode());
    stops.stop(job.id(), "stateless", resource);
    assertThat(stops.restorePath(job.id())).isEmpty();
    verify(kubernetes).call(eq("DELETE"), anyString(), any());
  }

  /** 旧停止请求不能作用于替换后的同名资源。 */
  @Test
  void replacementUidCannotBeDeletedByPendingStop() {
    var job = draft();
    var resource = savepointStopFixture(job);
    stops.stop(job.id(), "savepoint", resource);
    completeSavepointStop(resource);
    resource.withObject("/metadata").put("uid", "replacement-uid");
    assertThatThrownBy(() -> stops.advance(job.id())).isInstanceOf(BusinessException.class);
    verify(kubernetes, never()).call(eq("DELETE"), anyString(), any());
  }
}
