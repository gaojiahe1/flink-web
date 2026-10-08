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
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(StopServiceImpl.class);
  private final KubernetesClient client;
  private final RecordRepository records;
  private final JobRepository jobs;
  private final JobOwnershipService ownership;
  private final ObjectMapper mapper;

  /** 注入集群客户端和持久化仓库，不启动后台轮询。 */
  public StopServiceImpl(
      KubernetesClient client,
      RecordRepository records,
      JobRepository jobs,
      JobOwnershipService ownership,
      ObjectMapper mapper) {
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
      return pending == null
          ? "资源已删除，作业已停止"
          : completeMissing(id, pending).path("message").asText();
    }
    ownership.assertManaged(resource, id);
    boolean retrySavepoint =
        pending != null
            && "savepoint".equals(mode)
            && "savepoint".equals(pending.path("mode").asText())
            && "running".equals(resource.at("/spec/job/state").asText())
            && "RUNNING".equals(resource.at("/status/jobStatus/state").asText())
            && !"REQUESTED".equals(pending.path("phase").asText());
    if (pending != null && !"DONE".equals(pending.path("phase").asText())) {
      if (!pending.path("uid").asText().equals(resource.path("metadata").path("uid").asText()))
        fail("资源 UID 已变化，拒绝继续旧停止请求");
    }
    if (pending != null && !"DONE".equals(pending.path("phase").asText()) && !retrySavepoint) {
      if ("stateless".equals(mode)) {
        pending.put("mode", mode);
        pending = save(id, pending);
      }
      return proceed(id, pending, resource).path("message").asText();
    }
    if ("savepoint".equals(mode)) {
      if (!"RUNNING".equals(resource.path("status").path("jobStatus").path("state").asText())
          || !"running".equals(resource.path("spec").path("job").path("state").asText())
          || "ERROR".equals(resource.path("status").path("jobManagerDeploymentStatus").asText())
          || !resource.path("status").path("error").asText("").isBlank()
          || resource.path("metadata").hasNonNull("deletionTimestamp"))
        fail("Savepoint 后删除要求作业正常运行；启动中或异常作业请选择立即删除");
      if (reconciling(resource)) fail("作业正在协调或升级，请等待配置生效后再执行 Savepoint 停止");
      var conf = resource.path("spec").path("flinkConfiguration");
      if (conf.path("state.savepoints.dir").asText().isBlank()
          && conf.path("execution.checkpointing.savepoint-dir").asText().isBlank())
        fail("请先配置 Savepoint 存储目录，或选择立即删除");
    }
    var intent =
        mapper
            .createObjectNode()
            .put("jobId", id)
            .put("name", resource.path("metadata").path("name").asText())
            .put("uid", resource.path("metadata").path("uid").asText())
            .put("mode", mode)
            .put("phase", "REQUESTED")
            .put("baseline", location(resource))
            .put(
                "baselineLegacy",
                resource.at("/status/jobStatus/savepointInfo/lastSavepoint/location").asText())
            .put("baselineUpgrade", resource.at("/status/jobStatus/upgradeSavepointPath").asText())
            .put("message", "正在提交停止");
    if (resource.at("/metadata/generation").isIntegralNumber())
      intent.put("sourceGeneration", resource.at("/metadata/generation").asLong());
    ObjectNode deployment = ((ObjectNode) resource).deepCopy();
    deployment.remove("status");
    deployment
        .withObject("/metadata")
        .remove(
            java.util.List.of(
                "uid",
                "resourceVersion",
                "generation",
                "managedFields",
                "creationTimestamp",
                "deletionTimestamp",
                "ownerReferences",
                "finalizers"));
    intent.set("deployment", deployment);
    intent =
        records.save(
            "stop-intents", id, intent, pending == null ? 0 : pending.path("revision").asLong());
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
      return completeMissing(id, intent);
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
    boolean stopSpec =
        "suspended".equals(live.at("/spec/job/state").asText())
            && "savepoint".equals(live.at("/spec/job/upgradeMode").asText());
    boolean unchanged =
        !intent.has("stopGeneration")
            || intent.path("stopGeneration").asLong() == live.at("/metadata/generation").asLong();
    boolean saved =
        !point.isBlank()
            && !point.equals(intent.path("baseline").asText())
            && !point.equals(intent.path("baselineLegacy").asText())
            && !point.equals(intent.path("baselineUpgrade").asText())
            && stopSpec
            && unchanged
            && !reconciling(live)
            && Set.of("CANCELED", "FINISHED", "SUSPENDED")
                .contains(live.path("status").path("jobStatus").path("state").asText())
            && "SUSPENDED".equals(live.path("status").path("lifecycleState").asText());
    if (!immediate && !live.at("/status/error").asText().isBlank())
      fail("Operator 已报告停止或 Savepoint 异常，未删除资源；请检查诊断、修复后刷新，或选择立即删除");
    if (saved && !immediate && !upgradeSavepointConfirmed(live, point))
      fail("尚未确认本次停止的 Savepoint 快照，未删除资源；请刷新重试或检查 Operator 快照状态");
    if (immediate || saved) {
      if (saved && !immediate) intent.put("savepointPath", point);
      // 先落盘恢复路径，避免删除成功后进程退出导致路径丢失。
      intent =
          save(id, intent.put("phase", "DELETING").put("message", "正在删除 FlinkDeployment，请刷新确认"));
      ObjectNode options =
          mapper
              .createObjectNode()
              .put("apiVersion", "v1")
              .put("kind", "DeleteOptions")
              .put("propagationPolicy", "Foreground");
      options
          .putObject("preconditions")
          .put("uid", live.path("metadata").path("uid").asText())
          .put("resourceVersion", live.path("metadata").path("resourceVersion").asText());
      client.call("DELETE", client.base() + "/" + intent.path("name").asText(), options);
      return intent;
    }
    if ("REQUESTED".equals(intent.path("phase").asText())) {
      // PUT 响应丢失或进程重启后，已提交的停止配置应继续观察，不能再次推进 generation。
      if (stopSpec && unchanged)
        return save(
            id,
            intent.put("phase", "SAVEPOINT").put("message", "已提交停止，等待 Operator 确认本次 Savepoint"));
      if (!"running".equals(live.at("/spec/job/state").asText())
          || reconciling(live)
          || intent.has("sourceGeneration")
              && intent.path("sourceGeneration").asLong()
                  != live.at("/metadata/generation").asLong()) fail("作业配置已变化，停止请求未继续；请刷新后重新确认停止方式");
      ObjectNode changed = ((ObjectNode) live).deepCopy();
      changed.remove("status");
      changed.withObject("/metadata").remove("managedFields");
      changed
          .withObject("/spec")
          .withObject("/job")
          .put("state", "suspended")
          .put("upgradeMode", "savepoint");
      changed
          .withObject("/spec")
          .withObject("/flinkConfiguration")
          .put("kubernetes.operator.job.upgrade.last-state-fallback.enabled", "false");
      String path = client.base() + "/" + intent.path("name").asText();
      var checked = client.call("PUT", path + "?dryRun=All&fieldValidation=Strict", changed);
      if (!checked.path("spec").equals(changed.path("spec"))) fail("集群未保留停止配置，未提交实际修改");
      if (live.at("/metadata/generation").isIntegralNumber())
        intent.put("stopGeneration", live.at("/metadata/generation").asLong() + 1);
      // 在提交前持久化目标代数，未知结果可在下次刷新安全核对。
      intent = save(id, intent);
      client.call("PUT", path, changed);
      return save(
          id,
          intent
              .put("phase", "SAVEPOINT")
              .put("message", "等待本次停止的 Savepoint 完成；手动刷新将继续删除，失败时保留资源"));
    }
    if (!stopSpec || !unchanged) fail("停止期间作业配置已变化，未删除资源；请刷新后重新确认停止方式");
    if ("SUSPENDED".equals(live.at("/status/lifecycleState").asText()) && point.isBlank())
      fail("作业已挂起，但尚未确认本次停止生成的 Savepoint，已保留资源；请检查 Operator 状态");
    return save(id, intent.put("message", "等待本次停止的 Savepoint 完成；手动刷新将继续删除，失败时保留资源"));
  }

  /** 兼容新旧 Operator 的停止保存点，排除普通手动/定时快照和 last-state 哨兵。 */
  private String location(JsonNode resource) {
    String upgraded = resource.at("/status/jobStatus/upgradeSavepointPath").asText();
    if (!upgraded.isBlank()) return upgraded.contains("://") ? upgraded : "";
    JsonNode previous = resource.at("/status/jobStatus/savepointInfo/lastSavepoint");
    String path = previous.path("location").asText();
    return "UPGRADE".equals(previous.path("triggerType").asText()) && path.contains("://")
        ? path
        : "";
  }

  /** 区分停止产生的 Savepoint 与终态观测填入的 Checkpoint，兼容旧字段及新快照资源。 */
  private boolean upgradeSavepointConfirmed(JsonNode resource, String point) {
    JsonNode legacy = resource.at("/status/jobStatus/savepointInfo/lastSavepoint");
    if ("UPGRADE".equals(legacy.path("triggerType").asText())
        && point.equals(legacy.path("location").asText())) return true;
    String base = client.base();
    JsonNode snapshots =
        client.call("GET", base.substring(0, base.lastIndexOf('/')) + "/flinkstatesnapshots", null);
    for (JsonNode snapshot : snapshots.path("items")) {
      if (!"UPGRADE"
              .equals(
                  snapshot.path("metadata").path("labels").path("snapshot.trigger-type").asText())
          || snapshot.path("metadata").hasNonNull("deletionTimestamp")
          || !"FlinkDeployment".equals(snapshot.at("/spec/jobReference/kind").asText())
          || !resource
              .at("/metadata/name")
              .asText()
              .equals(snapshot.at("/spec/jobReference/name").asText())
          || !snapshot.at("/spec/savepoint/alreadyExists").asBoolean()
          || snapshot.path("spec").hasNonNull("checkpoint")
          || !point.equals(snapshot.at("/spec/savepoint/path").asText())
          || !"COMPLETED".equals(snapshot.at("/status/state").asText())
          || !point.equals(snapshot.at("/status/path").asText())) continue;
      return true;
    }
    return false;
  }

  /** 资源提前消失时如实说明无法确认保存点，不将停止完成等同于保存状态成功。 */
  private ObjectNode completeMissing(String id, ObjectNode intent) {
    String message =
        "savepoint".equals(intent.path("mode").asText())
                && intent.path("savepointPath").asText().isBlank()
            ? "资源已消失，作业已停止；未确认本次 Savepoint，无法保证状态已保存"
            : "资源已删除，作业已停止";
    return save(id, intent.put("phase", "DONE").put("message", message));
  }

  /** 仅使用 Operator 已确认的配置状态，缺少代数时保守依赖协调阶段。 */
  private boolean reconciling(JsonNode resource) {
    JsonNode generation = resource.at("/metadata/generation"),
        observed = resource.at("/status/observedGeneration");
    return generation.isIntegralNumber()
            && (!observed.isIntegralNumber() || generation.asLong() > observed.asLong())
        || Set.of("UPGRADING", "DEPLOYING", "ROLLING_BACK")
            .contains(resource.at("/status/lifecycleState").asText())
        || Set.of("UPGRADING", "ROLLING_BACK")
            .contains(resource.at("/status/reconciliationStatus/state").asText());
  }

  /** 使用记录修订号保存停止进度。 */
  private ObjectNode save(String id, ObjectNode value) {
    var previous = records.find("stop-intents", id);
    var saved = records.save("stop-intents", id, value, value.path("revision").asLong());
    if (previous == null || !previous.path("phase").equals(saved.path("phase")))
      log.info("Stop progress jobId={} phase={}", id, saved.path("phase").asText());
    return saved;
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
        ? ((ObjectNode) intent.get("deployment")).deepCopy()
        : null;
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
