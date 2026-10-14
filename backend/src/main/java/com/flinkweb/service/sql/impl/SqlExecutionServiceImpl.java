package com.flinkweb.service.sql.impl;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.flinkweb.client.flink.SessionRestClient;
import com.flinkweb.collector.job.DeploymentYamlSnapshot;
import com.flinkweb.config.sql.SqlValidationLog;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.audit.AuditService;
import com.flinkweb.service.permission.AccessService;
import com.flinkweb.service.session.SessionClusterService;
import com.flinkweb.service.sql.*;
import com.flinkweb.service.sql.support.*;
import jakarta.annotation.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 提交短时验证任务，监听固定 TaskManager 日志，结果不落库，一千条或两分钟后取消。 */
@Service
public class SqlExecutionServiceImpl implements SqlExecutionService {
  private final RecordRepository records;
  private final SqlJobService sql;
  private final SessionClusterService sessions;
  private final SessionRestClient rest;
  private final ValidationJarBundle bundles;
  private final SqlRuntimeArtifacts artifacts;
  private final AccessService access;
  private final ObjectMapper mapper;
  private final DeploymentYamlSnapshot secrets;
  private final AuditService audit;
  private final SqlValidationLogReader logReader;
  private boolean closed;
  private final Set<String> observing = new HashSet<>(),
      stopping = new HashSet<>(),
      preparing = new HashSet<>();
  private final Map<String, ArrayNode> rows = new HashMap<>();
  private final Map<String, Map<String, Long>> offsets = new HashMap<>();
  private final Map<String, ScheduledFuture<?>> listeners = new HashMap<>();
  private final ScheduledExecutorService collection =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread thread = new Thread(r, "sql-validation-log");
            thread.setDaemon(true);
            return thread;
          });
  private final ScheduledExecutorService deadlines =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread thread = new Thread(r, "sql-validation-deadline");
            thread.setDaemon(true);
            return thread;
          });
  private static final Set<String> TERMINAL =
      Set.of("COMPLETED", "FINISHED", "FAILED", "CANCELED", "TIMED_OUT", "ABORTED");

  /** 注入 SQL 检查、共享 Session 和受限日志客户端。 */
  public SqlExecutionServiceImpl(
      RecordRepository records,
      SqlJobService sql,
      SessionClusterService sessions,
      SessionRestClient rest,
      ValidationJarBundle bundles,
      SqlRuntimeArtifacts artifacts,
      AccessService access,
      ObjectMapper mapper,
      DeploymentYamlSnapshot secrets,
      AuditService audit,
      SqlValidationLogReader logReader) {
    this.logReader = logReader;
    this.records = records;
    this.sql = sql;
    this.sessions = sessions;
    this.rest = rest;
    this.bundles = bundles;
    this.artifacts = artifacts;
    this.access = access;
    this.mapper = mapper;
    this.secrets = secrets;
    this.audit = audit;
  }

  /** 重启后恢复已授权验证的超时取消，不启动周期状态轮询。 */
  @PostConstruct
  public void recover() {
    for (var run : records.list("sql_validations"))
      if (!TERMINAL.contains(run.path("state").asText())) schedule(run);
  }

  /** 关闭一次性超时执行器。 */
  @PreDestroy
  public synchronized void close() {
    closed = true;
    deadlines.shutdownNow();
    collection.shutdownNow();
  }

  /** 保留幂等及独占检查，准备/上传/提交 I/O 在状态锁外完成。 */
  public ObjectNode submit(String sqlId, long revision, String requestId) {
    access.require("sql:" + sqlId, "edit");
    if (requestId == null || !requestId.matches("[a-fA-F0-9-]{36}"))
      fail("INVALID_REQUEST_ID", "验证请求编号无效");
    ObjectNode previous = admission(sqlId, revision, requestId);
    if (previous != null) return previous;
    var draft = sql.get(sqlId);
    if (draft.path("revision").asLong() != revision) fail("REVISION_CONFLICT", "SQL 草稿已修改，请重新检查");
    var runtime = (ObjectNode) draft.path("runtime");
    var checked = sql.check(draft.path("sql").asText(), runtime);
    var cluster = sessions.validationCluster();
    var clusterRuntime = cluster.path("runtime");
    if (!runtime.path("imageId").asText().equals(clusterRuntime.path("imageId").asText()))
      fail("VALIDATION_IMAGE_MISMATCH", "SQL 与验证 Session 必须选择相同运行镜像，确保 Flink 和 Connector 版本一致");
    for (String field : List.of("podTemplate", "jmPodTemplate", "tmPodTemplate"))
      if (runtime.path(field).size() > 0 && !runtime.path(field).equals(clusterRuntime.path(field)))
        fail("VALIDATION_ENV_MISMATCH", "请在验证 Session 配置中同步 SQL 的 PodTemplate 和 Secret 环境变量后重启");
    List<String> references = new ArrayList<>();
    references.add(artifacts.runner(runtime));
    if (runtime.path("dependencyArtifactIds").size() > 50)
      fail("TOO_MANY_DEPENDENCIES", "最多选择 50 个依赖");
    for (var dependency : runtime.path("dependencyArtifactIds")) {
      String ref = dependency.asText();
      String filename =
          ref.startsWith("s3:") ? ref : records.require("artifacts", ref).path("filename").asText();
      if (!filename.toLowerCase(Locale.ROOT).endsWith(".jar")) {
        boolean mounted = false;
        for (var configured : clusterRuntime.path("dependencyArtifactIds"))
          if (configured.asText().equals(ref)) mounted = true;
        if (!mounted) fail("SESSION_CONFIG_FILE_REQUIRED", "非 JAR 配置文件请先在 Session 集群附加依赖中挂载，并重启集群");
      } else references.add(ref);
    }
    if (references.size() > 51) fail("TOO_MANY_DEPENDENCIES", "最多选择 50 个依赖");
    String id = UUID.randomUUID().toString().replace("-", "");
    ObjectNode run =
        mapper
            .createObjectNode()
            .put("sqlId", sqlId)
            .put("sqlRevision", revision)
            .put("actor", access.actor())
            .put("requestId", requestId)
            .put("jobId", id)
            .put("deploymentName", cluster.path("deploymentName").asText())
            .put("sessionUid", cluster.path("uid").asText())
            .put("state", "SUBMITTING")
            .put("message", "正在提交验证")
            .put("deadline", Instant.now().plusSeconds(120).toString())
            .put("limit", SqlValidationLog.ROW_LIMIT)
            .put("submissionAttempted", false);
    synchronized (this) {
      previous = admission(sqlId, revision, requestId);
      if (previous != null) return previous;
      if (sql.get(sqlId).path("revision").asLong() != revision)
        fail("REVISION_CONFLICT", "SQL 草稿已修改，请重新检查");
      run = records.save("sql_validations", id, run, 0);
      rows.put(id, mapper.createArrayNode());
      offsets.put(id, new HashMap<>());
      preparing.add(id);
      schedule(run);
    }
    boolean submissionAttempted = false;
    try {
      Path bundle = bundles.build(references);
      ObjectNode uploaded;
      try {
        uploaded = rest.upload(run.path("deploymentName").asText(), bundle);
      } finally {
        Files.deleteIfExists(bundle);
      }
      String filename = uploaded.path("filename").asText(),
          jarId = filename.substring(filename.lastIndexOf('/') + 1);
      if (!jarId.matches("[A-Za-z0-9_.-]+")) fail("INVALID_JAR_RESPONSE", "Session 返回的上传标识无效");
      synchronized (this) {
        run = records.require("sql_validations", id).deepCopy();
        run.put("jarId", jarId);
        run = records.save("sql_validations", id, run, run.path("revision").asLong());
      }
      if (TERMINAL.contains(run.path("state").asText())) {
        cleanupJar(run);
        return view(id);
      }
      if (run.has("cancelTarget")
          || Instant.now().isAfter(Instant.parse(run.path("deadline").asText())))
        return stop(id, "TIMED_OUT", "验证准备已超过截止时间");
      var current = sessions.validationStatus();
      if (!matchingCluster(run, current)) return finish(run, "ABORTED", "验证 Session 已停止或被替换");
      var input =
          mapper
              .createObjectNode()
              .put("entryClass", "com.flinkweb.sql.SqlRunner")
              .put("parallelism", runtime.path("parallelism").asInt(1))
              .put("jobId", id);
      var args = input.putArray("programArgsList").add("--validation").add(id);
      for (String statement : checked.statements())
        args.add("--statement")
            .add(Base64.getEncoder().encodeToString(statement.getBytes(StandardCharsets.UTF_8)));
      var config = input.putObject("flinkConfiguration");
      runtime
          .path("flinkConfiguration")
          .fields()
          .forEachRemaining(
              e -> {
                if (e.getKey().startsWith("table.") || e.getKey().startsWith("execution."))
                  config.set(e.getKey(), e.getValue());
              });
      config.put("pipeline.name", "SQL 验证 · " + sqlId).put("restart-strategy.type", "none");
      boolean launch;
      synchronized (this) {
        run = records.require("sql_validations", id).deepCopy();
        launch =
            !TERMINAL.contains(run.path("state").asText())
                && !run.has("cancelTarget")
                && Instant.now().isBefore(Instant.parse(run.path("deadline").asText()));
        if (launch) {
          run.put("submissionAttempted", true);
          run = records.save("sql_validations", id, run, run.path("revision").asLong());
        }
      }
      if (!launch) {
        if (TERMINAL.contains(run.path("state").asText())) return view(id);
        return stop(id, "TIMED_OUT", "验证在提交前结束");
      }
      submissionAttempted = true;
      rest.budgetedCall(
          run.path("deploymentName").asText(),
          "POST",
          "/jars/" + jarId + "/run",
          input,
          Duration.ofSeconds(30));
      synchronized (this) {
        run = records.require("sql_validations", id).deepCopy();
        run.put("submissionSettledAt", Instant.now().toString());
        if (!run.has("cancelTarget") && !TERMINAL.contains(run.path("state").asText()))
          run.put("state", "ACCEPTED").put("message", "验证已提交，正在等待运行和数据输出");
        run = records.save("sql_validations", id, run, run.path("revision").asLong());
      }
      audit.record("SQL_VERIFY", sqlId, "ACCEPTED");
    } catch (Exception ex) {
      boolean unknown =
          submissionAttempted
              && !(ex instanceof BusinessException business
                  && Set.of("SESSION_REST_REJECTED", "SESSION_REST_NOT_FOUND")
                      .contains(business.code()));
      synchronized (this) {
        run = records.require("sql_validations", id).deepCopy();
        if (!TERMINAL.contains(run.path("state").asText())) {
          run.put("submissionSettledAt", Instant.now().toString());
          if (!unknown)
            run.put(
                "state",
                ex instanceof BusinessException business
                        && Set.of("NOT_FOUND", "SESSION_IDENTITY_CONFLICT")
                            .contains(business.code())
                    ? "ABORTED"
                    : "FAILED");
          else if (!run.has("cancelTarget")) run.put("state", "UNKNOWN");
          run.put("message", redact(ex.getMessage() == null ? "SQL 验证提交失败" : ex.getMessage()));
          run = records.save("sql_validations", id, run, run.path("revision").asLong());
        }
      }
      if (TERMINAL.contains(run.path("state").asText())) cleanupJar(run);
    } finally {
      synchronized (this) {
        preparing.remove(id);
      }
    }
    if (run.has("cancelTarget") && !TERMINAL.contains(run.path("state").asText()))
      return stop(id, run.path("cancelTarget").asText(), "正在确认验证取消");
    return view(id);
  }

  /** 幂等重试优先返回已有结果，第二次准入检查阻止并发准备同时提交。 */
  private synchronized ObjectNode admission(String sqlId, long revision, String requestId) {
    var previous = records.list("sql_validations");
    for (var run : previous)
      if (requestId.equals(run.path("requestId").asText())
          && access.actor().equals(run.path("actor").asText())) {
        if (!sqlId.equals(run.path("sqlId").asText())
            || revision != run.path("sqlRevision").asLong())
          fail("REQUEST_ID_REUSED", "验证请求编号已用于另一份 SQL 修订");
        return visible(run);
      }
    if (!preparing.isEmpty()
        || previous.stream().anyMatch(run -> !TERMINAL.contains(run.path("state").asText())))
      fail("VALIDATION_BUSY", "专用 Session 正在验证其他 SQL，请等待完成或取消本次验证");
    return null;
  }

  /** 查询权限后尝试独占本次观察，已有慢请求时直接返回最近状态。 */
  public ObjectNode status(String id) {
    synchronized (this) {
      require(id);
    }
    return observe(id);
  }

  /** 网络读取不占用状态锁，修订变化后丢弃迟到状态和数据。 */
  private ObjectNode observe(String id) {
    ObjectNode run;
    synchronized (this) {
      run = records.require("sql_validations", id).deepCopy();
      if (TERMINAL.contains(run.path("state").asText())
          || stopping.contains(id)
          || observing.contains(id)
          || "SUBMITTING".equals(run.path("state").asText()) && !attempted(run))
        return visible(run);
      if (!run.has("cancelTarget")) observing.add(id);
    }
    if (run.has("cancelTarget")) return stop(id, run.path("cancelTarget").asText(), "正在确认验证取消");
    try {
      var cluster = sessions.validationStatus();
      if (!matchingCluster(run, cluster)) return finish(run, "ABORTED", "验证 Session 已停止或被替换");
      String deployment = run.path("deploymentName").asText();
      var detail = control(deployment, "GET", "/jobs/" + run.path("jobId").asText());
      String state = detail.path("state").asText("UNKNOWN");
      if ("FAILED".equals(state)) return finish(run, "FAILED", failureMessage(run));
      String logError = "";
      try {
        logReader.read(
            run,
            key -> cursor(id, key),
            (key, page) -> append(run, key, page),
            () -> currentObservation(run));
      } catch (Exception ex) {
        logError = "；暂未读到日志：" + redact(ex.getMessage());
      }
      if (!currentRevision(run)) return view(id);
      int count = rowCount(id);
      if (count >= SqlValidationLog.ROW_LIMIT)
        return stop(id, "COMPLETED", "已展示 1000 条验证数据，正在取消验证任务");
      if ("FINISHED".equals(state) && count < SqlValidationLog.ROW_LIMIT) {
        if (!run.has("terminalObservedAt")) run.put("terminalObservedAt", Instant.now().toString());
        if (Instant.now()
                .isBefore(Instant.parse(run.path("terminalObservedAt").asText()).plusSeconds(3))
            && Instant.now().isBefore(Instant.parse(run.path("deadline").asText()))) {
          run.put("state", "DRAINING").put("message", "任务已完成，正在读取最后一批验证数据" + logError);
          return saveObservation(run);
        }
      }
      if ("FINISHED".equals(state) || "CANCELED".equals(state))
        return finish(run, state, "FINISHED".equals(state) ? "有界 SQL 验证完成" : "验证任务已结束");
      run.put("state", state).put("message", "正在验证，已获取 " + count + " / 1000 条数据" + logError);
      return saveObservation(run);
    } catch (Exception ex) {
      if (ex instanceof BusinessException business
          && Set.of("NOT_FOUND", "SESSION_IDENTITY_CONFLICT").contains(business.code()))
        return finish(run, "ABORTED", "验证 Session 已删除或被替换");
      if (ex instanceof BusinessException business
          && "SESSION_REST_NOT_FOUND".equals(business.code())
          && missingJobCanFinish(run))
        return finish(run, "FAILED", "验证任务未在 Session 中找到，提交未成功或历史已清理");
      run.put("message", redact(ex.getMessage()));
      return saveObservation(run);
    } finally {
      synchronized (this) {
        observing.remove(id);
      }
    }
  }

  /** 每个文件的完整事件在同一短临界区追加并推进位点，文件失败不回滚已成功文件。 */
  private void append(ObjectNode run, String key, List<SqlValidationLogReader.Event> page) {
    var prepared = new ArrayList<ObjectNode>();
    var knownSecrets = secrets.secrets();
    for (var event : page)
      prepared.add(
          mapper
              .createObjectNode()
              .put("sink", event.sink())
              .put("kind", event.kind())
              .put("value", redact(event.value(), knownSecrets)));
    synchronized (this) {
      if (!currentObservation(run)) return;
      String id = run.path("id").asText();
      var result = rows.computeIfAbsent(id, ignored -> mapper.createArrayNode());
      var read = offsets.computeIfAbsent(id, ignored -> new HashMap<>());
      for (int index = 0;
          index < page.size() && result.size() < SqlValidationLog.ROW_LIMIT;
          index++) {
        var event = page.get(index);
        if (event.sequence() <= read.getOrDefault(key, 0L)) continue;
        result.add(prepared.get(index));
        read.put(key, event.sequence());
      }
    }
  }

  /** 并发取消和终态更新改变修订，使原观察结果不再有提交资格。 */
  private synchronized boolean currentRevision(ObjectNode run) {
    var latest = records.find("sql_validations", run.path("id").asText());
    return !closed
        && latest != null
        && latest.path("revision").asLong() == run.path("revision").asLong()
        && !TERMINAL.contains(latest.path("state").asText());
  }

  /** 达到上限或取消后不再读取后续文件。 */
  private synchronized boolean currentObservation(ObjectNode run) {
    return currentRevision(run)
        && !run.has("cancelTarget")
        && rowCount(run.path("id").asText()) < SqlValidationLog.ROW_LIMIT;
  }

  /** 仅在观察修订仍有效时提交状态，迟到响应返回当前状态。 */
  private synchronized ObjectNode saveObservation(ObjectNode run) {
    if (!currentRevision(run)) return view(run.path("id").asText());
    return visible(
        records.save(
            "sql_validations", run.path("id").asText(), run, run.path("revision").asLong()));
  }

  /** 复制当前状态和内存结果，访问权限在公开入口核验。 */
  private synchronized ObjectNode view(String id) {
    return visible(records.require("sql_validations", id));
  }

  /** 在短临界区查询结果数量。 */
  private synchronized int rowCount(String id) {
    return rows.getOrDefault(id, mapper.createArrayNode()).size();
  }

  /** 在短临界区复制 TM 的已提交位点。 */
  private synchronized long cursor(String id, String key) {
    return offsets.getOrDefault(id, Map.of()).getOrDefault(key, 0L);
  }

  /** 控制请求拥有独立预算，不等待日志读取完成。 */
  private JsonNode control(String deployment, String method, String path) {
    return rest.budgetedCall(deployment, method, path, null, Duration.ofSeconds(5));
  }

  /** 资源不存在或 UID 已变化时禁止读取及操作同名新集群。 */
  private boolean matchingCluster(ObjectNode run, ObjectNode cluster) {
    return !run.path("sessionUid").asText().isBlank()
        && run.path("sessionUid").asText().equals(cluster.path("uid").asText())
        && !Set.of("STOPPED", "MISSING").contains(cluster.path("state").asText());
  }

  /** 新记录明确标记提交尝试，旧 SUBMITTING 加载已保存 JAR 时保守视为可能提交。 */
  private boolean attempted(ObjectNode run) {
    return run.has("submissionAttempted")
        ? run.path("submissionAttempted").asBoolean()
        : !run.path("jarId").asText().isBlank();
  }

  /** 未知提交在请求未结束或短暂不可见时继续保留独占，避免迟到提交成为孤儿。 */
  private synchronized boolean missingJobCanFinish(ObjectNode run) {
    if (preparing.contains(run.path("id").asText())) return false;
    Instant deadline = Instant.parse(run.path("deadline").asText());
    Instant settled =
        Instant.parse(run.path("submissionSettledAt").asText(run.path("deadline").asText()))
            .plusSeconds(30);
    return Instant.now().isAfter(deadline) && Instant.now().isAfter(settled);
  }

  /** 手动取消只作用于当前用户有编辑权限的 SQL 验证。 */
  public ObjectNode cancel(String id) {
    synchronized (this) {
      require(id);
    }
    return stop(id, "CANCELED", "验证取消请求已提交");
  }

  /** 检查本次验证权限，其他用户不能读取或取消该 SQL 数据。 */
  private ObjectNode require(String id) {
    var run = records.require("sql_validations", id);
    access.require("sql:" + run.path("sqlId").asText(), "edit");
    return run;
  }

  /** 仅为当前验证安排一次截止取消；即使浏览器关闭也会结束任务。 */
  private synchronized void schedule(ObjectNode run) {
    if (closed) return;
    String id = run.path("id").asText();
    long delay =
        Math.max(
            0,
            Duration.between(Instant.now(), Instant.parse(run.path("deadline").asText()))
                .toMillis());
    deadlines.schedule(() -> expire(id), delay, TimeUnit.MILLISECONDS);
    listen(id);
  }

  /** 显式验证期间每两秒监听一次；浏览器关闭仍执行行数上限，完成后停止。 */
  private synchronized void listen(String id) {
    if (closed || listeners.containsKey(id)) return;
    listeners.put(id, collection.schedule(() -> collectNext(id), 2, TimeUnit.SECONDS));
  }

  /** 后台只观察已授权运行，I/O 结束后在短临界区安排下一次。 */
  private void collectNext(String id) {
    synchronized (this) {
      listeners.remove(id);
      var run = records.find("sql_validations", id);
      if (closed || run == null || TERMINAL.contains(run.path("state").asText())) return;
    }
    try {
      observe(id);
    } catch (Exception ex) {
      org.slf4j.LoggerFactory.getLogger(SqlExecutionServiceImpl.class)
          .warn(
              "SQL validation observation failed validationId={} type={}",
              id,
              ex.getClass().getSimpleName());
    }
    synchronized (this) {
      var latest = records.find("sql_validations", id);
      if (latest != null && !TERMINAL.contains(latest.path("state").asText())) listen(id);
    }
  }

  /** 截止任务在独立线程执行控制请求，不与慢日志采集共用 I/O 临界区。 */
  private void expire(String id) {
    ObjectNode run;
    synchronized (this) {
      run = records.find("sql_validations", id);
      if (closed || run == null || TERMINAL.contains(run.path("state").asText())) return;
    }
    stop(id, run.path("cancelTarget").asText("TIMED_OUT"), "验证结束，正在确认取消完成");
    synchronized (this) {
      var latest = records.find("sql_validations", id);
      if (!closed && latest != null && !TERMINAL.contains(latest.path("state").asText()))
        deadlines.schedule(() -> expire(id), 10, TimeUnit.SECONDS);
    }
  }

  /** 先保存取消意图使旧观察失效，再在锁外核验 UID 和固定 JobID，确认前不释放独占。 */
  private ObjectNode stop(String id, String target, String message) {
    ObjectNode run;
    synchronized (this) {
      run = records.require("sql_validations", id).deepCopy();
      if (TERMINAL.contains(run.path("state").asText()) || stopping.contains(id))
        return visible(run);
      if (!run.has("cancelTarget")) {
        run.put("cancelTarget", target).put("state", "CANCEL_PENDING").put("message", message);
        run = records.save("sql_validations", id, run, run.path("revision").asLong());
      }
      stopping.add(id);
    }
    try {
      if (!attempted(run)) return finish(run, run.path("cancelTarget").asText(), "验证在提交前结束");
      var cluster = sessions.validationStatus();
      if (!matchingCluster(run, cluster)) return finish(run, "ABORTED", "验证 Session 已停止或被替换");
      String deployment = run.path("deploymentName").asText();
      var observed = control(deployment, "GET", "/jobs/" + run.path("jobId").asText());
      if ("FAILED".equals(observed.path("state").asText()))
        return finish(run, "FAILED", failureMessage(run));
      if (Set.of("CANCELED", "FINISHED").contains(observed.path("state").asText()))
        return finish(run, run.path("cancelTarget").asText(), "验证任务已结束");
      control(deployment, "PATCH", "/jobs/" + run.path("jobId").asText() + "?mode=cancel");
      run.put("state", "CANCELING").put("message", message);
      return saveObservation(run);
    } catch (Exception ex) {
      if (ex instanceof BusinessException business
          && Set.of("NOT_FOUND", "SESSION_IDENTITY_CONFLICT").contains(business.code()))
        return finish(run, "ABORTED", "验证 Session 已删除或被替换");
      if (ex instanceof BusinessException business
          && "SESSION_REST_NOT_FOUND".equals(business.code())
          && missingJobCanFinish(run))
        return finish(run, "FAILED", "验证任务未在 Session 中找到，提交未成功或历史已清理");
      run.put("state", "CANCEL_PENDING").put("message", redact("取消暂未完成：" + ex.getMessage()));
      return saveObservation(run);
    } finally {
      synchronized (this) {
        stopping.remove(id);
      }
    }
  }

  /** 终态在锁内原子提交，上传 JAR 清理在锁外执行，迟到观察不能复活验证。 */
  private ObjectNode finish(ObjectNode run, String state, String message) {
    ObjectNode saved;
    synchronized (this) {
      if (!currentRevision(run)) return view(run.path("id").asText());
      var listener = listeners.remove(run.path("id").asText());
      if (listener != null) listener.cancel(false);
      run.put("state", state).put("message", message);
      saved =
          records.save(
              "sql_validations", run.path("id").asText(), run, run.path("revision").asLong());
    }
    cleanupJar(saved);
    return view(saved.path("id").asText());
  }

  /** 任务已失败时异常接口不可用也应结束验证，避免永久占用专用 Session。 */
  private String failureMessage(ObjectNode run) {
    try {
      var error =
          control(
              run.path("deploymentName").asText(),
              "GET",
              "/jobs/" + run.path("jobId").asText() + "/exceptions");
      return redact(error.path("root-exception").asText("SQL 验证运行失败"));
    } catch (Exception ex) {
      return redact("SQL 验证运行失败；暂时无法读取异常摘要：" + ex.getMessage());
    }
  }

  /** 清理上传副本，不删除用户共享 Connector 文件。 */
  private void cleanupJar(ObjectNode run) {
    if (!"ABORTED".equals(run.path("state").asText()) && !run.path("jarId").asText().isBlank())
      try {
        if (matchingCluster(run, sessions.validationStatus()))
          control(
              run.path("deploymentName").asText(), "DELETE", "/jars/" + run.path("jarId").asText());
      } catch (Exception ex) {
        org.slf4j.LoggerFactory.getLogger(SqlExecutionServiceImpl.class)
            .warn(
                "SQL validation JAR cleanup failed validationId={} stage=DELETE_JAR type={}",
                run.path("id").asText(),
                ex.getClass().getSimpleName());
      }
    String id = run.path("id").asText();
    synchronized (this) {
      if (rows.size() > 10) {
        var retained = new HashSet<String>();
        retained.add(id);
        for (var old : records.list("sql_validations"))
          if (!TERMINAL.contains(old.path("state").asText())) retained.add(old.path("id").asText());
        rows.keySet().retainAll(retained);
        offsets.keySet().retainAll(retained);
      }
    }
  }

  /** 只返回用户需要的状态与格式化数据，隐藏内部上传路径和会话身份。 */
  private synchronized ObjectNode visible(ObjectNode run) {
    var result = mapper.createObjectNode();
    for (String field :
        List.of("id", "sqlId", "sqlRevision", "jobId", "state", "message", "limit", "deadline"))
      result.set(field, run.path(field));
    result.set(
        "rows", rows.getOrDefault(run.path("id").asText(), mapper.createArrayNode()).deepCopy());
    return result;
  }

  /** 错误与数据使用相同脱敏边界，最多返回八千字符错误摘要。 */
  private String redact(String value) {
    return redact(value, secrets.secrets());
  }

  /** 使用本次文件的密钥快照处理数据，避免每一行重复扫描元数据。 */
  private String redact(String value, List<String> knownSecrets) {
    if (value == null) return "SQL 验证失败";
    for (String secret : knownSecrets) value = value.replace(secret, "******");
    value =
        value
            .replaceAll(
                "(?i)((?:password|passwd|secret|token|access[_-]?key|authorization)\\s*[:=]\\s*)([^\\s,;]+)",
                "$1******")
            .replaceAll("(://)[^\\s/@:]+:[^\\s/@]+@", "$1******@");
    return value.substring(0, Math.min(8000, value.length()));
  }

  /** 参数和状态错误使用稳定机器码返回。 */
  private void fail(String code, String message) {
    throw new BusinessException(HttpStatus.CONFLICT, code, message);
  }
}
