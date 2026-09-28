package com.flinkweb.service.job.management;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** 服务契约：合并本次集群快照与平台登记信息，不缓存运行状态，仅登记本次观测到的快照及操作结果。 */
public interface JobQueryService {
  /** 即时采集集群状态，标记已纳管、可纳管及同名身份冲突的作业。 */
  ObjectNode refresh();
}
