package com.flinkweb.service.job.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 持久化停止意图，并在显式操作或手动查询时推进 Savepoint 后删除流程。 */
public interface StopService {
  /** 提交停止请求或继续已有的停止流程。 */
  String stop(String id, String mode, JsonNode resource);

  /** 在手动刷新时推进本作业尚未完成的停止请求。 */
  ObjectNode advance(String id);

  /** 读取本次停止保存的恢复路径，不混用其他作业的状态。 */
  String restorePath(String id);

  /** 读取停止前的实际部署配置，供按原配置重新启动。 */
  ObjectNode restartResource(String id);

  /** 判断本作业是否仍有未完成的停止命令。 */
  boolean pending(String id);

  /** 新启动完成提交后清除停止意图。 */
  void clear(String id);
}
