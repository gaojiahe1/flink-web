package com.flinkweb.service.sql.support;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.client.storage.S3StorageClient;
import com.flinkweb.service.job.creation.YamlService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** SQL 复制保留当前脚本和运行参数，以新文件目录开始，不继承部署身份与恢复点。 */
@Component
public class SqlDraftCopySupport {
  private final S3StorageClient storage;
  private final YamlService yaml;

  /** 注入已有文件目录和安全 YAML 解析器。 */
  public SqlDraftCopySupport(S3StorageClient storage, YamlService yaml) {
    this.storage = storage;
    this.yaml = yaml;
  }

  /** 复制参数和专属文件，移除旧资源身份与恢复触发信息。 */
  public ObjectNode prepare(ObjectNode source, String name) {
    var runtime = source.withObject("/runtime").deepCopy();
    String from = runtime.path("workspaceId").asText(source.path("jobId").asText());
    String to = UUID.randomUUID().toString();
    boolean copyFiles = false;
    if (from.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")) {
      String oldPrefix = storage.jobPrefix(from), newPrefix = storage.jobPrefix(to);
      if (runtime.toString().contains(oldPrefix)) {
        copyFiles = true;
        runtime = yaml.parse(runtime.toString().replace(oldPrefix, newPrefix));
      }
    }
    runtime.put("workspaceId", to);
    for (String field : List.of("baseYaml", "deploymentYaml")) {
      if (runtime.path(field).asText().isBlank()) continue;
      var resource = yaml.parse(runtime.path(field).asText());
      resource.remove("status");
      var metadata = resource.withObject("/metadata");
      metadata.remove(
          List.of(
              "uid",
              "resourceVersion",
              "generation",
              "managedFields",
              "creationTimestamp",
              "deletionTimestamp",
              "ownerReferences",
              "finalizers"));
      metadata.put("name", name);
      for (String labels : List.of("labels", "annotations")) {
        if (!metadata.path(labels).isObject()) continue;
        var fields = new java.util.ArrayList<String>();
        metadata.path(labels).fieldNames().forEachRemaining(fields::add);
        for (String key : fields)
          if (key.startsWith("flink-web/") || key.equals("app.kubernetes.io/managed-by"))
            ((ObjectNode) metadata.get(labels)).remove(key);
      }
      var spec = resource.withObject("/spec");
      spec.remove("restartNonce");
      spec.withObject("/job")
          .remove(
              List.of(
                  "args",
                  "initialSavepointPath",
                  "savepointTriggerNonce",
                  "checkpointTriggerNonce",
                  "savepointRedeployNonce"));
      spec.withObject("/job").put("state", "running").put("upgradeMode", "stateless");
      resetConfig(spec.withObject("/flinkConfiguration"), name, to);
      runtime.put(field, yaml.dump(resource));
    }
    if (runtime.path("flinkConfiguration").isObject())
      resetConfig(runtime.withObject("/flinkConfiguration"), name, to);
    runtime.put("upgradeMode", "stateless");
    runtime.remove(List.of("initialSavepointPath", "savepointPath", "restartNonce"));
    if (copyFiles) {
      try {
        storage.copyJobFiles(from, to, true);
      } catch (RuntimeException failure) {
        cleanup(runtime, failure);
        throw failure;
      }
    }
    return runtime;
  }

  /** 新任务使用独立状态路径，不恢复原任务的快照。 */
  private void resetConfig(ObjectNode config, String name, String workspace) {
    config.remove(
        List.of(
            "kubernetes.cluster-id", "high-availability.cluster-id", "execution.savepoint.path"));
    config.put("pipeline.name", name);
    for (String key :
        List.of(
            "state.checkpoints.dir",
            "execution.checkpointing.dir",
            "state.savepoints.dir",
            "execution.checkpointing.savepoint-dir",
            "high-availability.storageDir"))
      if (config.has(key))
        config.put(
            key,
            storage.jobStateRoot(workspace)
                + (key.contains("savepoint")
                    ? "savepoints"
                    : key.equals("high-availability.storageDir") ? "ha" : "checkpoints"));
  }

  /** 仅清理由本次随机 UUID 创建的目录；失败信息不覆盖主异常。 */
  public void cleanup(ObjectNode runtime, RuntimeException failure) {
    try {
      storage.deleteJobStorage(runtime.path("workspaceId").asText(), true);
    } catch (RuntimeException cleanup) {
      failure.addSuppressed(cleanup);
    }
  }
}
