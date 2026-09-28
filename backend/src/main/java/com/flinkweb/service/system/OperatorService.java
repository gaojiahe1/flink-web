package com.flinkweb.service.system;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** 提供显式 Operator 状态与兼容性查询，不执行后台轮询。 */
public interface OperatorService {
  /** 读取指定 Deployment 和匹配 Pod，返回版本与兼容范围。 */
  ObjectNode inspect();
}
