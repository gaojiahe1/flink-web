package com.flinkweb.service.job.runtime;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.security.Principal;
import java.util.*;

/** 服务契约：编排作业部署、启停和快照操作，执行身份校验、幂等控制及 Kubernetes dry-run。 */
public interface OperationService {
  /** 渲染配置引用并校验 YAML，可选择以 Kubernetes dry-run 检查集群是否保留提交字段。 */
  ObjectNode validate(String source, String name, boolean dryRun);

  /** 校验权限、幂等标识与资源身份，提交指定操作并持久化接收、拒绝或未知结果。 */
  ObjectNode execute(String id, String action, ObjectNode request, Principal user);

  /** 返回指定作业的操作历史，并移除内部幂等指纹。 */
  java.util.List<ObjectNode> history(String id);
}
