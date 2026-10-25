package com.flinkweb.service.ai.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.client.kubernetes.KubernetesClient;
import com.flinkweb.entity.job.JobDraft;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.job.creation.YamlService;
import com.flinkweb.service.job.management.JobOwnershipService;
import com.flinkweb.service.job.runtime.DiagnosticService;
import com.flinkweb.service.permission.AccessService;
import com.flinkweb.service.sql.support.SqlDeploymentSource;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/** 仅在用户发起诊断时收集归属已核验的状态、事件、日志及配置投影。 */
@Component
public class AiJobEvidenceCollector {
  private final ObjectMapper mapper;
  private final KubernetesClient client;
  private final JobOwnershipService ownership;
  private final DiagnosticService diagnostics;
  private final YamlService yaml;
  private final AiContentGuard guard;
  private final AccessService access;
  private final RecordRepository records;
  private final AiEvidenceDocuments documents;
  private final AiJobEvidenceDetails details;

  /** 注入现有只读诊断与脱敏能力，模型不能指定任意集群目标。 */
  public AiJobEvidenceCollector(
      ObjectMapper mapper,
      KubernetesClient client,
      JobOwnershipService ownership,
      DiagnosticService diagnostics,
      YamlService yaml,
      AiContentGuard guard,
      AccessService access,
      RecordRepository records,
      AiEvidenceDocuments documents,
      AiJobEvidenceDetails details) {
    this.mapper = mapper;
    this.client = client;
    this.ownership = ownership;
    this.diagnostics = diagnostics;
    this.yaml = yaml;
    this.guard = guard;
    this.access = access;
    this.records = records;
    this.documents = documents;
    this.details = details;
  }

  /** 只通过明确关联找到 SQL 草稿；名称相同不能代替归属。 */
  public ObjectNode sql(String jobId) {
    return records.list("sql_jobs").stream()
        .filter(s -> jobId.equals(s.path("jobId").asText()))
        .findFirst()
        .orElse(null);
  }

  /** 草稿与 SQL 各自绑定版本和摘要，重启不会改变会话的草稿生命周期。 */
  public ObjectNode baseline(JobDraft job) {
    var base =
        mapper
            .createObjectNode()
            .put("jobRevision", job.revision())
            .put("jobCreatedAt", job.createdAt().toString())
            .put("yamlHash", SqlDeploymentSource.hash(job.yaml()));
    var sql = sql(job.id());
    if (sql != null)
      base.put("sqlId", sql.path("id").asText())
          .put("sqlRevision", sql.path("revision").asLong())
          .put("sqlHash", SqlDeploymentSource.hash(sql.path("sql").asText()));
    return base;
  }

  /** JSON 入库后整数节点可能由 Long 变为 Int，版本比较按数值语义而非节点类型。 */
  public boolean matches(JobDraft job, JsonNode expected) {
    var current = baseline(job);
    for (String field : List.of("jobRevision", "sqlRevision"))
      if (current.has(field) != expected.has(field)
          || current.path(field).asLong(-1) != expected.path(field).asLong(-1)) return false;
    for (String field : List.of("jobCreatedAt", "yamlHash", "sqlId", "sqlHash"))
      if (!current.path(field).asText().equals(expected.path(field).asText())) return false;
    return true;
  }

  /** 读取真实声明前核对平台所有权；不存在与读取失败在上层明确区分。 */
  public JsonNode resource(JobDraft job) {
    var resource = client.call("GET", client.base() + "/" + job.deploymentName(), null);
    if (!resource.isMissingNode()) ownership.assertManaged(resource, job.id());
    return resource;
  }

  /** 每份证据固定身份与采集时间；限制配置、日志和总字节，环境字面值与实际密钥不外发。 */
  public ObjectNode collect(JobDraft job, boolean includeLogs) {
    return collect(job, includeLogs, "");
  }

  /** 依据本轮问题和当前错误选取一类补充证据，不一次抓取所有观测端点。 */
  public ObjectNode collect(JobDraft job, boolean includeLogs, String focus) {
    return collect(job, includeLogs, "auto", focus);
  }

  /** 类别白名单用于主动取证；日志权限仍在服务端逐次检查。 */
  public ObjectNode collectCategory(JobDraft job, String category) {
    if (!java.util.Set.of(
            "auto",
            "configuration",
            "pods",
            "podMetrics",
            "logs",
            "flinkExceptions",
            "flinkCheckpoints",
            "flinkMetrics",
            "flinkLogs")
        .contains(category))
      throw new com.flinkweb.exception.common.BusinessException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "AI_INVALID_EVIDENCE_CATEGORY",
          "取证类别无效");
    return collect(job, category.equals("logs"), category, "");
  }

  /** 最新保存草稿每次重新读取，区分SQL草稿、已发布SQL和未渲染引用。 */
  public ObjectNode current(JobDraft job) {
    var value = mapper.createObjectNode();
    value.set("baseline", baseline(job));
    var source = yaml.parse(job.yaml());
    value.put("draftYaml", documents.yaml(source)).put("publishedSql", documents.sql(source));
    var sql = sql(job.id());
    if (sql != null && access.allowed("sql:" + sql.path("id").asText(), "read"))
      value.put("sql", guard.redact(sql.path("sql").asText()));
    value.put(
        "referenceNote",
        "{{secret:...}} / {{config:...}} 为平台合法引用，在实际提交时解析；[REDACTED]是只读脱敏标记，不能写回配置。");
    return value;
  }

  /** 完整文档仅供已授权页面查看；模型接收有界配置投影和对应类别摘录。 */
  public ObjectNode forModel(ObjectNode bundle) {
    var copy = bundle.deepCopy();
    copy.remove("documents");
    if (copy.toString().length() > 80000) {
      copy.remove("podDetails");
      copy.withArray("unavailable").add("Pod详细文档超过本轮模型预算，仅提供状态和配置投影");
    }
    return copy;
  }

  /** 每次采集构造新快照，类别结果不与上一轮旧时间的数据拼接。 */
  public ObjectNode collect(JobDraft job, boolean includeLogs, String requested, String focus) {
    if (!java.util.Set.of(
            "auto",
            "configuration",
            "pods",
            "podMetrics",
            "logs",
            "flinkExceptions",
            "flinkCheckpoints",
            "flinkMetrics",
            "flinkLogs")
        .contains(requested))
      throw new com.flinkweb.exception.common.BusinessException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "AI_INVALID_EVIDENCE_CATEGORY",
          "取证类别无效");
    var bundle = mapper.createObjectNode().put("queriedAt", Instant.now().toString());
    bundle.put("category", requested);
    var missing = bundle.putArray("unavailable");
    bundle.set("baseline", baseline(job));
    var source = yaml.parse(job.yaml());
    bundle.set("draftConfiguration", projection(source));
    bundle.set("documents", current(job));
    bundle.put("referenceNote", bundle.at("/documents/referenceNote").asText());
    var sql = sql(job.id());
    if (sql != null && access.allowed("sql:" + sql.path("id").asText(), "read"))
      bundle.put("currentSql", sql.path("sql").asText());
    if (sql != null
        && access.allowed("sql:" + sql.path("id").asText(), "read")
        && sql.path("runtime").has("udfArtifacts"))
      bundle.set("udfArtifacts", guard.sanitize(sql.path("runtime").path("udfArtifacts")));
    if (sql != null && access.allowed("sql:" + sql.path("id").asText(), "read"))
      bundle.set(
          "catalogs",
          guard.sanitize(AiCatalogContext.collect(records, mapper, sql.path("runtime"))));
    JsonNode live;
    try {
      live = resource(job);
      if (live.isMissingNode()) {
        bundle.put("resourceExists", false);
        missing.add("集群资源不存在，仅分析当前草稿与用户补充");
      } else {
        bundle
            .put("resourceExists", true)
            .put("uid", live.at("/metadata/uid").asText())
            .put("resourceVersion", live.at("/metadata/resourceVersion").asText())
            .put(
                "liveSpecHash",
                com.flinkweb.service.job.creation.support.JobResourceFingerprint.hash(
                    live.path("spec")));
        bundle.set("runtimeConfiguration", projection(live));
        ((ObjectNode) bundle.path("documents"))
            .put("runtimeYaml", documents.yaml(live))
            .put("runtimeSql", documents.sql(live));
        bundle.put("runtimeError", live.at("/status/error").asText());
        bundle.put("jobState", live.at("/status/jobStatus/state").asText());
        bundle.put("lifecycleState", live.at("/status/lifecycleState").asText());
        var detail = diagnostics.inspect(job.id());
        bundle.set("diagnostics", detail);
        String direction =
            (focus + " " + live.at("/status/error").asText()).toLowerCase(java.util.Locale.ROOT);
        String selected = requested;
        if (requested.equals("auto"))
          selected =
              direction.matches("(?s).*(checkpoint|savepoint|快照|保存点).*")
                  ? "flinkCheckpoints"
                  : direction.matches("(?s).*(outofmemory|oomkilled|jvm|gc overhead|堆内存).*")
                      ? "podMetrics"
                      : direction.matches("(?s).*(backpressure|反压|吞吐|gc|cpu).*")
                          ? "flinkMetrics"
                          : direction.matches(
                                  "(?s).*(failedscheduling|pending|挂载|volume|secret|imagepull|调度).*")
                              ? "pods"
                              : "configuration";
        bundle.put("category", selected);
        details.collect(job, live, bundle, selected);
        if (bundle.has("flink")) {
          try {
            var masked = diagnostics.redactEvidence(job.id(), bundle.path("flink"));
            if (masked == null) throw new IllegalStateException();
            bundle.set("flink", masked);
          } catch (RuntimeException error) {
            bundle.remove("flink");
            missing.add("Flink证据的Secret脱敏校验不可用，本轮不展示或外发原始内容");
          }
        }
        var logs = bundle.putArray("logs");
        if (includeLogs && access.allowed(job.id(), "operate")) {
          // 先在所有 Pod 中读取 Flink 主容器，避免两个名额被 JM 的下载器和主容器占满。
          int attempts = 0;
          outer:
          for (boolean main : List.of(true, false))
            for (var pod : detail.path("pods"))
              for (var container : pod.path("states")) {
                if (main != container.path("name").asText().equals("flink-main-container"))
                  continue;
                if (!main
                    && !(container.path("state").asText().equals("waiting")
                        || container.path("exitCode").asInt() != 0)) continue;
                boolean restarted = container.path("restarts").asInt() > 0;
                String phase = container.path("state").asText();
                boolean preferredPrevious =
                    restarted && !java.util.Set.of("running", "terminated").contains(phase);
                for (int attempt = 0; attempt < (restarted ? 2 : 1); attempt++) {
                  boolean previous = attempt == 0 ? preferredPrevious : !preferredPrevious;
                  if (attempts++ >= 4) break outer;
                  try {
                    var log =
                        diagnostics.log(
                            job.id(),
                            pod.path("name").asText(),
                            container.path("name").asText(),
                            600,
                            previous);
                    String text = log.path("text").asText();
                    logs.addObject()
                        .put("pod", pod.path("name").asText())
                        .put("container", container.path("name").asText())
                        .put("previous", previous)
                        .put("source", previous ? "previous" : "current")
                        .put("queriedAt", Instant.now().toString())
                        .put("fallback", attempt > 0)
                        .put("containerState", phase)
                        .put("text", AiJobEvidenceDetails.excerpt(text))
                        .put("excerpt", true)
                        .put("tailLines", 600);
                    break;
                  } catch (RuntimeException error) {
                    missing.add(
                        pod.path("name").asText()
                            + "/"
                            + container.path("name").asText()
                            + " 的"
                            + (previous ? "previous" : "current")
                            + "日志不可用"
                            + (attempt == 0 && restarted ? "，尝试另一日志来源" : "；不能据此排除故障"));
                  }
                }
              }
        } else if (includeLogs) missing.add("当前权限不能读取容器日志");
        var after = resource(job);
        if (after.isMissingNode()
            || !live.at("/metadata/uid").equals(after.at("/metadata/uid"))
            || !live.path("spec").equals(after.path("spec"))) {
          bundle.put("inconsistent", true);
          missing.add("采集期间资源身份或配置发生变化，请更新证据后再应用");
        }
      }
    } catch (RuntimeException error) {
      missing.add("运行证据读取失败，本轮不能证明集群资源是否存在，也不能执行重启");
    }
    var safe = (ObjectNode) guard.sanitize(bundle);
    if (forModel(safe).toString().length() > 80000) {
      safe.remove("logs");
      safe.remove("diagnostics");
      safe.withArray("unavailable").add("证据超过预算，已省略日志与事件详情，请缩小问题范围");
    }
    if (forModel(safe).toString().length() > 85000 || safe.toString().length() > 512000)
      throw new com.flinkweb.exception.common.BusinessException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "AI_INPUT_TOO_LARGE",
          "必要配置与 SQL 超过证据预算，请缩小诊断范围");
    return safe;
  }

  /** 配置投影保留Pod模板和引用结构，移除重复编码SQL；敏感值统一由文档边界遮蔽。 */
  private ObjectNode projection(JsonNode resource) {
    var safe = documents.resource(resource).path("spec");
    if (!safe.isObject()) return mapper.createObjectNode();
    var result = (ObjectNode) safe.deepCopy();
    if (result.path("job") instanceof ObjectNode job) job.remove("args");
    return result;
  }
}
