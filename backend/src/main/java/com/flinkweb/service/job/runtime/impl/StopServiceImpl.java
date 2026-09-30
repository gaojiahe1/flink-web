package com.flinkweb.service.job.runtime.impl;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.flinkweb.client.kubernetes.KubernetesClient;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.repository.job.JobRepository;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.job.management.JobOwnershipService;
import com.flinkweb.service.job.runtime.StopService;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 将保留状态的停止拆成安全挂起和资源删除，持久化进度供再次操作及刷新续做。 */
@Service
public class StopServiceImpl implements StopService {
  private final KubernetesClient client;
  private final RecordRepository records;
  private final JobRepository jobs;
  private final JobOwnershipService ownership;
  private final ObjectMapper mapper;

  /** 注入集群客户端和持久化仓库，不启动后台轮询。 */
  public StopServiceImpl(KubernetesClient client, RecordRepository records, JobRepository jobs,
      JobOwnershipService ownership, ObjectMapper mapper) {
    this.client = client;
    this.records = records;
    this.jobs = jobs;
    this.ownership = ownership;
    this.mapper = mapper;
  }

  /** 校验策略和资源身份，记录停止意图后提交对应操作。 */
  public synchronized String stop(String id, String mode, JsonNode resource) {
    if (!Set.of("savepoint", "stateless").contains(mode)) fail("停止只支持 Savepoint 后删除或立即删除");
    var pending = records.find("stop-intents", id);
    if (resource.isMissingNode()) {
      ownership.release(id);
      if (pending != null) save(id, pending.put("phase", "DONE"));
      return "资源已删除，作业已停止";
    }
    ownership.assertManaged(resource, id);
    if (pending != null && !"DONE".equals(pending.path("phase").asText())) {
      if (!pending.path("uid").asText().equals(resource.path("metadata").path("uid").asText()))
        fail("资源 UID 已变化，拒绝继续旧停止请求");
      if ("stateless".equals(mode)) {
        pending.put("mode", mode);
        pending = save(id, pending);
      }
      return proceed(id, pending, resource).path("message").asText();
    }
    if ("savepoint".equals(mode)) {
      if (!"RUNNING".equals(resource.path("status").path("jobStatus").path("state").asText())
          || !"running".equals(resource.path("spec").path("job").path("state").asText())
          || resource.path("metadata").hasNonNull("deletionTimestamp"))
        fail("Savepoint 后删除要求作业正常运行；启动中或异常作业请选择立即删除");
      var conf = resource.path("spec").path("flinkConfiguration");
      if (conf.path("state.savepoints.dir").asText().isBlank()
          && conf.path("execution.checkpointing.savepoint-dir").asText().isBlank())
        fail("请先配置 Savepoint 存储目录，或选择立即删除");
    }
    var intent = mapper.createObjectNode().put("jobId", id)
        .put("name", resource.path("metadata").path("name").asText())
        .put("uid", resource.path("metadata").path("uid").asText())
        .put("mode", mode).put("phase", "REQUESTED")
        .put("baseline", location(resource)).put("message", "正在提交停止");
    ObjectNode deployment = ((ObjectNode) resource).deepCopy();
    deployment.remove("status");
    deployment.withObject("/metadata").remove(java.util.List.of("uid", "resourceVersion", "generation",
        "managedFields", "creationTimestamp", "deletionTimestamp", "ownerReferences", "finalizers"));
    intent.set("deployment", deployment);
    intent = records.save("stop-intents", id, intent, pending == null ? 0 : pending.path("revision").asLong());
    return proceed(id, intent, resource).path("message").asText();
  }

  /** 只在用户手动刷新时检查持久化的进行中停止命令。 */
  public synchronized ObjectNode advance(String id) {
    var intent = records.find("stop-intents", id);
    if (intent == null || intent.path("cleared").asBoolean()) return null;
    if ("DONE".equals(intent.path("phase").asText())) return intent;
    var job = jobs.findById(id).orElseThrow();
    var live = client.call("GET", client.base() + "/" + job.deploymentName(), null);
    if (live.isMissingNode()) {
      ownership.release(id);
      return save(id, intent.put("phase", "DONE").put("message", "资源已删除，作业已停止"));
    }
    ownership.assertManaged(live, id);
    if (!intent.path("uid").asText().equals(live.path("metadata").path("uid").asText()))
      fail("资源 UID 已变化，拒绝继续旧停止请求");
    return proceed(id, intent, live);
  }

  /** 提交带前置条件的删除，或先让 Operator 完成新的 Savepoint。 */
  private ObjectNode proceed(String id, ObjectNode intent, JsonNode live) {
    if (live.path("metadata").hasNonNull("deletionTimestamp"))
      return save(id, intent.put("phase", "DELETING").put("message", "正在删除 FlinkDeployment，请刷新确认"));
    boolean immediate = "stateless".equals(intent.path("mode").asText());
    String point = location(live);
    boolean saved = !point.isBlank() && !point.equals(intent.path("baseline").asText())
        && "suspended".equals(live.path("spec").path("job").path("state").asText())
        && Set.of("CANCELED", "FINISHED", "SUSPENDED").contains(
            live.path("status").path("jobStatus").path("state").asText())
        && "SUSPENDED".equals(live.path("status").path("lifecycleState").asText());
    if (immediate || saved || "DELETING".equals(intent.path("phase").asText())) {
      if (saved) intent.put("savepointPath", point);
      // 先落盘恢复路径，避免删除成功后进程退出导致路径丢失。
      intent = save(id, intent.put("phase", "DELETING").put("message", "正在删除 FlinkDeployment，请刷新确认"));
      ObjectNode options = mapper.createObjectNode().put("apiVersion", "v1")
          .put("kind", "DeleteOptions").put("propagationPolicy", "Foreground");
      options.putObject("preconditions").put("uid", live.path("metadata").path("uid").asText())
          .put("resourceVersion", live.path("metadata").path("resourceVersion").asText());
      client.call("DELETE", client.base() + "/" + intent.path("name").asText(), options);
      return intent;
    }
    if ("REQUESTED".equals(intent.path("phase").asText())) {
      ObjectNode changed = ((ObjectNode) live).deepCopy();
      changed.remove("status");
      changed.withObject("/metadata").remove("managedFields");
      changed.withObject("/spec").withObject("/job").put("state", "suspended").put("upgradeMode", "savepoint");
      changed.withObject("/spec").withObject("/flinkConfiguration")
          .put("kubernetes.operator.job.upgrade.last-state-fallback.enabled", "false");
      String path = client.base() + "/" + intent.path("name").asText();
      var checked = client.call("PUT", path + "?dryRun=All&fieldValidation=Strict", changed);
      if (!checked.path("spec").equals(changed.path("spec"))) fail("集群未保留停止配置，未提交实际修改");
      client.call("PUT", path, changed);
      intent.put("phase", "SAVEPOINT");
    }
    return save(id, intent.put("message", "等待新的 Savepoint 完成；手动刷新将继续删除，失败时保留资源"));
  }

  /** 读取 Operator 已完成的最新 Savepoint 路径。 */
  private String location(JsonNode resource) {
    return resource.path("status").path("jobStatus").path("savepointInfo")
        .path("lastSavepoint").path("location").asText();
  }

  /** 使用记录修订号保存停止进度。 */
  private ObjectNode save(String id, ObjectNode value) {
    return records.save("stop-intents", id, value, value.path("revision").asLong());
  }

  /** 返回停止前已持久化的 Savepoint 地址。 */
  public String restorePath(String id) {
    var intent = records.find("stop-intents", id);
    return intent == null ? "" : intent.path("savepointPath").asText();
  }

  /** 返回停止前已经在集群运行的配置，避免把尚未发布的草稿当作启动配置。 */
  public ObjectNode restartResource(String id) {
    var intent = records.find("stop-intents", id);
    return intent != null && intent.path("deployment").isObject()
        ? ((ObjectNode) intent.get("deployment")).deepCopy() : null;
  }

  /** 判断持久化停止流程是否已完成。 */
  public boolean pending(String id) {
    var intent = records.find("stop-intents", id);
    return intent != null && !"DONE".equals(intent.path("phase").asText());
  }

  /** 启动提交后标记旧停止流程完成，避免后续刷新误删新资源。 */
  public synchronized void clear(String id) {
    var intent = records.find("stop-intents", id);
    if (intent != null) save(id, intent.put("phase", "DONE").put("cleared", true));
  }

  /** 拒绝无法安全执行的停止请求。 */
  private void fail(String message) {
    throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_STOP", message);
  }
}
