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
  @Autowired MockMvc mvc;
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
    node.withObject("/metadata").put("name", job.deploymentName())
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
    when(kubernetes.text(anyString(), eq(262144)))
        .thenReturn("password=should-not-show Bearer hidden-token https://user:secret@host/");
    String text = diagnostics.log(job.id(), "pod-test", "main", 10000, false).path("text").asText();
    assertThat(text).doesNotContain("should-not-show", "hidden-token", "user:secret");
    verify(kubernetes).text(contains("tailLines=1000"), eq(262144));
  }
  /** 编号稳定递增，名称预检不能通过不可见作业绕过唯一性。 */
  @Test
  void jobNumbersAreStableAndNeverReused() throws Exception {
    var first = draft();
    assertThat(first.jobNumber()).isPositive();
    assertThat(first.deploymentName()).isEqualTo("flink-" + first.jobNumber());
    mvc.perform(get("/api/jobs/name-availability").param("name", first.name())
        .with(user("outsider").roles("DEVELOPER")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.available").value(false));
    actor("admin", "ADMIN");
    var updated = jobs.update(first.id(), new DraftRequest(first.name(), "updated", first.flinkVersion(), first.yaml(), first.revision()));
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
      assertThat(operations.execute(job.id(), "start", command(job), () -> "admin").path("state").asText()).isEqualTo("REJECTED");
      for (String action : List.of("savepoint", "checkpoint"))
        assertThat(operations.execute(job.id(), action, command(job), () -> "admin").path("state").asText()).isEqualTo("REJECTED");
      assertThat(operations.execute(job.id(), "stop", command(job).put("upgradeMode", "stateless"), () -> "admin").path("state").asText()).isEqualTo("ACCEPTED");
    }
    stops.clear(job.id());
    var resource = live(job, "RUNNING", "suspended");
    when(kubernetes.call(eq("GET"), anyString(), isNull())).thenReturn(resource);
    assertThat(operations.execute(job.id(), "checkpoint", command(job), () -> "admin").path("state").asText()).isEqualTo("REJECTED");
    resource.withObject("/spec/job").put("state", "running");
    resource.withObject("/metadata").put("generation", 2);
    resource.withObject("/status").put("observedGeneration", 1);
    assertThat(operations.execute(job.id(), "savepoint", command(job), () -> "admin").path("state").asText()).isEqualTo("REJECTED");
    resource.withObject("/status").put("observedGeneration", 2).put("lifecycleState", "STABLE");
    for (String action : List.of("savepoint", "checkpoint"))
      assertThat(operations.execute(job.id(), action, command(job), () -> "admin").path("state").asText()).isEqualTo("ACCEPTED");
  }

  /** 保留状态的停止必须观测到新的 Savepoint 后才删除，并且删除前保存恢复路径。 */
  @Test
  void stopPersistsSavepointBeforeDeletingAndCanResume() {
    var job = draft();
    var resource = live(job, "RUNNING", "running");
    resource.withObject("/spec/flinkConfiguration").put("state.savepoints.dir", "s3://state/savepoints");
    resource.withObject("/status/jobStatus/savepointInfo/lastSavepoint").put("location", "s3://state/old");
    when(kubernetes.base()).thenReturn("/cr");
    when(kubernetes.call(eq("GET"), anyString(), isNull())).thenReturn(resource);
    when(kubernetes.call(eq("PUT"), anyString(), any())).thenAnswer(i -> ((ObjectNode)i.getArgument(2)).deepCopy());
    when(kubernetes.call(eq("DELETE"), anyString(), any())).thenReturn(mapper.createObjectNode());
    assertThat(operations.execute(job.id(), "stop", command(job), () -> "admin").path("state").asText()).isEqualTo("ACCEPTED");
    assertThat(stops.pending(job.id())).isTrue();
    stops.advance(job.id());
    verify(kubernetes, never()).call(eq("DELETE"), anyString(), any());
    resource.withObject("/spec/job").put("state", "suspended");
    resource.withObject("/status").put("lifecycleState", "SUSPENDED");
    resource.withObject("/status/jobStatus").put("state", "FINISHED");
    stops.advance(job.id());
    verify(kubernetes, never()).call(eq("DELETE"), anyString(), any());
    resource.withObject("/status/jobStatus/savepointInfo/lastSavepoint").put("location", "s3://state/new");
    var progress = stops.advance(job.id());
    assertThat(progress.path("phase").asText()).isEqualTo("DELETING");
    assertThat(stops.restorePath(job.id())).isEqualTo("s3://state/new");
    verify(kubernetes).call(eq("DELETE"), eq("/cr/" + job.deploymentName()),
        argThat(body -> body.at("/preconditions/uid").asText().equals("uid-" + job.id())));
    resource.withObject("/metadata").put("deletionTimestamp", "2026-09-30T00:00:00Z");
    assertThat(operations.execute(job.id(), "stop", command(job), () -> "admin").path("state").asText()).isEqualTo("ACCEPTED");
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
      assertThat(operations.execute(job.id(), "stop", command(job).put("upgradeMode", "stateless"), () -> "admin").path("state").asText()).isEqualTo("ACCEPTED");
    for (String action : List.of("start", "savepoint", "checkpoint", "deploy"))
      assertThat(operations.execute(job.id(), action, command(job), () -> "admin").path("state").asText()).isEqualTo("REJECTED");
    verify(kubernetes, atLeast(2)).call(eq("DELETE"), anyString(), any());
    verify(kubernetes, never()).call(eq("PUT"), anyString(), any());
    verify(kubernetes, never()).call(eq("POST"), anyString(), any());
  }

  /** 重建复用停止前的运行配置，不把未部署草稿修改隐式发布，也保留旧工作负载账号。 */
  @Test
  void startAfterStopUsesDeployedConfigurationInsteadOfEditedDraft() {
    var job = draft();
    var resource = live(job, "RUNNING", "running");
    resource.withObject("/spec").put("serviceAccount", "legacy-flink");
    resource.withObject("/spec/job").put("parallelism", 2);
    when(kubernetes.base()).thenReturn("/cr");
    when(kubernetes.call(eq("GET"), anyString(), isNull())).thenReturn(resource);
    when(kubernetes.call(eq("DELETE"), anyString(), any())).thenReturn(mapper.createObjectNode());
    assertThat(operations.execute(job.id(), "stop", command(job).put("upgradeMode", "stateless"), () -> "admin").path("state").asText()).isEqualTo("ACCEPTED");
    var edited = yaml.parse(job.yaml());
    edited.withObject("/spec/job").put("parallelism", 9);
    var changed = jobs.update(job.id(), new DraftRequest(job.name(), "not deployed", job.flinkVersion(), yaml.dump(edited), job.revision()));
    when(kubernetes.call(eq("GET"), anyString(), isNull())).thenReturn(MissingNode.getInstance());
    when(kubernetes.call(eq("POST"), anyString(), any())).thenAnswer(i -> ((ObjectNode)i.getArgument(2)).deepCopy());
    assertThat(operations.execute(job.id(), "start", command(changed), () -> "admin").path("state").asText()).isEqualTo("ACCEPTED");
    verify(kubernetes).call(eq("POST"), eq("/cr"), argThat(body ->
        body.at("/spec/job/parallelism").asInt() == 2
        && body.at("/spec/serviceAccount").asText().equals("legacy-flink")
        && !body.path("metadata").has("uid")));
    assertThat(stops.advance(job.id())).isNull();
  }

}
