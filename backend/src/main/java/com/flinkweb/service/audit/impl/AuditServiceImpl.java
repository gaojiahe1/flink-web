package com.flinkweb.service.audit.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.audit.AuditService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** 记录操作人、动作和结果，并提供审计记录查询。 */
@Service
public class AuditServiceImpl implements AuditService {
  private final RecordRepository store;
  private final ObjectMapper mapper;
  private final com.flinkweb.repository.job.JobRepository jobs;

  /** 注入元数据仓库与 JSON 映射器，供审计记录读写使用。 */
  public AuditServiceImpl(
      RecordRepository store, ObjectMapper mapper, com.flinkweb.repository.job.JobRepository jobs) {
    this.store = store;
    this.mapper = mapper;
    this.jobs = jobs;
  }

  /** 查询已持久化的审计记录，时间排序由展示层处理。 */
  public java.util.List<com.fasterxml.jackson.databind.node.ObjectNode> list() {
    var targets = new java.util.HashMap<String, com.fasterxml.jackson.databind.node.ObjectNode>();
    return store.list("audit").stream()
        .map(
            row -> {
              var value = row.deepCopy();
              String time = validTime(value.path("time").asText());
              if (time.isEmpty()) {
                time = validTime(value.path("createdAt").asText());
                value.put("timeSource", time.isEmpty() ? "unavailable" : "createdAt");
              } else value.put("timeSource", "time");
              value.put("time", time);
              if (value.path("targetName").asText().isBlank()) {
                String key = value.path("action").asText() + ":" + value.path("target").asText();
                var target =
                    targets.computeIfAbsent(
                        key,
                        ignored ->
                            describe(value.path("action").asText(), value.path("target").asText()));
                target.fields().forEachRemaining(e -> value.set(e.getKey(), e.getValue()));
              }
              return value;
            })
        .toList();
  }

  /** 只接受可解析的真实时间，不用当前时间冒充历史事件。 */
  private String validTime(String value) {
    try {
      return Instant.parse(value).toString();
    } catch (java.time.format.DateTimeParseException ex) {
      return "";
    }
  }

  /** 保存可读名称快照；旧记录仅按现有元数据补充，不猜测已删除目标的名称。 */
  private com.fasterxml.jackson.databind.node.ObjectNode describe(String action, String target) {
    var result = mapper.createObjectNode();
    String collection = "", id = target, type = "平台资源";
    if (action.startsWith("SQL_")) {
      collection = "sql_jobs";
      type = "SQL 作业";
    } else if (action.startsWith("SESSION_")) {
      collection = "sessions";
      type = "Session 集群";
    } else if (action.startsWith("DRAFT_")
        || action.startsWith("JOB_")
        || action.equals("ACCESS_UPDATE")) {
      if (target.startsWith("sql:")) {
        collection = "sql_jobs";
        id = target.substring(4);
        type = "SQL 作业";
      } else {
        type = "作业";
        var job = jobs.findById(target);
        if (job.isPresent())
          return result
              .put("targetType", type)
              .put("targetName", job.get().name())
              .put("targetReference", job.get().deploymentName());
      }
    } else if (action.startsWith("FLINK_CATALOG_")) {
      collection = "flink_catalogs";
      type = "Catalog";
    } else if (action.startsWith("CATALOG_")) {
      int slash = target.indexOf('/');
      if (slash > 0) {
        collection = target.substring(0, slash);
        id = target.substring(slash + 1);
      }
      type =
          switch (collection) {
            case "images" -> "运行镜像";
            case "templates" -> "默认模板";
            case "configs" -> "平台配置";
            case "datasources" -> "Catalog 连接";
            default -> "资源配置";
          };
    } else if (action.startsWith("DATASOURCE_")) {
      collection = "datasources";
      type = "Catalog 连接";
    } else if (action.startsWith("AI_MODEL_")) {
      collection = "ai_models";
      type = "AI 模型";
    } else if (action.startsWith("AI_TASK_")) {
      collection = "ai_tasks";
      type = "AI 任务";
    } else if (action.startsWith("USER_")) {
      type = "用户";
    } else if (action.startsWith("GROUP_")) {
      collection = "groups";
      type = "用户组";
    }
    String name = "";
    if (!collection.isEmpty()) {
      var record = store.find(collection, id);
      if (record != null) name = record.path("name").asText(id);
    } else if (!id.matches("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")) name = id;
    return result
        .put("targetType", type)
        .put("targetName", name.isBlank() ? "已删除或未登记的" + type : name);
  }

  /** 保存当前操作人、动作、目标及执行结果，不记录凭据内容。 */
  public void record(String action, String target, String outcome) {
    record(action, target, outcome, mapper.createObjectNode());
  }

  /** 统一保存时间、目标名称快照和白名单定位上下文。 */
  public void record(
      String action,
      String target,
      String outcome,
      com.fasterxml.jackson.databind.node.ObjectNode context) {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    var value =
        mapper
            .createObjectNode()
            .put("actor", auth == null ? "system" : auth.getName())
            .put("action", action)
            .put("target", target)
            .put("outcome", outcome)
            .put("time", Instant.now().toString());
    value.setAll(describe(action, target));
    var safe = value.putObject("context");
    for (String key : java.util.List.of("pod", "container", "previous", "lines"))
      if (context.has(key)) safe.set(key, context.get(key));
    store.save("audit", UUID.randomUUID().toString(), value, 0);
  }
}
