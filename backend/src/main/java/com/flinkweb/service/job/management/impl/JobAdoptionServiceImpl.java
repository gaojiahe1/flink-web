package com.flinkweb.service.job.management.impl;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.client.kubernetes.KubernetesClient;
import com.flinkweb.config.platform.PlatformProperties;
import com.flinkweb.dto.job.management.AdoptJobRequest;
import com.flinkweb.entity.job.JobBinding;
import com.flinkweb.entity.job.JobDraft;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.repository.job.JobBindingRepository;
import com.flinkweb.repository.job.JobRepository;
import com.flinkweb.service.audit.AuditService;
import com.flinkweb.service.job.creation.YamlService;
import com.flinkweb.service.job.management.JobAdoptionService;
import com.flinkweb.service.permission.AccessService;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 将既有 Application 作业登记到本地元数据；纳管仅查询集群，保留原 spec。 */
@Service
public class JobAdoptionServiceImpl implements JobAdoptionService {
  private final KubernetesClient client;
  private final JobRepository jobs;
  private final JobBindingRepository bindings;
  private final AuditService audit;
  private final AccessService access;
  private final YamlService yaml;
  private final String namespace;
  private final TransactionTemplate transactions;

  /** 注入资源读取及持久化依赖，创建仅用于元数据登记的短事务模板。 */
  public JobAdoptionServiceImpl(
      KubernetesClient client,
      JobRepository jobs,
      JobBindingRepository bindings,
      AuditService audit,
      YamlService yaml,
      PlatformProperties properties,
      PlatformTransactionManager manager,
      AccessService access) {
    this.client = client;
    this.jobs = jobs;
    this.bindings = bindings;
    this.audit = audit;
    this.access = access;
    this.yaml = yaml;
    this.namespace = properties.namespace();
    this.transactions = new TransactionTemplate(manager);
  }

  /** 核对用户确认的 UID、Namespace 和作业类型，随后在短事务中登记元数据。 */
  public JobDraft adopt(AdoptJobRequest request) {
    // 先读取集群，再开启短数据库事务，避免等待 API 时占用 SQLite 唯一连接。
    ObjectNode live = client.require(request.name());
    if (!namespace.equals(live.path("metadata").path("namespace").asText()))
      fail("NAMESPACE_MISMATCH", "禁止纳管其他 namespace 的资源");
    if (!request.name().equals(live.path("metadata").path("name").asText())
        || !request.uid().equals(live.path("metadata").path("uid").asText()))
      fail("RESOURCE_RECREATED", "资源身份已变化，请刷新列表后重试");
    if (live.path("metadata").hasNonNull("deletionTimestamp"))
      fail("RESOURCE_DELETING", "资源正在删除，不能纳管");
    if (!live.path("spec").path("job").isObject())
      fail("SESSION_CLUSTER_UNSUPPORTED", "当前仅支持 Application 作业，不纳管 Session 集群");
    return transactions.execute(status -> register(request, live));
  }

  /** 保留原始 spec 建立草稿与 UID 绑定；重复纳管返回原记录，同名冲突则拒绝。 */
  private JobDraft register(AdoptJobRequest request, ObjectNode live) {
    var existing = jobs.findByName(request.name());
    if (existing.isPresent()) {
      var binding = bindings.findByJobId(existing.get().id());
      if (binding.isPresent()
          && namespace.equals(binding.get().namespace())
          && request.uid().equals(binding.get().resourceUid()))
        return existing.get(); // 同一 UID 重复纳管返回原记录，不覆盖已有草稿。
      fail("JOB_NAME_CONFLICT", "平台已有同名草稿或其他资源绑定，不能覆盖，请先处理原记录");
    }
    if (!request.resourceVersion().equals(live.path("metadata").path("resourceVersion").asText()))
      fail("RESOURCE_CHANGED", "作业已变化，请刷新后确认当前版本再纳管");
    ObjectNode source = live.deepCopy();
    source.remove("status");
    com.flinkweb.service.job.creation.support.JobEditorPolicy.mark(source, false);
    ((ObjectNode) source.get("metadata"))
        .remove(
            List.of(
                "uid",
                "resourceVersion",
                "generation",
                "managedFields",
                "creationTimestamp",
                "deletionTimestamp",
                "ownerReferences",
                "finalizers"));
    // 不调用提交归一化：保留既有镜像、ServiceAccount、状态目录和 checkpoint 策略。
    String version =
        source.path("spec").path("flinkVersion").asText().replaceFirst("^v", "").replace('_', '.');
    var now = Instant.now();
    var draft =
        new JobDraft(
            UUID.randomUUID().toString(),
            request.name(),
            "从 namespace " + namespace + " 纳管的既有作业",
            version,
            yaml.dump(source),
            0,
            now,
            now);
    jobs.insert(draft);
    access.initialize(draft.id());
    bindings.insert(
        new JobBinding(
            draft.id(), namespace, draft.name(), request.uid(), request.resourceVersion(), now));
    audit.record("JOB_ADOPT", draft.id(), "SUCCESS");
    return jobs.findById(draft.id()).orElseThrow();
  }

  /** 抛出纳管冲突及对应的可识别错误码。 */
  private void fail(String code, String message) {
    throw new BusinessException(HttpStatus.CONFLICT, code, message);
  }
}
