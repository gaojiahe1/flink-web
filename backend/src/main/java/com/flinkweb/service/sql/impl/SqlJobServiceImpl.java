package com.flinkweb.service.sql.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.dto.job.creation.DraftRequest;
import com.flinkweb.dto.sql.SqlDraftRequest;
import com.flinkweb.entity.job.JobDraft;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.repository.job.JobRepository;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.audit.AuditService;
import com.flinkweb.service.job.creation.FormService;
import com.flinkweb.service.job.creation.JobService;
import com.flinkweb.service.job.creation.YamlService;
import com.flinkweb.service.permission.AccessService;
import com.flinkweb.service.sql.SqlJobService;
import com.flinkweb.service.sql.SqlParserService;
import com.flinkweb.service.sql.SqlValidationService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 管理 SQL 开发草稿并生成普通 Application 部署草稿，运行操作复用已有作业管理接口。 */
@Service
public class SqlJobServiceImpl implements SqlJobService {
  private final RecordRepository records;
  private final JobRepository jobRepository;
  private final JobService jobs;
  private final FormService forms;
  private final SqlValidationService validation;
  private final YamlService yaml;
  private final ObjectMapper mapper;
  private final AuditService audit;
  private final AccessService access;
  private final SqlParserService parser;
  private final com.flinkweb.service.sql.catalog.FlinkCatalogService catalogs;
  private final com.flinkweb.service.sql.support.SqlRuntimeArtifacts runtimeArtifacts;

  /** 注入草稿持久化、SQL 基础校验及部署渲染依赖。 */
  public SqlJobServiceImpl(
      RecordRepository records,
      JobRepository jobRepository,
      JobService jobs,
      FormService forms,
      SqlValidationService validation,
      YamlService yaml,
      ObjectMapper mapper,
      AuditService audit,
      AccessService access,
      SqlParserService parser,
      com.flinkweb.service.sql.catalog.FlinkCatalogService catalogs,
      com.flinkweb.service.sql.support.SqlRuntimeArtifacts runtimeArtifacts) {
    this.runtimeArtifacts = runtimeArtifacts;
    this.records = records;
    this.jobRepository = jobRepository;
    this.jobs = jobs;
    this.forms = forms;
    this.validation = validation;
    this.yaml = yaml;
    this.mapper = mapper;
    this.audit = audit;
    this.access = access;
    this.parser = parser;
    this.catalogs = catalogs;
  }

  /** 列出 SQL 开发草稿，不访问 Kubernetes 或外部数据源。 */
  public List<ObjectNode> list() {
    var permitted = access.evaluator();
    return records.list("sql_jobs").stream()
        .filter(s -> permitted.test("sql:" + s.path("id").asText(), "read"))
        .map(this::deploymentLink)
        .toList();
  }

  /** 读取指定 SQL 草稿及其已关联的部署信息。 */
  public ObjectNode get(String id) {
    access.require("sql:" + id, "read");
    return deploymentLink(records.require("sql_jobs", id));
  }

  /** 读取时兼容旧的失效关联；删除后的部署不再显示为已生成。 */
  private ObjectNode deploymentLink(ObjectNode record) {
    if (!record.path("jobId").asText().isBlank()
        && jobRepository.findById(record.path("jobId").asText()).isEmpty())
      record.remove(List.of("jobId", "publishedAt"));
    return record;
  }

  /** 保存尚在编写的 SQL，使用修订号保护并发修改；不要求未完成脚本通过执行检查。 */
  @Transactional
  public ObjectNode save(String id, SqlDraftRequest input) {
    access.require("sql:" + id, "edit");
    if (!id.equals(input.name()))
      throw new BusinessException(HttpStatus.BAD_REQUEST, "IMMUTABLE_NAME", "SQL 草稿名称不可修改");
    if (input.sql().getBytes(StandardCharsets.UTF_8).length > 32768)
      throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_SQL", "SQL 脚本不能超过 32 KiB");
    catalogs.prepare(input.runtime().deepCopy());
    ObjectNode old = records.find("sql_jobs", id);
    if (old != null) deploymentLink(old);
    var existingJob = jobRepository.findByName(input.name());
    if (existingJob.isPresent() && (old == null || !existingJob.get().id().equals(old.path("jobId").asText())))
      throw new BusinessException(HttpStatus.CONFLICT, "JOB_NAME_EXISTS", "作业名称已存在，请使用其他名称");
    ObjectNode record =
        mapper
            .createObjectNode()
            .put("name", input.name())
            .put("description", input.description())
            .put("sql", input.sql());
    record.set("runtime", input.runtime().deepCopy());
    if (old != null)
      for (String key : List.of("jobId", "publishedAt"))
        if (old.has(key)) record.set(key, old.get(key));
    ObjectNode saved = records.save("sql_jobs", id, record, input.revision());
    access.initialize("sql:" + id);
    records.save(
        "sql_versions",
        java.util.UUID.randomUUID().toString(),
        saved
            .deepCopy()
            .put("draftRevision", saved.path("revision").asLong())
            .put("sqlId", id)
            .put("actor", access.actor()),
        0);
    audit.record("SQL_SAVE", id, "SUCCESS");
    return saved;
  }

  /** 读取本作业不可变的 SQL 保存版本，仅查询元数据。 */
  public List<ObjectNode> versions(String id) {
    get(id);
    return records.listForSubject("sql_versions", id);
  }

  /** 将历史 SQL 和运行配置恢复为新草稿修订，不触发集群变更。 */
  @Transactional
  public ObjectNode restoreVersion(String id, String version, long revision) {
    get(id);
    var record = records.require("sql_versions", version);
    if (!id.equals(record.path("sqlId").asText()))
      throw new BusinessException(HttpStatus.NOT_FOUND, "NOT_FOUND", "SQL 版本不属于当前作业");
    return save(
        id,
        new SqlDraftRequest(
            id,
            record.path("description").asText(),
            record.path("sql").asText(),
            (ObjectNode) record.path("runtime"),
            revision));
  }

  /** 检查脚本及选中 Catalog，保持 BASIC/版本 Parser 级别区分。 */
  public com.flinkweb.dto.sql.SqlCheckResult check(String script, ObjectNode runtime) {
    return parser.check(
        validation.validate(catalogs.prepare(runtime.deepCopy()) + script),
        runtime.path("imageId").asText());
  }

  /** 校验 SQL 并生成 YAML 预览；不保存部署草稿，也不访问 Kubernetes。 */
  public ObjectNode preview(SqlDraftRequest input) {
    ObjectNode options = input.runtime().deepCopy();
    var checked =
        parser.check(
            validation.validate(catalogs.prepare(options) + input.sql()),
            options.path("imageId").asText());
    runtimeArtifacts.configure(options);
    options.put("name", input.name());
    var args = options.putArray("args");
    for (String statement : checked.statements()) {
      args.add("--statement");
      args.add(Base64.getEncoder().encodeToString(statement.getBytes(StandardCharsets.UTF_8)));
    }
    if (options.has("flinkConfiguration") && !options.path("flinkConfiguration").isObject())
      yaml.fail("flinkConfiguration 必须是 JSON 对象");
    options.withObject("/flinkConfiguration").put("pipeline.name", input.name());
    ObjectNode rendered = forms.render(options);
    ObjectNode resource = yaml.validate(yaml.parse(rendered.path("yaml").asText()), input.name());
    resource.withObject("/metadata").withObject("/labels").put("flink-web/job-type", "sql");
    resource.withObject("/metadata/labels").put("flink-web/sql-id", input.name());
    return rendered.put("yaml", yaml.dump(resource)).put("message", checked.message());
  }

  /** 在同一数据库事务中发布为部署草稿；仅显式运行操作才会提交 Kubernetes。 */
  @Transactional
  public JobDraft publish(String id, long revision) {
    access.require("sql:" + id, "edit");
    ObjectNode record = get(id);
    if (record.path("revision").asLong() != revision)
      throw new BusinessException(HttpStatus.CONFLICT, "REVISION_CONFLICT", "SQL 草稿已修改，请重新加载");
    SqlDraftRequest input =
        new SqlDraftRequest(
            id,
            record.path("description").asText(),
            record.path("sql").asText(),
            (ObjectNode) record.path("runtime"),
            revision);
    ObjectNode rendered = preview(input);
    var existing = jobRepository.findById(record.path("jobId").asText());
    var draft =
        new DraftRequest(
            id,
            input.description(),
            rendered.path("flinkVersion").asText(),
            rendered.path("yaml").asText(),
            existing.map(JobDraft::revision).orElse(0L),
            input.runtime().path("workspaceId").isTextual() ? input.runtime().path("workspaceId").asText() : null);
    JobDraft result =
        existing.isPresent() ? jobs.update(existing.get().id(), draft) : jobs.create(draft);
    if (existing.isEmpty()) access.copy("sql:" + id, result.id());
    record.put("jobId", result.id()).put("publishedAt", Instant.now().toString());
    records.save("sql_jobs", id, record, revision);
    audit.record("SQL_PUBLISH", id, "SUCCESS");
    return result;
  }

  /** 已发布草稿需先从作业管理移除对应部署登记，防止孤立运行资源。 */
  @Transactional
  public void delete(String id, long revision) {
    access.require("sql:" + id, "edit");
    var record = get(id);
    if (jobRepository.findById(record.path("jobId").asText()).isPresent())
      throw new BusinessException(
          HttpStatus.CONFLICT, "SQL_DEPLOYMENT_EXISTS", "请先在作业管理删除对应集群资源和平台草稿，再删除 SQL 草稿");
    records.delete("sql_jobs", id, revision);
    audit.record("SQL_DELETE", id, "SUCCESS");
  }
}
