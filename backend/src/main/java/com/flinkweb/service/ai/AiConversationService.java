package com.flinkweb.service.ai;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** 按 SQL 草稿与当前用户持久化私有对话，引用作业唯一总结并保护显式确认版本。 */
public interface AiConversationService {
  /** 读取当前用户的会话，未开始时返回空会话。 */
  ObjectNode get(String sqlId);
  /** 幂等追加用户消息，补充需求立即使旧确认失效。 */
  ObjectNode append(String sqlId, ObjectNode input);
  /** 以修订号保存用户核对后的完整总结，不覆盖并发补充。 */
  ObjectNode summary(String sqlId, ObjectNode input);
  /** 绑定本轮总结版本与当前 SQL，显式确认后才允许生成。 */
  ObjectNode confirm(String sqlId, ObjectNode input);
  /** 从服务端保存的全部会话构建任务上下文，不信任客户端伪造历史。 */
  ObjectNode context(String sqlId, long version, String mode, String sql);
  /** 仅接受对应需求版本的模型结果，保存回复和总结或产物引用。 */
  void complete(String sqlId, long version, String taskId, String mode, String sqlHash, long summaryRevision, ObjectNode result, Runnable commitTask);
}
