package com.flinkweb.service.job.creation.impl;

import com.flinkweb.dto.job.creation.DraftRequest;
import com.flinkweb.entity.job.JobDraft;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.repository.job.JobBindingRepository;
import com.flinkweb.repository.job.JobRepository;
import com.flinkweb.service.audit.AuditService;
import com.flinkweb.service.job.creation.JobService;
import com.flinkweb.service.job.creation.YamlService;
import com.flinkweb.service.job.creation.support.JobEditorPolicy;
import com.flinkweb.service.permission.AccessService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 维护作业草稿，处理 YAML 语法、名称不可变约束、乐观锁及事务审计。 */
@Service
public class JobServiceImpl implements JobService {
  private final JobRepository repository;
  private final YamlService yaml;
  private final JobBindingRepository bindings;
  private final AuditService audit;
  private final AccessService access;
  private final com.flinkweb.client.storage.S3StorageClient storage;
  private final com.flinkweb.repository.metadata.RecordRepository records;
  private final com.flinkweb.service.ai.support.AiJobState aiJobState;
  private final org.springframework.transaction.support.TransactionTemplate transactions;

  /** 注入草稿仓库、YAML 校验器、绑定仓库和审计服务。 */
  public JobServiceImpl(
      JobRepository repository,
      AuditService audit,
      YamlService yaml,
      JobBindingRepository bindings,
      AccessService access,
      com.flinkweb.client.storage.S3StorageClient storage,
      com.flinkweb.repository.metadata.RecordRepository records,
      com.flinkweb.service.ai.support.AiJobState aiJobState,
      org.springframework.transaction.PlatformTransactionManager manager) {
    this.transactions = new org.springframework.transaction.support.TransactionTemplate(manager);
    this.aiJobState = aiJobState;
    this.records = records;
    this.storage = storage;
    this.repository = repository;
    this.audit = audit;
    this.yaml = yaml;
    this.bindings = bindings;
    this.access = access;
  }

  /** 查询本地已登记草稿，不访问集群状态。 */
  public List<JobDraft> list() {
    var permitted = access.evaluator();
    return repository.findAll().stream().filter(job -> permitted.test(job.id(), "read")).toList();
  }

  /** 检查全局唯一名称，保存仍由数据库唯一约束兜底。 */
  public boolean nameAvailable(String name, String excludeId) {
    var sql = records.find("sql_jobs", name);
    return repository.findByName(name).filter(job -> !job.id().equals(excludeId)).isEmpty()
        && (sql == null || !excludeId.isBlank() && excludeId.equals(sql.path("jobId").asText()));
  }

  /** 唯一记录覆盖尚未发布的 SQL 名称，避免跨入口并发占用。 */
  public void reserveName(String name, String owner) {
    var old = records.find("job_names", name);
    if (old != null && !owner.equals(old.path("owner").asText()))
      throw new BusinessException(HttpStatus.CONFLICT, "JOB_NAME_EXISTS", "作业名称已存在，请使用其他名称");
    if (old == null) records.save("job_names", name,
        new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("owner", owner), 0);
  }

  /** SQL 与部署共享名称时，删除部署不能释放仍属于 SQL 草稿的名称。 */
  public void releaseName(String name, String owner) {
    var old = records.find("job_names", name);
    if (old != null && owner.equals(old.path("owner").asText()))
      records.delete("job_names", name, old.path("revision").asLong());
  }

  /** 读取指定草稿，不存在时返回明确业务错误。 */
  public JobDraft get(String id) {
    access.require(id, "read");
    return repository
        .findById(id)
        .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", "作业草稿不存在"));
  }

  /** 校验 YAML 语法，在同一事务内新增草稿和审计记录。 */
  @org.springframework.transaction.annotation.Transactional
  public JobDraft create(DraftRequest request) {
    return createDraft(request, null);
  }

  /** 仅受控 SQL 发布流程能复用同一名称，并重新核验编辑范围。 */
  @org.springframework.transaction.annotation.Transactional
  public JobDraft createSql(DraftRequest request, String sqlId) {
    access.require("sql:" + sqlId, "edit");
    if (!sqlId.equals(request.name())) throw new BusinessException(HttpStatus.BAD_REQUEST, "IMMUTABLE_NAME", "SQL 名称不可修改");
    records.require("sql_jobs", sqlId);
    return createDraft(request, sqlId);
  }

  /** 共用创建逻辑，在插入部署之前完成名称占用。 */
  private JobDraft createDraft(DraftRequest request, String sqlId) {
    var source = yaml.parse(request.yaml());
    if (source.at("/metadata/labels").has(JobEditorPolicy.LABEL))
      JobEditorPolicy.mark(source, JobEditorPolicy.allowsForm(source));
    if (request.workspaceId() != null
        && !request
            .workspaceId()
            .matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
      throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_WORKSPACE", "文件目录标识无效");
    if (request.workspaceId() != null && repository.findById(request.workspaceId()).isPresent())
      throw new BusinessException(HttpStatus.CONFLICT, "JOB_ID_EXISTS", "作业目录标识已存在，请重新创建");
    if (repository.findByName(request.name()).isPresent())
      throw new BusinessException(HttpStatus.CONFLICT, "JOB_NAME_EXISTS", "作业名称已存在，请修改名称或编辑已有作业");
    if (sqlId == null && records.find("sql_jobs", request.name()) != null)
      throw new BusinessException(HttpStatus.CONFLICT, "JOB_NAME_EXISTS", "作业名称已被 SQL 草稿占用");
    var now = Instant.now();
    var draft =
        new JobDraft(
            request.workspaceId() == null
                ? UUID.randomUUID().toString()
                : UUID.fromString(request.workspaceId()).toString(),
            request.name(),
            request.description(),
            request.flinkVersion(),
            source.equals(yaml.parse(request.yaml())) ? request.yaml() : yaml.dump(source),
            0,
            now,
            now,
            0,
            null);
    reserveName(request.name(), sqlId == null ? "job:" + draft.id() : "sql:" + sqlId);
    repository.insert(draft);
    access.initialize(draft.id());
    if (!"sql".equals(source.at("/metadata/labels/flink-web~1job-type").asText()))
      records.save(
          "jar_development",
          draft.id(),
          new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(),
          0);
    audit.record("DRAFT_CREATE", draft.id(), "SUCCESS");
    return repository.findById(draft.id()).orElseThrow();
  }

  /** 保持作业名称不变，校验 YAML 后以修订号更新草稿及审计。 */
  @org.springframework.transaction.annotation.Transactional
  public JobDraft update(String id, DraftRequest request) {
    access.require(id, "edit");
    var existing = get(id);
    if (!existing.name().equals(request.name()))
      throw new BusinessException(HttpStatus.BAD_REQUEST, "IMMUTABLE_NAME", "作业名称创建后不可修改");
    var source = yaml.parse(request.yaml());
    var previous = yaml.parse(existing.yaml());
    if (request.allowFormRewrite()
        || previous.at("/metadata/labels").has(JobEditorPolicy.LABEL)
        || source.at("/metadata/labels").has(JobEditorPolicy.LABEL))
      JobEditorPolicy.mark(
          source, JobEditorPolicy.allowsForm(previous) || request.allowFormRewrite());
    var updated =
        new JobDraft(
            id,
            request.name(),
            request.description(),
            request.flinkVersion(),
            source.equals(yaml.parse(request.yaml())) ? request.yaml() : yaml.dump(source),
            request.revision() + 1,
            existing.createdAt(),
            Instant.now(),
            existing.jobNumber(),
            existing.deploymentName());
    if (!repository.update(updated, request.revision())) throw conflict();
    audit.record(request.allowFormRewrite() ? "DRAFT_FORM_CONVERT" : "DRAFT_UPDATE", id, "SUCCESS");
    return updated;
  }

  /** 以修订号删除草稿及资源绑定，并在同一事务内记录审计。 */
  public void delete(String id, long revision) {
    // 先取生命周期锁再开启 SQLite 事务，避免独占连接与完成回调之间形成锁顺序反转。
    synchronized(aiJobState.monitor()) { transactions.executeWithoutResult(status -> deleteLocked(id,revision)); }
  }

  /** 作业与所有用户的诊断记录在同一事务内删除，旧任务不能恢复该生命周期。 */
  private void deleteLocked(String id,long revision) {
    access.require(id, "edit");
    var existing = get(id);
    audit.record("DRAFT_DELETE", id, "SUCCESS");
    if (!repository.delete(id, revision)) throw conflict();
    releaseName(existing.name(), "job:" + id);
    for (var sql : records.list("sql_jobs")) {
      if (!id.equals(sql.path("jobId").asText())) continue;
      String workspace =
          UUID.nameUUIDFromBytes(
                  ("sql-workspace:" + sql.path("id").asText() + ":" + id)
                      .getBytes(java.nio.charset.StandardCharsets.UTF_8))
              .toString();
      String oldPrefix = storage.jobPrefix(id), newPrefix = storage.jobPrefix(workspace);
      var runtime = sql.withObject("/runtime");
      if (runtime.toString().contains(oldPrefix)) {
        // SQL 开发草稿仍存在，先迁移其依赖，再按原规则清理已删除作业目录。
        storage.copyJobFiles(id, workspace, true);
        sql.set("runtime", yaml.parse(runtime.toString().replace(oldPrefix, newPrefix)));
      }
      sql.remove(List.of("jobId", "publishedAt"));
      sql.withObject("/runtime").put("workspaceId", workspace);
      records.save("sql_jobs", sql.path("id").asText(), sql, sql.path("revision").asLong());
    }
    storage.deleteJobStorage(id, existing.yaml().contains("s3://"));
    bindings.delete(id);
    var jar = records.find("jar_development", id);
    if (jar != null) records.delete("jar_development", id, jar.path("revision").asLong());
    aiJobState.delete(id);
  }

  /** 复制源配置为独立草稿；新编号、文件目录和状态目录不与源作业共用。 */
  @org.springframework.transaction.annotation.Transactional
  public JobDraft copy(String id, com.flinkweb.dto.job.creation.CopyJobRequest request) {
    access.require(id, "edit");
    var source = get(id);
    if (source.revision() != request.revision()) throw conflict();
    if (records.list("sql_jobs").stream().anyMatch(sql -> id.equals(sql.path("jobId").asText())))
      throw new BusinessException(
          HttpStatus.CONFLICT, "SQL_EDITOR_REQUIRED", "SQL 作业请在 SQL 开发中新建草稿，不能复制为无 SQL 关联的 JAR 作业");
    String target = UUID.randomUUID().toString();
    var node = yaml.parse(source.yaml());
    node.remove("status");
    var metadata = node.withObject("/metadata");
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
    for (String field : List.of("labels", "annotations"))
      if (metadata.path(field).isObject()) {
        var names = new java.util.ArrayList<String>();
        metadata.path(field).fieldNames().forEachRemaining(names::add);
        for (String key : names)
          if (key.startsWith("flink-web/") || key.equals("app.kubernetes.io/managed-by"))
            ((com.fasterxml.jackson.databind.node.ObjectNode) metadata.get(field)).remove(key);
      }
    JobEditorPolicy.mark(
        node, request.allowFormRewrite() || JobEditorPolicy.allowsForm(yaml.parse(source.yaml())));
    metadata.put("name", request.name());
    var spec = node.withObject("/spec");
    spec.remove(List.of("restartNonce"));
    var job = spec.withObject("/job");
    job.put("state", "running");
    job.remove(
        List.of(
            "initialSavepointPath",
            "savepointTriggerNonce",
            "checkpointTriggerNonce",
            "savepointRedeployNonce"));
    var config = spec.withObject("/flinkConfiguration");
    config.remove(
        List.of(
            "kubernetes.cluster-id", "high-availability.cluster-id", "execution.savepoint.path"));
    for (String key :
        List.of(
            "state.checkpoints.dir",
            "execution.checkpointing.dir",
            "state.savepoints.dir",
            "execution.checkpointing.savepoint-dir",
            "high-availability.storageDir")) {
      if (config.has(key)) {
        String suffix =
            key.contains("savepoint")
                ? "savepoints"
                : key.equals("high-availability.storageDir") ? "ha" : "checkpoints";
        config.put(key, storage.jobStateRoot(target) + suffix);
      }
    }
    String content = yaml.dump(node);
    String bucket = storage.defaults().path("bucket").asText();
    String sourceUri = "s3://" + bucket + "/" + storage.jobPrefix(id);
    String targetUri = "s3://" + bucket + "/" + storage.jobPrefix(target);
    content = content.replace(sourceUri, targetUri);
    var created =
        create(
            new DraftRequest(
                request.name(), source.description(), source.flinkVersion(), content, 0, target));
    node = yaml.parse(created.yaml());
    node.withObject("/metadata").put("name", created.deploymentName());
    var result =
        update(
            created.id(),
            new DraftRequest(
                created.name(),
                created.description(),
                created.flinkVersion(),
                yaml.dump(node),
                created.revision()));
    audit.record("DRAFT_COPY", target, "SUCCESS");
    storage.copyJobFiles(id, target, source.yaml().contains(sourceUri));
    return result;
  }

  /** 创建草稿被并发修改的冲突异常。 */
  private BusinessException conflict() {
    return new BusinessException(HttpStatus.CONFLICT, "REVISION_CONFLICT", "草稿已被修改，请重新加载后再操作");
  }
}
