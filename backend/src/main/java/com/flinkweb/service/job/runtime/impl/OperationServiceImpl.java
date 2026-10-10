package com.flinkweb.service.job.runtime.impl;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.flinkweb.client.kubernetes.KubernetesClient;
import com.flinkweb.dto.job.creation.RenderedDeployment;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.audit.AuditService;
import com.flinkweb.service.job.creation.JobService;
import com.flinkweb.service.job.creation.YamlService;
import com.flinkweb.service.job.creation.support.VariableRenderer;
import com.flinkweb.service.job.management.JobOwnershipService;
import com.flinkweb.service.job.release.ReleaseService;
import com.flinkweb.service.job.runtime.OperationService;
import com.flinkweb.service.permission.AccessService;
import java.security.Principal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 编排作业部署、启停和快照操作，执行身份校验、幂等控制及 Kubernetes dry-run。 */
@Service
public class OperationServiceImpl implements OperationService {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(OperationServiceImpl.class);
  private final JobService jobs;
  private final com.flinkweb.repository.job.JobRepository jobRepository;
  private final com.flinkweb.service.job.runtime.StopService stops;
  private final JobOwnershipService ownership;
  private final YamlService yaml;
  private final VariableRenderer variables;
  private final KubernetesClient client;
  private final RecordRepository store;
  private final AuditService audit;
  private final ObjectMapper mapper;
  private final AccessService access;
  private final ReleaseService releases;
  private final com.flinkweb.client.storage.S3StorageClient storage;

  /** 注入作业、渲染、集群、幂等记录及身份校验服务。 */
  public OperationServiceImpl(
      JobService jobs,
      com.flinkweb.repository.job.JobRepository jobRepository,
      com.flinkweb.service.job.runtime.StopService stops,
      YamlService yaml,
      VariableRenderer variables,
      KubernetesClient client,
      RecordRepository store,
      AuditService audit,
      ObjectMapper mapper,
      JobOwnershipService ownership,
      AccessService access,
      ReleaseService releases,
      com.flinkweb.client.storage.S3StorageClient storage) {
    this.jobs = jobs;
    this.jobRepository = jobRepository;
    this.stops = stops;
    this.ownership = ownership;
    this.access = access;
    this.releases = releases;
    this.storage = storage;
    this.yaml = yaml;
    this.variables = variables;
    this.client = client;
    this.store = store;
    this.audit = audit;
    this.mapper = mapper;
  }

  /** 渲染配置引用并校验 YAML，可选择以 Kubernetes dry-run 检查集群是否保留提交字段。 */
  public ObjectNode validate(String source, String name, boolean dryRun) {
    var rendered = variables.render(yaml.parse(source));
    var resource = yaml.validate(rendered.deployment(), name);
    if (dryRun) {
      var existing = client.call("GET", client.base() + "/" + name, null);
      if (!existing.isMissingNode()) {
        ((ObjectNode) resource.get("metadata"))
            .put("resourceVersion", existing.path("metadata").path("resourceVersion").asText());
        assertPreserved(
            resource.path("spec"),
            client
                .call(
                    "PUT",
                    client.base() + "/" + name + "?dryRun=All&fieldValidation=Strict",
                    resource)
                .path("spec"));
      } else
        assertPreserved(
            resource.path("spec"),
            client
                .call("POST", client.base() + "?dryRun=All&fieldValidation=Strict", resource)
                .path("spec"));
    }
    return mapper
        .createObjectNode()
        .put("valid", true)
        .put("dryRun", dryRun)
        .put("yaml", yaml.dump(variables.preview(resource)))
        .put("message", dryRun ? "本地验证及 Kubernetes dry-run 通过" : "本地验证通过；提交时仍需 Kubernetes dry-run");
  }

  /** 校验权限、幂等标识与资源身份，提交指定操作并持久化接收、拒绝或未知结果。 */
  public synchronized ObjectNode execute(
      String id, String action, ObjectNode request, Principal user) {
    access.require(id, Set.of("deploy", "delete").contains(action) ? "edit" : "operate");
    var auth =
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .getAuthentication();
    if ((action.equals("deploy") || action.equals("delete"))
        && (auth == null
            || auth.getAuthorities().stream()
                .noneMatch(
                    a ->
                        a.getAuthority().equals("ROLE_ADMIN")
                            || a.getAuthority().equals("ROLE_DEVELOPER"))))
      throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "运行维护角色不能部署或删除作业");
    if (!Set.of(
            "deploy",
            "start",
            "stop",
            "suspend",
            "restart",
            "savepoint",
            "checkpoint",
            "restore",
            "delete")
        .contains(action)) fail("操作无效");
    String key = request.path("requestId").asText();
    try {
      UUID.fromString(key);
    } catch (Exception ex) {
      fail("缺少有效的 requestId");
    }
    String fingerprint =
        UUID.nameUUIDFromBytes(
                (id + action + request.toString() + user.getName())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .toString();
    var previous = store.find("operations", key);
    if (previous != null) {
      if (!fingerprint.equals(previous.path("fingerprint").asText()))
        throw new BusinessException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "请求标识已用于其他操作");
      log.debug(
          "Reusing operation jobId={} action={} requestId={} state={}",
          id,
          action,
          key,
          previous.path("state").asText());
      return previous;
    }
    var job = jobs.get(id);
    if (job.revision() != request.path("revision").asLong(-1))
      throw new BusinessException(HttpStatus.CONFLICT, "REVISION_CONFLICT", "草稿已修改，请刷新");
    var operation =
        mapper
            .createObjectNode()
            .put("jobId", id)
            .put("jobName", job.name())
            .put("action", action)
            .put("actor", user.getName())
            .put("fingerprint", fingerprint)
            .put("state", "SUBMITTING");
    operation = store.save("operations", key, operation, 0);
    log.info(
        "Operation submitting jobId={} action={} requestId={} revision={}",
        id,
        action,
        key,
        job.revision());
    try {
      JsonNode existing = client.call("GET", client.base() + "/" + job.deploymentName(), null);
      if (!existing.isMissingNode()) ownership.assertManaged(existing, id);
      if (action.equals("stop") || action.equals("suspend")) {
        String progress = stops.stop(id, request.path("upgradeMode").asText("savepoint"), existing);
        operation.put("state", "ACCEPTED").put("message", progress);
        audit.record("JOB_STOP", id, "ACCEPTED");
        log.info("Stop accepted jobId={} requestId={}", id, key);
        return store.save("operations", key, operation, 1);
      }
      if (stops.pending(id)) {
        var progress = stops.advance(id);
        if (!"DONE".equals(progress.path("phase").asText())) fail("停止尚未完成，请刷新确认资源删除后再操作");
      }
      if (Set.of("start", "restore", "deploy").contains(action) && existing.isMissingNode()) {
        ownership.release(id);
        jobRepository.useNumberedName(id);
        job = jobs.get(id);
        existing = client.call("GET", client.base() + "/" + job.deploymentName(), null);
        if (!existing.isMissingNode()) ownership.assertManaged(existing, id);
      }
      if (Set.of("start", "restore").contains(action)
          && !job.deploymentName()
              .equals(com.flinkweb.entity.job.JobDraft.numberedName(job.jobNumber())))
        fail("旧作业请先停止并删除原 FlinkDeployment；停止完成后启动会使用编号资源名");
      if (action.equals("delete") && !existing.isMissingNode()) {
        String state = existing.path("status").path("jobStatus").path("state").asText("UNKNOWN");
        if ("suspended".equals(existing.path("spec").path("job").path("state").asText())
            || !Set.of("FAILED", "FINISHED", "CANCELED").contains(state))
          fail("运行中、挂起中或状态未知的作业禁止删除；仅可删除终态作业");
      }
      String desired = existing.path("spec").path("job").path("state").asText();
      String observed = existing.path("status").path("jobStatus").path("state").asText();
      boolean terminal = Set.of("FAILED", "FINISHED", "CANCELED").contains(observed);
      boolean reconciling =
          existing.path("metadata").path("generation").asLong()
                  > existing.path("status").path("observedGeneration").asLong(Long.MAX_VALUE)
              || Set.of("UPGRADING", "DEPLOYING", "ROLLING_BACK")
                  .contains(existing.path("status").path("lifecycleState").asText());
      if (existing.path("metadata").hasNonNull("deletionTimestamp")) fail("资源正在删除，不能操作");
      if (action.equals("start") && "running".equals(desired) && (!terminal || reconciling))
        fail("作业已启动或正在启动，请勿重复启动");
      if ((action.equals("savepoint") || action.equals("checkpoint"))
          && (!"RUNNING".equals(observed)
              || !"running".equals(desired)
              || reconciling
              || "ERROR".equals(existing.path("status").path("jobManagerDeploymentStatus").asText())
              || !existing.path("status").path("error").asText().isBlank()))
        fail("快照需要实际正常运行且未在启停或升级中的作业");
      JsonNode response;
      if (action.equals("delete")) {
        if (existing.isMissingNode()) {
          response = mapper.createObjectNode();
          ownership.release(id);
        } else {
          ObjectNode options =
              mapper
                  .createObjectNode()
                  .put("apiVersion", "v1")
                  .put("kind", "DeleteOptions")
                  .put("propagationPolicy", "Foreground");
          options
              .putObject("preconditions")
              .put("uid", existing.path("metadata").path("uid").asText())
              .put("resourceVersion", existing.path("metadata").path("resourceVersion").asText());
          response = client.call("DELETE", client.base() + "/" + job.deploymentName(), options);
        }
      } else {
        ObjectNode resource;
        RenderedDeployment rendered = null;
        if (action.equals("deploy")
            || (Set.of("start", "restore").contains(action) && existing.isMissingNode())) {
          ObjectNode stoppedResource = action.equals("deploy") ? null : stops.restartResource(id);
          if (stoppedResource != null) {
            rendered = variables.render(stoppedResource);
            resource = rendered.deployment();
            resource.withObject("/metadata").put("name", job.deploymentName());
            resource.withObject("/spec/flinkConfiguration").remove("kubernetes.cluster-id");
          } else {
            if (auth == null
                || auth.getAuthorities().stream()
                    .noneMatch(
                        a ->
                            a.getAuthority().equals("ROLE_ADMIN")
                                || a.getAuthority().equals("ROLE_DEVELOPER")))
              fail("首次部署或没有停止前配置时，需要开发者执行部署");
            access.require(id, "edit");
            rendered = variables.render(yaml.parse(job.yaml()));
            rendered.deployment().withObject("/metadata").put("name", job.deploymentName());
            rendered
                .deployment()
                .withObject("/spec")
                .withObject("/flinkConfiguration")
                .remove("kubernetes.cluster-id");
            resource = yaml.validate(rendered.deployment(), job.deploymentName());
          }
          if (!action.equals("deploy")) {
            resource.withObject("/spec").withObject("/job").put("state", "running");
            String path =
                action.equals("restore")
                    ? request.path("savepointPath").asText()
                    : stops.restorePath(id);
            if (!path.isBlank()) {
              if (action.equals("restore")
                  && !storage.checkSnapshot(path).path("exists").asBoolean())
                fail("恢复快照元数据不存在，请检查状态存储");
              resource.withObject("/spec").withObject("/job").put("initialSavepointPath", path);
            } else resource.withObject("/spec").withObject("/job").remove("initialSavepointPath");
          }
          if (stoppedResource == null
              && !("v" + job.flinkVersion().replaceAll("^(\\d+\\.\\d+).*", "$1").replace('.', '_'))
                  .equals(resource.path("spec").path("flinkVersion").asText()))
            fail("草稿 Flink 版本与 YAML 不一致");
        } else {
          if (existing.isMissingNode())
            throw new BusinessException(HttpStatus.NOT_FOUND, "DEPLOYMENT_NOT_FOUND", "请先部署作业");
          resource = ((ObjectNode) existing).deepCopy();
          resource.remove("status");
          ((ObjectNode) resource.get("metadata")).remove("managedFields");
          ObjectNode spec = (ObjectNode) resource.get("spec"),
              specJob = (ObjectNode) spec.get("job");
          long nonce = System.currentTimeMillis();
          String mode =
              request.path("upgradeMode").asText(specJob.path("upgradeMode").asText("savepoint"));
          if (!Set.of("savepoint", "last-state", "stateless").contains(mode)) fail("升级模式无效");
          specJob.put("upgradeMode", mode);
          switch (action) {
            case "start" -> specJob.put("state", "running");
            case "restart" ->
                spec.put("restartNonce", Math.max(nonce, spec.path("restartNonce").asLong() + 1));
            case "savepoint", "checkpoint" -> {
              if (!"RUNNING"
                  .equals(existing.path("status").path("jobStatus").path("state").asText()))
                fail("快照需要实际运行中的作业");
              if (action.equals("checkpoint") && spec.path("flinkVersion").asText().equals("v1_16"))
                fail("Flink 1.16 不支持此手动 checkpoint 接口");
              String field = action + "TriggerNonce";
              specJob.put(field, Math.max(nonce, specJob.path(field).asLong() + 1));
            }
            case "restore" -> {
              String path = request.path("savepointPath").asText();
              if (!path.startsWith("s3://") || path.length() > 2048)
                fail("恢复路径必须是 S3 上已完成的 savepoint/checkpoint 路径");
              if (!storage.checkSnapshot(path).path("exists").asBoolean()) fail("快照元数据文件不存在，拒绝恢复");
              specJob
                  .put("initialSavepointPath", path)
                  .put(
                      "savepointRedeployNonce",
                      Math.max(nonce, specJob.path("savepointRedeployNonce").asLong() + 1))
                  .put("state", "running");
            }
          }
        }
        if (rendered == null && Set.of("start", "restore").contains(action)) {
          rendered = variables.render(resource);
          resource = rendered.deployment();
        }
        ObjectNode metadata = (ObjectNode) resource.get("metadata");
        metadata
            .withObject("/labels")
            .put("flink-web/job-id", id)
            .putIfAbsent("app.kubernetes.io/managed-by", TextNode.valueOf("flink-web"));
        metadata.withObject("/annotations").put("flink-web/last-request", key);
        String method = "POST", path = client.base();
        if (!existing.isMissingNode()) {
          method = "PUT";
          path += "/" + job.deploymentName();
          metadata.put(
              "resourceVersion", existing.path("metadata").path("resourceVersion").asText());
          for (String field : List.of("uid", "finalizers", "ownerReferences", "creationTimestamp"))
            if (existing.path("metadata").has(field))
              metadata.set(field, existing.path("metadata").get(field));
        }
        JsonNode checked =
            client.call(method, path + "?dryRun=All&fieldValidation=Strict", resource);
        assertPreserved(resource.path("spec"), checked.path("spec"));
        if (rendered != null) {
          var release = releases.capture(job, variables.preview(resource));
          operation.put("releaseId", release.path("id").asText());
          metadata
              .withObject("/annotations")
              .put("flink-web/release-id", release.path("id").asText());
          variables.materialize(rendered, client);
        }
        response = client.call(method, path, resource);
        if (Set.of("start", "restore", "deploy").contains(action)) stops.clear(id);
      }
      operation
          .put("state", "ACCEPTED")
          .put("message", "API 已接受，请手动刷新确认实际运行状态")
          .put("resourceVersion", response.path("metadata").path("resourceVersion").asText());
      audit.record("JOB_" + action.toUpperCase(), id, "ACCEPTED");
      log.info("Operation accepted jobId={} action={} requestId={}", id, action, key);
    } catch (BusinessException ex) {
      log.warn(
          "Operation rejected jobId={} action={} requestId={} code={}",
          id,
          action,
          key,
          ex.code(),
          ex);
      operation
          .put("state", ex.code().equals("KUBERNETES_UNAVAILABLE") ? "UNKNOWN" : "REJECTED")
          .put("message", ex.getMessage())
          .put("errorCode", ex.code());
      audit.record("JOB_" + action.toUpperCase(), id, operation.path("state").asText());
    } catch (RuntimeException ex) {
      log.error(
          "Operation outcome unknown jobId={} action={} requestId={} errorType={}",
          id,
          action,
          key,
          ex.getClass().getSimpleName());
      operation
          .put("state", "UNKNOWN")
          .put("errorCode", "OPERATION_FAILED")
          .put("message", "操作结果不确定，请刷新集群状态后再决定是否重试");
      audit.record("JOB_" + action.toUpperCase(), id, "UNKNOWN");
    }
    return store.save("operations", key, operation, 1);
  }

  /** 递归比较 dry-run 前后的 spec，拒绝字段被 CRD 裁剪或更改的提交。 */
  private void assertPreserved(JsonNode requested, JsonNode checked) {
    if (requested.isObject())
      requested
          .fields()
          .forEachRemaining(
              entry -> assertPreserved(entry.getValue(), checked.path(entry.getKey())));
    else if (requested.isArray()) {
      if (!checked.isArray() || requested.size() != checked.size())
        fail("集群 CRD 未保留提交字段，请检查 Operator/CRD 版本");
      for (int i = 0; i < requested.size(); i++) assertPreserved(requested.get(i), checked.path(i));
    } else if (requested.isNumber() && checked.isNumber()) {
      if (requested.decimalValue().compareTo(checked.decimalValue()) != 0) fail("集群修改了数值参数");
    } else if (!requested.equals(checked)) fail("集群 CRD 修改或裁剪了提交字段，请检查 Operator/CRD 版本");
  }

  /** 返回指定作业的操作历史，并移除内部幂等指纹。 */
  public java.util.List<ObjectNode> history(String id) {
    jobs.get(id);
    return store.listForSubject("operations", id).stream()
        .filter(op -> id.equals(op.path("jobId").asText()))
        .map(
            op -> {
              op.remove("fingerprint");
              return op;
            })
        .toList();
  }

  /** 抛出作业操作参数不合法的业务异常。 */
  private void fail(String text) {
    throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_OPERATION", text);
  }
}
