package com.flinkweb.service.ai;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.Principal;

/** 作业私有持续诊断会话、有限证据、修复审阅与显式应用契约。 */
public interface AiJobService {
  /** 恢复总结及最近消息，不访问集群或自动请求模型。 */
  ObjectNode get(String jobId);
  /** 幂等追加用户补充并使旧方案失效。 */
  ObjectNode append(String jobId, ObjectNode input);
  /** 保存人工核对的总结，保留未解决事项。 */
  ObjectNode summary(String jobId, ObjectNode input);
  /** 校验会话版本并按授权采集当前作业的受限上下文。 */
  ObjectNode context(String jobId, long version, boolean includeLogs);
  /** 显式选择一类证据参与本轮，未选时由故障方向决定。 */
  ObjectNode context(String jobId,long version,boolean includeLogs,String category);
  /** 用户按类别主动刷新证据，不启动模型或修改草稿。 */
  ObjectNode evidence(String jobId, String category);
  /** 将成功回复、完整总结和候选补丁写入当前生命周期，随后压缩旧原文。 */
  void complete(String jobId, long version, String taskId, ObjectNode context, ObjectNode result, Runnable commitTask);
  /** 用户确认后按原请求号保存或发布，分阶段记录异步运行操作。 */
  ObjectNode apply(String jobId, String planId, ObjectNode input, Principal actor);
  /** 读取原应用请求，未知提交不通过新请求号重放。 */
  ObjectNode application(String jobId, String requestId);
}
