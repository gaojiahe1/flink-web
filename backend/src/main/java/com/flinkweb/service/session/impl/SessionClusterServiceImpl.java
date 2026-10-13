package com.flinkweb.service.session.impl;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.flinkweb.client.kubernetes.KubernetesClient;
import com.flinkweb.collector.job.DeploymentYamlSnapshot;
import com.flinkweb.dto.session.SessionRequest;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.audit.AuditService;
import com.flinkweb.service.job.creation.*;
import com.flinkweb.service.job.creation.support.VariableRenderer;
import com.flinkweb.service.session.SessionClusterService;
import com.flinkweb.service.sql.support.SqlRuntimeArtifacts;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 以不含 spec.job 的 FlinkDeployment 管理 Session，保护集群身份和唯一验证入口。 */
@Service
public class SessionClusterServiceImpl implements SessionClusterService {
  private final RecordRepository records;
  private final FormService forms;
  private final YamlService yaml;
  private final SqlRuntimeArtifacts artifacts;
  private final VariableRenderer variables;
  private final KubernetesClient client;
  private final ObjectMapper mapper;
  private final AuditService audit;
  private final DeploymentYamlSnapshot redactor;
  private final com.flinkweb.service.job.creation.support.RuntimeYamlMapper runtimeYaml;
  private final com.flinkweb.client.flink.SessionRestClient rest;

  /** 注入现有表单、下载挂载、Secret 渲染和集群客户端。 */
  public SessionClusterServiceImpl(
      RecordRepository records,
      FormService forms,
      YamlService yaml,
      SqlRuntimeArtifacts artifacts,
      VariableRenderer variables,
      KubernetesClient client,
      ObjectMapper mapper,
      AuditService audit,
      DeploymentYamlSnapshot redactor,
      com.flinkweb.service.job.creation.support.RuntimeYamlMapper runtimeYaml,
      com.flinkweb.client.flink.SessionRestClient rest) {
    this.rest = rest;
    this.runtimeYaml = runtimeYaml;
    this.records = records;
    this.forms = forms;
    this.yaml = yaml;
    this.artifacts = artifacts;
    this.variables = variables;
    this.client = client;
    this.mapper = mapper;
    this.audit = audit;
    this.redactor = redactor;
  }

  /** Session 属于共享基础设施，列表只查询平台配置。 */
  public List<ObjectNode> list() {
    return records.list("sessions");
  }

  /** 保存之前本地渲染并验证；运行期间禁止修改配置，避免隐式重建。 */
  public synchronized ObjectNode save(String id, SessionRequest input) {
    if (!id.equals(input.validation() ? "sql-validation" : input.name())
        || !input.validation() && "sql-validation".equals(id))
      fail("INVALID_SESSION", "SQL 验证集群使用固定标识 sql-validation，且只能创建一个");
    var old = records.find("sessions", id);
    if (old != null && !"stopped".equals(old.path("desiredState").asText("stopped")))
      fail("SESSION_RUNNING", "请先停止 Session 再修改配置");
    if (old != null && !current(id, old).isMissingNode())
      fail("SESSION_RUNNING", "Session 资源仍存在，请先停止并确认删除后修改配置");
    var record =
        mapper
            .createObjectNode()
            .put("name", input.name())
            .put("description", input.description())
            .put("validation", input.validation())
            .put("deploymentName", "fw-session-" + id)
            .put("desiredState", "stopped");
    record.set("runtime", runtimeYaml.prepare(input.runtime(), "fw-session-" + id, true));
    resource(id, record);
    var saved = records.save("sessions", id, record, input.revision());
    audit.record("SESSION_SAVE", id, "SUCCESS");
    return saved;
  }

  /** 本地渲染和回填不会查询或创建集群，也不推进修订号。 */
  public ObjectNode configuration(SessionRequest input) {
    String id = input.validation() ? "sql-validation" : input.name();
    var record =
        mapper
            .createObjectNode()
            .put("deploymentName", "fw-session-" + id)
            .put("validation", input.validation());
    var runtime = runtimeYaml.prepare(input.runtime(), "fw-session-" + id, true);
    record.set("runtime", runtime);
    return mapper
        .createObjectNode()
        .put("yaml", yaml.dump(resource(id, record)))
        .put("formIssue", runtimeYaml.formIssue(runtime))
        .set("runtime", runtime);
  }

  /** 复用安全校验和文件挂载，仅在校验后移除 Application job 部分。 */
  private ObjectNode resource(String id, ObjectNode record) {
    var options =
        runtimeYaml.prepare(
            (ObjectNode) record.path("runtime"), record.path("deploymentName").asText(), true);
    if (record.path("validation").asBoolean()
        || !options.path("runnerArtifactId").asText().isBlank()) artifacts.configure(options);
    else {
      options.remove(java.util.List.of("artifactIds", "mainArtifactId", "entryClass"));
      options.put("jarURI", "local:///opt/flink/lib/session-placeholder.jar");
    }
    String name = record.path("deploymentName").asText();
    options.put("name", name).put("upgradeMode", "stateless");
    var node = yaml.validate(yaml.parse(forms.render(options).path("yaml").asText()), name);
    node.withObject("/metadata/labels").put("flink-web/session-id", id);
    node.withObject("/metadata/labels").remove("flink-web/editor");
    node.withObject("/spec").remove("job");
    if (record.path("validation").asBoolean()) {
      com.flinkweb.config.sql.SqlValidationLog.configure(node);
    }
    runtimeYaml.verifyManaged(options, node, true);
    return node;
  }

  /** 核对资源标签及绑定 UID，拒绝操作同名外部集群。 */
  private JsonNode current(String id, ObjectNode record) {
    var current =
        client.call("GET", client.base() + "/" + record.path("deploymentName").asText(), null);
    if (!current.isMissingNode()
        && (!id.equals(current.at("/metadata/labels/flink-web~1session-id").asText())
            || current.path("spec").has("job")
            || !record.path("uid").asText().isBlank()
                && !record.path("uid").asText().equals(current.at("/metadata/uid").asText())))
      fail("SESSION_IDENTITY_CONFLICT", "Session 资源身份已改变，请检查集群，禁止覆盖同名外部资源");
    return current;
  }

  /** 对共享诊断和代理仍执行 Session 身份核验，禁止访问同名替换资源。 */
  public synchronized JsonNode observedResource(String id) {
    var resource = current(id, records.require("sessions", id));
    if (resource.isMissingNode()) fail("SESSION_MISSING", "Session 资源不存在，请启动后刷新");
    return resource;
  }

  /** 按需刷新状态，保留 Operator 错误摘要并对输出脱敏。 */
  public synchronized ObjectNode status(String id) {
    return status(id, true);
  }

  /** 验证前只核对集群就绪，数量查询仅在用户显式刷新状态时执行。 */
  private synchronized ObjectNode status(String id, boolean observeTaskManagers) {
    var record = records.require("sessions", id);
    var current = current(id, record);
    if (current.isMissingNode()) {
      if ("stopping".equals(record.path("desiredState").asText())) {
        record.put("desiredState", "stopped");
        record.remove("uid");
        record = records.save("sessions", id, record, record.path("revision").asLong());
      }
      return record
          .put(
              "state",
              "stopped".equals(record.path("desiredState").asText()) ? "STOPPED" : "MISSING")
          .put("message", "Session 资源不存在");
    }
    String error = redact(current.at("/status/error").asText());
    if (record.path("uid").asText().isBlank() && !current.at("/metadata/uid").asText().isBlank()) {
      record.put("uid", current.at("/metadata/uid").asText());
      record = records.save("sessions", id, record, record.path("revision").asLong());
    }
    String observed = current.at("/status/jobManagerDeploymentStatus").asText("STARTING");
    var result =
        record
            .put(
                "state",
                current.at("/metadata/deletionTimestamp").isMissingNode()
                    ? !error.isBlank() || "ERROR".equals(observed) ? "ERROR" : observed
                    : "STOPPING")
            .put("message", error)
            .put("queriedAt", java.time.Instant.now().toString());
    if (current.at("/spec/taskManager/replicas").isIntegralNumber())
      result.set("configuredTaskManagers", current.at("/spec/taskManager/replicas"));
    if (observeTaskManagers && "READY".equals(result.path("state").asText())) {
      try {
        var overview = rest.call(record.path("deploymentName").asText(), "GET", "/overview", null);
        if (overview.path("taskmanagers").isIntegralNumber())
          result.set("registeredTaskManagers", overview.path("taskmanagers"));
      } catch (Exception ex) {
        result.put("taskManagerMessage", "暂时无法查询已注册 TaskManager 数量，请手动刷新");
      }
    }
    return result;
  }

  /** 启动执行 Kubernetes 严格 dry-run，再物化 Secret 和提交资源。 */
  public synchronized ObjectNode start(String id, long revision) {
    var record = records.require("sessions", id);
    if (record.path("revision").asLong() != revision)
      fail("REVISION_CONFLICT", "Session 配置已修改，请刷新后重试");
    var existing = current(id, record);
    if (!existing.isMissingNode()) {
      if (existing.path("metadata").has("deletionTimestamp"))
        fail("SESSION_STOPPING", "Session 正在停止，请刷新确认后启动");
      return status(id).put("operationState", "ACCEPTED");
    }
    var rendered = variables.render(resource(id, record));
    rendered.deployment().withObject("/spec").remove("job");
    var checked =
        client.call(
            "POST", client.base() + "?dryRun=All&fieldValidation=Strict", rendered.deployment());
    var differences = new ArrayList<String>();
    differences(rendered.deployment().path("spec"), checked.path("spec"), "/spec", differences);
    if (!differences.isEmpty())
      fail(
          "SESSION_SPEC_CHANGED",
          "启动前配置校验失败，Kubernetes 返回配置存在差异："
              + String.join("；", differences)
              + "。请核对对应字段、已安装 CRD 和 Operator；未创建 Session 资源");
    variables.materialize(rendered, client);
    record.put("desiredState", "running");
    record = records.save("sessions", id, record, revision);
    var submitted = client.call("POST", client.base(), rendered.deployment());
    record.put("uid", submitted.at("/metadata/uid").asText());
    record = records.save("sessions", id, record, record.path("revision").asLong());
    audit.record("SESSION_START", id, "ACCEPTED");
    return record
        .put("operationState", "ACCEPTED")
        .put("state", "STARTING")
        .put("message", "启动请求已受理，请刷新查看就绪状态");
  }

  /** 停止使用 UID/resourceVersion 前置条件，等待实际删除后才允许删除登记。 */
  public synchronized ObjectNode stop(String id) {
    var record = records.require("sessions", id);
    var current = current(id, record);
    if (current.isMissingNode()) {
      record.put("desiredState", "stopped");
      record.remove("uid");
    } else {
      if (!current.path("metadata").has("deletionTimestamp")) {
        var body = mapper.createObjectNode().put("apiVersion", "v1").put("kind", "DeleteOptions");
        body.putObject("preconditions")
            .put("uid", current.at("/metadata/uid").asText())
            .put("resourceVersion", current.at("/metadata/resourceVersion").asText());
        client.call("DELETE", client.base() + "/" + record.path("deploymentName").asText(), body);
      }
      record.put("desiredState", "stopping");
    }
    var saved = records.save("sessions", id, record, record.path("revision").asLong());
    audit.record("SESSION_STOP", id, "ACCEPTED");
    return saved
        .put("operationState", "ACCEPTED")
        .put(
            "state",
            "stopped".equals(saved.path("desiredState").asText()) ? "STOPPED" : "STOPPING");
  }

  /** 登记只能在集群资源确实消失后删除。 */
  public synchronized void delete(String id, long revision) {
    var record = records.require("sessions", id);
    if (!current(id, record).isMissingNode()) fail("SESSION_EXISTS", "请先停止集群并刷新确认删除完成");
    records.delete("sessions", id, revision);
    audit.record("SESSION_DELETE", id, "SUCCESS");
  }

  /** 专用验证集群不存在或未就绪时给出可操作提示。 */
  public ObjectNode validationCluster() {
    if (records.find("sessions", "sql-validation") == null)
      fail("VALIDATION_SESSION_REQUIRED", "请前往 Session 集群创建并启动唯一的 SQL 验证集群");
    var result = status("sql-validation", false);
    if (!"READY".equals(result.path("state").asText()))
      fail(
          "VALIDATION_SESSION_NOT_READY",
          "SQL 验证 Session 尚未就绪，请前往 Session 集群启动或检查错误：" + result.path("message").asText());
    if (!com.flinkweb.config.sql.SqlValidationLog.configured(observedResource("sql-validation")))
      fail("VALIDATION_LOG_CONFIGURATION_REQUIRED", "验证 Session 尚未使用固定日志配置，请停止并重新启动该集群后再验证");
    return result;
  }

  /** 验证生命周期内部查询不附带 TaskManager 数量观测。 */
  public ObjectNode validationStatus() {
    return status("sql-validation", false);
  }

  /** 服务端默认值可增加，但提交字段不允许被修改或裁剪。 */
  private void differences(JsonNode expected, JsonNode actual, String path, List<String> result) {
    if (result.size() >= 8) return;
    if (expected.isObject()) {
      if (!actual.isObject()) {
        result.add(path + "（对象缺失或类型改变）");
        return;
      }
      var fields = expected.fields();
      while (fields.hasNext()) {
        var entry = fields.next();
        differences(
            entry.getValue(),
            actual.path(entry.getKey()),
            path + "/" + entry.getKey().replace("~", "~0").replace("/", "~1"),
            result);
      }
      return;
    }
    if (expected.isArray()) {
      if (!actual.isArray() || expected.size() != actual.size()) {
        result.add(path + "（数组长度或类型改变）");
        return;
      }
      for (int i = 0; i < expected.size(); i++)
        differences(expected.get(i), actual.get(i), path + "/" + i, result);
      return;
    }
    if (expected.isNumber()
        && actual.isNumber()
        && expected.decimalValue().compareTo(actual.decimalValue()) == 0) return;
    if (!expected.equals(actual))
      result.add(path + (actual.isMissingNode() ? "（被裁剪）" : "（值或类型改变）"));
  }

  /** 遮蔽平台已知密钥和常见连接凭据。 */
  private String redact(String value) {
    for (String secret : redactor.secrets()) value = value.replace(secret, "******");
    return value
        .replaceAll(
            "(?i)((?:password|secret|token|access[_-]?key)\\s*[:=]\\s*)([^\\s,;]+)", "$1******")
        .replaceAll("(://)[^\\s/@:]+:[^\\s/@]+@", "$1******@");
  }

  /** 输出可识别的 Session 配置或状态冲突。 */
  private void fail(String code, String message) {
    throw new BusinessException(HttpStatus.CONFLICT, code, message);
  }
}
