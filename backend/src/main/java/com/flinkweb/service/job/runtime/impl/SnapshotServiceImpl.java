package com.flinkweb.service.job.runtime.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.client.storage.S3StorageClient;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.job.runtime.SnapshotService;
import com.flinkweb.service.permission.AccessService;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;

/** 将用户刷新时实际看到的快照登记为台账；不定时扫描 S3 或补造未观测的历史。 */
@Service
public class SnapshotServiceImpl implements SnapshotService {
  private final RecordRepository records;
  private final ObjectMapper mapper;
  private final AccessService access;
  private final S3StorageClient storage;

  /** 注入快照台账、权限与对象存储检查服务。 */
  public SnapshotServiceImpl(
      RecordRepository records,
      ObjectMapper mapper,
      AccessService access,
      S3StorageClient storage) {
    this.records = records;
    this.mapper = mapper;
    this.access = access;
    this.storage = storage;
  }

  /** 收集 Operator 已上报的完成快照，记录发现时的资源和发布标识。 */
  public void observe(ObjectNode row, String time) {
    String jobId = row.path("jobId").asText();
    if (jobId.isEmpty()) return;
    collect(jobId, "savepoint", row.path("savepointInfo"), row, time);
    collect(jobId, "checkpoint", row.path("checkpointInfo"), row, time);
    for (JsonNode point : row.path("snapshots"))
      if (Set.of("COMPLETED", "SUCCEEDED")
          .contains(point.path("state").asText().toUpperCase(Locale.ROOT)))
        collect(jobId, point.path("type").asText("savepoint"), point, row, time);
    String request = row.path("lastRequest").asText();
    if (!request.isEmpty()) {
      var op = records.find("operations", request);
      if (op != null
          && java.util.Set.of("ACCEPTED", "UNKNOWN").contains(op.path("state").asText())
          && jobId.equals(op.path("jobId").asText())) {
        op.put("observedAt", time)
            .put("observedState", row.path("jobState").asText())
            .put("observedLifecycle", row.path("lifecycleState").asText());
        try {
          records.save("operations", request, op, op.path("revision").asLong());
        } catch (com.flinkweb.exception.common.BusinessException ex) {
          if (!"REVISION_CONFLICT".equals(ex.code())) throw ex;
        }
      }
    }
  }

  /** 递归识别不同 Operator 版本的路径字段，去重保存而不改变快照文件。 */
  private void collect(String job, String type, JsonNode node, ObjectNode row, String time) {
    if (node.isObject()) {
      String path =
          node.path("location")
              .asText(node.path("path").asText(node.path("externalPath").asText()));
      String state = node.path("state").asText(node.path("status").asText(""));
      if (path.startsWith("s3://")
          && (state.isEmpty()
              || Set.of("COMPLETED", "SUCCEEDED").contains(state.toUpperCase(Locale.ROOT)))) {
        String id =
            UUID.nameUUIDFromBytes((job + type + path).getBytes(StandardCharsets.UTF_8)).toString();
        if (records.find("snapshots", id) == null) {
          var record =
              mapper
                  .createObjectNode()
                  .put("jobId", job)
                  .put("type", type)
                  .put("path", path)
                  .put("firstObservedAt", time)
                  .put(
                      "snapshotTime",
                      node.path("timeStamp")
                          .asText(
                              node.path("timestamp")
                                  .asText(node.path("resultTimestamp").asText(""))))
                  .put("resourceUid", row.path("uid").asText())
                  .put("observedReleaseId", row.path("releaseId").asText())
                  .put("source", "OPERATOR");
          try {
            records.save("snapshots", id, record, 0);
          } catch (com.flinkweb.exception.common.BusinessException ex) {
            if (!"REVISION_CONFLICT".equals(ex.code()) || records.find("snapshots", id) == null)
              throw ex;
          }
        }
      }
    }
    if (node.isContainerNode()) for (JsonNode child : node) collect(job, type, child, row, time);
  }

  /** 查询历史台账；观测时发布标识不等于快照创建时的准确代码版本。 */
  public List<ObjectNode> list(String id) {
    access.require(id, "read");
    return records.listForSubject("snapshots", id).stream()
        .sorted(
            Comparator.comparing((ObjectNode n) -> n.path("firstObservedAt").asText()).reversed())
        .toList();
  }

  /** 用户按需检查已登记路径的元数据文件是否存在，不宣称完整恢复兼容。 */
  public ObjectNode check(String jobId, String snapshotId) {
    access.require(jobId, "operate");
    var record = records.require("snapshots", snapshotId);
    if (!jobId.equals(record.path("jobId").asText()))
      throw new com.flinkweb.exception.common.BusinessException(
          org.springframework.http.HttpStatus.NOT_FOUND, "NOT_FOUND", "快照不属于该作业");
    var result = storage.checkSnapshot(record.path("path").asText());
    record.set("lastCheck", result);
    records.save("snapshots", snapshotId, record, record.path("revision").asLong());
    return result;
  }
}
