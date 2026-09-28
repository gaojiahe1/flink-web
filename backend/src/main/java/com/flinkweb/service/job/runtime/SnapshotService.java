package com.flinkweb.service.job.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** 服务契约：将用户刷新时实际看到的快照登记为台账；不定时扫描 S3 或补造未观测的历史。 */
public interface SnapshotService {
  /** 收集 Operator 已上报的完成快照，记录发现时的资源和发布标识。 */
  void observe(ObjectNode row, String time);

  /** 查询历史台账；观测时发布标识不等于快照创建时的准确代码版本。 */
  List<ObjectNode> list(String id);

  /** 用户按需检查已登记路径的元数据文件是否存在，不宣称完整恢复兼容。 */
  ObjectNode check(String jobId, String snapshotId);
}
