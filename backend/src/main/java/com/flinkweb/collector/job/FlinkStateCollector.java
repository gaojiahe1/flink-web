package com.flinkweb.collector.job;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.flinkweb.client.kubernetes.KubernetesClient;
import com.flinkweb.config.platform.PlatformProperties;
import com.flinkweb.exception.common.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 响应显式刷新请求，采集 FlinkDeployment 和快照状态；不创建轮询、监听器或共享缓存。 */
@Component
public class FlinkStateCollector {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(FlinkStateCollector.class);
  private final KubernetesClient client;
  private final ObjectMapper mapper;
  private final String namespace;
  private final DeploymentYamlSnapshot yamlSnapshot;

  /** 注入集群客户端及 JSON 映射器，限定本次采集的 Namespace。 */
  public FlinkStateCollector(
      KubernetesClient client,
      ObjectMapper mapper,
      PlatformProperties properties,
      DeploymentYamlSnapshot yamlSnapshot) {
    this.client = client;
    this.yamlSnapshot = yamlSnapshot;
    this.mapper = mapper;
    this.namespace = properties.namespace();
  }

  /** 每次直接查询部署及快照资源，提取运行状态和可纳管信息，不读写状态缓存。 */
  public ObjectNode collect() {
    var result = mapper.createArrayNode();
    var snapshotQuery = mapper.createObjectNode();
    JsonNode deployments = client.call("GET", client.base(), null);
    if (deployments.isMissingNode())
      throw new BusinessException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "CRD_UNAVAILABLE",
          "未找到目标 namespace 或 FlinkDeployment CRD");
    JsonNode snapshots = collectSnapshots(snapshotQuery);
    var secrets = yamlSnapshot.secrets();
    for (var item : deployments.path("items")) {
      if (!item.path("spec").path("job").isObject()) continue;
      var row =
          mapper
              .createObjectNode()
              .put("name", item.path("metadata").path("name").asText())
              .put(
                  "ownerJobId",
                  item.path("metadata").path("labels").path("flink-web/job-id").asText())
              .put("deleting", item.path("metadata").hasNonNull("deletionTimestamp"))
              .put("uid", item.path("metadata").path("uid").asText())
              .put("namespace", namespace)
              .put("application", item.path("spec").path("job").isObject())
              .put(
                  "flinkVersion",
                  item.path("spec")
                      .path("flinkVersion")
                      .asText()
                      .replaceFirst("^v", "")
                      .replace('_', '.'))
              .put("image", item.path("spec").path("image").asText())
              .put("resourceVersion", item.path("metadata").path("resourceVersion").asText())
              .put("desiredState", item.path("spec").path("job").path("state").asText())
              .put(
                  "jobState", item.path("status").path("jobStatus").path("state").asText("UNKNOWN"))
              .put("lifecycleState", item.path("status").path("lifecycleState").asText("UNKNOWN"))
              .put(
                  "jobManagerDeploymentStatus",
                  item.path("status").path("jobManagerDeploymentStatus").asText("UNKNOWN"));
      String error = item.path("status").path("error").asText("");
      for (String secret : secrets) error = error.replace(secret, "******");
      row.put("error", error);
      row.put(
          "healthState",
          "ERROR".equals(row.path("jobManagerDeploymentStatus").asText())
                  || !error.isBlank()
                  || "FAILED".equals(row.path("jobState").asText())
                  || "FAILED".equals(row.path("lifecycleState").asText())
              ? "ERROR"
              : row.path("jobState").asText());
      row.put(
          "lastRequest",
          item.path("metadata").path("annotations").path("flink-web/last-request").asText());
      row.put(
          "releaseId",
          item.path("metadata").path("annotations").path("flink-web/release-id").asText());
      row.set("savepointInfo", item.path("status").path("jobStatus").path("savepointInfo"));
      row.set("checkpointInfo", item.path("status").path("jobStatus").path("checkpointInfo"));
      var points = row.putArray("snapshots");
      for (JsonNode snapshot : snapshots.path("items"))
        if (snapshot
                .path("spec")
                .path("jobReference")
                .path("kind")
                .asText()
                .equals("FlinkDeployment")
            && snapshot
                .path("spec")
                .path("jobReference")
                .path("name")
                .asText()
                .equals(row.path("name").asText())) {
          points
              .addObject()
              .put("name", snapshot.path("metadata").path("name").asText())
              .put("type", snapshot.path("spec").has("checkpoint") ? "checkpoint" : "savepoint")
              .put("state", snapshot.path("status").path("state").asText("UNKNOWN"))
              .put("path", snapshot.path("status").path("path").asText())
              .put("resultTimestamp", snapshot.path("status").path("resultTimestamp").asText());
        }
      row.put("yaml", yamlSnapshot.render(item, secrets));
      result.add(row);
    }
    var response = mapper.createObjectNode().put("queriedAt", java.time.Instant.now().toString());
    response.set("snapshotQuery", snapshotQuery);
    return response.set("items", result);
  }

  /** 快照独立查询，旧版缺少 CRD 或读取失败时保留已成功取得的部署状态。 */
  private JsonNode collectSnapshots(ObjectNode query) {
    String path = "/apis/flink.apache.org/v1beta1/namespaces/" + namespace + "/flinkstatesnapshots";
    try {
      JsonNode snapshots = client.call("GET", path, null);
      if (snapshots.isMissingNode()) {
        query
            .put("state", "UNSUPPORTED")
            .put("message", "集群未提供 FlinkStateSnapshot 资源，继续读取部署中的旧版快照状态");
        return mapper.createObjectNode();
      }
      query.put("state", "AVAILABLE").put("message", "");
      return snapshots;
    } catch (BusinessException ex) {
      log.warn(
          "Snapshot query failed namespace={} resource=flinkstatesnapshots code={}; deployment"
              + " status retained",
          namespace,
          ex.code(),
          ex);
      query
          .put("state", "UNAVAILABLE")
          .put("code", ex.code())
          .put("message", "快照资源查询失败，作业状态已更新；" + ex.getMessage());
      return mapper.createObjectNode();
    }
  }
}
