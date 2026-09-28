package com.flinkweb.service.audit;

/** 服务契约：记录操作人、动作和结果，并提供审计记录查询。 */
public interface AuditService {
  /** 查询已持久化的审计记录，时间排序由展示层处理。 */
  java.util.List<com.fasterxml.jackson.databind.node.ObjectNode> list();

  /** 保存当前操作人、动作、目标及执行结果，不记录凭据内容。 */
  void record(String action, String target, String outcome);
}
