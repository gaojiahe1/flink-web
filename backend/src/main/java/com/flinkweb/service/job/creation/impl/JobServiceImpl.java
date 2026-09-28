package com.flinkweb.service.job.creation.impl;

import com.flinkweb.dto.job.creation.DraftRequest;
import com.flinkweb.entity.job.JobDraft;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.repository.job.JobBindingRepository;
import com.flinkweb.repository.job.JobRepository;
import com.flinkweb.service.audit.AuditService;
import com.flinkweb.service.job.creation.JobService;
import com.flinkweb.service.job.creation.YamlService;
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

  /** 注入草稿仓库、YAML 校验器、绑定仓库和审计服务。 */
  public JobServiceImpl(
      JobRepository repository,
      AuditService audit,
      YamlService yaml,
      JobBindingRepository bindings,
      AccessService access) {
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
    yaml.parse(request.yaml());
    if (repository.findByName(request.name()).isPresent())
      throw new BusinessException(HttpStatus.CONFLICT, "JOB_NAME_EXISTS", "作业名称已存在，请修改名称或编辑已有作业");
    var now = Instant.now();
    var draft =
        new JobDraft(
            UUID.randomUUID().toString(),
            request.name(),
            request.description(),
            request.flinkVersion(),
            request.yaml(),
            0,
            now,
            now);
    repository.insert(draft);
    access.initialize(draft.id());
    audit.record("DRAFT_CREATE", draft.id(), "SUCCESS");
    return draft;
  }

  /** 保持作业名称不变，校验 YAML 后以修订号更新草稿及审计。 */
  @org.springframework.transaction.annotation.Transactional
  public JobDraft update(String id, DraftRequest request) {
    access.require(id, "edit");
    var existing = get(id);
    if (!existing.name().equals(request.name()))
      throw new BusinessException(HttpStatus.BAD_REQUEST, "IMMUTABLE_NAME", "作业名称创建后不可修改");
    yaml.parse(request.yaml());
    var updated =
        new JobDraft(
            id,
            request.name(),
            request.description(),
            request.flinkVersion(),
            request.yaml(),
            request.revision() + 1,
            existing.createdAt(),
            Instant.now());
    if (!repository.update(updated, request.revision())) throw conflict();
    audit.record("DRAFT_UPDATE", id, "SUCCESS");
    return updated;
  }

  /** 以修订号删除草稿及资源绑定，并在同一事务内记录审计。 */
  @org.springframework.transaction.annotation.Transactional
  public void delete(String id, long revision) {
    access.require(id, "edit");
    get(id);
    if (!repository.delete(id, revision)) throw conflict();
    bindings.delete(id);
    audit.record("DRAFT_DELETE", id, "SUCCESS");
  }

  /** 创建草稿被并发修改的冲突异常。 */
  private BusinessException conflict() {
    return new BusinessException(HttpStatus.CONFLICT, "REVISION_CONFLICT", "草稿已被修改，请重新加载后再操作");
  }
}
