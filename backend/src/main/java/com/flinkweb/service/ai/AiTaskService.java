package com.flinkweb.service.ai;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** 用户主动发起的 AI 任务，不部署作业、不执行模型建议。 */
public interface AiTaskService {
  /** 创建有界异步任务，同一请求标识不重复调用模型。 */
  ObjectNode create(ObjectNode input);
  /** 读取自己的任务并重新校验相关作业访问范围。 */
  ObjectNode get(String id);
  /** 取消模型调用，迟到结果不得覆盖取消状态。 */
  ObjectNode cancel(String id);
  /** 查询当前用户最近的生成记录。 */
  List<ObjectNode> history(String sqlId);
  /** 编译临时数据定义，返回明确的待补项而不登记共享资产。 */
  ObjectNode compile(ObjectNode input);
}
