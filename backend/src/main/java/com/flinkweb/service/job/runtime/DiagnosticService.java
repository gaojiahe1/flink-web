package com.flinkweb.service.job.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** 服务契约：按用户请求读取作业事件和受限日志；校验 Pod 所有者链，不提供自动跟随。 */
public interface DiagnosticService {
  /** 手动查询当前作业事件与 Pod 概览，不返回容器环境变量。 */
  ObjectNode inspect(String id);
  /** 返回沿所有者链核验的单个Pod，仅供服务内按需投影和脱敏。 */
  ObjectNode pod(String id,String podName);
  /** 服务内部脱敏已核验工作负载引用的Secret值，失败时调用方不得返回原始证据。 */
  com.fasterxml.jackson.databind.JsonNode redactEvidence(String id,com.fasterxml.jackson.databind.JsonNode evidence);

  /** 获取最多 10000 行、4 MiB 的日志，Pod 与容器必须属于该作业。 */
  ObjectNode log(String id, String podName, String container, int lines, boolean previous);

  /** 核验 Session 身份后读取同格式的 Pod 与事件。 */
  ObjectNode inspectSession(String id);

  /** 读取已核验 Session 所属 Pod 日志，沿用脱敏和长度限制。 */
  ObjectNode sessionLog(String id, String podName, String container, int lines, boolean previous);
}
