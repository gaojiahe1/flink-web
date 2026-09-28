package com.flinkweb.service.job.management;

import com.fasterxml.jackson.databind.JsonNode;

/** 服务契约：统一校验纳管 UID 或平台创建标签，防止操作尚未纳管或被替换的资源。 */
public interface JobOwnershipService {
  /** 验证实际资源与绑定 UID、Namespace 和名称一致；未绑定资源必须携带本作业标签。 */
  void assertManaged(JsonNode resource, String id);

  /** 在资源删除成功或确认不存在后解除 UID 绑定。 */
  void release(String id);
}
