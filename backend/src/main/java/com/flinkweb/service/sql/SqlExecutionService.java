package com.flinkweb.service.sql;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** SQL 逻辑验证的提交、状态与受限数据结果契约。 */
public interface SqlExecutionService {
  /** 提交已保存修订；重复请求返回相同验证编号。 */
  ObjectNode submit(String sqlId, long revision, String requestId);

  /** 查询验证状态和最多一百条本次数据。 */
  ObjectNode status(String id);

  /** 显式取消本次验证任务。 */
  ObjectNode cancel(String id);
}
