package com.flinkweb.service.job.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** 服务契约：按用户请求读取作业事件和受限日志；校验 Pod 所有者链，不提供自动跟随。 */
public interface DiagnosticService {
  /** 手动查询当前作业事件与 Pod 概览，不返回容器环境变量。 */
  ObjectNode inspect(String id);

  /** 获取最多 1000 行、256 KiB 的日志，Pod 与容器必须属于该作业。 */
  ObjectNode log(String id, String podName, String container, int lines, boolean previous);
}
