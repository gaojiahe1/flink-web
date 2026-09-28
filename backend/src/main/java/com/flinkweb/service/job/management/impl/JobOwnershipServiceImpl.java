package com.flinkweb.service.job.management.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.flinkweb.config.platform.PlatformProperties;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.repository.job.JobBindingRepository;
import com.flinkweb.service.job.management.JobOwnershipService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 统一校验纳管 UID 或平台创建标签，防止操作尚未纳管或被替换的资源。 */
@Service
public class JobOwnershipServiceImpl implements JobOwnershipService {
  private final JobBindingRepository bindings;
  private final String namespace;

  /** 注入身份绑定仓库并读取平台固定 Namespace。 */
  public JobOwnershipServiceImpl(JobBindingRepository bindings, PlatformProperties properties) {
    this.bindings = bindings;
    this.namespace = properties.namespace();
  }

  /** 验证实际资源与绑定 UID、Namespace 和名称一致；未绑定资源必须携带本作业标签。 */
  public void assertManaged(JsonNode resource, String id) {
    var binding = bindings.findByJobId(id);
    if (binding.isPresent()) {
      var expected = binding.get();
      if (!namespace.equals(expected.namespace())
          || !expected.namespace().equals(resource.path("metadata").path("namespace").asText())
          || !expected.name().equals(resource.path("metadata").path("name").asText())
          || !expected.resourceUid().equals(resource.path("metadata").path("uid").asText()))
        throw new BusinessException(
            HttpStatus.CONFLICT,
            "RESOURCE_IDENTITY_CHANGED",
            "已纳管资源的 UID 或 namespace 发生变化，禁止操作同名替代资源");
      return;
    }
    if (!id.equals(resource.path("metadata").path("labels").path("flink-web/job-id").asText()))
      throw new BusinessException(HttpStatus.CONFLICT, "UNMANAGED_RESOURCE", "资源尚未纳管，请先在作业列表登记");
  }

  /** 在资源删除成功或确认不存在后解除 UID 绑定。 */
  public void release(String id) {
    bindings.delete(id);
  }
}
