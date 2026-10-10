package com.flinkweb.service.sql.impl;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.flinkweb.client.flink.SessionRestClient;
import com.flinkweb.collector.job.DeploymentYamlSnapshot;
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

/** 提交短时验证任务，按需读取 TaskManager 日志，结果不落库，一百条或两分钟后取消。 */
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
  private final Map<String, ArrayNode> rows = new HashMap<>();
  private final Map<String, Map<String, Integer>> offsets = new HashMap<>();
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
      AuditService audit) {
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
  public void close() {
    deadlines.shutdownNow();
  }

  /** 提交时重新检查保存修订和 SQL，服务端不信任浏览器的检查通过标记。 */
  public synchronized ObjectNode submit(String sqlId, long revision, String requestId) {
    access.require("sql:" + sqlId, "edit");
    if (requestId == null || !requestId.matches("[a-fA-F0-9-]{36}"))
      fail("INVALID_REQUEST_ID", "验证请求编号无效");
    var previousRuns = records.list("sql_validations");
    for (var old : previousRuns) {
      if (requestId.equals(old.path("requestId").asText())
          && access.actor().equals(old.path("actor").asText())) {
        if (!sqlId.equals(old.path("sqlId").asText())
            || revision != old.path("sqlRevision").asLong())
          fail("REQUEST_ID_REUSED", "验证请求编号已用于另一份 SQL 修订");
        return visible(old);
      }
    }
    if (previousRuns.stream().anyMatch(old -> !TERMINAL.contains(old.path("state").asText())))
      fail("VALIDATION_BUSY", "专用 Session 正在验证其他 SQL，请等待完成或取消本次验证");
    var draft = sql.get(sqlId);
    if (draft.path("revision").asLong() != revision) fail("REVISION_CONFLICT", "SQL 草稿已修改，请重新检查");
    var runtime = (ObjectNode) draft.path("runtime");
    var checked = sql.check(draft.path("sql").asText(), runtime);
    var cluster = sessions.validationCluster();
    var clusterRuntime = cluster.path("runtime");
    if (!runtime.path("imageId").asText().equals(clusterRuntime.path("imageId").asText()))
      fail("VALIDATION_IMAGE_MISMATCH", "SQL 与验证 Session 必须选择相同运行镜像，确保 Flink 和 Connector 版本一致");
    // Session 不能临时注入另一套环境变量，必须在创建集群时配置对应 Secret 引用。
    if (!runtime.path("podTemplate").isMissingNode()
        && runtime.path("podTemplate").size() > 0
        && !runtime.path("podTemplate").equals(clusterRuntime.path("podTemplate")))
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
    String jobId = id;
    var run =
        mapper
            .createObjectNode()
            .put("sqlId", sqlId)
            .put("sqlRevision", revision)
            .put("actor", access.actor())
            .put("requestId", requestId)
            .put("jobId", jobId)
            .put("deploymentName", cluster.path("deploymentName").asText())
            .put("sessionUid", cluster.path("uid").asText())
            .put("state", "SUBMITTING")
            .put("message", "正在提交验证")
            .put("deadline", Instant.now().plusSeconds(120).toString())
            .put("limit", 100);
    run = records.save("sql_validations", id, run, 0);
    rows.put(id, mapper.createArrayNode());
    offsets.put(id, new HashMap<>());
    boolean submissionAttempted = false;
    try {
      Path bundle = bundles.build(references);
      ObjectNode uploaded;
      try {
        uploaded = rest.upload(run.path("deploymentName").asText(), bundle);
      } finally {
        Files.deleteIfExists(bundle);
      }
      String filename = uploaded.path("filename").asText();
      String jarId = filename.substring(filename.lastIndexOf('/') + 1);
      if (!jarId.matches("[A-Za-z0-9_.-]+")) fail("INVALID_JAR_RESPONSE", "Session 返回的上传标识无效");
      run.put("jarId", jarId);
      run = records.save("sql_validations", id, run, run.path("revision").asLong());
      var input =
          mapper
              .createObjectNode()
              .put("entryClass", "com.flinkweb.sql.SqlRunner")
              .put("parallelism", runtime.path("parallelism").asInt(1))
              .put("jobId", jobId);
      var args = input.putArray("programArgsList").add("--validation").add(id);
      for (String statement : checked.statements())
        args.add("--statement")
            .add(Base64.getEncoder().encodeToString(statement.getBytes(StandardCharsets.UTF_8)));
      // 只传递 Table/执行配置，禁止临时覆盖 Session 基础设施和 HA 身份。
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
      if (Instant.now().isAfter(Instant.parse(run.path("deadline").asText())))
        fail("VALIDATION_PREPARATION_TIMEOUT", "依赖准备超过两分钟，请减少依赖或检查网络后重试");
      submissionAttempted = true;
      rest.call(run.path("deploymentName").asText(), "POST", "/jars/" + jarId + "/run", input);
      run.put("state", "ACCEPTED").put("message", "验证已提交，正在等待运行和数据输出");
      audit.record("SQL_VERIFY", sqlId, "ACCEPTED");
    } catch (Exception ex) {
      // REST 超时仍可能已提交，保留固定 JobID 并按期限尝试取消，避免重试创建第二个作业。
      run.put(
              "state",
              submissionAttempted
                      && !(ex instanceof BusinessException business
                          && Set.of("SESSION_REST_REJECTED", "SESSION_REST_NOT_FOUND")
                              .contains(business.code()))
                  ? "UNKNOWN"
                  : "FAILED")
          .put("message", redact(ex.getMessage() == null ? "SQL 验证提交失败" : ex.getMessage()));
    }
    run = records.save("sql_validations", id, run, run.path("revision").asLong());
    if (!TERMINAL.contains(run.path("state").asText())) schedule(run);
    else cleanupJar(run);
    return visible(run);
  }

  /** 查询运行状态和带本次标识的数据，达到固定上限主动取消。 */
  public synchronized ObjectNode status(String id) {
    var run = require(id);
    if (TERMINAL.contains(run.path("state").asText())) return visible(run);
    try {
      var cluster = sessions.status("sql-validation");
      if (!run.path("sessionUid").asText().equals(cluster.path("uid").asText())
          || "STOPPED".equals(cluster.path("state").asText()))
        return finish(run, "ABORTED", "验证 Session 已停止或被替换");
      String deployment = run.path("deploymentName").asText();
      var detail = rest.call(deployment, "GET", "/jobs/" + run.path("jobId").asText(), null);
      String state = detail.path("state").asText("UNKNOWN");
      if ("FAILED".equals(state)) {
        return finish(run, "FAILED", failureMessage(run));
      }
      String logError = "";
      try {
        collect(run);
      } catch (Exception ex) {
        logError = "；暂未读到日志：" + redact(ex.getMessage());
      }
      // Flink UI 缓存和日志刷新可能晚于有界任务结束，给最后一批 Print 结果留出读取时间。
      if ("FINISHED".equals(state) && rows.get(id).size() < 100) {
        if (!run.has("terminalObservedAt")) run.put("terminalObservedAt", Instant.now().toString());
        if (Instant.now()
                .isBefore(Instant.parse(run.path("terminalObservedAt").asText()).plusSeconds(3))
            && Instant.now().isBefore(Instant.parse(run.path("deadline").asText()))) {
          run.put("state", "DRAINING").put("message", "任务已完成，正在读取最后一批验证数据" + logError);
          return visible(records.save("sql_validations", id, run, run.path("revision").asLong()));
        }
      }
      if ("FINISHED".equals(state) || "CANCELED".equals(state))
        return finish(
            run,
            run.path("cancelTarget").asText("FINISHED".equals(state) ? "FINISHED" : "CANCELED"),
            "FINISHED".equals(state) ? "有界 SQL 验证完成" : "验证任务已结束");
      if (run.has("cancelTarget")) {
        run.put("state", "CANCELING");
        return visible(records.save("sql_validations", id, run, run.path("revision").asLong()));
      }
      if (rows.get(id).size() >= 100) return stop(run, "COMPLETED", "已展示 100 条验证数据，正在取消验证任务");
      run.put("state", state)
          .put("message", "正在验证，已获取 " + rows.get(id).size() + " / 100 条数据" + logError);
    } catch (Exception ex) {
      if (ex instanceof BusinessException business
          && Set.of("NOT_FOUND", "SESSION_IDENTITY_CONFLICT").contains(business.code()))
        return finish(run, "ABORTED", "验证 Session 已删除或被替换");
      if (ex instanceof BusinessException business
          && "SESSION_REST_NOT_FOUND".equals(business.code())
          && Instant.now().isAfter(Instant.parse(run.path("deadline").asText())))
        return finish(run, "FAILED", "验证任务未在 Session 中找到，提交未成功或历史已清理");
      run.put("message", redact(ex.getMessage()));
    }
    return visible(records.save("sql_validations", id, run, run.path("revision").asLong()));
  }

  /** 手动取消只作用于当前用户有编辑权限的 SQL 验证。 */
  public synchronized ObjectNode cancel(String id) {
    var run = require(id);
    return TERMINAL.contains(run.path("state").asText())
        ? visible(run)
        : stop(run, "CANCELED", "验证取消请求已提交");
  }

  /** 检查本次验证权限；其他开发者不能读取专用 Session 中别人的数据。 */
  private ObjectNode require(String id) {
    var run = records.require("sql_validations", id);
    access.require("sql:" + run.path("sqlId").asText(), "edit");
    return run;
  }

  /** 从各 TaskManager 日志只提取本次标识并按已读取数量去重，重复业务行仍保留。 */
  private void collect(ObjectNode run) {
    String id = run.path("id").asText();
    var result = rows.computeIfAbsent(id, k -> mapper.createArrayNode());
    var read = offsets.computeIfAbsent(id, k -> new HashMap<>());
    String deployment = run.path("deploymentName").asText();
    Pattern pattern =
        Pattern.compile(
            "FWV_" + id + "_(\\d+)(?::\\d+)?>\\s*([+-][IUD])\\[(.*?)](?=\\r?\\n|$)",
            Pattern.DOTALL);
    for (var tm : rest.call(deployment, "GET", "/taskmanagers", null).path("taskmanagers")) {
      String key = tm.path("id").asText();
      String log = rest.log(deployment, key);
      int count = 0, previous = read.getOrDefault(key, 0);
      Matcher match = pattern.matcher(log);
      while (match.find()) {
        if (++count <= previous || result.size() >= 100) continue;
        result
            .addObject()
            .put("sink", Integer.parseInt(match.group(1)) + 1)
            .put("kind", match.group(2))
            .put("value", redact(match.group(3)));
      }
      read.put(key, Math.max(previous, count));
    }
  }

  /** 仅为当前验证安排一次截止取消；即使浏览器关闭也会结束任务。 */
  private void schedule(ObjectNode run) {
    String id = run.path("id").asText();
    long delay =
        Math.max(
            0,
            Duration.between(Instant.now(), Instant.parse(run.path("deadline").asText()))
                .toMillis());
    deadlines.schedule(() -> expire(id), delay, TimeUnit.MILLISECONDS);
  }

  /** 到期动作属于用户提交验证时已授权的清理，失败保留取消状态供后续请求重试。 */
  private synchronized void expire(String id) {
    var run = records.find("sql_validations", id);
    if (run == null || TERMINAL.contains(run.path("state").asText())) return;
    try {
      stop(run, run.path("cancelTarget").asText("TIMED_OUT"), "验证结束，正在确认取消完成");
      var updated = records.find("sql_validations", id);
      if (updated != null && !TERMINAL.contains(updated.path("state").asText()))
        deadlines.schedule(() -> expire(id), 10, TimeUnit.SECONDS);
    } catch (Exception ex) {
      if (ex instanceof BusinessException business
          && Set.of("NOT_FOUND", "SESSION_IDENTITY_CONFLICT").contains(business.code())) {
        finish(run, "ABORTED", "验证 Session 已删除或被替换");
        return;
      }
      if (ex instanceof BusinessException business
          && "SESSION_REST_NOT_FOUND".equals(business.code())) {
        finish(run, "FAILED", "验证任务未在 Session 中找到，提交未成功或历史已清理");
        return;
      }
      run.put("state", "CANCEL_PENDING").put("message", redact("超时取消暂未完成：" + ex.getMessage()));
      records.save("sql_validations", id, run, run.path("revision").asLong());
      deadlines.schedule(() -> expire(id), 10, TimeUnit.SECONDS);
    }
  }

  /** 固定 JobID 和 Session UID 保护取消操作，不影响其他任务或新建集群。 */
  private ObjectNode stop(ObjectNode run, String state, String message) {
    var cluster = sessions.status("sql-validation");
    if (!run.path("sessionUid").asText().equals(cluster.path("uid").asText())
        || "STOPPED".equals(cluster.path("state").asText()))
      return finish(run, "ABORTED", "验证 Session 已停止或被替换");
    String deployment = run.path("deploymentName").asText();
    var observed = rest.call(deployment, "GET", "/jobs/" + run.path("jobId").asText(), null);
    if ("FAILED".equals(observed.path("state").asText())) {
      return finish(run, "FAILED", failureMessage(run));
    }
    if (Set.of("CANCELED", "FINISHED").contains(observed.path("state").asText()))
      return finish(
          run, run.path("cancelTarget").asText(observed.path("state").asText()), "验证任务已结束");
    rest.call(deployment, "PATCH", "/jobs/" + run.path("jobId").asText() + "?mode=cancel", null);
    run.put("state", "CANCELING").put("cancelTarget", state).put("message", message);
    return visible(
        records.save(
            "sql_validations", run.path("id").asText(), run, run.path("revision").asLong()));
  }

  /** 保存结果状态并清理已上传 JAR；结果数据仅保留于本进程内。 */
  private ObjectNode finish(ObjectNode run, String state, String message) {
    run.put("state", state).put("message", message);
    var saved =
        records.save(
            "sql_validations", run.path("id").asText(), run, run.path("revision").asLong());
    cleanupJar(saved);
    return visible(saved);
  }

  /** 任务已失败时异常接口不可用也应结束验证，避免永久占用专用 Session。 */
  private String failureMessage(ObjectNode run) {
    try {
      var error =
          rest.call(
              run.path("deploymentName").asText(),
              "GET",
              "/jobs/" + run.path("jobId").asText() + "/exceptions",
              null);
      return redact(error.path("root-exception").asText("SQL 验证运行失败"));
    } catch (Exception ex) {
      return redact("SQL 验证运行失败；暂时无法读取异常摘要：" + ex.getMessage());
    }
  }

  /** 清理上传副本，不删除用户共享 Connector 文件。 */
  private void cleanupJar(ObjectNode run) {
    if (!"ABORTED".equals(run.path("state").asText()) && !run.path("jarId").asText().isBlank())
      try {
        rest.call(
            run.path("deploymentName").asText(),
            "DELETE",
            "/jars/" + run.path("jarId").asText(),
            null);
      } catch (Exception ignored) {
        /* 集群销毁时上传目录随 Pod 删除，不影响原始文件。 */
      }
    String id = run.path("id").asText();
    if (rows.size() > 100) {
      var retained = new HashSet<String>();
      retained.add(id);
      for (var old : records.list("sql_validations"))
        if (!TERMINAL.contains(old.path("state").asText())) retained.add(old.path("id").asText());
      rows.keySet().retainAll(retained);
      offsets.keySet().retainAll(retained);
    }
  }

  /** 只返回用户需要的状态与格式化数据，隐藏内部上传路径和会话身份。 */
  private ObjectNode visible(ObjectNode run) {
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
    if (value == null) return "SQL 验证失败";
    for (String secret : secrets.secrets()) value = value.replace(secret, "******");
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
