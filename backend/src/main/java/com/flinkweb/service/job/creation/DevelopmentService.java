package com.flinkweb.service.job.creation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.entity.job.JobDraft;
import java.util.List;

/** 作业开发入口与部署登记之间的映射契约。 */
public interface DevelopmentService {
  /** 仅列出已进入 JAR 开发区的草稿。 */
  List<JobDraft> jarJobs();

  /** 即时读取 JAR 草稿对应资源的部署标记，不触发生命周期推进。 */
  ObjectNode deploymentStatus();

  /** 获取有读取权限的作业类型与开发入口。 */
  ObjectNode links();

  /** 编辑旧作业时显式加入 JAR 开发列表。 */
  JobDraft openJar(String id);
}
